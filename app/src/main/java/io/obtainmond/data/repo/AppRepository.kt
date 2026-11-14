package io.obtainmond.data.repo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import io.obtainmond.data.model.AppEventLog
import io.obtainmond.data.model.ObtainmondSettings
import io.obtainmond.data.model.TrackedApp
import io.obtainmond.privilege.PrivilegeManager
import io.obtainmond.rust.ObtainmondNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object AppRepository {
    private const val CHANNEL_SILENT_UPDATES = "obtainmond_silent_updates"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private lateinit var appContext: Context

    private val _apps = MutableStateFlow<List<TrackedApp>>(emptyList())
    val apps: StateFlow<List<TrackedApp>> = _apps.asStateFlow()

    private val _settings = MutableStateFlow(ObtainmondSettings())
    val settings: StateFlow<ObtainmondSettings> = _settings.asStateFlow()

    private val _logs = MutableStateFlow<List<AppEventLog>>(emptyList())
    val logs: StateFlow<List<AppEventLog>> = _logs.asStateFlow()

    private val _busyAppIds = MutableStateFlow<Set<String>>(emptySet())
    val busyAppIds: StateFlow<Set<String>> = _busyAppIds.asStateFlow()

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        createNotificationChannel()
        loadAllFromDisk()
        scope.launch {
            syncInstalledPackagesState()
        }
    }

    private fun appsFile(): File = File(appContext.filesDir, "tracked_apps.json")
    private fun settingsFile(): File = File(appContext.filesDir, "obtainmond_settings.json")
    private fun logsFile(): File = File(appContext.filesDir, "event_logs.json")

    private fun loadAllFromDisk() {
        runCatching {
            val sf = settingsFile()
            if (sf.exists()) {
                _settings.value = json.decodeFromString<ObtainmondSettings>(sf.readText())
            }
        }
        runCatching {
            val af = appsFile()
            if (af.exists()) {
                _apps.value = json.decodeFromString<List<TrackedApp>>(af.readText())
            } else {
                // Seed with Shevery & Obtainmond self-tracking defaults on first launch
                val defaults = listOf(
                    TrackedApp(
                        id = "moe.shizuku.privileged.api",
                        name = "Shevery",
                        author = "HmnDev-Tech",
                        url = "https://github.com/HmnDev-Tech/Shevery",
                        sourceType = "GITHUB",
                        apiUrl = "https://api.github.com/repos/HmnDev-Tech/Shevery/releases?per_page=15",
                        categories = listOf("Privilege", "System")
                    ),
                    TrackedApp(
                        id = "io.obtainmond",
                        name = "Obtainmond",
                        author = "HmnDev-Tech",
                        url = "https://github.com/HmnDev-Tech/Obtainmond",
                        sourceType = "GITHUB",
                        apiUrl = "https://api.github.com/repos/HmnDev-Tech/Obtainmond/releases?per_page=15",
                        categories = listOf("Updater", "Rust")
                    )
                )
                _apps.value = defaults
                saveAppsToDisk(defaults)
            }
        }
        runCatching {
            val lf = logsFile()
            if (lf.exists()) {
                _logs.value = json.decodeFromString<List<AppEventLog>>(lf.readText())
            }
        }
    }

    private fun saveAppsToDisk(list: List<TrackedApp>) {
        runCatching {
            appsFile().writeText(json.encodeToString(list))
        }
    }

    fun updateSettings(transform: (ObtainmondSettings) -> ObtainmondSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        scope.launch {
            runCatching {
                settingsFile().writeText(json.encodeToString(next))
            }
            if (next.autoWhitelistBatteryDoze) {
                PrivilegeManager.enforceZeroBatteryRestriction(appContext, next)
            }
        }
    }

    fun appendLog(
        appId: String,
        appName: String,
        action: String,
        backendUsed: String,
        details: String,
        success: Boolean
    ) {
        val entry = AppEventLog(
            timestampMs = System.currentTimeMillis(),
            appId = appId,
            appName = appName,
            action = action,
            backendUsed = backendUsed,
            details = details,
            success = success
        )
        val updated = (listOf(entry) + _logs.value).take(150)
        _logs.value = updated
        scope.launch {
            runCatching { logsFile().writeText(json.encodeToString(updated)) }
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
        scope.launch {
            runCatching { logsFile().delete() }
        }
    }

    /**
     * Inspects Android's PackageManager for every tracked app to synchronize its
     * currently installed versionName and primaryCpuAbi (e.g. `arm64-v8a`).
     */
    suspend fun syncInstalledPackagesState() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pm = appContext.packageManager
            val updated = _apps.value.map { app ->
                val pkgInfo = runCatching {
                    pm.getPackageInfo(app.id, 0)
                }.getOrNull()

                if (pkgInfo != null) {
                    val ver = pkgInfo.versionName ?: app.installedVersion
                    val detectedAbi = detectInstalledPackageAbi(pkgInfo.applicationInfo?.nativeLibraryDir)
                        .ifEmpty { app.installedAbi }
                    val suspended = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        (pkgInfo.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SUSPENDED != 0
                    } else {
                        false
                    }
                    app.copy(
                        installedVersion = ObtainmondNative.safeNormalizeVersion(ver, app.versionExtractionRegex),
                        installedAbi = detectedAbi,
                        isFrozen = suspended
                    )
                } else {
                    app
                }
            }
            _apps.value = updated
            saveAppsToDisk(updated)
        }
    }

    private fun detectInstalledPackageAbi(nativeLibDir: String?): String {
        if (nativeLibDir.isNullOrBlank()) return ""
        val lower = nativeLibDir.lowercase()
        return when {
            lower.endsWith("/arm64") || lower.contains("arm64-v8a") -> "arm64-v8a"
            lower.endsWith("/arm") || lower.contains("armeabi-v7a") -> "armeabi-v7a"
            lower.endsWith("/x86_64") -> "x86_64"
            lower.endsWith("/x86") -> "x86"
            else -> Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        }
    }

    /**
     * Resolves a repository URL via Rust `ObtainmondNative.resolveSource`, fetches its latest
     * release, selects the optimal APK for the device's ABI, and adds it to tracked apps.
     */
    suspend fun addAppByUrl(
        rawUrl: String,
        customPackageId: String = "",
        customName: String = "",
        includePrereleases: Boolean = false,
        silentAutoUpdate: Boolean = true,
        trackOnly: Boolean = false,
        apkFilterRegex: String = "",
        versionExtractionRegex: String = "",
        category: String = "",
        installImmediately: Boolean = true
    ): Result<TrackedApp> = withContext(Dispatchers.IO) {
        runCatching {
            val desc = ObtainmondNative.resolveSource(rawUrl)
            val pkgId = customPackageId.trim().ifEmpty { desc.packageIdHint.ifEmpty { desc.repo } }
            val name = customName.trim().ifEmpty { desc.appNameHint.ifEmpty { desc.repo } }

            val initial = TrackedApp(
                id = pkgId,
                name = name,
                author = desc.owner,
                url = desc.canonicalUrl,
                sourceType = desc.sourceType,
                apiUrl = desc.apiUrl,
                includePrereleases = includePrereleases,
                silentAutoUpdate = silentAutoUpdate,
                trackOnly = trackOnly,
                apkFilterRegex = apkFilterRegex.trim(),
                versionExtractionRegex = versionExtractionRegex.trim(),
                categories = if (category.isBlank()) emptyList() else listOf(category.trim())
            )

            val refreshed = fetchLatestReleaseForApp(initial)
            mutex.withLock {
                val filtered = _apps.value.filterNot {
                    it.id.equals(refreshed.id, ignoreCase = true) ||
                        it.url.equals(refreshed.url, ignoreCase = true)
                }
                val next = listOf(refreshed) + filtered
                _apps.value = next
                saveAppsToDisk(next)
            }
            syncInstalledPackagesState()

            val latestSnapshot = _apps.value.firstOrNull { it.url == refreshed.url } ?: refreshed
            if (installImmediately && latestSnapshot.matchedApkUrl.isNotBlank() && !latestSnapshot.trackOnly) {
                if (latestSnapshot.installedVersion.isBlank() || latestSnapshot.hasUpdate) {
                    downloadAndInstallAppSilently(latestSnapshot.id, isBackground = false)
                }
            }
            _apps.value.firstOrNull { it.url == refreshed.url } ?: latestSnapshot
        }
    }

    fun removeApp(appId: String) {
        scope.launch {
            mutex.withLock {
                val next = _apps.value.filterNot { it.id == appId }
                _apps.value = next
                saveAppsToDisk(next)
            }
        }
    }

    fun removeApps(appIds: Set<String>) {
        if (appIds.isEmpty()) return
        scope.launch {
            mutex.withLock {
                val next = _apps.value.filterNot { it.id in appIds }
                _apps.value = next
                saveAppsToDisk(next)
            }
        }
    }

    fun updateApp(appId: String, transform: (TrackedApp) -> TrackedApp) {
        scope.launch {
            mutex.withLock {
                val next = _apps.value.map {
                    if (it.id == appId) transform(it) else it
                }
                _apps.value = next
                saveAppsToDisk(next)
            }
        }
    }

    fun toggleSilentAutoUpdate(appId: String, enabled: Boolean) {
        updateApp(appId) { it.copy(silentAutoUpdate = enabled) }
    }

    suspend fun checkSingleApp(appId: String): Result<TrackedApp> = withContext(Dispatchers.IO) {
        runCatching {
            _busyAppIds.value = _busyAppIds.value + appId
            try {
                syncInstalledPackagesState()
                val current = _apps.value.firstOrNull { it.id == appId }
                    ?: throw IllegalStateException("App not found")
                val updated = fetchLatestReleaseForApp(current)
                mutex.withLock {
                    val next = _apps.value.map { if (it.id == appId) updated else it }
                    _apps.value = next
                    saveAppsToDisk(next)
                }
                updated
            } finally {
                _busyAppIds.value = _busyAppIds.value - appId
            }
        }
    }

    suspend fun batchUpdateApps(appIds: Set<String>): Int = withContext(Dispatchers.IO) {
        var count = 0
        for (id in appIds) {
            val refreshed = checkSingleApp(id).getOrNull() ?: continue
            if ((refreshed.hasUpdate || refreshed.installedVersion.isBlank()) && refreshed.matchedApkUrl.isNotBlank()) {
                if (downloadAndInstallAppSilently(refreshed.id, isBackground = false)) {
                    count++
                }
            }
        }
        count
    }

    /**
     * Queries the upstream release API and runs the Rust release parser + Rust smart ABI APK selector.
     */
    suspend fun fetchLatestReleaseForApp(app: TrackedApp): TrackedApp = withContext(Dispatchers.IO) {
        val desc = ObtainmondNative.resolveSource(app.url)
        val endpoint = app.apiUrl.ifBlank { desc.apiUrl }
        val currentSettings = _settings.value

        val payload = if (desc.sourceType == "DIRECT_APK") {
            ""
        } else {
            val reqBuilder = Request.Builder()
                .url(endpoint)
                .header("User-Agent", "Obtainmond/2.0.0 (Android; RustCore)")
                .header("Accept", "application/json, text/html;q=0.9, */*;q=0.8")

            if (desc.sourceType == "GITHUB" && currentSettings.githubPersonalAccessToken.isNotBlank()) {
                reqBuilder.header(
                    "Authorization",
                    "Bearer ${currentSettings.githubPersonalAccessToken.trim()}"
                )
            }

            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code} from ${desc.sourceType} endpoint")
                }
                resp.body?.string().orEmpty()
            }
        }

        val parsed = ObtainmondNative.parseRelease(
            sourceType = desc.sourceType,
            payload = payload,
            includePrereleases = app.includePrereleases,
            fallbackUrl = app.url
        )

        if (parsed.error != null) {
            throw IllegalStateException(parsed.error)
        }

        val normalizedRemote = ObtainmondNative.safeNormalizeVersion(
            parsed.version.ifBlank { parsed.rawTag },
            app.versionExtractionRegex
        )

        val selection = ObtainmondNative.selectBestApkForDevice(
            candidates = parsed.apkAssets,
            installedAbi = app.installedAbi,
            previousApkName = app.matchedApkName,
            filterRegex = app.apkFilterRegex,
            excludeDebug = true
        )

        app.copy(
            sourceType = desc.sourceType,
            apiUrl = endpoint,
            latestVersion = normalizedRemote,
            matchedApkName = selection.selectedName,
            matchedApkUrl = selection.selectedUrl,
            matchedAbi = selection.detectedAbi,
            selectionReason = "${selection.confidence}: ${selection.reason}",
            availableApks = parsed.apkAssets,
            changelog = parsed.changelog.take(2500),
            lastCheckedEpochMs = System.currentTimeMillis()
        )
    }

    /**
     * Checks all tracked apps for updates and, when [autoInstallUpdates] is true,
     * silently downloads and installs every available update on the fly via Shevery/Shizuku
     * without opening the UI or prompting the user.
     */
    suspend fun refreshAllAndAutoUpdate(
        autoInstallUpdates: Boolean = true,
        isBackgroundWorker: Boolean = false
    ): Int = withContext(Dispatchers.IO) {
        syncInstalledPackagesState()

        if (_settings.value.autoWhitelistBatteryDoze) {
            PrivilegeManager.enforceZeroBatteryRestriction(appContext, _settings.value)
        }

        val snapshot = _apps.value
        val updatedList = snapshot.map { app ->
            runCatching { fetchLatestReleaseForApp(app) }.getOrElse { e ->
                appendLog(
                    appId = app.id,
                    appName = app.name,
                    action = "Check Release",
                    backendUsed = "Rust Release Engine",
                    details = e.message ?: "Failed to check release",
                    success = false
                )
                app
            }
        }

        mutex.withLock {
            _apps.value = updatedList
            saveAppsToDisk(updatedList)
        }

        var installedCount = 0
        if (autoInstallUpdates) {
            val candidates = _apps.value.filter { app ->
                app.hasUpdate &&
                    app.matchedApkUrl.isNotBlank() &&
                    (!isBackgroundWorker || app.silentAutoUpdate)
            }
            for (app in candidates) {
                val ok = downloadAndInstallAppSilently(app.id, isBackground = isBackgroundWorker)
                if (ok) installedCount++
            }
        }
        installedCount
    }

    /**
     * Downloads the Rust-selected primary APK for [appId], verifies its ZIP/ABI/SHA-256 integrity
     * in Rust, backs up the existing APK for Instant Rollback, and installs silently via Shevery/Shizuku.
     */
    suspend fun downloadAndInstallAppSilently(
        appId: String,
        overrideApkUrl: String? = null,
        isBackground: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val currentApp = _apps.value.firstOrNull { it.id == appId } ?: return@withContext false
        val rawDownloadUrl = (overrideApkUrl ?: currentApp.matchedApkUrl).trim()
        if (!rawDownloadUrl.startsWith("https://") && !rawDownloadUrl.startsWith("http://")) {
            return@withContext false
        }

        _busyAppIds.value = _busyAppIds.value + appId
        try {
            val currentSettings = _settings.value
            val effectiveUrl = if (
                currentSettings.labGitHubMirrorAcceleration &&
                rawDownloadUrl.startsWith("https://github.com/")
            ) {
                "https://ghfast.top/$rawDownloadUrl"
            } else {
                rawDownloadUrl
            }

            val downloadsDir = File(appContext.cacheDir, "apk_downloads").apply { mkdirs() }
            val safeFileName = (currentApp.matchedApkName.ifBlank { "${currentApp.id}.apk" })
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .trimStart('.')
                .ifBlank { "obtainmond_download.apk" }
            val targetApkFile = File(downloadsDir, safeFileName)

            val req = Request.Builder()
                .url(effectiveUrl)
                .header("User-Agent", "Obtainmond/2.0.0 (Android; RustCore)")
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("APK download failed with HTTP ${resp.code}")
                }
                val body = resp.body ?: throw IllegalStateException("Empty APK response body")
                targetApkFile.outputStream().use { out ->
                    body.byteStream().copyTo(out, bufferSize = 64 * 1024)
                }
            }

            // Rust ABI & ZIP/Signature Guard
            val integrity = ObtainmondNative.inspectApk(targetApkFile.absolutePath)
            if (currentSettings.labRustAbiAndSignatureGuard) {
                if (!integrity.validApkZip) {
                    appendLog(
                        appId = currentApp.id,
                        appName = currentApp.name,
                        action = "Rust Integrity Guard",
                        backendUsed = "libobtainmond_core.so",
                        details = "Blocked corrupt APK: ${integrity.summary}",
                        success = false
                    )
                    return@withContext false
                }
                if (!integrity.abiCompatible) {
                    appendLog(
                        appId = currentApp.id,
                        appName = currentApp.name,
                        action = "Rust ABI Guard",
                        backendUsed = "libobtainmond_core.so",
                        details = "Blocked incompatible ABI: ${integrity.summary}",
                        success = false
                    )
                    return@withContext false
                }
            }

            // Extract real packageName and versionName from downloaded APK archive
            val archiveInfo = runCatching {
                appContext.packageManager.getPackageArchiveInfo(
                    targetApkFile.absolutePath,
                    PackageManager.GET_META_DATA
                )
            }.getOrNull()

            val realPackageId = archiveInfo?.packageName?.takeIf { it.isNotBlank() } ?: currentApp.id
            val archiveVersion = archiveInfo?.versionName
                ?.let { ObtainmondNative.safeNormalizeVersion(it, currentApp.versionExtractionRegex) }
                ?.takeIf { it.isNotBlank() }
                ?: currentApp.latestVersion

            // Lab Feature: Instant Rollback Time-Machine (backup current base.apk before updating)
            var backupPath = currentApp.backupApkPath
            var backupVer = currentApp.backupVersion
            if (currentSettings.labInstantRollbackBackup && currentApp.installedVersion.isNotBlank()) {
                val backupFile = PrivilegeManager.backupInstalledApkForRollback(appContext, realPackageId)
                if (backupFile != null) {
                    backupPath = backupFile.absolutePath
                    backupVer = currentApp.installedVersion
                }
            }

            // Execute silent on-the-fly installation via Shevery-API / Shizuku
            val installOutcome = PrivilegeManager.installApkSilentlyOrFallback(
                context = appContext,
                apkFile = targetApkFile,
                targetPackageId = realPackageId,
                settings = currentSettings,
                allowDowngrade = currentApp.allowDowngrade
            )

            val succeeded = installOutcome.isSuccess
            appendLog(
                appId = realPackageId,
                appName = currentApp.name,
                action = if (isBackground) "Background Silent Update" else "Silent Install",
                backendUsed = installOutcome.backendUsed,
                details = "${currentApp.matchedApkName} (${integrity.summary}) -> ${
                    installOutcome.stdout.ifBlank { installOutcome.stderr }
                }",
                success = succeeded
            )

            if (succeeded) {
                mutex.withLock {
                    _apps.value = _apps.value.map { item ->
                        if (item.id == appId || item.id == realPackageId) {
                            item.copy(
                                id = realPackageId,
                                installedVersion = archiveVersion,
                                latestVersion = archiveVersion,
                                lastUpdatedEpochMs = System.currentTimeMillis(),
                                backupApkPath = backupPath,
                                backupVersion = backupVer,
                                lastSha256 = integrity.sha256Hex
                            )
                        } else {
                            item
                        }
                    }
                    saveAppsToDisk(_apps.value)
                }
                syncInstalledPackagesState()
                postSilentUpdateNotification(
                    appName = currentApp.name,
                    newVersion = archiveVersion,
                    backend = installOutcome.backendUsed
                )
            }
            succeeded
        } catch (e: Throwable) {
            appendLog(
                appId = currentApp.id,
                appName = currentApp.name,
                action = if (isBackground) "Background Silent Update" else "Install Failed",
                backendUsed = "Obtainmond Engine",
                details = e.message ?: "Unexpected error during silent update",
                success = false
            )
            false
        } finally {
            _busyAppIds.value = _busyAppIds.value - appId
        }
    }

    /**
     * Lab Feature: 1-Click Instant Rollback (Downgrades the app to its backed-up APK using `pm install -r -d`).
     */
    suspend fun rollbackAppToBackup(appId: String): Boolean = withContext(Dispatchers.IO) {
        val app = _apps.value.firstOrNull { it.id == appId } ?: return@withContext false
        if (app.backupApkPath.isBlank()) return@withContext false
        val backupFile = File(app.backupApkPath)
        if (!backupFile.exists()) return@withContext false

        _busyAppIds.value = _busyAppIds.value + appId
        try {
            val outcome = PrivilegeManager.installApkSilentlyOrFallback(
                context = appContext,
                apkFile = backupFile,
                targetPackageId = app.id,
                settings = _settings.value,
                allowDowngrade = true
            )
            appendLog(
                appId = app.id,
                appName = app.name,
                action = "Lab Instant Rollback (-r -d)",
                backendUsed = outcome.backendUsed,
                details = "Rolled back to v${app.backupVersion}: ${outcome.stdout.ifBlank { outcome.stderr }}",
                success = outcome.isSuccess
            )
            syncInstalledPackagesState()
            outcome.isSuccess
        } finally {
            _busyAppIds.value = _busyAppIds.value - appId
        }
    }

    /**
     * Lab Feature: Freezes or unfreezes a tracked app via Shevery/Shizuku `pm suspend` / `pm unsuspend`.
     */
    suspend fun toggleFreezeApp(appId: String): Boolean = withContext(Dispatchers.IO) {
        val app = _apps.value.firstOrNull { it.id == appId } ?: return@withContext false
        val targetFreeze = !app.isFrozen
        val outcome = PrivilegeManager.setPackageFrozenState(app.id, targetFreeze, _settings.value)
        appendLog(
            appId = app.id,
            appName = app.name,
            action = if (targetFreeze) "Lab Freeze App" else "Lab Unfreeze App",
            backendUsed = outcome.backendUsed,
            details = outcome.stdout.ifBlank { outcome.stderr.ifBlank { "Exit ${outcome.exitCode}" } },
            success = outcome.isSuccess
        )
        syncInstalledPackagesState()
        outcome.isSuccess
    }

    /**
     * Exports tracked apps and settings in Obtainium/Obtainmond compatible JSON format.
     */
    fun exportConfigurationJson(): String {
        return json.encodeToString(_apps.value)
    }

    /**
     * Imports either an Obtainmond JSON array or a legacy Obtainium `{ "apps": [...] }` export.
     */
    suspend fun importConfigurationJson(rawJson: String): Int = withContext(Dispatchers.IO) {
        val trimmed = rawJson.trim()
        val importedApps = mutableListOf<TrackedApp>()

        val validPkgRegex = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
        runCatching {
            if (trimmed.startsWith("[")) {
                val decoded = json.decodeFromString<List<TrackedApp>>(trimmed)
                importedApps.addAll(decoded.filter { validPkgRegex.matches(it.id) })
            } else {
                val root = json.parseToJsonElement(trimmed).jsonObject
                val appsArr = root["apps"]?.jsonArray.orEmpty()
                for (el in appsArr) {
                    val obj = el.jsonObject
                    val url = obj["url"]?.jsonPrimitive?.content.orEmpty()
                    if (url.isBlank()) continue
                    val id = obj["id"]?.jsonPrimitive?.content.orEmpty()
                    val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
                    val author = obj["author"]?.jsonPrimitive?.content.orEmpty()
                    val installedVer = obj["installedVersion"]?.jsonPrimitive?.content.orEmpty()
                    val latestVer = obj["latestVersion"]?.jsonPrimitive?.content.orEmpty()
                    val desc = ObtainmondNative.resolveSource(url)
                    val safeId = id.takeIf { validPkgRegex.matches(it) } ?: desc.packageIdHint
                    importedApps.add(
                        TrackedApp(
                            id = safeId,
                            name = name.ifBlank { desc.appNameHint },
                            author = author.ifBlank { desc.owner },
                            url = desc.canonicalUrl,
                            sourceType = desc.sourceType,
                            apiUrl = desc.apiUrl,
                            installedVersion = ObtainmondNative.safeNormalizeVersion(installedVer),
                            latestVersion = ObtainmondNative.safeNormalizeVersion(latestVer),
                            silentAutoUpdate = true
                        )
                    )
                }
            }
        }

        if (importedApps.isNotEmpty()) {
            mutex.withLock {
                val existingUrls = _apps.value.map { it.url.lowercase() }.toSet()
                val merged = _apps.value + importedApps.filterNot { it.url.lowercase() in existingUrls }
                _apps.value = merged
                saveAppsToDisk(merged)
            }
            syncInstalledPackagesState()
        }
        importedApps.size
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_SILENT_UPDATES,
                "Zero-Touch Silent Updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications when Obtainmond silently updates apps in the background via Shevery/Shizuku"
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun postSilentUpdateNotification(appName: String, newVersion: String, backend: String) {
        runCatching {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val notif = NotificationCompat.Builder(appContext, CHANNEL_SILENT_UPDATES)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("$appName updated to v$newVersion")
                .setContentText("Silently installed in background via $backend")
                .setAutoCancel(true)
                .build()
            nm.notify((appName.hashCode() and 0x7FFFFFFF), notif)
        }
    }
}
