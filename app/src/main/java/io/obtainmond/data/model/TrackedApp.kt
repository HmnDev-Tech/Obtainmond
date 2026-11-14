package io.obtainmond.data.model

import io.obtainmond.rust.ApkCandidateDto
import kotlinx.serialization.Serializable

enum class PrivilegeBackendMode(val title: String, val subtitle: String) {
    AUTO("Auto (Shevery-API → Shizuku → Root)", "Uses Shevery fast IPC first, falls back to Shizuku or Root"),
    SHEVERY("Shevery Native API (v14.6.4)", "Ultra-low-latency flat Parcel execFast() + capability bitmasks"),
    SHIZUKU("Shizuku Legacy Binder", "Standard Shizuku IShizukuService process execution"),
    ROOT("Root Shell (su -c)", "Direct Magisk / KernelSU / APatch root shell"),
    SYSTEM("Standard Android Installer", "Manual system PackageInstaller confirmation")
}

enum class InstallerSpoofPreset(val packageId: String, val label: String) {
    PLAY_STORE("com.android.vending", "Google Play Store (com.android.vending)"),
    GALAXY_STORE("com.sec.android.app.samsungapps", "Samsung Galaxy Store"),
    FDROID("org.fdroid.fdroid", "F-Droid Official Client"),
    OBTAINMOND("io.obtainmond", "Obtainmond Self-Signed")
}

@Serializable
data class TrackedApp(
    val id: String,
    val name: String,
    val author: String,
    val url: String,
    val sourceType: String = "GITHUB",
    val apiUrl: String = "",
    val installedVersion: String = "",
    val latestVersion: String = "",
    val installedAbi: String = "",
    val matchedApkName: String = "",
    val matchedApkUrl: String = "",
    val matchedAbi: String = "",
    val selectionReason: String = "",
    val availableApks: List<ApkCandidateDto> = emptyList(),
    val changelog: String = "",
    val includePrereleases: Boolean = false,
    val silentAutoUpdate: Boolean = true,
    val trackOnly: Boolean = false,
    val allowDowngrade: Boolean = false,
    val apkFilterRegex: String = "",
    val versionExtractionRegex: String = "",
    val categories: List<String> = emptyList(),
    val lastCheckedEpochMs: Long = 0L,
    val lastUpdatedEpochMs: Long = 0L,
    val backupApkPath: String = "",
    val backupVersion: String = "",
    val lastSha256: String = "",
    val isFrozen: Boolean = false,
    val pinned: Boolean = false
) {
    val hasUpdate: Boolean
        get() = !trackOnly &&
            latestVersion.isNotBlank() &&
            (installedVersion.isBlank() ||
                io.obtainmond.rust.ObtainmondNative.safeIsUpdateAvailable(
                    installedVersion,
                    latestVersion,
                    allowDowngrade
                ))
}

@Serializable
data class AppEventLog(
    val timestampMs: Long,
    val appId: String,
    val appName: String,
    val action: String,
    val backendUsed: String,
    val details: String,
    val success: Boolean
)

@Serializable
data class ObtainmondSettings(
    val privilegeMode: PrivilegeBackendMode = PrivilegeBackendMode.AUTO,
    val zeroTouchBackgroundUpdate: Boolean = true,
    val backgroundIntervalMinutes: Int = 30,
    val autoWhitelistBatteryDoze: Boolean = true,
    val installerSpoofPreset: InstallerSpoofPreset = InstallerSpoofPreset.PLAY_STORE,
    val autoGrantRuntimePermissions: Boolean = true,
    // Lab Features
    val labInstantRollbackBackup: Boolean = true,
    val labPostInstallDexoptSpeedProfile: Boolean = true,
    val labRustAbiAndSignatureGuard: Boolean = true,
    val labImmortalStandbyBucketActive: Boolean = true,
    val labGitHubMirrorAcceleration: Boolean = false,
    val githubPersonalAccessToken: String = "",
    // Expressive UI Settings
    val themeMode: Int = 0, // 0 = System, 1 = Light, 2 = Dark
    val dynamicColor: Boolean = true,
    val pureBlackOled: Boolean = false,
    val motionBlurEnabled: Boolean = true,
    val motionBlurIntensity: Int = 65,
    val motionBlurStrength: Int = 60,
    val groupByCategory: Boolean = false,
    val appSortMode: Int = 0
)
