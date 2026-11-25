package io.obtainmond.privilege

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.FileProvider
import com.hamondev.shevery.api.SheveryApiConstants
import com.hamondev.shevery.api.SheveryPrivilege
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import io.obtainmond.R
import io.obtainmond.data.model.ObtainmondSettings
import io.obtainmond.data.model.PrivilegeBackendMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File
import kotlin.coroutines.resume
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class PrivilegeStatus(
    val sheveryBinderAlive: Boolean = false,
    val sheveryGranted: Boolean = false,
    val sheveryCapabilities: Int = 0,
    val shizukuBinderAlive: Boolean = false,
    val shizukuGranted: Boolean = false,
    val dhizukuAvailable: Boolean = false,
    val dhizukuGranted: Boolean = false,
    val serverUid: Int = -1,
    val serverVersion: Int = -1,
    val batteryOptimizationIgnored: Boolean = false,
    val activeBackendLabel: String = "Checking..."
) {
    val hasPrivilegedShell: Boolean
        get() = (sheveryBinderAlive && sheveryGranted) || (shizukuBinderAlive && shizukuGranted)

    val hasDhizuku: Boolean
        get() = dhizukuAvailable && dhizukuGranted
}

data class ShellExecOutcome(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val backendUsed: String,
    val installPending: Boolean = false
) {
    val isSuccess: Boolean
        get() = !installPending && exitCode == 0 && !stdout.contains("Failure [", ignoreCase = true)

    internal val isInstallSuccess: Boolean
        get() = isSuccess && stderr.isBlank() && stdout.trim() == "Success"
}

object PrivilegeManager {
    private const val REQUEST_CODE_PRIVILEGE = 4201
    private val VALID_PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    private val ALLOWED_DEXOPT_MODES = setOf("speed-profile", "speed", "everything", "quicken")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshInFlight = AtomicBoolean(false)
    private val installResults = ConcurrentHashMap<Int, CompletableDeferred<Intent>>()

    internal fun onPackageInstallerResult(intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        installResults[sessionId]?.complete(intent)
    }

    private val _status = MutableStateFlow(PrivilegeStatus())
    val status: StateFlow<PrivilegeStatus> = _status.asStateFlow()

    @Volatile
    private var initialized = false
    private lateinit var appContext: Context

    private fun validatePackageName(packageName: String): String {
        val trimmed = packageName.trim()
        require(VALID_PACKAGE_REGEX.matches(trimmed)) { "Invalid Android package name: $packageName" }
        return trimmed
    }

    private fun shellQuote(arg: String): String = "'" + arg.replace("'", "'\"'\"'") + "'"

    private val sheveryBinderReceivedListener = SheveryPrivilege.OnBinderReceivedListener {
        refreshStatus(connectShevery = false)
    }
    private val sheveryBinderDeadListener = SheveryPrivilege.OnBinderDeadListener {
        refreshStatus(connectShevery = false)
    }
    private val sheveryPermListener =
        SheveryPrivilege.OnPermissionResultListener { _, _ ->
            refreshStatus(connectShevery = false)
        }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshStatus(connectShevery = false)
    }
    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        refreshStatus(connectShevery = false)
    }
    private val shizukuPermListener =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            refreshStatus(connectShevery = false)
        }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!initialized) {
            initialized = true
            SheveryPrivilege.addBinderReceivedListenerSticky(sheveryBinderReceivedListener)
            SheveryPrivilege.addBinderDeadListener(sheveryBinderDeadListener)
            SheveryPrivilege.addPermissionResultListener(sheveryPermListener)

            Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
            Shizuku.addRequestPermissionResultListener(shizukuPermListener)
        }
        refreshStatus(connectShevery = true)
    }

    fun refreshStatus(connectShevery: Boolean = true) {
        if (!::appContext.isInitialized) return
        scope.launch {
            refreshStatusSuspend(connectShevery)
        }
    }

    private suspend fun refreshStatusSuspend(connectShevery: Boolean = true) = withContext(Dispatchers.IO) {
        if (!::appContext.isInitialized) return@withContext
        if (connectShevery && refreshInFlight.compareAndSet(false, true)) {
            try {
                runCatching { SheveryPrivilege.connectFast(appContext) }
            } finally {
                refreshInFlight.set(false)
            }
        }

        val sheveryAlive = runCatching { SheveryPrivilege.pingBinder() }.getOrDefault(false)
        val sheveryGranted = sheveryAlive && runCatching { SheveryPrivilege.isGranted() }.getOrDefault(false)
        val caps = if (sheveryAlive) runCatching { SheveryPrivilege.getCapabilities() }.getOrDefault(0) else 0

        val shizukuAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val shizukuGranted = shizukuAlive && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        val dhizukuAvail = runCatching { Dhizuku.init(appContext) }.getOrDefault(false)
        val dhizukuGranted = dhizukuAvail && runCatching { Dhizuku.isPermissionGranted() }.getOrDefault(false)

        val uid = when {
            sheveryAlive -> runCatching { SheveryPrivilege.getUid() }.getOrDefault(-1)
            shizukuAlive -> runCatching { Shizuku.getUid() }.getOrDefault(-1)
            else -> -1
        }
        val ver = when {
            sheveryAlive -> runCatching { SheveryPrivilege.getVersion() }.getOrDefault(-1)
            shizukuAlive -> runCatching { Shizuku.getVersion() }.getOrDefault(-1)
            else -> -1
        }

        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryIgnored = pm?.isIgnoringBatteryOptimizations(appContext.packageName) ?: false

        val label = when {
            sheveryGranted -> {
                val mode = if (uid == 0) "Root" else "ADB"
                "Shevery-API Fast IPC ($mode)"
            }
            shizukuGranted -> {
                val mode = if (uid == 0) "Root" else "ADB"
                "Shizuku Binder ($mode)"
            }
            dhizukuGranted -> "Dhizuku (Device Owner)"
            sheveryAlive || shizukuAlive || dhizukuAvail -> "Daemon Connected (Permission Needed)"
            else -> "Unprivileged (Standard Mode)"
        }

        _status.value = PrivilegeStatus(
            sheveryBinderAlive = sheveryAlive,
            sheveryGranted = sheveryGranted,
            sheveryCapabilities = caps,
            shizukuBinderAlive = shizukuAlive,
            shizukuGranted = shizukuGranted,
            dhizukuAvailable = dhizukuAvail,
            dhizukuGranted = dhizukuGranted,
            serverUid = uid,
            serverVersion = ver,
            batteryOptimizationIgnored = batteryIgnored,
            activeBackendLabel = label
        )
    }

    fun requestPrivilegePermission(context: Context) {
        val appCtx = context.applicationContext
        scope.launch {
            runCatching { SheveryPrivilege.connectFast(appCtx) }
            if (runCatching { SheveryPrivilege.pingBinder() }.getOrDefault(false)) {
                if (!SheveryPrivilege.isGranted()) {
                    runCatching { SheveryPrivilege.requestPermission(REQUEST_CODE_PRIVILEGE) }
                    return@launch
                }
            }
            if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    runCatching { Shizuku.requestPermission(REQUEST_CODE_PRIVILEGE) }
                    return@launch
                }
            }
            if (runCatching { Dhizuku.init(appCtx) }.getOrDefault(false) &&
                !runCatching { Dhizuku.isPermissionGranted() }.getOrDefault(false)
            ) {
                requestDhizukuPermission(appCtx)
            }
        }
    }

    fun requestDhizukuPermission(context: Context) {
        val appCtx = context.applicationContext
        runCatching {
            if (Dhizuku.init(appCtx) && !Dhizuku.isPermissionGranted()) {
                Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                    override fun onRequestPermission(grantResult: Int) {
                        refreshStatus(connectShevery = false)
                    }
                })
            }
        }
    }

    /**
     * Executes a privileged shell command using Shevery-API's `execFast()` first,
     * falling back to `SheveryPrivilege.newProcess`, `Shizuku`, or `su -c`.
     */
    suspend fun execPrivilegedShell(
        command: String,
        preferredMode: PrivilegeBackendMode = PrivilegeBackendMode.AUTO,
        timeoutMs: Long = 90_000L
    ): ShellExecOutcome = withContext(Dispatchers.IO) {
        refreshStatusSuspend(connectShevery = true)
        val current = _status.value

        val tryShevery = preferredMode == PrivilegeBackendMode.AUTO || preferredMode == PrivilegeBackendMode.SHEVERY
        val tryShizuku = preferredMode == PrivilegeBackendMode.AUTO || preferredMode == PrivilegeBackendMode.SHIZUKU
        val tryRoot = preferredMode == PrivilegeBackendMode.AUTO || preferredMode == PrivilegeBackendMode.ROOT

        // 1. Primary Fast Path: Shevery-API v14.6.4 execFast()
        if (tryShevery && current.sheveryBinderAlive && current.sheveryGranted) {
            val fastOutcome = runCatching {
                val canFastExec = SheveryPrivilege.hasCapability(SheveryApiConstants.CAP_SHELL_EXEC) ||
                    current.sheveryCapabilities == 0
                if (canFastExec) {
                    val res = SheveryPrivilege.execFast(
                        arrayOf("sh", "-c", command),
                        null,
                        null,
                        timeoutMs
                    )
                    ShellExecOutcome(
                        exitCode = res.exitCode,
                        stdout = res.outputText.trim(),
                        stderr = res.errorText.trim(),
                        backendUsed = "Shevery-API execFast"
                    )
                } else {
                    null
                }
            }.getOrNull()

            if (fastOutcome != null) {
                return@withContext fastOutcome
            }

            // Fallback to SheveryRemoteProcess if server is older than execFast transaction
            val procOutcome = runCatching {
                val proc = SheveryPrivilege.newProcess(arrayOf("sh", "-c", command), null, null)
                val out = proc.inputStream.bufferedReader().use { it.readText() }
                val err = proc.errorStream.bufferedReader().use { it.readText() }
                val code = proc.waitFor()
                proc.destroy()
                ShellExecOutcome(
                    exitCode = code,
                    stdout = out.trim(),
                    stderr = err.trim(),
                    backendUsed = "Shevery RemoteProcess"
                )
            }.getOrNull()

            if (procOutcome != null) {
                return@withContext procOutcome
            }
        }

        // 2. Secondary Path: Shizuku Compatibility API
        if (tryShizuku && current.shizukuBinderAlive && current.shizukuGranted) {
            val shizukuOutcome = runCatching {
                val proc = startShizukuProcess(command)
                val out = proc.inputStream.bufferedReader().use { it.readText() }
                val err = proc.errorStream.bufferedReader().use { it.readText() }
                val code = proc.waitFor()
                proc.destroy()
                ShellExecOutcome(
                    exitCode = code,
                    stdout = out.trim(),
                    stderr = err.trim(),
                    backendUsed = "Shizuku Binder"
                )
            }.getOrNull()

            if (shizukuOutcome != null) {
                return@withContext shizukuOutcome
            }
        }

        // 3. Tertiary Path: Root su shell
        if (tryRoot) {
            val rootOutcome = runCatching {
                val proc = ProcessBuilder("su", "-c", command).start()
                val out = proc.inputStream.bufferedReader().use { it.readText() }
                val err = proc.errorStream.bufferedReader().use { it.readText() }
                val code = proc.waitFor()
                ShellExecOutcome(
                    exitCode = code,
                    stdout = out.trim(),
                    stderr = err.trim(),
                    backendUsed = "Root (su)"
                )
            }.getOrNull()

            if (rootOutcome != null && rootOutcome.exitCode == 0) {
                return@withContext rootOutcome
            }
        }

        ShellExecOutcome(
            exitCode = -1,
            stdout = "",
            stderr = "No privileged backend (Shevery / Shizuku / Root) available or granted",
            backendUsed = "None"
        )
    }

    /**
     * Automatically whitelists `io.obtainmond` from Android Doze / Battery Optimizations
     * and sets its standby bucket to ACTIVE using Shevery/Shizuku privileges so background
     * auto-updates never get throttled without requiring the user to open Settings.
     */
    suspend fun enforceZeroBatteryRestriction(
        context: Context,
        settings: ObtainmondSettings
    ): ShellExecOutcome {
        val pkg = shellQuote(validatePackageName(context.packageName))
        val cmds = buildList {
            add("dumpsys deviceidle whitelist +$pkg")
            add("cmd deviceidle whitelist +$pkg")
            add("cmd appops set $pkg RUN_IN_BACKGROUND allow")
            add("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
            add("cmd appops set $pkg REQUEST_INSTALL_PACKAGES allow")
            add("cmd appops set $pkg RUN_USER_INITIATED_JOBS allow")
            if (settings.labImmortalStandbyBucketActive) {
                add("am set-standby-bucket $pkg active")
            }
        }.joinToString(" ; ")

        val res = execPrivilegedShell(cmds, settings.privilegeMode, timeoutMs = 15_000L)
        refreshStatusSuspend(connectShevery = false)
        return res
    }

    fun requestBatteryExemptionDialogIfNeeded(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            runCatching {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    fun isPackageDebuggable(context: Context, packageName: String): Boolean {
        return runCatching {
            val safePkg = validatePackageName(packageName)
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    safePkg,
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(safePkg, 0)
            }
            (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }.getOrDefault(false)
    }

    fun resolveBackupApkFiles(backupPathOrFile: File): List<File> {
        if (!backupPathOrFile.exists()) return emptyList()
        if (backupPathOrFile.isFile) {
            return if (backupPathOrFile.name.endsWith(".apk", ignoreCase = true) &&
                backupPathOrFile.length() >= 22L
            ) {
                listOf(backupPathOrFile)
            } else {
                emptyList()
            }
        }
        val apks = backupPathOrFile.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) && it.length() >= 22L }
            ?.sortedWith(compareBy<File> { it.name != "base.apk" }.thenBy { it.name })
            .orEmpty()
        return apks
    }

    fun resolveRollbackBackup(context: Context, packageName: String, path: String): File? = runCatching {
        val safePkg = validatePackageName(packageName)
        val vault = File(context.filesDir, "rollback_vault").canonicalFile
        File(path).canonicalFile.takeIf {
            it.parentFile == vault && (
                it.name == safePkg || it.name == "${safePkg}_rollback.apk" ||
                    it.name.startsWith("${safePkg}_rollback_")
                )
        }
    }.getOrNull()

    fun analyzeRollbackDowngradeFailure(
        context: Context,
        packageName: String,
        outcome: ShellExecOutcome,
        settings: ObtainmondSettings
    ): String {
        val raw = "${outcome.stdout} ${outcome.stderr}"
        val isDowngradeErr = raw.contains("INSTALL_FAILED_VERSION_DOWNGRADE", ignoreCase = true) ||
            raw.contains("downgrade", ignoreCase = true)
        if (!isDowngradeErr) {
            return outcome.stderr.ifBlank { outcome.stdout }.ifBlank { "Rollback install failed" }
        }
        val debuggable = isPackageDebuggable(context, packageName)
        return when {
            settings.privilegeMode == PrivilegeBackendMode.SYSTEM ||
                settings.privilegeMode == PrivilegeBackendMode.EXTERNAL ||
                settings.privilegeMode == PrivilegeBackendMode.DHIZUKU -> {
                "Android blocks downgrades without a privileged ADB/Root shell (`pm install -r -d`). " +
                    "Switch backend to Shevery/Shizuku/Root or uninstall first (data will be lost)."
            }
            !debuggable -> {
                "Blocked by Android 7+ (`INSTALL_FAILED_VERSION_DOWNGRADE`): production `user` builds " +
                    "restrict `-d` downgrades on non-debuggable release APKs. Uninstall the newer version first " +
                    "if you still want to restore the snapshot (app data will be cleared unless backed up)."
            }
            else -> {
                "Rollback rejected with `INSTALL_FAILED_VERSION_DOWNGRADE` (${outcome.stdout.ifBlank { outcome.stderr }})."
            }
        }
    }

    /**
     * Copies the currently installed base APK and any split APKs of [packageName] into
     * a new rollback snapshot before an update so the previous backup stays intact.
     */
    suspend fun backupInstalledApkForRollback(
        context: Context,
        packageName: String
    ): File? = withContext(Dispatchers.IO) {
        var newBackup: File? = null
        runCatching {
            val safePkg = validatePackageName(packageName)
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    safePkg,
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(safePkg, 0)
            }
            val srcBase = File(appInfo.sourceDir)
            if (!srcBase.exists()) return@withContext null

            val vaultRoot = File(context.filesDir, "rollback_vault").apply { mkdirs() }
            val pkgVaultDir = Files.createTempDirectory(vaultRoot.toPath(), "${safePkg}_rollback_").toFile()
            newBackup = pkgVaultDir
            srcBase.copyTo(File(pkgVaultDir, "base.apk"))

            val splits = appInfo.splitSourceDirs?.filter { it.isNotBlank() }.orEmpty()
            splits.forEachIndexed { idx, splitPath ->
                val splitSrc = File(splitPath)
                val safeName = splitSrc.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
                splitSrc.copyTo(File(pkgVaultDir, "split_${idx}_$safeName"))
            }

            val debuggable = (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            File(pkgVaultDir, "rollback_meta.txt").writeText(
                "package=$safePkg\nsplits=${splits.size}\ndebuggable=$debuggable\n",
                Charsets.UTF_8
            )
            pkgVaultDir
        }.getOrElse {
            newBackup?.deleteRecursively()
            null
        }
    }

    suspend fun installApkSilentlyOrFallback(
        context: Context,
        apkFile: File,
        targetPackageId: String,
        settings: ObtainmondSettings,
        allowDowngrade: Boolean = false
    ): ShellExecOutcome = installApksSilentlyOrFallback(
        context = context,
        apkFiles = listOf(apkFile),
        targetPackageId = targetPackageId,
        settings = settings,
        allowDowngrade = allowDowngrade
    )

    suspend fun installApksSilentlyOrFallback(
        context: Context,
        apkFiles: List<File>,
        targetPackageId: String,
        settings: ObtainmondSettings,
        allowDowngrade: Boolean = false,
        obbFiles: List<File> = emptyList(),
        externalSourceFile: File? = null
    ): ShellExecOutcome = withContext(Dispatchers.IO) {
        val validApks = apkFiles.filter { it.exists() && it.isFile && it.length() >= 22L }
        if (validApks.isEmpty() || validApks.size != apkFiles.size) {
            return@withContext ShellExecOutcome(-1, "", "No valid APK files to install", "Validation")
        }

        val safeTargetPkg = if (targetPackageId.isNotBlank()) {
            runCatching { validatePackageName(targetPackageId) }.getOrNull().orEmpty()
        } else {
            ""
        }

        if (obbFiles.isNotEmpty() && safeTargetPkg.isNotBlank()) {
            stageObbEntries(safeTargetPkg, obbFiles, settings)
        }

        if (settings.privilegeMode == PrivilegeBackendMode.EXTERNAL) {
            return@withContext launchExternalInstaller(
                context = context,
                fileToShare = externalSourceFile?.takeIf { it.exists() } ?: validApks.first(),
                installerPackage = settings.externalInstallerPackage
            )
        }

        val allowShell = settings.privilegeMode == PrivilegeBackendMode.AUTO ||
            settings.privilegeMode == PrivilegeBackendMode.SHEVERY ||
            settings.privilegeMode == PrivilegeBackendMode.SHIZUKU ||
            settings.privilegeMode == PrivilegeBackendMode.ROOT

        if (allowShell) {
            val shellOutcome = installApksViaShell(context, validApks, safeTargetPkg, settings, allowDowngrade)
            if (shellOutcome.isInstallSuccess) {
                return@withContext shellOutcome
            }
            if (settings.privilegeMode == PrivilegeBackendMode.SHEVERY ||
                settings.privilegeMode == PrivilegeBackendMode.SHIZUKU ||
                settings.privilegeMode == PrivilegeBackendMode.ROOT
            ) {
                return@withContext shellOutcome
            }
            if (allowDowngrade && shellOutcome.stdout.contains("INSTALL_FAILED_VERSION_DOWNGRADE", ignoreCase = true)) {
                return@withContext shellOutcome
            }
        }

        if (settings.privilegeMode == PrivilegeBackendMode.DHIZUKU ||
            settings.privilegeMode == PrivilegeBackendMode.AUTO
        ) {
            val dhizukuOutcome = tryDhizukuPackageInstallerSession(context, validApks, safeTargetPkg)
            if (dhizukuOutcome != null) {
                return@withContext dhizukuOutcome
            }
            if (settings.privilegeMode == PrivilegeBackendMode.DHIZUKU) {
                return@withContext ShellExecOutcome(-1, "", context.getString(R.string.dhizuku_session_unavailable), "Dhizuku")
            }
        }

        val sessionOutcome = commitStandardPackageInstallerSession(
            packageInstaller = context.packageManager.packageInstaller,
            context = context,
            apkFiles = validApks,
            targetPackageId = safeTargetPkg,
            backendLabel = "System PackageInstaller"
        )
        if (sessionOutcome != null) {
            return@withContext sessionOutcome
        }

        if (validApks.size > 1) {
            return@withContext ShellExecOutcome(
                exitCode = -1,
                stdout = "",
                stderr = "Split APK bundles require Shevery, Shizuku, Dhizuku, Root, or External Installer",
                backendUsed = "System PackageInstaller"
            )
        }

        launchExternalInstaller(
            context = context,
            fileToShare = validApks.first(),
            installerPackage = ""
        )
    }

    private fun startShizukuProcess(command: String): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
        return method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
    }

    private class InstallShell(val label: String, val start: (String) -> Process)

    private suspend fun runInstallCommand(
        shell: InstallShell,
        command: String,
        failureMessage: String,
        apk: File? = null,
        timeoutMs: Long = 90_000L
    ): ShellExecOutcome {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val process = AtomicReference<Process>()
                val worker = scope.launch {
                    val outcome = try {
                        // Merge stderr before draining the process output. Keep the APK in app-private storage.
                        val proc = shell.start("exec $command 2>&1")
                        process.set(proc)
                        ensureActive()
                        val transferred = if (apk == null) {
                            true
                        } else {
                            val expectedBytes = apk.length()
                            apk.inputStream().use { input ->
                                proc.outputStream.use { output ->
                                    input.copyTo(output, bufferSize = 64 * 1024) == expectedBytes
                                }
                            }
                        }
                        val output = proc.inputStream.bufferedReader().use { it.readText().trim() }
                        ShellExecOutcome(if (transferred) proc.waitFor() else -1, output, "", shell.label)
                    } catch (e: Exception) {
                        ShellExecOutcome(-1, "", e.message ?: failureMessage, shell.label)
                    } finally {
                        process.getAndSet(null)?.let { runCatching { it.destroy() } }
                    }
                    continuation.resume(outcome)
                }
                continuation.invokeOnCancellation {
                    worker.cancel()
                    process.getAndSet(null)?.let { running ->
                        scope.launch { runCatching { running.destroy() } }
                    }
                }
            }
        } ?: ShellExecOutcome(-1, "", failureMessage, shell.label)
    }

    private suspend fun installApksViaShell(
        context: Context,
        apkFiles: List<File>,
        safeTargetPkg: String,
        settings: ObtainmondSettings,
        allowDowngrade: Boolean
    ): ShellExecOutcome {
        refreshStatusSuspend(connectShevery = true)
        val current = _status.value
        val mode = settings.privilegeMode
        val shells = buildList {
            if ((mode == PrivilegeBackendMode.AUTO || mode == PrivilegeBackendMode.SHEVERY) &&
                current.sheveryBinderAlive && current.sheveryGranted
            ) {
                add(InstallShell("Shevery RemoteProcess") { command ->
                    SheveryPrivilege.newProcess(arrayOf("sh", "-c", command), null, null)
                })
            }
            if ((mode == PrivilegeBackendMode.AUTO || mode == PrivilegeBackendMode.SHIZUKU) &&
                current.shizukuBinderAlive && current.shizukuGranted
            ) {
                add(InstallShell("Shizuku Binder", ::startShizukuProcess))
            }
            if (mode == PrivilegeBackendMode.AUTO || mode == PrivilegeBackendMode.ROOT) {
                add(InstallShell("Root (su)") { command -> ProcessBuilder("su", "-c", command).start() })
            }
        }

        val failureMessage = context.getString(R.string.package_install_failed)
        val flags = buildList {
            add("-r")
            if (allowDowngrade) add("-d")
            if (settings.autoGrantRuntimePermissions) add("-g")
            val spoof = settings.installerSpoofPreset.packageId
            if (VALID_PACKAGE_REGEX.matches(spoof)) {
                add("-i")
                add(shellQuote(spoof))
            }
            add("-S")
            add(apkFiles.sumOf { it.length() }.toString())
        }.joinToString(" ")

        // Sessions belong to the UID that creates them, so use one backend for all session commands.
        val sessionPattern = Regex("""^Success: created install session \[(\d+)\]$""")
        var lastOutcome = ShellExecOutcome(-1, "", failureMessage, "Package Installer")
        for (shell in shells) {
            val create = runInstallCommand(shell, "cmd package install-create $flags", failureMessage)
            val sessionId = sessionPattern.matchEntire(create.stdout)?.groupValues?.get(1)?.toIntOrNull()
            if (!create.isSuccess || sessionId == null) {
                lastOutcome = create.copy(exitCode = -1, stderr = create.stderr.ifBlank { create.stdout.ifBlank { failureMessage } })
                continue
            }

            var committed = false
            try {
                apkFiles.forEachIndexed { index, apk ->
                    val name = if (index == 0) "base.apk" else "split_$index.apk"
                    val bytes = apk.length()
                    val write = runInstallCommand(
                        shell, "cmd package install-write -S $bytes $sessionId $name -",
                        failureMessage, apk, timeoutMs = 240_000L
                    )
                    if (!write.isSuccess || write.stdout != "Success: streamed $bytes bytes") {
                        return write.copy(exitCode = -1, stderr = write.stderr.ifBlank { write.stdout.ifBlank { failureMessage } })
                    }
                }
                val result = runInstallCommand(shell, "cmd package install-commit $sessionId", failureMessage, timeoutMs = 240_000L)
                if (!result.isInstallSuccess) {
                    return result.copy(exitCode = -1, stderr = result.stderr.ifBlank { result.stdout.ifBlank { failureMessage } })
                }
                committed = true
                if (settings.labPostInstallDexoptSpeedProfile && safeTargetPkg.isNotBlank()) {
                    runInstallCommand(shell, "cmd package compile -m speed-profile -f ${shellQuote(safeTargetPkg)}", failureMessage)
                }
                return result
            } finally {
                if (!committed) {
                    withContext(NonCancellable) {
                        runInstallCommand(shell, "cmd package install-abandon $sessionId", failureMessage, timeoutMs = 10_000L)
                    }
                }
            }
        }
        return lastOutcome
    }

    private suspend fun stageObbEntries(
        safePackageId: String,
        obbFiles: List<File>,
        settings: ObtainmondSettings
    ) {
        val dstDir = "/sdcard/Android/obb/$safePackageId"
        val script = buildString {
            append("mkdir -p ${shellQuote(dstDir)}")
            obbFiles.forEach { file ->
                val safeName = file.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                append(" && cp ${shellQuote(file.absolutePath)} ${shellQuote("$dstDir/$safeName")}")
            }
        }
        execPrivilegedShell(script, settings.privilegeMode, timeoutMs = 60_000L)
    }

    private suspend fun tryDhizukuPackageInstallerSession(
        context: Context,
        apkFiles: List<File>,
        targetPackageId: String
    ): ShellExecOutcome? {
        return runCatching {
            if (!Dhizuku.init(context) || !Dhizuku.isPermissionGranted()) {
                return@runCatching null
            }
            val sysPi = context.packageManager.packageInstaller
            val installerField = PackageInstaller::class.java.getDeclaredField("mInstaller").apply {
                isAccessible = true
            }
            val origInstaller = installerField.get(sysPi) as? IInterface
                ?: return@runCatching null
            val wrappedBinder = Dhizuku.binderWrapper(origInstaller.asBinder())
            val stubClass = Class.forName("android.content.pm.IPackageInstaller\$Stub")
            val wrappedProxy = stubClass.getMethod("asInterface", IBinder::class.java)
                .invoke(null, wrappedBinder)
            val ownerPkg = Dhizuku.getOwnerPackageName() ?: context.packageName

            var dhizukuPi: PackageInstaller? = null
            for (ctor in PackageInstaller::class.java.declaredConstructors) {
                ctor.isAccessible = true
                val params = ctor.parameterTypes
                if (params.size == 4 && params[1] == String::class.java) {
                    dhizukuPi = ctor.newInstance(wrappedProxy, ownerPkg, context.packageName, 0) as PackageInstaller
                    break
                } else if (params.size == 3 && params[1] == String::class.java) {
                    dhizukuPi = ctor.newInstance(wrappedProxy, ownerPkg, 0) as PackageInstaller
                    break
                }
            }
            val pi = dhizukuPi
                ?: return@runCatching null

            commitStandardPackageInstallerSession(
                packageInstaller = pi,
                context = context,
                apkFiles = apkFiles,
                targetPackageId = targetPackageId,
                backendLabel = "Dhizuku Device Owner"
            )
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            null
        }
    }

    private suspend fun commitStandardPackageInstallerSession(
        packageInstaller: PackageInstaller,
        context: Context,
        apkFiles: List<File>,
        targetPackageId: String,
        backendLabel: String
    ): ShellExecOutcome? {
        var sessionId: Int? = null
        var committed = false
        val result = CompletableDeferred<Intent>()
        try {
            val totalSize = apkFiles.sumOf { it.length() }
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setSize(totalSize)
                if (targetPackageId.isNotBlank()) {
                    setAppPackageName(targetPackageId)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    setRequestUpdateOwnership(true)
                    setApplicationEnabledSettingPersistent()
                }
            }
            val id = packageInstaller.createSession(params)
            sessionId = id
            installResults[id] = result
            packageInstaller.openSession(id).use { session ->
                apkFiles.forEachIndexed { idx, apkFile ->
                    val entryName = if (idx == 0) "base.apk" else "split_${idx}.apk"
                    apkFile.inputStream().use { input ->
                        session.openWrite(entryName, 0, apkFile.length()).use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                            session.fsync(output)
                        }
                    }
                }
                val callbackIntent = Intent(context, PackageInstallerStatusReceiver::class.java).apply {
                    setPackage(context.packageName)
                }
                val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    id,
                    callbackIntent,
                    pendingFlags
                )
                session.commit(pendingIntent.intentSender)
                committed = true
            }
            val statusIntent = withTimeoutOrNull(300_000L) { result.await() }
                ?: return ShellExecOutcome(0, "", "", backendLabel, installPending = true)
            val status = statusIntent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            return if (status == PackageInstaller.STATUS_SUCCESS) {
                ShellExecOutcome(0, "Success", "", backendLabel)
            } else {
                ShellExecOutcome(
                    status,
                    "",
                    statusIntent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        ?: context.getString(R.string.package_install_failed),
                    backendLabel
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return if (committed) ShellExecOutcome(0, "", "", backendLabel, installPending = true) else null
        } finally {
            sessionId?.let { id ->
                installResults.remove(id)
                if (!committed) runCatching { packageInstaller.abandonSession(id) }
            }
        }
    }

    private suspend fun launchExternalInstaller(
        context: Context,
        fileToShare: File,
        installerPackage: String
    ): ShellExecOutcome = withContext(Dispatchers.Main) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                fileToShare
            )
            val mime = if (fileToShare.name.endsWith(".apk", ignoreCase = true)) {
                "application/vnd.android.package-archive"
            } else {
                "application/octet-stream"
            }
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pkg = installerPackage.trim()
                if (pkg.isNotEmpty() && VALID_PACKAGE_REGEX.matches(pkg)) {
                    `package` = pkg
                }
            }
            val launchIntent = if (installerPackage.isBlank()) {
                Intent.createChooser(viewIntent, "Install package with").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                viewIntent
            }
            context.startActivity(launchIntent)
            ShellExecOutcome(
                exitCode = 0,
                stdout = "Launched External Installer (${installerPackage.ifBlank { "chooser" }})",
                stderr = "",
                backendUsed = installerPackage.ifBlank { "System PackageInstaller" },
                installPending = true
            )
        }.getOrElse { e ->
            ShellExecOutcome(
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "Failed to launch installer",
                backendUsed = "External Installer"
            )
        }
    }

    /**
     * Lab Feature: Freezes or unfreezes a tracked package via Shevery/Shizuku (`pm suspend` / `pm unsuspend`
     * + `cmd package suspend`).
     */
    suspend fun setPackageFrozenState(
        packageName: String,
        freeze: Boolean,
        settings: ObtainmondSettings
    ): ShellExecOutcome {
        val safePkg = runCatching { validatePackageName(packageName) }.getOrElse {
            return ShellExecOutcome(-1, "", it.message ?: "Invalid package name", "Validation")
        }
        val action = if (freeze) "suspend" else "unsuspend"
        val cmd = "pm $action ${shellQuote(safePkg)}"
        return execPrivilegedShell(cmd, settings.privilegeMode, timeoutMs = 15_000L)
    }

    /**
     * Lab Feature: Runs immediate `speed-profile` or `everything` ART compilation on a package.
     */
    suspend fun compilePackageDexopt(
        packageName: String,
        mode: String = "speed-profile",
        settings: ObtainmondSettings
    ): ShellExecOutcome {
        val safePkg = runCatching { validatePackageName(packageName) }.getOrElse {
            return ShellExecOutcome(-1, "", it.message ?: "Invalid package name", "Validation")
        }
        if (mode !in ALLOWED_DEXOPT_MODES) {
            return ShellExecOutcome(-1, "", "Unsupported dexopt mode: $mode", "Validation")
        }
        val cmd = "cmd package compile -m $mode -f ${shellQuote(safePkg)}"
        return execPrivilegedShell(cmd, settings.privilegeMode, timeoutMs = 60_000L)
    }
}
