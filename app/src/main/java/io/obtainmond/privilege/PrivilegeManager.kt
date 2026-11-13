package io.obtainmond.privilege

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.FileProvider
import com.hamondev.shevery.api.SheveryApiConstants
import com.hamondev.shevery.api.SheveryPrivilege
import io.obtainmond.data.model.ObtainmondSettings
import io.obtainmond.data.model.PrivilegeBackendMode
import io.obtainmond.rust.ObtainmondNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class PrivilegeStatus(
    val sheveryBinderAlive: Boolean = false,
    val sheveryGranted: Boolean = false,
    val sheveryCapabilities: Int = 0,
    val shizukuBinderAlive: Boolean = false,
    val shizukuGranted: Boolean = false,
    val serverUid: Int = -1,
    val serverVersion: Int = -1,
    val batteryOptimizationIgnored: Boolean = false,
    val activeBackendLabel: String = "Checking..."
) {
    val hasPrivilegedShell: Boolean
        get() = (sheveryBinderAlive && sheveryGranted) || (shizukuBinderAlive && shizukuGranted)
}

data class ShellExecOutcome(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val backendUsed: String
) {
    val isSuccess: Boolean
        get() = exitCode == 0 && !stdout.contains("Failure [", ignoreCase = true)
}

object PrivilegeManager {
    private const val REQUEST_CODE_PRIVILEGE = 4201
    private val VALID_PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    private val ALLOWED_DEXOPT_MODES = setOf("speed-profile", "speed", "everything", "quicken")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshInFlight = AtomicBoolean(false)

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
                "Shevery-API v14.6.4 Fast IPC ($mode)"
            }
            shizukuGranted -> {
                val mode = if (uid == 0) "Root" else "ADB"
                "Shizuku Binder ($mode)"
            }
            sheveryAlive || shizukuAlive -> "Daemon Connected (Permission Needed)"
            else -> "Unprivileged (Standard Mode)"
        }

        _status.value = PrivilegeStatus(
            sheveryBinderAlive = sheveryAlive,
            sheveryGranted = sheveryGranted,
            sheveryCapabilities = caps,
            shizukuBinderAlive = shizukuAlive,
            shizukuGranted = shizukuGranted,
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
                }
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
                val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                ).apply { isAccessible = true }
                val proc = newProcessMethod.invoke(
                    null,
                    arrayOf("sh", "-c", command),
                    null,
                    null
                ) as Process
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

    /**
     * Copies the currently installed base APK of [packageName] into Obtainmond's
     * rollback vault before an update so the user can 1-click downgrade in Labs.
     */
    suspend fun backupInstalledApkForRollback(
        context: Context,
        packageName: String
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val safePkg = validatePackageName(packageName)
            val appInfo = context.packageManager.getApplicationInfo(safePkg, 0)
            val srcFile = File(appInfo.sourceDir)
            if (!srcFile.exists()) return@withContext null
            val rollbackDir = File(context.filesDir, "rollback_vault").apply { mkdirs() }
            val dstFile = File(rollbackDir, "${safePkg}_rollback.apk")
            srcFile.copyTo(dstFile, overwrite = true)
            dstFile
        }.getOrNull()
    }

    /**
     * Silently installs an APK on the fly in the background using Shevery-API / Shizuku.
     * Stages the APK in `/data/local/tmp/obtainmond_stage.apk` so `system_server` / `pm`
     * can read it regardless of app sandbox SELinux restrictions, then executes `pm install`
     * with installer spoofing and optional `speed-profile` dexopt compilation.
     */
    suspend fun installApkSilentlyOrFallback(
        context: Context,
        apkFile: File,
        targetPackageId: String,
        settings: ObtainmondSettings,
        allowDowngrade: Boolean = false
    ): ShellExecOutcome = withContext(Dispatchers.IO) {
        if (settings.privilegeMode != PrivilegeBackendMode.SYSTEM) {
            val safeTargetPkg = if (targetPackageId.isNotBlank()) {
                runCatching { validatePackageName(targetPackageId) }.getOrNull().orEmpty()
            } else {
                ""
            }
            val stagePath = "/data/local/tmp/obtainmond_${System.currentTimeMillis()}.apk"
            val dexoptTarget = if (settings.labPostInstallDexoptSpeedProfile) safeTargetPkg else ""
            val installCmd = ObtainmondNative.buildInstallShellCmd(
                apkPath = stagePath,
                installerPackage = settings.installerSpoofPreset.packageId,
                allowDowngrade = allowDowngrade,
                grantRuntimePermissions = settings.autoGrantRuntimePermissions,
                targetPackageForDexopt = dexoptTarget
            )

            val fullScript = buildString {
                append("cp ")
                append("'${apkFile.absolutePath.replace("'", "'\"'\"'")}' ")
                append("'$stagePath' && chmod 644 '$stagePath' && ")
                append(installCmd)
                append(" ; EXIT_CODE=\$? ; rm -f '$stagePath' ; exit \$EXIT_CODE")
            }

            val outcome = execPrivilegedShell(
                command = fullScript,
                preferredMode = settings.privilegeMode,
                timeoutMs = 180_000L
            )
            if (outcome.isSuccess && outcome.stdout.contains("Success", ignoreCase = true)) {
                return@withContext outcome
            }
        }

        // Standard system PackageInstaller fallback if unprivileged
        withContext(Dispatchers.Main) {
            runCatching {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ShellExecOutcome(
                    exitCode = 0,
                    stdout = "Launched System PackageInstaller UI",
                    stderr = "",
                    backendUsed = "System PackageInstaller"
                )
            }.getOrElse { e ->
                ShellExecOutcome(
                    exitCode = -1,
                    stdout = "",
                    stderr = e.message ?: "Failed to launch system installer",
                    backendUsed = "System PackageInstaller"
                )
            }
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
