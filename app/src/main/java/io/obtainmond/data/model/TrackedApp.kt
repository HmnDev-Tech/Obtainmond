package io.obtainmond.data.model

import io.obtainmond.rust.ApkCandidateDto
import kotlinx.serialization.Serializable

enum class PrivilegeBackendMode(val title: String, val subtitle: String) {
    AUTO("Auto (Shevery-API → Shizuku → Dhizuku → Root)", "Uses Shevery fast IPC first, then Shizuku, Dhizuku, or Root"),
    SHEVERY("Shevery Native API (v14.6.4)", "Ultra-low-latency flat Parcel execFast() + capability bitmasks"),
    SHIZUKU("Shizuku Legacy Binder", "Standard Shizuku IShizukuService process execution"),
    DHIZUKU("Dhizuku Device Owner", "Silent Device Owner PackageInstaller via Dhizuku API"),
    ROOT("Root Shell (su -c)", "Direct Magisk / KernelSU / APatch root shell"),
    EXTERNAL("External Installer (InstallerX / App Manager)", "Delegates verified package via FileProvider ACTION_VIEW"),
    SYSTEM("Standard Android Installer", "Manual system PackageInstaller confirmation")
}

enum class InstallerSpoofPreset(val packageId: String, val label: String) {
    PLAY_STORE("com.android.vending", "Google Play Store (com.android.vending)"),
    GALAXY_STORE("com.sec.android.app.samsungapps", "Samsung Galaxy Store"),
    FDROID("org.fdroid.fdroid", "F-Droid Official Client"),
    OBTAINMOND("io.obtainmond", "Obtainmond Self-Signed")
}

enum class VerificationPolicy(val title: String) {
    OFF("Off"),
    AUDIT("Audit (Warn & Log)"),
    ENFORCE("Enforce (Block Install)")
}

@Serializable
enum class ReleaseNotesExpansion {
    REMEMBER_LAST_STATE,
    ALWAYS_EXPANDED,
    ALWAYS_COLLAPSED;

    fun initiallyExpanded(lastExpanded: Boolean): Boolean = when (this) {
        REMEMBER_LAST_STATE -> lastExpanded
        ALWAYS_EXPANDED -> true
        ALWAYS_COLLAPSED -> false
    }
}

@Serializable
data class FolderRule(
    val id: String,
    val folderName: String,
    val sourceTypes: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val includeCategories: List<String> = emptyList()
)

@Serializable
data class TrackedApp(
    val id: String,
    val name: String,
    val author: String = "",
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
    val onDemandOnly: Boolean = false,
    val skippedVersion: String = "",
    val apkFilterRegex: String = "",
    val versionExtractionRegex: String = "",
    val categories: List<String> = emptyList(),
    val folders: List<String> = emptyList(),
    val lastCheckedEpochMs: Long = 0L,
    val lastUpdatedEpochMs: Long = 0L,
    val backupApkPath: String = "",
    val backupVersion: String = "",
    val lastSha256: String = "",
    val isFrozen: Boolean = false,
    val pinned: Boolean = false,
    val installedReleaseTag: String = "",
    val installedApkUrl: String = "",
    val pinnedSigningCertSha256: String = "",
    val lastError: String = "",
    val errorCategory: String = "",
    val lastSupplyChainSummary: String = "",
    val installedReleaseVersionCode: Long? = null,
    val expectedPackageId: String = ""
) {
    val needsAttention: Boolean
        get() = errorCategory.isNotBlank() || lastError.isNotBlank()

    val hasUpdate: Boolean
        get() {
            if (trackOnly || latestVersion.isBlank() || installedVersion.isBlank()) return false
            if (!allowDowngrade) {
                if (skippedVersion.isNotBlank() &&
                    io.obtainmond.rust.ObtainmondNative.areVersionsEquivalent(skippedVersion, latestVersion)
                ) {
                    return false
                }
                if (installedReleaseTag.isNotBlank() &&
                    io.obtainmond.rust.ObtainmondNative.areVersionsEquivalent(installedReleaseTag, latestVersion)
                ) {
                    return false
                }
                if (installedApkUrl.isNotBlank() &&
                    matchedApkUrl.isNotBlank() &&
                    installedApkUrl == matchedApkUrl
                ) {
                    return false
                }
                if (io.obtainmond.rust.ObtainmondNative.areVersionsEquivalent(installedVersion, latestVersion)) {
                    return false
                }
            }
            return io.obtainmond.rust.ObtainmondNative.safeIsUpdateAvailable(
                installedVersion,
                latestVersion,
                allowDowngrade
            )
        }
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
    val externalInstallerPackage: String = "",
    val autoGrantRuntimePermissions: Boolean = true,
    // Lab Features
    val labInstantRollbackBackup: Boolean = true,
    val labPostInstallDexoptSpeedProfile: Boolean = true,
    val labRustAbiAndSignatureGuard: Boolean = true,
    val labImmortalStandbyBucketActive: Boolean = true,
    val labGitHubMirrorAcceleration: Boolean = false,
    val githubMirrorUrl: String = "",
    // Supply-Chain Verification Policies
    val reproducibleBuildsPolicy: VerificationPolicy = VerificationPolicy.AUDIT,
    val githubAttestationPolicy: VerificationPolicy = VerificationPolicy.AUDIT,
    val virusTotalPolicy: VerificationPolicy = VerificationPolicy.OFF,
    // Folder Rules
    val folderRules: List<FolderRule> = emptyList(),
    // Expressive UI Settings
    val themeMode: Int = 0, // 0 = System, 1 = Light, 2 = Dark
    val dynamicColor: Boolean = true,
    val pureBlackOled: Boolean = false,
    val motionBlurEnabled: Boolean = true,
    val motionBlurIntensity: Int = 65,
    val motionBlurStrength: Int = 60,
    val groupByCategory: Boolean = false,
    val appSortMode: Int = 0,
    val autoScaleUi: Boolean = true,
    val uiScalePercent: Int = 100,
    val checkUpdatesOnStartup: Boolean = true,
    val wifiOnlyBackgroundUpdates: Boolean = false,
    val deleteApkAfterInstall: Boolean = true,
    val excludeDebugApks: Boolean = true,
    val showUpdateNotifications: Boolean = true,
    val defaultIncludePrereleases: Boolean = false,
    val releaseNotesExpansion: ReleaseNotesExpansion = ReleaseNotesExpansion.REMEMBER_LAST_STATE,
    val lastReleaseNotesExpanded: Boolean = true
)
