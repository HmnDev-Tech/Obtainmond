package io.obtainmond.data.repo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import io.obtainmond.data.model.AppEventLog
import io.obtainmond.data.model.FolderRule
import io.obtainmond.data.model.ObtainmondSettings
import io.obtainmond.data.model.PrivilegeBackendMode
import io.obtainmond.data.model.TrackedApp
import io.obtainmond.privilege.PrivilegeManager
import io.obtainmond.rust.ObtainmondNative
import io.obtainmond.rust.SourceDescriptorDto
import io.obtainmond.security.ApkSignatureVerifier
import io.obtainmond.security.HttpFetchResponse
import io.obtainmond.security.SplitBundleExtractor
import io.obtainmond.security.SupplyChainVerifier
import io.obtainmond.worker.AutoUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class DiscoveredDeviceApp(
    val packageId: String,
    val appName: String,
    val versionName: String,
    val installerPackage: String,
    val suggestedUrl: String,
    val isSystemUpdatedApp: Boolean
)

data class BulkImportCandidate(
    val packageId: String,
    val appName: String,
    val sourceUrl: String
)

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

    data class SecretStatus(
        val githubTokenCount: Int = 0,
        val hasGitLabToken: Boolean = false,
        val hasVirusTotalKey: Boolean = false
    ) {
        val hasGitHubToken: Boolean get() = githubTokenCount > 0
    }

    private val _secretStatus = MutableStateFlow(SecretStatus())
    val secretStatus: StateFlow<SecretStatus> = _secretStatus.asStateFlow()

    private lateinit var secretStore: SecretStore
    private lateinit var appStorage: AtomicAppStorage
    private val githubTokenCursor = AtomicInteger(0)
    private val logLock = Any()

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        secretStore = TinkEncryptedSecretStore(appContext)
        appStorage = AtomicAppStorage(
            appsDir = File(appContext.filesDir, "apps"),
            legacyAppsFile = appsFile(),
            json = json
        )
        createNotificationChannel()
        loadAllFromDisk()
        val initialSettings = _settings.value
        if (initialSettings.zeroTouchBackgroundUpdate) {
            AutoUpdateWorker.schedulePeriodic(
                appContext,
                initialSettings.backgroundIntervalMinutes,
                initialSettings.wifiOnlyBackgroundUpdates
            )
        }
        scope.launch {
            syncInstalledPackagesState()
            if (_settings.value.checkUpdatesOnStartup) {
                delay(600)
                runCatching {
                    refreshAllAndAutoUpdate(
                        autoInstallUpdates = _settings.value.zeroTouchBackgroundUpdate,
                        isBackgroundWorker = true
                    )
                }
            }
        }
    }

    private fun appsFile(): File = File(appContext.filesDir, "tracked_apps.json")
    private fun appsInitializedMarker(): File = File(appContext.filesDir, "apps/.initialized")
    private fun settingsFile(): File = File(appContext.filesDir, "obtainmond_settings.json")
    private fun logsFile(): File = File(appContext.filesDir, "event_logs.json")

    private fun loadAllFromDisk() {
        runCatching {
            val sf = settingsFile()
            if (sf.exists()) {
                val rawSettings = sf.readText()
                val decoded = json.decodeFromString<ObtainmondSettings>(rawSettings)
                _settings.value = decoded
                val legacyObj = runCatching { json.parseToJsonElement(rawSettings).jsonObject }.getOrNull()
                val legacyPat = legacyObj?.get("githubPersonalAccessToken")?.jsonPrimitive?.content?.trim().orEmpty()
                if (legacyPat.isNotBlank()) {
                    val existing = secretStore.load()
                    val merged = existing.copy(
                        githubTokens = (existing.githubTokens + parseTokenPoolInput(legacyPat)).distinct()
                    )
                    secretStore.save(merged)
                    sf.writeText(json.encodeToString(decoded))
                } else if (legacyObj?.containsKey("githubPersonalAccessToken") == true) {
                    sf.writeText(json.encodeToString(decoded))
                }
            }
        }
        refreshSecretStatus()
        runCatching {
            val loaded = appStorage.loadAll()
            val hasMigratedBefore = File(appContext.filesDir, "tracked_apps.json.migrated").exists()
            if (loaded.isNotEmpty() || appsInitializedMarker().exists() || hasMigratedBefore) {
                val sanitized = loaded.map { app ->
                    val filteredCats = app.categories.filterNot { it.equals("Rust", ignoreCase = true) }
                    if (filteredCats.size != app.categories.size) {
                        app.copy(categories = filteredCats)
                    } else {
                        app
                    }
                }
                _apps.value = sanitized
                if (sanitized != loaded || !appsInitializedMarker().exists()) {
                    saveAppsToDisk(sanitized)
                }
            } else {
                val defaults = listOf(
                    TrackedApp(
                        id = "moe.shizuku.privileged.api",
                        name = "Shevery",
                        author = "HmnDev-Tech",
                        url = "https://github.com/HmnDev-Tech/Shevery",
                        sourceType = "GITHUB",
                        apiUrl = "https://api.github.com/repos/HmnDev-Tech/Shevery/releases?per_page=100",
                        categories = listOf("Privilege", "System")
                    ),
                    TrackedApp(
                        id = "io.obtainmond",
                        name = "Obtainmond",
                        author = "HmnDev-Tech",
                        url = "https://github.com/HmnDev-Tech/Obtainmond",
                        sourceType = "GITHUB",
                        apiUrl = "https://api.github.com/repos/HmnDev-Tech/Obtainmond/releases?per_page=100",
                        categories = listOf("Updater")
                    )
                )
                _apps.value = defaults
                saveAppsToDisk(defaults)
            }
        }
        runCatching {
            val lf = logsFile()
            if (lf.exists()) {
                val loadedLogs = json.decodeFromString<List<AppEventLog>>(lf.readText())
                val sanitizedLogs = loadedLogs
                    .filterNot {
                        it.appId == appContext.packageName &&
                            it.details.contains("No release containing .apk assets found", ignoreCase = true)
                    }
                    .map { entry ->
                        if (entry.backendUsed == "Rust Release Engine") {
                            entry.copy(backendUsed = "Release Checker")
                        } else {
                            entry
                        }
                    }
                _logs.value = sanitizedLogs
                if (sanitizedLogs != loadedLogs) {
                    lf.writeText(json.encodeToString(sanitizedLogs))
                }
            }
        }
    }

    private fun saveAppsToDisk(list: List<TrackedApp>) {
        runCatching {
            if (::appStorage.isInitialized) {
                appStorage.syncList(list)
                appsInitializedMarker().apply {
                    parentFile?.mkdirs()
                    if (!exists()) writeText("1")
                }
            } else {
                appsFile().writeText(json.encodeToString(list))
            }
        }
    }

    private fun refreshSecretStatus() {
        val secrets = if (::secretStore.isInitialized) secretStore.load() else StoredSecrets()
        _secretStatus.value = SecretStatus(
            githubTokenCount = secrets.githubTokens.size,
            hasGitLabToken = secrets.gitlabToken.isNotBlank(),
            hasVirusTotalKey = secrets.virusTotalApiKey.isNotBlank()
        )
    }

    fun getGitHubTokenInput(): String {
        if (!::secretStore.isInitialized) return ""
        return secretStore.load().githubTokens.joinToString("\n")
    }

    fun updateGitHubTokenInput(rawInput: String) {
        if (!::secretStore.isInitialized) return
        val current = secretStore.load()
        secretStore.save(current.copy(githubTokens = parseTokenPoolInput(rawInput)))
        refreshSecretStatus()
    }

    fun getGitLabToken(): String = if (::secretStore.isInitialized) secretStore.load().gitlabToken else ""

    fun updateGitLabToken(rawToken: String) {
        if (!::secretStore.isInitialized) return
        val current = secretStore.load()
        secretStore.save(current.copy(gitlabToken = rawToken.trim()))
        refreshSecretStatus()
    }

    fun getVirusTotalApiKey(): String = if (::secretStore.isInitialized) secretStore.load().virusTotalApiKey else ""

    fun updateVirusTotalApiKey(rawKey: String) {
        if (!::secretStore.isInitialized) return
        val current = secretStore.load()
        secretStore.save(current.copy(virusTotalApiKey = rawKey.trim()))
        refreshSecretStatus()
    }

    internal fun resolveEffectiveDownloadUrl(
        rawDownloadUrl: String,
        settings: ObtainmondSettings
    ): Pair<String, Boolean> {
        val mirrorBase = settings.githubMirrorUrl.trim()
        val isHttpsMirror = mirrorBase.startsWith("https://", ignoreCase = true)
        if (settings.labGitHubMirrorAcceleration &&
            isHttpsMirror &&
            rawDownloadUrl.startsWith("https://github.com/")
        ) {
            return "${mirrorBase.trimEnd('/')}/$rawDownloadUrl" to true
        }
        return rawDownloadUrl to false
    }

    private suspend fun markAppError(
        appId: String,
        category: String,
        message: String
    ) {
        mutex.withLock {
            _apps.value = _apps.value.map { item ->
                if (item.id == appId) {
                    item.copy(errorCategory = category, lastError = message)
                } else {
                    item
                }
            }
            saveAppsToDisk(_apps.value)
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
        val updated = synchronized(logLock) {
            val next = (listOf(entry) + _logs.value).take(150)
            _logs.value = next
            next
        }
        scope.launch {
            runCatching {
                synchronized(logLock) {
                    logsFile().writeText(json.encodeToString(updated))
                }
            }
        }
    }

    fun clearLogs() {
        synchronized(logLock) {
            _logs.value = emptyList()
        }
        scope.launch {
            runCatching {
                synchronized(logLock) {
                    logsFile().delete()
                }
            }
        }
    }

    /**
     * Pure helper for strict package-ID matching (never matches by display name, preventing app spoofing).
     */
    internal fun applyMatchedPackageState(
        app: TrackedApp,
        matchedPackageId: String,
        versionName: String?,
        detectedAbi: String,
        isSuspended: Boolean,
        installedCertSha256: String
    ): TrackedApp {
        if (!matchedPackageId.equals(app.id, ignoreCase = true)) {
            return app
        }
        val ver = versionName ?: app.installedVersion
        val normalizedVer = ObtainmondNative.safeNormalizeVersion(ver, app.versionExtractionRegex)
        val effectiveAbi = detectedAbi.ifEmpty { app.installedAbi }
        val updatedTag = if (
            app.latestVersion.isNotBlank() &&
            ObtainmondNative.areVersionsEquivalent(normalizedVer, app.latestVersion)
        ) {
            app.latestVersion
        } else {
            app.installedReleaseTag
        }
        val pinError = if (
            app.pinnedSigningCertSha256.isNotBlank() &&
            installedCertSha256.isNotBlank() &&
            !app.pinnedSigningCertSha256.equals(installedCertSha256, ignoreCase = true)
        ) {
            "Installed app certificate (${installedCertSha256.take(12)}...) differs from pinned (${app.pinnedSigningCertSha256.take(12)}...)"
        } else {
            null
        }
        return app.copy(
            id = matchedPackageId,
            installedVersion = normalizedVer,
            installedAbi = effectiveAbi,
            isFrozen = isSuspended,
            installedReleaseTag = updatedTag,
            pinnedSigningCertSha256 = app.pinnedSigningCertSha256.ifBlank { installedCertSha256 },
            errorCategory = if (pinError != null) "SIGNATURE_MISMATCH" else app.errorCategory,
            lastError = pinError ?: app.lastError
        )
    }

    /**
     * Inspects Android's PackageManager strictly by exact package ID for every tracked app.
     */
    suspend fun syncInstalledPackagesState() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pm = appContext.packageManager
            val installedByPkg by lazy {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getInstalledPackages(0)
                    }
                }.getOrDefault(emptyList()).associateBy { it.packageName.lowercase() }
            }
            val updated = _apps.value.map { app ->
                val directInfo = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackageInfo(app.id, PackageManager.PackageInfoFlags.of(0L))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(app.id, 0)
                    }
                }.getOrNull()
                val pkgInfo = directInfo ?: installedByPkg[app.id.lowercase()]

                if (pkgInfo != null) {
                    val detectedAbi = detectInstalledPackageAbi(pkgInfo.applicationInfo?.nativeLibraryDir)
                    val suspended = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        (pkgInfo.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SUSPENDED != 0
                    } else {
                        false
                    }
                    val installedCert = ApkSignatureVerifier
                        .getInstalledPackageCertSha256s(appContext, pkgInfo.packageName)
                        .firstOrNull()
                        .orEmpty()
                    applyMatchedPackageState(
                        app = app,
                        matchedPackageId = pkgInfo.packageName,
                        versionName = pkgInfo.versionName,
                        detectedAbi = detectedAbi,
                        isSuspended = suspended,
                        installedCertSha256 = installedCert
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
        _busyAppIds.value = _busyAppIds.value + appId
        try {
            syncInstalledPackagesState()
            val current = _apps.value.firstOrNull { it.id == appId }
                ?: return@withContext Result.failure(IllegalStateException("App not found"))
            val result = runCatching { fetchLatestReleaseForApp(current) }
            result.onSuccess { updated ->
                mutex.withLock {
                    val next = _apps.value.map { if (it.id == appId) updated else it }
                    _apps.value = next
                    saveAppsToDisk(next)
                }
            }.onFailure { e ->
                val (cat, msg) = categorizeError(e)
                markAppError(appId, cat, msg)
                appendLog(
                    appId = current.id,
                    appName = current.name,
                    action = "Check Release",
                    backendUsed = "Release Checker",
                    details = msg,
                    success = false
                )
            }
            result
        } finally {
            _busyAppIds.value = _busyAppIds.value - appId
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

    internal fun applyFolderRules(app: TrackedApp, rules: List<FolderRule>): TrackedApp {
        if (rules.isEmpty()) return app
        val targetText = "${app.name}\n${app.url}\n${app.id}"
        val matched = rules.mapNotNull { rule ->
            val folder = rule.folderName.trim()
            if (folder.isBlank()) return@mapNotNull null
            val hasSourceFilter = rule.sourceTypes.any { it.isNotBlank() }
            val hasKeywordFilter = rule.keywords.any { it.isNotBlank() }
            val hasCategoryFilter = rule.includeCategories.any { it.isNotBlank() }
            if (!hasSourceFilter && !hasKeywordFilter && !hasCategoryFilter) return@mapNotNull null

            val sourceOk = !hasSourceFilter || rule.sourceTypes.any {
                it.trim().equals(app.sourceType, ignoreCase = true)
            }
            val keywordOk = !hasKeywordFilter || rule.keywords.any { kw ->
                val clean = kw.trim()
                clean.isNotEmpty() && (
                    targetText.contains(clean, ignoreCase = true) ||
                        runCatching { Regex(clean, RegexOption.IGNORE_CASE).containsMatchIn(targetText) }.getOrDefault(false)
                    )
            }
            val categoryOk = !hasCategoryFilter || app.categories.any { c ->
                rule.includeCategories.any { rc -> rc.trim().equals(c, ignoreCase = true) }
            }
            if (sourceOk && keywordOk && categoryOk) folder else null
        }
        if (matched.isEmpty()) return app
        return app.copy(folders = (app.folders + matched).distinct())
    }

    internal fun categorizeError(e: Throwable): Pair<String, String> {
        val msg = e.message ?: e.javaClass.simpleName
        val cat = when {
            msg.contains("429") || msg.contains("403") || msg.contains("rate limit", ignoreCase = true) -> "RATE_LIMIT"
            msg.contains("signature", ignoreCase = true) || msg.contains("certificate", ignoreCase = true) -> "SIGNATURE_MISMATCH"
            msg.contains("verify", ignoreCase = true) || msg.contains("attestation", ignoreCase = true) || msg.contains("virustotal", ignoreCase = true) -> "VERIFICATION"
            msg.contains("parse", ignoreCase = true) || msg.contains("No release", ignoreCase = true) -> "PARSE"
            else -> "NETWORK"
        }
        return cat to msg
    }

    internal fun fetchEndpointWithTokenRotation(
        endpoint: String,
        sourceType: String,
        githubTokens: List<String>,
        gitlabToken: String,
        tokenCursor: AtomicInteger = githubTokenCursor,
        httpCaller: (url: String, headers: Map<String, String>) -> HttpFetchResponse
    ): String {
        val baseHeaders = mutableMapOf(
            "User-Agent" to "Obtainmond/2.5.0 (Android; RustCore)",
            "Accept" to "application/json, text/html, application/xml;q=0.9, */*;q=0.8"
        )
        if (sourceType == "RUSTORE") {
            baseHeaders["ruStoreVerCode"] = "110802"
        }
        if (sourceType == "GITLAB" && gitlabToken.isNotBlank()) {
            baseHeaders["PRIVATE-TOKEN"] = gitlabToken
        }

        if (sourceType == "GITHUB" && githubTokens.isNotEmpty()) {
            val startIdx = (tokenCursor.getAndIncrement() and 0x7FFFFFFF) % githubTokens.size
            var lastCode = 0
            for (attempt in githubTokens.indices) {
                val token = githubTokens[(startIdx + attempt) % githubTokens.size]
                val headers = baseHeaders + ("Authorization" to "Bearer $token")
                val resp = httpCaller(endpoint, headers)
                if (resp.code in 200..299) {
                    return resp.body
                }
                lastCode = resp.code
                if (resp.code != 403 && resp.code != 429) {
                    throw IllegalStateException("HTTP ${resp.code} from $sourceType endpoint")
                }
            }
            throw IllegalStateException(
                "HTTP $lastCode rate limit from GITHUB (${githubTokens.size} token(s) exhausted)"
            )
        }

        val resp = httpCaller(endpoint, baseHeaders)
        if (resp.code !in 200..299) {
            throw IllegalStateException("HTTP ${resp.code} from $sourceType endpoint")
        }
        return resp.body
    }

    internal suspend fun refreshAppsWithConcurrency(
        snapshot: List<TrackedApp>,
        maxConcurrency: Int = 6,
        isBackgroundWorker: Boolean = false,
        fetcher: suspend (TrackedApp) -> TrackedApp,
        onError: (TrackedApp, Throwable) -> TrackedApp
    ): List<TrackedApp> = coroutineScope {
        val sem = Semaphore(maxConcurrency.coerceAtLeast(1))
        snapshot.map { app ->
            async {
                if (isBackgroundWorker && app.onDemandOnly) {
                    app
                } else {
                    sem.withPermit {
                        runCatching { fetcher(app) }.getOrElse { e -> onError(app, e) }
                    }
                }
            }
        }.awaitAll()
    }

    internal fun resolveEffectiveApiEndpoint(storedApiUrl: String, desc: SourceDescriptorDto): String {
        val trimmed = storedApiUrl.trim()
        if (trimmed.isBlank()) return desc.apiUrl
        val lower = trimmed.lowercase()
        val isLegacyGitHub = desc.sourceType == "GITHUB" &&
            lower.startsWith("https://api.github.com/repos/") &&
            (lower.endsWith("/releases") || lower.endsWith("/releases?per_page=15"))
        if (isLegacyGitHub) return desc.apiUrl

        val isLegacyForge = desc.sourceType in setOf("CODEBERG", "FORGEJO", "GITEA") &&
            (lower.endsWith("/releases") || lower.endsWith("/releases?limit=15"))
        if (isLegacyForge) return desc.apiUrl

        return trimmed
    }

    internal fun resolveGitHubLatestFallbackUrl(
        desc: SourceDescriptorDto,
        usedEndpoint: String,
        includePrereleases: Boolean
    ): String? {
        if (desc.sourceType != "GITHUB" || includePrereleases) return null
        if (desc.owner.isBlank() || desc.owner == "unknown" || desc.repo.isBlank() || desc.repo == "unknown") {
            return null
        }
        val cleanUsed = usedEndpoint.trimEnd('/').lowercase()
        if (cleanUsed.endsWith("/releases/latest")) return null
        return "https://api.github.com/repos/${desc.owner}/${desc.repo}/releases/latest"
    }

    /**
     * Queries the upstream release API and runs the Rust release parser + Rust smart ABI APK selector.
     */
    suspend fun fetchLatestReleaseForApp(app: TrackedApp): TrackedApp = withContext(Dispatchers.IO) {
        val desc = ObtainmondNative.resolveSource(app.url)
        val endpoint = resolveEffectiveApiEndpoint(app.apiUrl, desc)
        val currentSettings = _settings.value
        val secrets = if (desc.sourceType != "DIRECT_APK" && ::secretStore.isInitialized) {
            secretStore.load()
        } else {
            StoredSecrets()
        }
        val httpCaller: (String, Map<String, String>) -> HttpFetchResponse = { url, headers ->
            val rb = Request.Builder().url(url)
            headers.forEach { (k, v) -> rb.header(k, v) }
            httpClient.newCall(rb.build()).execute().use { resp ->
                HttpFetchResponse(resp.code, resp.body.string())
            }
        }

        val payload = if (desc.sourceType == "DIRECT_APK") {
            ""
        } else {
            fetchEndpointWithTokenRotation(
                endpoint = endpoint,
                sourceType = desc.sourceType,
                githubTokens = secrets.githubTokens,
                gitlabToken = secrets.gitlabToken,
                tokenCursor = githubTokenCursor,
                httpCaller = httpCaller
            )
        }

        var parsed = ObtainmondNative.parseRelease(
            sourceType = desc.sourceType,
            payload = payload,
            includePrereleases = app.includePrereleases,
            fallbackUrl = app.url
        )

        if (parsed.error != null) {
            val latestFallbackUrl = resolveGitHubLatestFallbackUrl(
                desc = desc,
                usedEndpoint = endpoint,
                includePrereleases = app.includePrereleases
            )
            if (latestFallbackUrl != null) {
                runCatching {
                    val latestPayload = fetchEndpointWithTokenRotation(
                        endpoint = latestFallbackUrl,
                        sourceType = desc.sourceType,
                        githubTokens = secrets.githubTokens,
                        gitlabToken = secrets.gitlabToken,
                        tokenCursor = githubTokenCursor,
                        httpCaller = httpCaller
                    )
                    ObtainmondNative.parseRelease(
                        sourceType = desc.sourceType,
                        payload = latestPayload,
                        includePrereleases = app.includePrereleases,
                        fallbackUrl = app.url
                    )
                }.getOrNull()?.let { latestParsed ->
                    if (latestParsed.error == null) {
                        parsed = latestParsed
                    }
                }
            }
        }

        if (parsed.error != null) {
            if (app.id == appContext.packageName && app.installedVersion.isNotBlank()) {
                val normalizedInstalled = ObtainmondNative.safeNormalizeVersion(app.installedVersion)
                return@withContext app.copy(
                    latestVersion = normalizedInstalled,
                    matchedAbi = app.installedAbi.ifBlank { "arm64-v8a" },
                    lastCheckedEpochMs = System.currentTimeMillis(),
                    installedReleaseTag = normalizedInstalled
                )
            }
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
            excludeDebug = currentSettings.excludeDebugApks
        )

        val matchedTag = if (
            app.installedVersion.isNotBlank() &&
            ObtainmondNative.areVersionsEquivalent(app.installedVersion, normalizedRemote)
        ) {
            normalizedRemote
        } else {
            app.installedReleaseTag
        }

        val keepPinError = app.errorCategory == "SIGNATURE_MISMATCH"
        val updated = app.copy(
            sourceType = desc.sourceType,
            apiUrl = endpoint,
            latestVersion = normalizedRemote,
            matchedApkName = selection.selectedName,
            matchedApkUrl = selection.selectedUrl,
            matchedAbi = selection.detectedAbi,
            selectionReason = "${selection.confidence}: ${selection.reason}",
            availableApks = parsed.apkAssets,
            changelog = parsed.changelog,
            lastCheckedEpochMs = System.currentTimeMillis(),
            installedReleaseTag = matchedTag,
            errorCategory = if (keepPinError) app.errorCategory else "",
            lastError = if (keepPinError) app.lastError else ""
        )
        applyFolderRules(updated, currentSettings.folderRules)
    }

    /**
     * Checks all tracked apps concurrently (bounded Semaphore) and, when [autoInstallUpdates]
     * is true, silently downloads and installs eligible updates (skipping on-demand-only apps
     * during background runs and blocked/errored apps).
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
        val updatedList = refreshAppsWithConcurrency(
            snapshot = snapshot,
            maxConcurrency = 6,
            isBackgroundWorker = isBackgroundWorker,
            fetcher = { app -> fetchLatestReleaseForApp(app) },
            onError = { app, e ->
                val (cat, msg) = categorizeError(e)
                appendLog(
                    appId = app.id,
                    appName = app.name,
                    action = "Check Release",
                    backendUsed = "Release Checker",
                    details = msg,
                    success = false
                )
                app.copy(errorCategory = cat, lastError = msg)
            }
        )

        mutex.withLock {
            _apps.value = updatedList
            saveAppsToDisk(updatedList)
        }

        var installedCount = 0
        if (autoInstallUpdates) {
            val candidates = _apps.value.filter { app ->
                app.hasUpdate &&
                    !app.trackOnly &&
                    !app.needsAttention &&
                    app.matchedApkUrl.isNotBlank() &&
                    (!isBackgroundWorker || (app.silentAutoUpdate && !app.onDemandOnly))
            }
            for (app in candidates) {
                val ok = downloadAndInstallAppSilently(app.id, isBackground = isBackgroundWorker)
                if (ok) installedCount++
            }
        }
        installedCount
    }

    private fun resolveRuStoreDownloadUrl(rawUri: String): String {
        val afterScheme = rawUri.removePrefix("rustore-appid://").trim()
        val appIdStr = afterScheme.substringBefore('/').trim()
        val appId = appIdStr.toLongOrNull()
            ?: throw IllegalStateException("Invalid RuStore appId marker: $rawUri")
        val payload = """{"appId":$appId,"firstInstall":true}"""
        val req = Request.Builder()
            .url("https://backapi.rustore.ru/applicationData/v2/download-link")
            .header("User-Agent", "Obtainmond/2.5.0 (Android; RuStoreClient)")
            .header("ruStoreVerCode", "110802")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("RuStore download-link failed with HTTP ${resp.code}")
            }
            val root = json.parseToJsonElement(resp.body.string()).jsonObject
            val bodyObj = root["body"]?.jsonObject ?: root
            val urls = bodyObj["downloadUrls"]?.jsonArray
            val resolved = urls?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
                ?: bodyObj["url"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("RuStore did not return a download URL")
            return resolved.trim()
        }
    }

    /**
     * Downloads the selected package/bundle for [appId], extracts split APKs if `.apks`/`.xapk`/`.apkm`,
     * verifies integrity, ABI compatibility, cryptographic signatures & pins across all splits,
     * runs optional Reproducible Builds / GitHub Attestation / VirusTotal verification,
     * backs up installed APKs (base + splits), and installs silently via active privilege backend.
     */
    suspend fun downloadAndInstallAppSilently(
        appId: String,
        overrideApkUrl: String? = null,
        isBackground: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val currentApp = _apps.value.firstOrNull { it.id == appId } ?: return@withContext false
        val rawUrlInput = (overrideApkUrl ?: currentApp.matchedApkUrl).trim()
        val rawDownloadUrl = if (rawUrlInput.startsWith("rustore-appid://")) {
            runCatching { resolveRuStoreDownloadUrl(rawUrlInput) }.getOrElse { e ->
                markAppError(currentApp.id, "NETWORK", e.message ?: "RuStore link error")
                appendLog(
                    appId = currentApp.id,
                    appName = currentApp.name,
                    action = "RuStore Resolve",
                    backendUsed = "Obtainmond Engine",
                    details = e.message ?: "Failed to resolve RuStore download URL",
                    success = false
                )
                    return@withContext false
            }
        } else {
            rawUrlInput
        }
        if (!rawDownloadUrl.startsWith("https://") && !rawDownloadUrl.startsWith("http://")) {
            return@withContext false
        }

        _busyAppIds.value = _busyAppIds.value + appId
        var stagingDir: File? = null
        var downloadedFile: File? = null
        val currentSettings = _settings.value
        try {
            val (effectiveUrl, usedMirror) = resolveEffectiveDownloadUrl(rawDownloadUrl, currentSettings)

            val downloadsDir = File(appContext.cacheDir, "apk_downloads").apply { mkdirs() }
            val fallbackExt = rawDownloadUrl.substringBefore('?').substringAfterLast('.', "apk")
                .takeIf { it.lowercase() in setOf("apk", "apks", "xapk", "apkm", "zip") } ?: "apk"
            val safeFileName = (currentApp.matchedApkName.ifBlank { "${currentApp.id}.$fallbackExt" })
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .trimStart('.')
                .ifBlank { "obtainmond_download.$fallbackExt" }
            val targetPackageFile = File(downloadsDir, safeFileName)
            downloadedFile = targetPackageFile

            val req = Request.Builder()
                .url(effectiveUrl)
                .header("User-Agent", "Obtainmond/2.5.0 (Android; RustCore)")
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Package download failed with HTTP ${resp.code}")
                }
                val body = resp.body
                targetPackageFile.outputStream().use { out ->
                    body.byteStream().copyTo(out, bufferSize = 64 * 1024)
                }
            }

            val isSplitBundle = SplitBundleExtractor.isSplitBundleFile(targetPackageFile)
            val deviceAbis = Build.SUPPORTED_ABIS?.toList().orEmpty()
            val apkFilesToInstall: List<File>
            val obbFiles: List<File>
            if (isSplitBundle) {
                val bundleStaging = File(downloadsDir, "staging_${currentApp.id.replace('.', '_')}_${System.currentTimeMillis()}")
                stagingDir = bundleStaging
                val extracted = SplitBundleExtractor.extractBundle(
                    bundleFile = targetPackageFile,
                    stagingDir = bundleStaging,
                    deviceAbis = deviceAbis
                )
                apkFilesToInstall = extracted.apkFiles
                obbFiles = extracted.obbEntries.map { it.sourceFile }
            } else {
                apkFilesToInstall = listOf(targetPackageFile)
                obbFiles = emptyList()
            }

            val enforceStrictGuard = currentSettings.labRustAbiAndSignatureGuard || usedMirror
            var primaryIntegrity = ObtainmondNative.inspectApk(apkFilesToInstall.first().absolutePath)
            val splitCertSets = mutableListOf<Set<String>>()
            var firstSigResult = ApkSignatureVerifier.verifyApkFile(apkFilesToInstall.first())

            for ((idx, apkFile) in apkFilesToInstall.withIndex()) {
                val integrity = if (idx == 0) primaryIntegrity else ObtainmondNative.inspectApk(apkFile.absolutePath)
                if (idx == 0) primaryIntegrity = integrity

                if (enforceStrictGuard) {
                    if (!integrity.validApkZip || !integrity.hasManifest || !integrity.hasSignatureBlock) {
                        val reason = "Blocked integrity/signature check (${apkFile.name}): ${integrity.summary}"
                        markAppError(currentApp.id, "INTEGRITY_FAILED", reason)
                        appendLog(
                            appId = currentApp.id,
                            appName = currentApp.name,
                            action = if (usedMirror) "Mirror Integrity Guard" else "Integrity Guard",
                            backendUsed = "libobtainmond_core.so",
                            details = reason,
                            success = false
                        )
                        return@withContext false
                    }
                    if (!integrity.abiCompatible) {
                        val reason = "Blocked incompatible ABI (${apkFile.name}): ${integrity.summary}"
                        markAppError(currentApp.id, "ABI_MISMATCH", reason)
                        appendLog(
                            appId = currentApp.id,
                            appName = currentApp.name,
                            action = "ABI Guard",
                            backendUsed = "libobtainmond_core.so",
                            details = reason,
                            success = false
                        )
                        return@withContext false
                    }
                }

                val sigResult = if (idx == 0) firstSigResult else ApkSignatureVerifier.verifyApkFile(apkFile)
                if (idx == 0) firstSigResult = sigResult
                splitCertSets.add(sigResult.signerCertSha256s.map { it.lowercase() }.toSet())
            }

            if (splitCertSets.distinct().size > 1) {
                val reason = "Blocked split APK bundle: inconsistent signing certificates across split files"
                markAppError(currentApp.id, "SIGNATURE_MISMATCH", reason)
                appendLog(
                    appId = currentApp.id,
                    appName = currentApp.name,
                    action = "Split Signature Guard",
                    backendUsed = "apksig",
                    details = reason,
                    success = false
                )
                return@withContext false
            }

            val baseApk = apkFilesToInstall.first()
            val archiveInfo = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.packageManager.getPackageArchiveInfo(
                        baseApk.absolutePath,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appContext.packageManager.getPackageArchiveInfo(
                        baseApk.absolutePath,
                        PackageManager.GET_META_DATA
                    )
                }
            }.getOrNull()

            val realPackageId = archiveInfo?.packageName?.takeIf { it.isNotBlank() } ?: currentApp.id
            val installedCerts = ApkSignatureVerifier.getInstalledPackageCertSha256s(appContext, realPackageId)
            val mustVerifyCryptoSig = enforceStrictGuard || installedCerts.isNotEmpty() || currentApp.pinnedSigningCertSha256.isNotBlank()
            if (mustVerifyCryptoSig) {
                val certError = ApkSignatureVerifier.validateUpdateCertificates(
                    verification = firstSigResult,
                    installedCertSha256s = installedCerts,
                    pinnedCertSha256 = currentApp.pinnedSigningCertSha256
                )
                if (certError != null) {
                    markAppError(currentApp.id, "SIGNATURE_MISMATCH", certError)
                    appendLog(
                        appId = currentApp.id,
                        appName = currentApp.name,
                        action = if (usedMirror) "Mirror Signature Guard" else "Signature Pinning Guard",
                        backendUsed = "apksig",
                        details = certError,
                        success = false
                    )
                    return@withContext false
                }
            }

            val secrets = if (::secretStore.isInitialized) secretStore.load() else StoredSecrets()
            val scResult = SupplyChainVerifier.withOkHttp(httpClient).verify(
                app = currentApp,
                packageId = realPackageId,
                apkSha256Hex = primaryIntegrity.sha256Hex,
                settings = currentSettings,
                githubToken = secrets.primaryGitHubToken(),
                virusTotalApiKey = secrets.virusTotalApiKey
            )
            if (scResult.checks.isNotEmpty()) {
                appendLog(
                    appId = realPackageId,
                    appName = currentApp.name,
                    action = if (scResult.shouldBlock) "Supply-Chain Blocked" else "Supply-Chain Audit",
                    backendUsed = "SupplyChainVerifier",
                    details = scResult.detailedReport,
                    success = !scResult.shouldBlock
                )
            }
            if (scResult.shouldBlock) {
                val blockMsg = scResult.detailedReport.ifBlank { "Blocked by supply-chain verification policy" }
                markAppError(currentApp.id, "VERIFICATION", blockMsg)
                return@withContext false
            }

            val archiveVersion = archiveInfo?.versionName
                ?.let { ObtainmondNative.safeNormalizeVersion(it, currentApp.versionExtractionRegex) }
                ?.takeIf { it.isNotBlank() }
                ?: currentApp.latestVersion

            var backupPath = currentApp.backupApkPath
            var backupVer = currentApp.backupVersion
            if (currentSettings.labInstantRollbackBackup && currentApp.installedVersion.isNotBlank()) {
                val backupDir = PrivilegeManager.backupInstalledApkForRollback(appContext, realPackageId)
                if (backupDir != null) {
                    backupPath = backupDir.absolutePath
                    backupVer = currentApp.installedVersion
                }
            }

            val installOutcome = PrivilegeManager.installApksSilentlyOrFallback(
                context = appContext,
                apkFiles = apkFilesToInstall,
                targetPackageId = realPackageId,
                settings = currentSettings,
                allowDowngrade = currentApp.allowDowngrade,
                obbFiles = obbFiles,
                externalSourceFile = if (isSplitBundle) targetPackageFile else null
            )

            val succeeded = installOutcome.isSuccess
            appendLog(
                appId = realPackageId,
                appName = currentApp.name,
                action = if (isBackground) "Background Silent Update" else "Silent Install",
                backendUsed = installOutcome.backendUsed,
                details = "${currentApp.matchedApkName} (${apkFilesToInstall.size} APK(s), ${primaryIntegrity.summary}) -> ${
                    installOutcome.stdout.ifBlank { installOutcome.stderr }
                }",
                success = succeeded
            )

            if (succeeded) {
                val releaseTag = currentApp.latestVersion.ifBlank { archiveVersion }
                mutex.withLock {
                    _apps.value = _apps.value.map { item ->
                        if (item.id == appId || item.id == realPackageId) {
                            item.copy(
                                id = realPackageId,
                                installedVersion = archiveVersion,
                                latestVersion = releaseTag,
                                lastUpdatedEpochMs = System.currentTimeMillis(),
                                backupApkPath = backupPath,
                                backupVersion = backupVer,
                                lastSha256 = primaryIntegrity.sha256Hex,
                                installedReleaseTag = releaseTag,
                                installedApkUrl = rawDownloadUrl,
                                pinnedSigningCertSha256 = firstSigResult.primaryFingerprint.ifBlank { item.pinnedSigningCertSha256 },
                                lastSupplyChainSummary = scResult.badgeSummary.ifBlank { item.lastSupplyChainSummary },
                                skippedVersion = "",
                                lastError = "",
                                errorCategory = ""
                            )
                        } else {
                            item
                        }
                    }
                    saveAppsToDisk(_apps.value)
                }
                syncInstalledPackagesState()
                if (currentSettings.showUpdateNotifications) {
                    postSilentUpdateNotification(
                        appName = currentApp.name,
                        newVersion = archiveVersion,
                        backend = installOutcome.backendUsed
                    )
                }
            } else {
                val errText = installOutcome.stderr.ifBlank { installOutcome.stdout }.ifBlank { "Install failed" }
                markAppError(currentApp.id, "INSTALL_FAILED", errText)
            }
            succeeded
        } catch (e: Throwable) {
            val (cat, msg) = categorizeError(e)
            markAppError(currentApp.id, cat, msg)
            appendLog(
                appId = currentApp.id,
                appName = currentApp.name,
                action = if (isBackground) "Background Silent Update" else "Install Failed",
                backendUsed = "Obtainmond Engine",
                details = msg,
                success = false
            )
            false
        } finally {
            stagingDir?.let { runCatching { it.deleteRecursively() } }
            if (currentSettings.deleteApkAfterInstall) {
                downloadedFile?.let { runCatching { it.delete() } }
            }
            _busyAppIds.value = _busyAppIds.value - appId
        }
    }

    /**
     * 1-Click Instant Rollback: restores all backed-up APKs (base + splits) and surfaces
     * Android 7+ `INSTALL_FAILED_VERSION_DOWNGRADE` diagnostic guidance cleanly on failure.
     */
    suspend fun rollbackAppToBackup(appId: String): Boolean = withContext(Dispatchers.IO) {
        val app = _apps.value.firstOrNull { it.id == appId } ?: return@withContext false
        if (app.backupApkPath.isBlank()) return@withContext false
        val backupApks = PrivilegeManager.resolveBackupApkFiles(File(app.backupApkPath))
        if (backupApks.isEmpty()) {
            markAppError(appId, "ROLLBACK_FAILED", "No valid rollback APK files found in vault")
            return@withContext false
        }

        _busyAppIds.value = _busyAppIds.value + appId
        try {
            val currentSettings = _settings.value
            val outcome = PrivilegeManager.installApksSilentlyOrFallback(
                context = appContext,
                apkFiles = backupApks,
                targetPackageId = app.id,
                settings = currentSettings,
                allowDowngrade = true
            )
            val details = if (outcome.isSuccess) {
                "Rolled back ${backupApks.size} APK(s) to v${app.backupVersion}: ${outcome.stdout}"
            } else {
                PrivilegeManager.analyzeRollbackDowngradeFailure(appContext, app.id, outcome, currentSettings)
            }
            if (!outcome.isSuccess) {
                markAppError(app.id, "ROLLBACK_FAILED", details)
            } else {
                clearAppError(app.id)
            }
            appendLog(
                appId = app.id,
                appName = app.name,
                action = "Instant Rollback (-r -d)",
                backendUsed = outcome.backendUsed,
                details = details,
                success = outcome.isSuccess
            )
            syncInstalledPackagesState()
            outcome.isSuccess
        } finally {
            _busyAppIds.value = _busyAppIds.value - appId
        }
    }

    fun skipVersion(appId: String, version: String) {
        updateApp(appId) { it.copy(skippedVersion = version.trim()) }
    }

    fun clearAppError(appId: String) {
        updateApp(appId) { it.copy(lastError = "", errorCategory = "") }
    }

    fun setOnDemandOnly(appId: String, onDemand: Boolean) {
        updateApp(appId) { it.copy(onDemandOnly = onDemand) }
    }

    fun setAppFolders(appId: String, folders: List<String>) {
        updateApp(appId) { it.copy(folders = folders.map { f -> f.trim() }.filter { f -> f.isNotEmpty() }.distinct()) }
    }

    suspend fun discoverInstallableDeviceApps(): List<DiscoveredDeviceApp> = withContext(Dispatchers.IO) {
        if (!::appContext.isInitialized) return@withContext emptyList()
        val pm = appContext.packageManager
        val trackedIds = _apps.value.map { it.id.lowercase() }.toSet()
        val packages = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
        }.getOrDefault(emptyList())

        packages.mapNotNull { pkgInfo ->
            val pkgId = pkgInfo.packageName
            if (pkgId.lowercase() in trackedIds) return@mapNotNull null
            val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem && !isUpdatedSystem) return@mapNotNull null

            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkgId)
            val installer = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    pm.getInstallSourceInfo(pkgId).installingPackageName.orEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstallerPackageName(pkgId).orEmpty()
                }
            }.getOrDefault("")

            val suggestedUrl = when {
                installer.contains("fdroid", ignoreCase = true) -> "https://f-droid.org/packages/$pkgId/"
                installer.contains("izzy", ignoreCase = true) -> "https://apt.izzysoft.de/fdroid/index/apk/$pkgId"
                else -> "https://apt.izzysoft.de/fdroid/index/apk/$pkgId"
            }

            DiscoveredDeviceApp(
                packageId = pkgId,
                appName = label,
                versionName = pkgInfo.versionName ?: "",
                installerPackage = installer,
                suggestedUrl = suggestedUrl,
                isSystemUpdatedApp = isUpdatedSystem
            )
        }.sortedBy { it.appName.lowercase() }
    }

    suspend fun bulkImportApps(candidates: List<BulkImportCandidate>): Int = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext 0
        var imported = 0
        coroutineScope {
            val sem = Semaphore(4)
            val results = candidates.filter { it.sourceUrl.isNotBlank() }.map { candidate ->
                async {
                    sem.withPermit {
                        addAppByUrl(
                            rawUrl = candidate.sourceUrl,
                            customPackageId = candidate.packageId,
                            customName = candidate.appName,
                            installImmediately = false
                        ).isSuccess
                    }
                }
            }.awaitAll()
            imported = results.count { it }
        }
        imported
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

    fun getApkCacheSizeBytes(): Long {
        if (!::appContext.isInitialized) return 0L
        val dir = File(appContext.cacheDir, "apk_downloads")
        if (!dir.exists()) return 0L
        return dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    }

    fun clearApkCache(): Int {
        if (!::appContext.isInitialized) return 0
        val dir = File(appContext.cacheDir, "apk_downloads")
        if (!dir.exists()) return 0
        var deleted = 0
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name.startsWith("staging_")) {
                if (file.deleteRecursively()) deleted++
            } else if (file.isFile) {
                val lower = file.name.lowercase()
                if ((lower.endsWith(".apk") || SplitBundleExtractor.isSplitBundleFile(file)) && file.delete()) {
                    deleted++
                }
            }
        }
        return deleted
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
