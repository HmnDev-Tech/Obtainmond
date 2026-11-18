package io.obtainmond.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.ImportExport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.obtainmond.BuildConfig
import io.obtainmond.R
import io.obtainmond.data.model.FolderRule
import io.obtainmond.data.model.InstallerSpoofPreset
import io.obtainmond.data.model.ObtainmondSettings
import io.obtainmond.data.model.PrivilegeBackendMode
import io.obtainmond.data.model.TrackedApp
import io.obtainmond.data.model.VerificationPolicy
import io.obtainmond.data.repo.AppRepository
import io.obtainmond.privilege.PrivilegeManager
import io.obtainmond.privilege.PrivilegeStatus
import io.obtainmond.ui.compose.ExpressiveAppAvatar
import io.obtainmond.ui.compose.ExpressiveButton
import io.obtainmond.ui.compose.ExpressivePulsingDot
import io.obtainmond.ui.compose.ExpressiveShapeBadge
import io.obtainmond.ui.compose.ExpressiveTonalButton
import io.obtainmond.ui.compose.NavigationSettingsRow
import io.obtainmond.ui.compose.SettingsGroup
import io.obtainmond.ui.compose.SliderSettingsRow
import io.obtainmond.ui.compose.SwitchSettingsRow
import io.obtainmond.ui.compose.rememberAppIconBitmap
import io.obtainmond.ui.compose.scrollMotionBlur
import io.obtainmond.worker.AutoUpdateWorker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun ObtainedLabsScreen(
    apps: List<TrackedApp>,
    privStatus: PrivilegeStatus,
    settings: ObtainmondSettings,
    bottomPadding: Dp
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var isDexoptRunning by remember { mutableStateOf(false) }

    val rollbackApps = remember(apps) {
        apps.filter { it.backupApkPath.isNotBlank() }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .scrollMotionBlur(
                listState = listState,
                enabled = settings.motionBlurEnabled,
                intensity = settings.motionBlurIntensity,
                strength = settings.motionBlurStrength
            ),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = bottomPadding + 104.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Labs",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = if (privStatus.hasPrivilegedShell) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExpressiveShapeBadge(
                            polygon = if (privStatus.hasPrivilegedShell) MaterialShapes.Cookie7Sided else MaterialShapes.Diamond,
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            size = 46.dp
                        ) {
                            Icon(
                                imageVector = if (privStatus.hasPrivilegedShell) Icons.Rounded.Bolt else Icons.Rounded.Security,
                                contentDescription = null
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ExpressivePulsingDot(
                                    color = if (privStatus.hasPrivilegedShell) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    active = privStatus.hasPrivilegedShell
                                )
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    text = privStatus.activeBackendLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Text(
                                text = "Doze: ${if (privStatus.batteryOptimizationIgnored) "Exempt" else "Active"}  •  ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ExpressiveTonalButton(
                            onClick = { PrivilegeManager.refreshStatus() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Ping Binder")
                        }
                        if (!privStatus.hasPrivilegedShell) {
                            ExpressiveButton(
                                onClick = { PrivilegeManager.requestPrivilegePermission(context) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Rounded.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Grant")
                            }
                        }
                        ExpressiveTonalButton(
                            onClick = {
                                AutoUpdateWorker.triggerImmediateBackgroundCheck(context)
                                Toast.makeText(context, "Dispatched background check worker", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Run Worker")
                        }
                    }
                }
            }
        }

        item {
            SettingsGroup(
                title = "Background Updates",
                items = listOf(
                    {
                        SwitchSettingsRow(
                            icon = Icons.Rounded.AutoAwesome,
                            title = "Silent Background Auto-Updates",
                            subtitle = "Update tracked apps automatically in background",
                            checked = settings.zeroTouchBackgroundUpdate,
                            shape = MaterialShapes.Cookie6Sided,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(zeroTouchBackgroundUpdate = enabled) }
                                if (enabled) {
                                    AutoUpdateWorker.schedulePeriodic(
                                        context,
                                        settings.backgroundIntervalMinutes,
                                        settings.wifiOnlyBackgroundUpdates
                                    )
                                }
                            }
                        )
                    },
                    {
                        SwitchSettingsRow(
                            icon = Icons.Rounded.Wifi,
                            title = "Wi-Fi Only Background Updates",
                            subtitle = "Defer background sync and downloads on metered networks",
                            checked = settings.wifiOnlyBackgroundUpdates,
                            shape = MaterialShapes.Cookie4Sided,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(wifiOnlyBackgroundUpdates = enabled) }
                                if (settings.zeroTouchBackgroundUpdate) {
                                    AutoUpdateWorker.schedulePeriodic(
                                        context,
                                        settings.backgroundIntervalMinutes,
                                        enabled
                                    )
                                }
                            }
                        )
                    },
                    {
                        SwitchSettingsRow(
                            icon = Icons.Rounded.Refresh,
                            title = "Check Updates on App Startup",
                            subtitle = "Sync tracked releases automatically after launch",
                            checked = settings.checkUpdatesOnStartup,
                            shape = MaterialShapes.Flower,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(checkUpdatesOnStartup = enabled) }
                            }
                        )
                    },
                    {
                        SwitchSettingsRow(
                            icon = Icons.Rounded.Notifications,
                            title = "Update Notifications",
                            subtitle = "Show notification after background or silent installation",
                            checked = settings.showUpdateNotifications,
                            shape = MaterialShapes.Sunny,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(showUpdateNotifications = enabled) }
                            }
                        )
                    },
                    {
                        SwitchSettingsRow(
                            icon = Icons.Rounded.DeleteOutline,
                            title = "Delete Downloaded APK After Install",
                            subtitle = "Remove temporary APK installer files on successful update",
                            checked = settings.deleteApkAfterInstall,
                            shape = MaterialShapes.Gem,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(deleteApkAfterInstall = enabled) }
                            }
                        )
                    },
                    {
                        SwitchSettingsRow(
                            icon = Icons.Rounded.BatteryChargingFull,
                            title = "Battery Optimization Exempt",
                            subtitle = "Keep app exempt from Doze restrictions",
                            checked = settings.autoWhitelistBatteryDoze,
                            shape = MaterialShapes.Clover4Leaf,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(autoWhitelistBatteryDoze = enabled) }
                            }
                        )
                    },
                    {
                        SwitchSettingsRow(
                            icon = Icons.Rounded.Bolt,
                            title = "Active Standby Bucket",
                            subtitle = "Keep app in ACTIVE standby bucket",
                            checked = settings.labImmortalStandbyBucketActive,
                            shape = MaterialShapes.SoftBurst,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(labImmortalStandbyBucketActive = enabled) }
                            }
                        )
                    },
                    {
                        SliderSettingsRow(
                            icon = Icons.Rounded.History,
                            title = "Background Poll Interval",
                            value = settings.backgroundIntervalMinutes,
                            valueRange = 15..180,
                            unitSuffix = " min",
                            onValueChange = { mins ->
                                AppRepository.updateSettings { it.copy(backgroundIntervalMinutes = mins) }
                                AutoUpdateWorker.schedulePeriodic(
                                    context,
                                    mins,
                                    settings.wifiOnlyBackgroundUpdates
                                )
                            }
                        )
                    }
                )
            )
        }

        item {
            SettingsGroup(
                title = "Security & Install",
                items = listOf(
                    {
                        SwitchSettingsRow(
                            icon = Icons.Rounded.Restore,
                            title = "Rollback Backup",
                            subtitle = "Keep previous APK before updating to allow 1-tap downgrade",
                            checked = settings.labInstantRollbackBackup,
                            shape = MaterialShapes.Arch,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(labInstantRollbackBackup = enabled) }
                            }
                        )
                    },
                    {
                        SwitchSettingsRow(
                            icon = Icons.Rounded.Speed,
                            title = "Post-Install ART Speed-Profile",
                            subtitle = "Compile with speed-profile after silent updates",
                            checked = settings.labPostInstallDexoptSpeedProfile,
                            shape = MaterialShapes.Gem,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(labPostInstallDexoptSpeedProfile = enabled) }
                            }
                        )
                    },
                    {
                        SwitchSettingsRow(
                            icon = Icons.Rounded.Security,
                            title = "APK Integrity & ABI Check",
                            subtitle = "Verify archive structure and device ABI before install",
                            checked = settings.labRustAbiAndSignatureGuard,
                            shape = MaterialShapes.Diamond,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(labRustAbiAndSignatureGuard = enabled) }
                            }
                        )
                    },
                    {
                        SwitchSettingsRow(
                            icon = Icons.Rounded.VerifiedUser,
                            title = "Auto-Grant Runtime Permissions (-g)",
                            subtitle = "Grants runtime permissions during privileged pm install",
                            checked = settings.autoGrantRuntimePermissions,
                            shape = MaterialShapes.Cookie4Sided,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(autoGrantRuntimePermissions = enabled) }
                            }
                        )
                    }
                )
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Installer Package Name (-i)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        InstallerSpoofPreset.entries.forEach { preset ->
                            FilledTonalToggleButton(
                                checked = settings.installerSpoofPreset == preset,
                                onCheckedChange = {
                                    AppRepository.updateSettings { it.copy(installerSpoofPreset = preset) }
                                }
                            ) {
                                Text(preset.packageId.substringAfterLast('.'))
                            }
                        }
                    }
                    Text(
                        text = settings.installerSpoofPreset.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Batch ART Compiler",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Pre-compile installed tracked apps with speed-profile",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        ExpressiveButton(
                            enabled = !isDexoptRunning,
                            onClick = {
                                scope.launch {
                                    isDexoptRunning = true
                                    var compiled = 0
                                    for (app in apps) {
                                        if (app.installedVersion.isNotBlank()) {
                                            val res = PrivilegeManager.compilePackageDexopt(app.id, "speed-profile", settings)
                                            if (res.isSuccess) compiled++
                                        }
                                    }
                                    isDexoptRunning = false
                                    Toast.makeText(context, "Compiled $compiled app(s)", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Boost All")
                        }
                    }
                    if (isDexoptRunning) {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        if (rollbackApps.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "${stringResource(R.string.label_rollback_vault)} (${rollbackApps.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    Text(
                        text = stringResource(R.string.msg_rollback_android7_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
            items(rollbackApps, key = { "rb_${it.id}" }) { app ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ExpressiveAppAvatar(
                            packageId = app.id,
                            appName = app.name,
                            hasUpdate = false,
                            isSelected = false,
                            isFrozen = app.isFrozen,
                            isBusy = false,
                            size = 44.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                text = "Snapshot: v${app.backupVersion}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        ExpressiveTonalButton(
                            onClick = {
                                scope.launch {
                                    val ok = AppRepository.rollbackAppToBackup(app.id) ?: return@launch
                                    Toast.makeText(
                                        context,
                                        if (ok) "Rolled back ${app.name}" else "Rollback failed — see Logs / Attention",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.action_restore))
                        }
                    }
                }
            }
        }
    }
}

private enum class SettingsSubPage {
    ROOT,
    APPEARANCE,
    PRIVILEGE,
    SECURITY,
    FOLDER_RULES,
    IMPORT_EXPORT,
    GITHUB_NETWORK,
    LOGS,
    ABOUT
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    settings: ObtainmondSettings,
    privStatus: PrivilegeStatus,
    bottomPadding: Dp
) {
    val logs by AppRepository.logs.collectAsState()
    var activePage by rememberSaveable { mutableStateOf(SettingsSubPage.ROOT) }

    BackHandler(enabled = activePage != SettingsSubPage.ROOT) {
        activePage = SettingsSubPage.ROOT
    }

    AnimatedContent(
        targetState = activePage,
        transitionSpec = {
            (scaleIn(
                initialScale = 0.96f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeIn()) togetherWith (scaleOut(targetScale = 0.98f) + fadeOut())
        },
        label = "settingsSubPage"
    ) { page ->
        when (page) {
            SettingsSubPage.ROOT -> SettingsRootPage(
                settings = settings,
                privStatus = privStatus,
                logsCount = logs.size,
                bottomPadding = bottomPadding,
                onNavigate = { activePage = it }
            )
            SettingsSubPage.APPEARANCE -> AppearanceSettingsSubPage(
                settings = settings,
                bottomPadding = bottomPadding,
                onBack = { activePage = SettingsSubPage.ROOT }
            )
            SettingsSubPage.PRIVILEGE -> PrivilegeSettingsSubPage(
                settings = settings,
                privStatus = privStatus,
                bottomPadding = bottomPadding,
                onBack = { activePage = SettingsSubPage.ROOT }
            )
            SettingsSubPage.SECURITY -> SecurityVerificationSubPage(
                settings = settings,
                bottomPadding = bottomPadding,
                onBack = { activePage = SettingsSubPage.ROOT }
            )
            SettingsSubPage.FOLDER_RULES -> FolderRulesSubPage(
                settings = settings,
                bottomPadding = bottomPadding,
                onBack = { activePage = SettingsSubPage.ROOT }
            )
            SettingsSubPage.IMPORT_EXPORT -> ImportExportSubPage(
                settings = settings,
                bottomPadding = bottomPadding,
                onBack = { activePage = SettingsSubPage.ROOT }
            )
            SettingsSubPage.GITHUB_NETWORK -> GitHubNetworkSubPage(
                settings = settings,
                bottomPadding = bottomPadding,
                onBack = { activePage = SettingsSubPage.ROOT }
            )
            SettingsSubPage.LOGS -> AuditLogsSubPage(
                settings = settings,
                logs = logs,
                bottomPadding = bottomPadding,
                onBack = { activePage = SettingsSubPage.ROOT }
            )
            SettingsSubPage.ABOUT -> AboutSubPage(
                settings = settings,
                bottomPadding = bottomPadding,
                onBack = { activePage = SettingsSubPage.ROOT }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsRootPage(
    settings: ObtainmondSettings,
    privStatus: PrivilegeStatus,
    logsCount: Int,
    bottomPadding: Dp,
    onNavigate: (SettingsSubPage) -> Unit
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .scrollMotionBlur(
                listState = listState,
                enabled = settings.motionBlurEnabled,
                intensity = settings.motionBlurIntensity,
                strength = settings.motionBlurStrength
            ),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = bottomPadding + 104.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.tab_settings),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp
            )
        }

        item {
            SettingsGroup(
                title = "Appearance & Backend",
                items = listOf(
                    {
                        NavigationSettingsRow(
                            icon = Icons.Rounded.Palette,
                            shape = MaterialShapes.Flower,
                            iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                            title = "Appearance",
                            subtitle = "Theme, dynamic color, OLED black, category grouping",
                            onClick = { onNavigate(SettingsSubPage.APPEARANCE) }
                        )
                    },
                    {
                        NavigationSettingsRow(
                            icon = Icons.Rounded.Terminal,
                            shape = MaterialShapes.Cookie7Sided,
                            iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                            title = "Privilege Backend",
                            subtitle = privStatus.activeBackendLabel,
                            onClick = { onNavigate(SettingsSubPage.PRIVILEGE) }
                        )
                    },
                    {
                        NavigationSettingsRow(
                            icon = Icons.Rounded.VerifiedUser,
                            shape = MaterialShapes.Cookie9Sided,
                            iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                            title = stringResource(R.string.label_supply_chain),
                            subtitle = "Reproducible Builds, GitHub Attestations, VirusTotal v3",
                            onClick = { onNavigate(SettingsSubPage.SECURITY) }
                        )
                    },
                    {
                        NavigationSettingsRow(
                            icon = Icons.Rounded.Folder,
                            shape = MaterialShapes.Clover4Leaf,
                            iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                            title = stringResource(R.string.label_folder_rules),
                            subtitle = "${settings.folderRules.size} automatic regex assignment rule(s)",
                            onClick = { onNavigate(SettingsSubPage.FOLDER_RULES) }
                        )
                    }
                )
            )
        }

        item {
            SettingsGroup(
                title = "Data & Network",
                items = listOf(
                    {
                        NavigationSettingsRow(
                            icon = Icons.Rounded.ImportExport,
                            shape = MaterialShapes.Clover4Leaf,
                            iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                            title = "Import / Export",
                            subtitle = "Backup & restore tracked apps JSON",
                            onClick = { onNavigate(SettingsSubPage.IMPORT_EXPORT) }
                        )
                    },
                    {
                        val secretStatus by AppRepository.secretStatus.collectAsState()
                        NavigationSettingsRow(
                            icon = Icons.Rounded.Key,
                            shape = MaterialShapes.Gem,
                            iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                            title = "GitHub & Network",
                            subtitle = if (secretStatus.hasGitHubToken) {
                                "${secretStatus.githubTokenCount} encrypted token(s) configured"
                            } else {
                                "Encrypted PAT & custom HTTPS mirror"
                            },
                            onClick = { onNavigate(SettingsSubPage.GITHUB_NETWORK) }
                        )
                    }
                )
            )
        }

        item {
            SettingsGroup(
                title = "Diagnostics & Info",
                items = listOf(
                    {
                        NavigationSettingsRow(
                            icon = Icons.Rounded.Code,
                            shape = MaterialShapes.SoftBurst,
                            iconContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            iconTint = MaterialTheme.colorScheme.onSurface,
                            title = "Logs",
                            subtitle = "Update, install and background events",
                            badgeText = if (logsCount > 0) logsCount.toString() else null,
                            onClick = { onNavigate(SettingsSubPage.LOGS) }
                        )
                    },
                    {
                        NavigationSettingsRow(
                            icon = Icons.Rounded.Info,
                            shape = MaterialShapes.Cookie9Sided,
                            iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                            title = "About",
                            subtitle = "Version & repository",
                            onClick = { onNavigate(SettingsSubPage.ABOUT) }
                        )
                    }
                )
            )
        }
    }
}

@Composable
private fun SubPageHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun AppearanceSettingsSubPage(
    settings: ObtainmondSettings,
    bottomPadding: Dp,
    onBack: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 104.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { SubPageHeader("Appearance & Motion", onBack) }

        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.DarkMode, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("Theme Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "System", 1 to "Light", 2 to "Dark").forEach { (mode, label) ->
                            FilledTonalToggleButton(
                                checked = settings.themeMode == mode,
                                onCheckedChange = {
                                    AppRepository.updateSettings { it.copy(themeMode = mode) }
                                }
                            ) {
                                Text(label)
                            }
                        }
                    }
                }
            }
        }

        item {
            SettingsGroup(
                title = "Material 3 Expressive Surfaces",
                items = listOf(
                    {
                        SwitchSettingsRow(
                            icon = Icons.Rounded.Palette,
                            title = "Material You Dynamic Color",
                            subtitle = "Adapts expressive palette from Android 12+ wallpaper",
                            checked = settings.dynamicColor,
                            shape = MaterialShapes.Flower,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(dynamicColor = enabled) }
                            }
                        )
                    },
                    {
                        SwitchSettingsRow(
                            icon = Icons.Rounded.Contrast,
                            title = "Pure Black OLED",
                            subtitle = "True #000000 background in dark theme",
                            checked = settings.pureBlackOled,
                            shape = MaterialShapes.Circle,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(pureBlackOled = enabled) }
                            }
                        )
                    },
                    {
                        SwitchSettingsRow(
                            icon = Icons.Rounded.FolderCopy,
                            title = "Group Apps by Category",
                            subtitle = "Expandable Obtainium category sections on Apps tab",
                            checked = settings.groupByCategory,
                            shape = MaterialShapes.Clover4Leaf,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(groupByCategory = enabled) }
                            }
                        )
                    }
                )
            )
        }

        item {
            SettingsGroup(
                title = "Display & Auto Scale",
                items = listOf(
                    {
                        SwitchSettingsRow(
                            icon = Icons.Rounded.AspectRatio,
                            title = "Auto Scale UI",
                            subtitle = "Automatically calibrate UI dimensions and font scale to screen width",
                            checked = settings.autoScaleUi,
                            shape = MaterialShapes.Cookie7Sided,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(autoScaleUi = enabled) }
                            }
                        )
                    },
                    {
                        SliderSettingsRow(
                            icon = Icons.Rounded.AspectRatio,
                            title = "Interface Scale",
                            value = settings.uiScalePercent,
                            valueRange = 85..115,
                            unitSuffix = "%",
                            onValueChange = { pct ->
                                AppRepository.updateSettings { it.copy(uiScalePercent = pct) }
                            }
                        )
                    }
                )
            )
        }

        item {
            SettingsGroup(
                title = "AGSL Scroll Motion Blur",
                items = listOf(
                    {
                        SwitchSettingsRow(
                            icon = Icons.Rounded.BlurOn,
                            title = "Directional AGSL Motion Blur",
                            subtitle = "10-sample velocity shader blur on scrolls & page swipes",
                            checked = settings.motionBlurEnabled,
                            shape = MaterialShapes.SoftBurst,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(motionBlurEnabled = enabled) }
                            }
                        )
                    },
                    {
                        SliderSettingsRow(
                            icon = Icons.Rounded.BlurOn,
                            title = "Motion Blur Intensity",
                            value = settings.motionBlurIntensity,
                            onValueChange = { v ->
                                AppRepository.updateSettings { it.copy(motionBlurIntensity = v) }
                            }
                        )
                    },
                    {
                        SliderSettingsRow(
                            icon = Icons.Rounded.Speed,
                            title = "Velocity Sensitivity",
                            value = settings.motionBlurStrength,
                            onValueChange = { v ->
                                AppRepository.updateSettings { it.copy(motionBlurStrength = v) }
                            }
                        )
                    }
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PrivilegeSettingsSubPage(
    settings: ObtainmondSettings,
    privStatus: PrivilegeStatus,
    bottomPadding: Dp,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var externalPkgInput by rememberSaveable(settings.externalInstallerPackage) {
        mutableStateOf(settings.externalInstallerPackage)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SubPageHeader("Privilege Backend", onBack) }

        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Active: ${privStatus.activeBackendLabel}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    if (privStatus.dhizukuAvailable && !privStatus.dhizukuGranted) {
                        TextButton(onClick = { PrivilegeManager.requestDhizukuPermission(context) }) {
                            Text("Grant Dhizuku")
                        }
                    }
                }
            }
        }

        items(PrivilegeBackendMode.entries) { mode ->
            val selected = settings.privilegeMode == mode
            Surface(
                onClick = { AppRepository.updateSettings { it.copy(privilegeMode = mode) } },
                shape = RoundedCornerShape(22.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { AppRepository.updateSettings { it.copy(privilegeMode = mode) } }
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(mode.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(mode.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (settings.privilegeMode == PrivilegeBackendMode.EXTERNAL) {
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("External Installer Target Package", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = externalPkgInput,
                            onValueChange = {
                                externalPkgInput = it
                                AppRepository.updateSettings { s -> s.copy(externalInstallerPackage = it.trim()) }
                            },
                            placeholder = { Text("com.rosan.installer.x (blank = system chooser)") },
                            supportingText = {
                                Text("Passes APK/APKS/XAPK/APKM via FileProvider directly to InstallerX, App Manager, or chooser.")
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SecurityVerificationSubPage(
    settings: ObtainmondSettings,
    bottomPadding: Dp,
    onBack: () -> Unit
) {
    var vtKeyInput by remember { mutableStateOf(AppRepository.getVirusTotalApiKey()) }
    var revealKey by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SubPageHeader(stringResource(R.string.label_supply_chain), onBack) }

        item {
            VerificationPolicySelectorCard(
                title = stringResource(R.string.label_reproducible_builds),
                subtitle = "Queries F-Droid verification.f-droid.org & IzzyOnDroid RBTLog by APK SHA-256",
                selected = settings.reproducibleBuildsPolicy,
                onSelect = { pol -> AppRepository.updateSettings { it.copy(reproducibleBuildsPolicy = pol) } }
            )
        }

        item {
            VerificationPolicySelectorCard(
                title = stringResource(R.string.label_github_attestation),
                subtitle = stringResource(R.string.desc_github_attestation),
                selected = settings.githubAttestationPolicy,
                onSelect = { pol -> AppRepository.updateSettings { it.copy(githubAttestationPolicy = pol) } }
            )
        }

        item {
            VerificationPolicySelectorCard(
                title = stringResource(R.string.label_virustotal_check),
                subtitle = "Checks APK SHA-256 against VirusTotal v3 (always blocks if engines report malicious)",
                selected = settings.virusTotalPolicy,
                onSelect = { pol -> AppRepository.updateSettings { it.copy(virusTotalPolicy = pol) } }
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("VirusTotal v3 API Key", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Stored encrypted with Tink / Android Keystore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = vtKeyInput,
                        onValueChange = {
                            vtKeyInput = it
                            AppRepository.updateVirusTotalApiKey(it)
                        },
                        placeholder = { Text("Enter VirusTotal x-apikey...") },
                        visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { revealKey = !revealKey }) {
                                Icon(
                                    imageVector = if (revealKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (revealKey) "Hide key" else "Show key"
                                )
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerificationPolicySelectorCard(
    title: String,
    subtitle: String,
    selected: VerificationPolicy,
    onSelect: (VerificationPolicy) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                VerificationPolicy.entries.forEach { policy ->
                    FilledTonalToggleButton(
                        checked = selected == policy,
                        onCheckedChange = { onSelect(policy) }
                    ) {
                        Text(policy.title)
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRulesSubPage(
    settings: ObtainmondSettings,
    bottomPadding: Dp,
    onBack: () -> Unit
) {
    var folderInput by remember { mutableStateOf("") }
    var sourcesInput by remember { mutableStateOf("") }
    var keywordsInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SubPageHeader(stringResource(R.string.label_folder_rules), onBack) }

        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Add Auto-Folder Rule", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = folderInput,
                        onValueChange = { folderInput = it },
                        label = { Text("Folder Name") },
                        placeholder = { Text("F-Droid Apps") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sourcesInput,
                        onValueChange = { sourcesInput = it },
                        label = { Text("Source Types (comma-separated, optional)") },
                        placeholder = { Text("FDROID, IZZYONDROID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = keywordsInput,
                        onValueChange = { keywordsInput = it },
                        label = { Text("Keywords / Regex (comma-separated, optional)") },
                        placeholder = { Text("f-droid.org, privacy") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val parsedSources = sourcesInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val parsedKeywords = keywordsInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val hasCriteria = parsedSources.isNotEmpty() || parsedKeywords.isNotEmpty()
                    ExpressiveButton(
                        onClick = {
                            val folder = folderInput.trim()
                            if (folder.isNotEmpty() && hasCriteria) {
                                val nextRule = FolderRule(
                                    id = "rule_${System.currentTimeMillis()}",
                                    folderName = folder,
                                    sourceTypes = parsedSources,
                                    keywords = parsedKeywords
                                )
                                AppRepository.updateSettings { s ->
                                    s.copy(folderRules = s.folderRules + nextRule)
                                }
                                folderInput = ""
                                sourcesInput = ""
                                keywordsInput = ""
                            }
                        },
                        enabled = folderInput.isNotBlank() && hasCriteria,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add Folder Rule")
                    }
                }
            }
        }

        items(settings.folderRules) { rule ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(rule.folderName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        val descParts = buildList {
                            if (rule.sourceTypes.isNotEmpty()) add("Sources: ${rule.sourceTypes.joinToString(", ")}")
                            if (rule.keywords.isNotEmpty()) add("Match: ${rule.keywords.joinToString(", ")}")
                        }.joinToString(" • ")
                        Text(
                            text = descParts.ifBlank { "Custom rule" },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = {
                            AppRepository.updateSettings { s ->
                                s.copy(folderRules = s.folderRules.filterNot { it == rule })
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove rule")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ImportExportSubPage(
    settings: ObtainmondSettings,
    bottomPadding: Dp,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showImportDialog by remember { mutableStateOf(false) }
    var importJsonText by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SubPageHeader("Import / Export", onBack) }

        item {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExpressiveShapeBadge(
                            polygon = MaterialShapes.Clover4Leaf,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            size = 44.dp
                        ) {
                            Icon(Icons.Rounded.Upload, contentDescription = null)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Export Obtainmond JSON", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Copy or share all tracked apps & configuration", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ExpressiveButton(
                            onClick = {
                                val exported = AppRepository.exportConfigurationJson()
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("obtainmond_export.json", exported))
                                Toast.makeText(context, "Copied JSON backup to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Copy JSON")
                        }
                        ExpressiveTonalButton(
                            onClick = {
                                val exported = AppRepository.exportConfigurationJson()
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_TEXT, exported)
                                }
                                context.startActivity(Intent.createChooser(send, "Share Obtainmond JSON"))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share")
                        }
                    }
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExpressiveShapeBadge(
                            polygon = MaterialShapes.Cookie7Sided,
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            size = 44.dp
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Import Obtainium / Obtainmond JSON", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Supports Obtainium { \"apps\": [...] } & Obtainmond backups", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    ExpressiveTonalButton(
                        onClick = { showImportDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Paste JSON Backup")
                    }
                }
            }
        }

        item {
            Surface(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://apps.obtainium.imranr.dev/"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Crowdsourced Obtainium App Directory", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("apps.obtainium.imranr.dev", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                }
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import JSON Backup") },
            text = {
                OutlinedTextField(
                    value = importJsonText,
                    onValueChange = { importJsonText = it },
                    placeholder = { Text("Paste exported Obtainium or Obtainmond JSON...") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val count = AppRepository.importConfigurationJson(importJsonText)
                            showImportDialog = false
                            importJsonText = ""
                            Toast.makeText(context, "Imported $count app(s)", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GitHubNetworkSubPage(
    settings: ObtainmondSettings,
    bottomPadding: Dp,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var githubTokenInput by remember {
        mutableStateOf(AppRepository.getGitHubTokenInput())
    }
    var gitlabTokenInput by remember {
        mutableStateOf(AppRepository.getGitLabToken())
    }
    var revealTokens by remember { mutableStateOf(false) }
    var githubMirrorInput by rememberSaveable(settings.githubMirrorUrl) {
        mutableStateOf(settings.githubMirrorUrl)
    }
    val mirrorUrlValid = githubMirrorInput.isBlank() ||
        githubMirrorInput.trim().startsWith("https://", ignoreCase = true)
    var cacheBytes by remember { mutableStateOf(AppRepository.getApkCacheSizeBytes()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SubPageHeader("GitHub & Network", onBack) }

        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("GitHub Personal Access Tokens", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Stored encrypted with Android Keystore / Tink. Enter one token per line to rotate on rate limits.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = githubTokenInput,
                        onValueChange = {
                            githubTokenInput = it
                            AppRepository.updateGitHubTokenInput(it)
                        },
                        placeholder = { Text("ghp_... (raises rate limit to 5,000 req/hr)") },
                        visualTransformation = if (revealTokens) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { revealTokens = !revealTokens }) {
                                Icon(
                                    imageVector = if (revealTokens) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (revealTokens) "Hide tokens" else "Show tokens"
                                )
                            }
                        },
                        maxLines = 4,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("GitLab Personal Access Token (Optional)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = gitlabTokenInput,
                        onValueChange = {
                            gitlabTokenInput = it
                            AppRepository.updateGitLabToken(it)
                        },
                        placeholder = { Text(stringResource(R.string.gitlab_token_placeholder)) },
                        visualTransformation = if (revealTokens) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            SettingsGroup(
                title = "Acceleration",
                items = listOf(
                    {
                        SwitchSettingsRow(
                            icon = Icons.Rounded.Bolt,
                            title = "GitHub Mirror Acceleration",
                            subtitle = "Routes release APK downloads via custom HTTPS mirror (signature verified)",
                            checked = settings.labGitHubMirrorAcceleration,
                            shape = MaterialShapes.Gem,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(labGitHubMirrorAcceleration = enabled) }
                            }
                        )
                    }
                )
            )
        }

        if (settings.labGitHubMirrorAcceleration) {
            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Custom Mirror Base URL", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = githubMirrorInput,
                            onValueChange = {
                                githubMirrorInput = it
                                val trimmed = it.trim()
                                if (trimmed.isEmpty() || trimmed.startsWith("https://", ignoreCase = true)) {
                                    AppRepository.updateSettings { s -> s.copy(githubMirrorUrl = trimmed) }
                                }
                            },
                            placeholder = { Text("https://mirror.example.com/") },
                            isError = !mirrorUrlValid,
                            supportingText = {
                                Text(
                                    if (!mirrorUrlValid) {
                                        "Mirror URL must use https://"
                                    } else {
                                        "Downloads from mirrors are strictly blocked if APK signature verification fails."
                                    }
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        item {
            SettingsGroup(
                title = "Release & APK Preferences",
                items = listOf(
                    {
                        SwitchSettingsRow(
                            icon = Icons.Rounded.BugReport,
                            title = "Exclude Debug APKs",
                            subtitle = "Skip -debug.apk assets when selecting release binaries",
                            checked = settings.excludeDebugApks,
                            shape = MaterialShapes.Cookie6Sided,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(excludeDebugApks = enabled) }
                            }
                        )
                    },
                    {
                        SwitchSettingsRow(
                            icon = Icons.Rounded.NewReleases,
                            title = "Include Pre-releases by Default",
                            subtitle = "Pre-select pre-release toggle when adding new apps",
                            checked = settings.defaultIncludePrereleases,
                            shape = MaterialShapes.Flower,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(defaultIncludePrereleases = enabled) }
                            }
                        )
                    },
                    {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val deleted = AppRepository.clearApkCache()
                                    cacheBytes = AppRepository.getApkCacheSizeBytes()
                                    Toast.makeText(
                                        context,
                                        if (deleted > 0) "Cleared $deleted cached APK(s)" else "APK cache is already empty",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                .padding(horizontal = 18.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ExpressiveShapeBadge(
                                polygon = MaterialShapes.Clover4Leaf,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                size = 40.dp
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Clear Downloaded APK Cache",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Used: ${Formatter.formatShortFileSize(context, cacheBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AuditLogsSubPage(
    settings: ObtainmondSettings,
    logs: List<io.obtainmond.data.model.AppEventLog>,
    bottomPadding: Dp,
    onBack: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val visibleLogs = remember(logs) { logs.take(50) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 104.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Logs (${logs.size})",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                if (logs.isNotEmpty()) {
                    TextButton(onClick = { AppRepository.clearLogs() }) {
                        Text("Clear")
                    }
                }
            }
        }

        if (visibleLogs.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No events recorded yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }
        } else {
            itemsIndexed(
                items = visibleLogs,
                key = { index, entry -> "${index}_${entry.timestampMs}" }
            ) { _, entry ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        ExpressiveShapeBadge(
                            polygon = if (entry.success) MaterialShapes.Clover4Leaf else MaterialShapes.Diamond,
                            containerColor = if (entry.success) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            },
                            contentColor = if (entry.success) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                            size = 34.dp
                        ) {
                            Icon(
                                imageVector = if (entry.success) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${entry.appName} • ${entry.action}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = runCatching { fmt.format(Date(entry.timestampMs)) }.getOrDefault("--:--:--"),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Text(
                                text = entry.backendUsed,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = entry.details,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AboutSubPage(
    settings: ObtainmondSettings,
    bottomPadding: Dp,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val appIconBitmap = rememberAppIconBitmap(context.packageName)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SubPageHeader("About", onBack) }

        item {
            Surface(
                shape = RoundedCornerShape(30.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (appIconBitmap != null) {
                        Image(
                            bitmap = appIconBitmap,
                            contentDescription = "Obtainmond",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                        )
                    } else {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(76.dp)
                        ) {
                            androidx.compose.foundation.layout.Box(
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "OB",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                    Text("Obtainmond", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                    Text(
                        text = "v${BuildConfig.VERSION_NAME.removeSuffix("-rust")}",
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Direct Android app updater.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "@hmndevtech",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ExpressiveTonalButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/HmnDev-Tech/Obtainmond"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("GitHub", maxLines = 1)
                        }
                        ExpressiveButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/hmndevtech"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Telegram", maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
