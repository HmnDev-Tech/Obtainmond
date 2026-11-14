package io.obtainmond.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Launch
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.obtainmond.data.model.ObtainmondSettings
import io.obtainmond.data.model.TrackedApp
import io.obtainmond.data.repo.AppRepository
import io.obtainmond.ui.compose.ExpressiveAppAvatar
import io.obtainmond.ui.compose.ExpressiveShapeBadge
import io.obtainmond.ui.compose.ExpressiveTonalButton
import io.obtainmond.ui.compose.SettingsGroup
import io.obtainmond.ui.compose.SwitchSettingsRow
import io.obtainmond.ui.compose.categoryAccentColor
import io.obtainmond.ui.compose.scrollMotionBlur
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun AppDetailScreen(
    app: TrackedApp,
    isBusy: Boolean,
    settings: ObtainmondSettings,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var showSplitMenu by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showAllAssets by remember { mutableStateOf(false) }
    var expandChangelog by remember { mutableStateOf(true) }

    val launchIntent = remember(app.id) {
        runCatching { context.packageManager.getLaunchIntentForPackage(app.id) }.getOrNull()
    }
    val dateFmt = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .scrollMotionBlur(
                    listState = listState,
                    enabled = settings.motionBlurEnabled,
                    intensity = settings.motionBlurIntensity,
                    strength = settings.motionBlurStrength
                ),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(
                            onClick = {
                                AppRepository.updateApp(app.id) { it.copy(pinned = !it.pinned) }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PushPin,
                                contentDescription = "Pin",
                                tint = if (app.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showEditDialog = true }) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Edit config")
                        }
                        IconButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(app.url))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open source URL")
                        }
                        IconButton(
                            onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, app.url)
                                }
                                context.startActivity(Intent.createChooser(send, "Share ${app.name}"))
                            }
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = "Share URL")
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(32.dp),
                    color = if (app.hasUpdate) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ExpressiveAppAvatar(
                                packageId = app.id,
                                appName = app.name,
                                hasUpdate = app.hasUpdate,
                                isSelected = false,
                                isFrozen = app.isFrozen,
                                isBusy = isBusy,
                                size = 76.dp
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.name,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = app.author.ifBlank { app.sourceType },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = app.id,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = MaterialShapes.Slanted.toShape(),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = app.sourceType,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                            app.categories.forEach { cat ->
                                val c = categoryAccentColor(cat, MaterialTheme.colorScheme.tertiary)
                                Surface(
                                    shape = CircleShape,
                                    color = c.copy(alpha = 0.18f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(MaterialShapes.Cookie6Sided.toShape())
                                                .background(c)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = cat,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        if (isBusy) {
                            LinearWavyProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Working with Rust engine & privileged installer...",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    SplitButtonLayout(
                                        leadingButton = {
                                            SplitButtonDefaults.LeadingButton(
                                                onClick = {
                                                    scope.launch {
                                                        when {
                                                            app.hasUpdate || app.installedVersion.isBlank() -> {
                                                                val targetUrl = if (app.matchedApkUrl.isBlank()) {
                                                                    AppRepository.checkSingleApp(app.id).getOrNull()?.matchedApkUrl.orEmpty()
                                                                } else {
                                                                    app.matchedApkUrl
                                                                }
                                                                if (targetUrl.isNotBlank()) {
                                                                    AppRepository.downloadAndInstallAppSilently(app.id, targetUrl)
                                                                } else {
                                                                    Toast.makeText(context, "No compatible APK asset found", Toast.LENGTH_SHORT).show()
                                                                }
                                                            }
                                                            else -> {
                                                                val res = AppRepository.checkSingleApp(app.id)
                                                                Toast.makeText(
                                                                    context,
                                                                    res.fold(
                                                                        onSuccess = { if (it.hasUpdate) "Update v${it.latestVersion} available" else "Already up to date" },
                                                                        onFailure = { "Check failed: ${it.message}" }
                                                                    ),
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            }
                                                        }
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = when {
                                                        app.hasUpdate || app.installedVersion.isBlank() -> Icons.Rounded.Download
                                                        else -> Icons.Rounded.Refresh
                                                    },
                                                    contentDescription = null,
                                                    modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = when {
                                                        app.installedVersion.isBlank() -> "Install"
                                                        app.hasUpdate -> "Update to ${app.latestVersion}"
                                                        else -> "Check Release"
                                                    },
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        },
                                        trailingButton = {
                                            SplitButtonDefaults.TrailingButton(
                                                checked = showSplitMenu,
                                                onCheckedChange = { showSplitMenu = it }
                                            ) {
                                                val rotation by animateFloatAsState(
                                                    targetValue = if (showSplitMenu) 180f else 0f,
                                                    label = "splitChevron"
                                                )
                                                Icon(
                                                    imageVector = Icons.Rounded.KeyboardArrowDown,
                                                    contentDescription = "More actions",
                                                    modifier = Modifier
                                                        .size(SplitButtonDefaults.TrailingIconSize)
                                                        .graphicsLayer { rotationZ = rotation }
                                                )
                                            }
                                        }
                                    )

                                    DropdownMenu(
                                        expanded = showSplitMenu,
                                        onDismissRequest = { showSplitMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Force Reinstall") },
                                            leadingIcon = { Icon(Icons.Rounded.Bolt, contentDescription = null) },
                                            onClick = {
                                                showSplitMenu = false
                                                scope.launch {
                                                    val refreshed = AppRepository.checkSingleApp(app.id).getOrNull()
                                                    val url = refreshed?.matchedApkUrl ?: app.matchedApkUrl
                                                    if (url.isNotBlank()) {
                                                        AppRepository.downloadAndInstallAppSilently(app.id, url)
                                                    }
                                                }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Check Release Now") },
                                            leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                                            onClick = {
                                                showSplitMenu = false
                                                scope.launch { AppRepository.checkSingleApp(app.id) }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if (app.isFrozen) "Unfreeze Package" else "Freeze Package") },
                                            leadingIcon = { Icon(Icons.Rounded.AcUnit, contentDescription = null) },
                                            onClick = {
                                                showSplitMenu = false
                                                scope.launch { AppRepository.toggleFreezeApp(app.id) }
                                            }
                                        )
                                        if (app.backupApkPath.isNotBlank()) {
                                            DropdownMenuItem(
                                                text = { Text("Rollback to v${app.backupVersion}") },
                                                leadingIcon = { Icon(Icons.Rounded.Restore, contentDescription = null) },
                                                onClick = {
                                                    showSplitMenu = false
                                                    scope.launch {
                                                        val ok = AppRepository.rollbackAppToBackup(app.id)
                                                        Toast.makeText(
                                                            context,
                                                            if (ok) "Rolled back to v${app.backupVersion}" else "Rollback failed",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }

                                if (launchIntent != null) {
                                    ExpressiveTonalButton(
                                        onClick = {
                                            runCatching { context.startActivity(launchIntent) }
                                        }
                                    ) {
                                        Icon(Icons.Rounded.Launch, contentDescription = "Open app", modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Open")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp, topEnd = 10.dp, bottomEnd = 10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "INSTALLED",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = app.installedVersion.ifBlank { "Not installed" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = app.installedAbi.ifBlank { "auto ABI" },
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    ExpressiveShapeBadge(
                        polygon = if (app.hasUpdate) MaterialShapes.Sunny else MaterialShapes.Circle,
                        containerColor = if (app.hasUpdate) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = if (app.hasUpdate) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        size = 38.dp
                    ) {
                        Icon(
                            imageVector = if (app.hasUpdate) Icons.Rounded.Download else Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp, topEnd = 26.dp, bottomEnd = 26.dp),
                        color = if (app.hasUpdate) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "LATEST RELEASE",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (app.hasUpdate) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = app.latestVersion.ifBlank { "Unchecked" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = app.matchedAbi.ifBlank { "pending" },
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (app.hasUpdate) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ExpressiveShapeBadge(
                                    polygon = MaterialShapes.Gem,
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    size = 34.dp
                                ) {
                                    Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Rust Smart APK Selection",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = app.matchedApkName.ifBlank { "Sync release to inspect APKs" },
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (app.availableApks.size > 1) {
                                TextButton(onClick = { showAllAssets = !showAllAssets }) {
                                    Text(if (showAllAssets) "Hide (${app.availableApks.size})" else "All (${app.availableApks.size})")
                                }
                            }
                        }

                        if (app.selectionReason.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = app.selectionReason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        AnimatedVisibility(
                            visible = showAllAssets && app.availableApks.isNotEmpty(),
                            enter = expandVertically(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            ) + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(
                                modifier = Modifier.padding(top = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                app.availableApks.forEach { asset ->
                                    val selected = asset.name == app.matchedApkName
                                    val sizeMb = if (asset.size > 0) {
                                        String.format(Locale.US, "%.1f MB", asset.size / (1024f * 1024f))
                                    } else {
                                        "APK"
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = asset.name,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = sizeMb,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            OutlinedButton(
                                                enabled = !isBusy,
                                                onClick = {
                                                    scope.launch {
                                                        AppRepository.downloadAndInstallAppSilently(app.id, asset.url)
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                            ) {
                                                Text("Install")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (app.changelog.isNotBlank()) {
                item {
                    val chevronRot by animateFloatAsState(
                        targetValue = if (expandChangelog) 180f else 0f,
                        label = "changelogChevron"
                    )
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expandChangelog = !expandChangelog },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Release Notes",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            cm?.setPrimaryClip(ClipData.newPlainText("changelog", app.changelog))
                                            Toast.makeText(context, "Release notes copied", Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp))
                                    }
                                    Icon(
                                        imageVector = Icons.Rounded.ExpandMore,
                                        contentDescription = null,
                                        modifier = Modifier.graphicsLayer { rotationZ = chevronRot }
                                    )
                                }
                            }
                            AnimatedVisibility(visible = expandChangelog) {
                                Text(
                                    text = app.changelog,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    }
                }
            }

            item {
                SettingsGroup(
                    title = "Tracking Rules",
                    items = listOf(
                        {
                            SwitchSettingsRow(
                                icon = Icons.Rounded.AutoAwesome,
                                title = "Silent Background Auto-Update",
                                subtitle = "Update automatically via Shevery/Shizuku",
                                checked = app.silentAutoUpdate,
                                onCheckedChange = { enabled ->
                                    AppRepository.toggleSilentAutoUpdate(app.id, enabled)
                                }
                            )
                        },
                        {
                            SwitchSettingsRow(
                                icon = Icons.Rounded.Science,
                                title = "Include Pre-releases",
                                subtitle = "Track beta, alpha, and nightly releases",
                                checked = app.includePrereleases,
                                shape = MaterialShapes.Cookie4Sided,
                                onCheckedChange = { enabled ->
                                    AppRepository.updateApp(app.id) { it.copy(includePrereleases = enabled) }
                                }
                            )
                        },
                        {
                            SwitchSettingsRow(
                                icon = Icons.Rounded.Visibility,
                                title = "Track Only",
                                subtitle = "Show new versions without downloading or installing",
                                checked = app.trackOnly,
                                shape = MaterialShapes.Arch,
                                onCheckedChange = { enabled ->
                                    AppRepository.updateApp(app.id) { it.copy(trackOnly = enabled) }
                                }
                            )
                        },
                        {
                            SwitchSettingsRow(
                                icon = Icons.Rounded.Restore,
                                title = "Allow Downgrade (-d)",
                                subtitle = "Permit installing older version codes",
                                checked = app.allowDowngrade,
                                shape = MaterialShapes.Diamond,
                                onCheckedChange = { enabled ->
                                    AppRepository.updateApp(app.id) { it.copy(allowDowngrade = enabled) }
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
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Integrity & Rollback Vault",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (app.lastSha256.isNotBlank()) {
                            Text(
                                text = "SHA-256: ${app.lastSha256}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val lastCheckLabel = if (app.lastCheckedEpochMs > 0L) dateFmt.format(Date(app.lastCheckedEpochMs)) else "Never"
                        val lastUpdLabel = if (app.lastUpdatedEpochMs > 0L) dateFmt.format(Date(app.lastUpdatedEpochMs)) else "Never"
                        Text(
                            text = "Last checked: $lastCheckLabel  •  Last updated: $lastUpdLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (app.backupApkPath.isNotBlank()) {
                            ExpressiveTonalButton(
                                onClick = {
                                    scope.launch {
                                        val ok = AppRepository.rollbackAppToBackup(app.id)
                                        Toast.makeText(
                                            context,
                                            if (ok) "Rolled back to v${app.backupVersion}" else "Rollback failed",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            ) {
                                Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Instant Rollback to v${app.backupVersion}")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditDialog) {
        var nameEdit by remember(app.id) { mutableStateOf(app.name) }
        var catEdit by remember(app.id) { mutableStateOf(app.categories.joinToString(", ")) }
        var apkRegexEdit by remember(app.id) { mutableStateOf(app.apkFilterRegex) }
        var verRegexEdit by remember(app.id) { mutableStateOf(app.versionExtractionRegex) }

        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit App Configuration") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = nameEdit,
                        onValueChange = { nameEdit = it },
                        label = { Text("Display Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = catEdit,
                        onValueChange = { catEdit = it },
                        label = { Text("Categories (comma-separated)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = apkRegexEdit,
                        onValueChange = { apkRegexEdit = it },
                        label = { Text("APK Filter Regex") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = verRegexEdit,
                        onValueChange = { verRegexEdit = it },
                        label = { Text("Version Extraction Regex") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cats = catEdit.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        AppRepository.updateApp(app.id) {
                            it.copy(
                                name = nameEdit.trim().ifBlank { it.name },
                                categories = cats,
                                apkFilterRegex = apkRegexEdit.trim(),
                                versionExtractionRegex = verRegexEdit.trim()
                            )
                        }
                        showEditDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Remove ${app.name}?") },
            text = { Text("Stops tracking updates for ${app.id}. The installed app on your device is not uninstalled.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        AppRepository.removeApp(app.id)
                        onBack()
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
