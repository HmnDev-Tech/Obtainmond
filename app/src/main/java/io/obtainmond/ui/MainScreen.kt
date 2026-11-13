package io.obtainmond.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.obtainmond.data.model.InstallerSpoofPreset
import io.obtainmond.data.model.ObtainmondSettings
import io.obtainmond.data.model.PrivilegeBackendMode
import io.obtainmond.data.model.TrackedApp
import io.obtainmond.data.repo.AppRepository
import io.obtainmond.privilege.PrivilegeManager
import io.obtainmond.privilege.PrivilegeStatus
import io.obtainmond.rust.ObtainmondNative
import io.obtainmond.ui.compose.ExpressiveCard
import io.obtainmond.ui.compose.ExpressiveFloatingNavigationBar
import io.obtainmond.ui.compose.ExpressiveNavDestination
import io.obtainmond.ui.compose.ExpressivePulsingDot
import io.obtainmond.ui.compose.ExpressiveStaggeredEntrance
import io.obtainmond.ui.compose.ExpressiveTelemetryTile
import io.obtainmond.ui.compose.ObtainmondExpressiveTheme
import io.obtainmond.ui.compose.SettingsGroup
import io.obtainmond.ui.compose.SliderSettingsRow
import io.obtainmond.ui.compose.SwitchSettingsRow
import io.obtainmond.ui.compose.scrollMotionBlur
import io.obtainmond.worker.AutoUpdateWorker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class QuickCatalogItem(
    val name: String,
    val author: String,
    val url: String,
    val pkgHint: String,
    val category: String
)

private val QUICK_CATALOG = listOf(
    QuickCatalogItem(
        name = "Shevery",
        author = "HmnDev-Tech",
        url = "https://github.com/HmnDev-Tech/Shevery",
        pkgHint = "moe.shizuku.privileged.api",
        category = "Privilege"
    ),
    QuickCatalogItem(
        name = "Obtainmond",
        author = "HmnDev-Tech",
        url = "https://github.com/HmnDev-Tech/Obtainmond",
        pkgHint = "io.obtainmond",
        category = "Updater"
    ),
    QuickCatalogItem(
        name = "Lawnchair",
        author = "LawnchairLauncher",
        url = "https://github.com/LawnchairLauncher/lawnchair",
        pkgHint = "app.lawnchair",
        category = "Launcher"
    ),
    QuickCatalogItem(
        name = "NewPipe",
        author = "TeamNewPipe",
        url = "https://github.com/TeamNewPipe/NewPipe",
        pkgHint = "org.schabi.newpipe",
        category = "Media"
    ),
    QuickCatalogItem(
        name = "Mihon",
        author = "mihonapp",
        url = "https://github.com/mihonapp/mihon",
        pkgHint = "app.mihon",
        category = "Reader"
    ),
    QuickCatalogItem(
        name = "Breezy Weather",
        author = "breezy-weather",
        url = "https://github.com/breezy-weather/breezy-weather",
        pkgHint = "org.breezyweather",
        category = "Utility"
    ),
    QuickCatalogItem(
        name = "Seal",
        author = "JunkFood02",
        url = "https://github.com/JunkFood02/Seal",
        pkgHint = "com.junkfood.seal",
        category = "Downloader"
    )
)

@Composable
fun ObtainmondRootApp(sharedUrlFromIntent: String? = null) {
    val settings by AppRepository.settings.collectAsState()
    val apps by AppRepository.apps.collectAsState()
    val busyIds by AppRepository.busyAppIds.collectAsState()
    val privStatus by PrivilegeManager.status.collectAsState()
    val scope = rememberCoroutineScope()

    val updateBadgeCount = apps.count { it.hasUpdate }
    val destinations = listOf(
        ExpressiveNavDestination(
            label = "Apps",
            selectedIcon = Icons.Filled.Widgets,
            unselectedIcon = Icons.Outlined.Widgets,
            badgeCount = updateBadgeCount
        ),
        ExpressiveNavDestination(
            label = "Add",
            selectedIcon = Icons.Filled.AddCircle,
            unselectedIcon = Icons.Outlined.AddCircleOutline
        ),
        ExpressiveNavDestination(
            label = "Labs",
            selectedIcon = Icons.Filled.Science,
            unselectedIcon = Icons.Outlined.Science
        ),
        ExpressiveNavDestination(
            label = "Settings",
            selectedIcon = Icons.Filled.Settings,
            unselectedIcon = Icons.Outlined.Settings
        )
    )

    val pagerState = rememberPagerState(
        initialPage = if (!sharedUrlFromIntent.isNullOrBlank()) 1 else 0,
        pageCount = { destinations.size }
    )

    LaunchedEffect(sharedUrlFromIntent) {
        if (!sharedUrlFromIntent.isNullOrBlank()) {
            pagerState.animateScrollToPage(1)
        }
    }

    ObtainmondExpressiveTheme(
        themeMode = settings.themeMode,
        dynamicColor = settings.dynamicColor,
        pureBlack = settings.pureBlackOled
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                ExpressiveFloatingNavigationBar(
                    destinations = destinations,
                    selectedIndex = pagerState.currentPage,
                    onSelect = { page ->
                        scope.launch { pagerState.animateScrollToPage(page) }
                    }
                )
            }
        ) { innerPadding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .scrollMotionBlur(
                        pagerState = pagerState,
                        enabled = settings.motionBlurEnabled,
                        intensity = settings.motionBlurIntensity,
                        strength = settings.motionBlurStrength
                    )
            ) { page ->
                when (page) {
                    0 -> AppsDashboardPage(
                        apps = apps,
                        busyIds = busyIds,
                        privStatus = privStatus,
                        settings = settings,
                        bottomPadding = innerPadding.calculateBottomPadding(),
                        onJumpToAdd = { scope.launch { pagerState.animateScrollToPage(1) } }
                    )
                    1 -> AddAppPage(
                        initialSharedUrl = sharedUrlFromIntent.orEmpty(),
                        settings = settings,
                        bottomPadding = innerPadding.calculateBottomPadding(),
                        onAdded = { scope.launch { pagerState.animateScrollToPage(0) } }
                    )
                    2 -> ObtainedLabsPage(
                        apps = apps,
                        busyIds = busyIds,
                        privStatus = privStatus,
                        settings = settings,
                        bottomPadding = innerPadding.calculateBottomPadding()
                    )
                    3 -> SettingsAndLogsPage(
                        settings = settings,
                        privStatus = privStatus,
                        bottomPadding = innerPadding.calculateBottomPadding()
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppsDashboardPage(
    apps: List<TrackedApp>,
    busyIds: Set<String>,
    privStatus: PrivilegeStatus,
    settings: ObtainmondSettings,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onJumpToAdd: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var isRefreshingAll by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf("All") }

    val allCategories = remember(apps) {
        listOf("All", "Updates") + apps.flatMap { it.categories }.distinct().sorted()
    }

    val filteredApps = remember(apps, searchQuery, selectedCategory) {
        apps.filter { app ->
            val matchesQuery = searchQuery.isBlank() ||
                app.name.contains(searchQuery, ignoreCase = true) ||
                app.id.contains(searchQuery, ignoreCase = true) ||
                app.author.contains(searchQuery, ignoreCase = true)
            val matchesCat = when (selectedCategory) {
                "All" -> true
                "Updates" -> app.hasUpdate
                else -> selectedCategory in app.categories
            }
            matchesQuery && matchesCat
        }
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
            start = 18.dp,
            end = 18.dp,
            top = 16.dp,
            bottom = bottomPadding + 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Obtainmond",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "Rust Core • Zero-Touch Auto-Updater",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                FilledTonalButton(
                    enabled = !isRefreshingAll,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    onClick = {
                        scope.launch {
                            isRefreshingAll = true
                            val count = AppRepository.refreshAllAndAutoUpdate(
                                autoInstallUpdates = settings.zeroTouchBackgroundUpdate,
                                isBackgroundWorker = false
                            )
                            isRefreshingAll = false
                            Toast.makeText(
                                context,
                                if (count > 0) "Silently updated $count app(s)" else "All tracked apps checked",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) {
                    if (isRefreshingAll) {
                        LoadingIndicator(modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Syncing", maxLines = 1)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sync", maxLines = 1)
                    }
                }
            }
        }

        // Expressive Privilege & Battery Doze Status Hero Card
        item {
            val isPrivileged = privStatus.hasPrivilegedShell
            ExpressiveCard(
                containerColor = if (isPrivileged) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (isPrivileged) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isPrivileged) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPrivileged) Icons.Filled.Bolt else Icons.Filled.Security,
                            contentDescription = null,
                            tint = if (isPrivileged) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            }
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ExpressivePulsingDot(
                                color = if (isPrivileged) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                                active = isPrivileged
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = privStatus.activeBackendLabel,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (isPrivileged) {
                                "Silent background updates ready via IPC"
                            } else {
                                "Grant Shevery or Shizuku for silent background installs"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isPrivileged) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    IconButton(
                        onClick = {
                            PrivilegeManager.refreshStatus()
                            Toast.makeText(context, "Refreshed privilege binder", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh Binder")
                    }
                }

                Spacer(Modifier.height(14.dp))
                val abiPrimary = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                val tileBg = if (isPrivileged) {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExpressiveTelemetryTile(
                        label = "Primary ABI",
                        value = abiPrimary,
                        containerColor = tileBg,
                        modifier = Modifier.weight(1f)
                    )
                    ExpressiveTelemetryTile(
                        label = "Doze",
                        value = if (privStatus.batteryOptimizationIgnored) "Exempt" else "Active",
                        containerColor = tileBg,
                        valueColor = if (privStatus.batteryOptimizationIgnored) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.weight(1f)
                    )
                    ExpressiveTelemetryTile(
                        label = "Zero-Touch",
                        value = if (settings.zeroTouchBackgroundUpdate) "ON" else "OFF",
                        containerColor = tileBg,
                        modifier = Modifier.weight(1f)
                    )
                }

                AnimatedVisibility(
                    visible = !isPrivileged || !privStatus.batteryOptimizationIgnored,
                    enter = expandVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!isPrivileged) {
                            Button(
                                onClick = { PrivilegeManager.requestPrivilegePermission(context) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Filled.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Grant Privilege", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (!privStatus.batteryOptimizationIgnored) {
                            FilledTonalButton(
                                onClick = {
                                    scope.launch {
                                        val res = PrivilegeManager.enforceZeroBatteryRestriction(context, settings)
                                        if (!res.isSuccess) {
                                            PrivilegeManager.requestBatteryExemptionDialogIfNeeded(context)
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "Whitelisted via ${res.backendUsed}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Filled.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Bypass Doze", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // Search + Category Filter Chips
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search apps, package IDs, authors...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    AnimatedVisibility(
                        visible = searchQuery.isNotBlank(),
                        enter = scaleIn() + fadeIn(),
                        exit = scaleOut() + fadeOut()
                    ) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                allCategories.forEach { cat ->
                    val count = when (cat) {
                        "All" -> apps.size
                        "Updates" -> apps.count { it.hasUpdate }
                        else -> apps.count { cat in it.categories }
                    }
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        shape = CircleShape,
                        label = { Text("$cat ($count)") }
                    )
                }
            }
        }

        if (filteredApps.isEmpty()) {
            item {
                ExpressiveCard {
                    Text(
                        text = "No tracked applications match your filter",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Add any GitHub, GitLab, Codeberg, F-Droid, or IzzyOnDroid app and Obtainmond's Rust engine will automatically pick the exact APK matching your device ABI and silently update it in the background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onJumpToAdd) {
                        Icon(Icons.Filled.AddCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add Your First App")
                    }
                }
            }
        } else {
            itemsIndexed(filteredApps, key = { _, app -> app.id + app.url }) { index, app ->
                ExpressiveStaggeredEntrance(index = index) {
                    TrackedAppExpressiveCard(
                        app = app,
                        isBusy = app.id in busyIds
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun TrackedAppExpressiveCard(
    app: TrackedApp,
    isBusy: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable(app.id) { mutableStateOf(false) }

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "chevronRot"
    )

    ExpressiveCard(
        containerColor = if (expanded) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        topCorner = if (expanded) 24.dp else 28.dp,
        bottomCorner = if (expanded) 24.dp else 28.dp,
        onClick = { expanded = !expanded }
    ) {
        // Row 1: App Monogram + Full-width Name/ID + Source Pill + Expand Chevron
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (app.hasUpdate) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = app.name.take(2).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (app.hasUpdate) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = app.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Text(
                            text = app.sourceType,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            maxLines = 1
                        )
                    }
                    if (app.isFrozen) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                text = "FROZEN",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                maxLines = 1
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${app.id} • ${app.author}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer { rotationZ = chevronRotation }
            )
        }

        Spacer(Modifier.height(12.dp))

        // Row 2: Version Telemetry Pill + Primary Action Button (never squished!)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val instLabel = app.installedVersion.ifBlank { "Not installed" }
            val remLabel = app.latestVersion.ifBlank { "Unchecked" }
            val abiBadge = app.matchedAbi.ifBlank { app.installedAbi.ifBlank { "auto" } }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (app.hasUpdate) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$instLabel → $remLabel",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (app.hasUpdate) FontWeight.Bold else FontWeight.Medium,
                        color = if (app.hasUpdate) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "• $abiBadge",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.width(10.dp))
            if (isBusy) {
                LoadingIndicator(modifier = Modifier.size(32.dp))
            } else if (app.hasUpdate || app.installedVersion.isBlank()) {
                Button(
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    onClick = {
                        scope.launch {
                            if (app.matchedApkUrl.isBlank()) {
                                val refreshed = runCatching { AppRepository.fetchLatestReleaseForApp(app) }.getOrNull()
                                if (refreshed != null) {
                                    AppRepository.downloadAndInstallAppSilently(refreshed.id, refreshed.matchedApkUrl)
                                }
                            } else {
                                AppRepository.downloadAndInstallAppSilently(app.id)
                            }
                        }
                    }
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (app.installedVersion.isBlank()) "Install" else "Update", maxLines = 1)
                }
            } else {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Up to date",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Latest",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeIn(tween(220)),
            exit = shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) + fadeOut(tween(150))
        ) {
            Column(modifier = Modifier.padding(top = 14.dp)) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Rust Smart APK Selection",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Selected APK: ${app.matchedApkName.ifBlank { "Pending release sync" }}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                        if (app.selectionReason.isNotBlank()) {
                            Text(
                                text = "Reason: ${app.selectionReason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (app.lastSha256.isNotBlank()) {
                            Text(
                                text = "SHA-256: ${app.lastSha256.take(24)}…",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Silent Background Auto-Update",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Automatically updates in background via Shevery/Shizuku using matched ABI APK",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = app.silentAutoUpdate,
                        onCheckedChange = { AppRepository.toggleSilentAutoUpdate(app.id, it) }
                    )
                }

                if (app.changelog.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Release Notes",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = app.changelog.take(500),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                val updated = runCatching { AppRepository.fetchLatestReleaseForApp(app) }.getOrNull()
                                if (updated != null && updated.matchedApkUrl.isNotBlank()) {
                                    AppRepository.downloadAndInstallAppSilently(updated.id, updated.matchedApkUrl)
                                } else {
                                    Toast.makeText(context, "Release checked", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Force Reinstall")
                    }

                    if (app.backupApkPath.isNotBlank()) {
                        OutlinedButton(
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
                            Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Rollback v${app.backupVersion}")
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch { AppRepository.toggleFreezeApp(app.id) }
                        }
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (app.isFrozen) "Unfreeze" else "Freeze")
                    }

                    IconButton(onClick = { AppRepository.removeApp(app.id) }) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = "Remove tracked app",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddAppPage(
    initialSharedUrl: String,
    settings: ObtainmondSettings,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onAdded: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var urlInput by rememberSaveable(initialSharedUrl) { mutableStateOf(initialSharedUrl) }
    var customName by rememberSaveable { mutableStateOf("") }
    var customPkgId by rememberSaveable { mutableStateOf("") }
    var apkRegexFilter by rememberSaveable { mutableStateOf("") }
    var categoryInput by rememberSaveable { mutableStateOf("") }
    var includePrereleases by rememberSaveable { mutableStateOf(false) }
    var silentAutoUpdate by rememberSaveable { mutableStateOf(true) }
    var installImmediately by rememberSaveable { mutableStateOf(true) }
    var isSubmitting by remember { mutableStateOf(false) }

    val resolvedPreview = remember(urlInput) {
        if (urlInput.isBlank()) null else ObtainmondNative.resolveSource(urlInput)
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
            start = 18.dp,
            end = 18.dp,
            top = 16.dp,
            bottom = bottomPadding + 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "Add Application",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "GitHub, GitLab, Codeberg, F-Droid, IzzyOnDroid, Direct APK, or HTML Release Page",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            ExpressiveCard {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("Repository or Release URL") },
                    placeholder = { Text("https://github.com/owner/repo") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                AnimatedVisibility(
                    visible = resolvedPreview != null,
                    enter = expandVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    if (resolvedPreview != null) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Rust URL Resolver: ${resolvedPreview.sourceType}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = "Owner/Repo: ${resolvedPreview.owner}/${resolvedPreview.repo}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "Target Device ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("Custom Name (Optional)") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = categoryInput,
                        onValueChange = { categoryInput = it },
                        label = { Text("Category") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = customPkgId,
                        onValueChange = { customPkgId = it },
                        label = { Text("Package ID Hint (Optional)") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = apkRegexFilter,
                        onValueChange = { apkRegexFilter = it },
                        label = { Text("APK Regex Filter") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Zero-Touch Silent Background Auto-Update", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = silentAutoUpdate, onCheckedChange = { silentAutoUpdate = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Include Pre-releases / Nightly tags", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = includePrereleases, onCheckedChange = { includePrereleases = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Download & Silently Install Immediately", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = installImmediately, onCheckedChange = { installImmediately = it })
                }

                Spacer(Modifier.height(14.dp))
                Button(
                    enabled = urlInput.isNotBlank() && !isSubmitting,
                    onClick = {
                        scope.launch {
                            isSubmitting = true
                            val result = AppRepository.addAppByUrl(
                                rawUrl = urlInput,
                                customPackageId = customPkgId,
                                customName = customName,
                                includePrereleases = includePrereleases,
                                silentAutoUpdate = silentAutoUpdate,
                                apkFilterRegex = apkRegexFilter,
                                category = categoryInput,
                                installImmediately = installImmediately
                            )
                            isSubmitting = false
                            result.onSuccess { added ->
                                Toast.makeText(
                                    context,
                                    "Added ${added.name} (${added.matchedApkName.ifBlank { added.latestVersion }})",
                                    Toast.LENGTH_SHORT
                                ).show()
                                urlInput = ""
                                onAdded()
                            }.onFailure { err ->
                                Toast.makeText(
                                    context,
                                    "Failed: ${err.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Resolving & Selecting Best ABI APK...")
                    } else {
                        Icon(Icons.Filled.RocketLaunch, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Track & Auto-Select Primary ABI APK")
                    }
                }
            }
        }

        item {
            Text(
                text = "1-Tap Quick-Add Catalog",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        items(QUICK_CATALOG, key = { it.url }) { item ->
            ExpressiveCard(
                onClick = {
                    urlInput = item.url
                    customName = item.name
                    customPkgId = item.pkgHint
                    categoryInput = item.category
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            text = "${item.author} • ${item.pkgHint}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                isSubmitting = true
                                AppRepository.addAppByUrl(
                                    rawUrl = item.url,
                                    customPackageId = item.pkgHint,
                                    customName = item.name,
                                    category = item.category,
                                    installImmediately = true
                                )
                                isSubmitting = false
                                onAdded()
                            }
                        }
                    ) {
                        Text("Track + Install")
                    }
                }
            }
        }
    }
}

@Composable
private fun ObtainedLabsPage(
    apps: List<TrackedApp>,
    busyIds: Set<String>,
    privStatus: PrivilegeStatus,
    settings: ObtainmondSettings,
    bottomPadding: androidx.compose.ui.unit.Dp
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var showSpoofDialog by remember { mutableStateOf(false) }

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
            start = 18.dp,
            end = 18.dp,
            top = 16.dp,
            bottom = bottomPadding + 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Obtainmond Labs",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "Privileged Shevery-API & Rust power features missing from standard Obtainium",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            SettingsGroup(
                title = "Zero-Touch Background Daemon & Doze Bypass",
                items = listOf(
                    {
                        SwitchSettingsRow(
                            icon = Icons.Filled.AutoAwesome,
                            title = "On-The-Fly Silent Background Updates",
                            subtitle = "Updates tracked apps in the background without opening Obtainmond or asking confirmation, picking the APK matching your installed ABI",
                            checked = settings.zeroTouchBackgroundUpdate,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(zeroTouchBackgroundUpdate = enabled) }
                                if (enabled) {
                                    AutoUpdateWorker.schedulePeriodic(context, settings.backgroundIntervalMinutes)
                                }
                            }
                        )
                    },
                    {
                        SwitchSettingsRow(
                            icon = Icons.Filled.BatteryChargingFull,
                            title = "Privileged Battery & Doze Self-Whitelist",
                            subtitle = "Uses Shevery/Shizuku to run `cmd deviceidle whitelist +io.obtainmond` and `RUN_ANY_IN_BACKGROUND allow` automatically",
                            checked = settings.autoWhitelistBatteryDoze,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(autoWhitelistBatteryDoze = enabled) }
                            }
                        )
                    },
                    {
                        SwitchSettingsRow(
                            icon = Icons.Filled.Bolt,
                            title = "Immortal Standby Bucket (ACTIVE)",
                            subtitle = "Locks `io.obtainmond` into Android's `active` standby bucket (`am set-standby-bucket io.obtainmond active`)",
                            checked = settings.labImmortalStandbyBucketActive,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(labImmortalStandbyBucketActive = enabled) }
                            }
                        )
                    },
                    {
                        SliderSettingsRow(
                            icon = Icons.Filled.History,
                            title = "Background Poll Interval",
                            value = settings.backgroundIntervalMinutes,
                            valueRange = 15..180,
                            unitSuffix = " min",
                            onValueChange = { mins ->
                                AppRepository.updateSettings { it.copy(backgroundIntervalMinutes = mins) }
                                AutoUpdateWorker.schedulePeriodic(context, mins)
                            }
                        )
                    }
                )
            )
        }

        item {
            SettingsGroup(
                title = "Rust Security, Rollback & ART Compiler",
                items = listOf(
                    {
                        SwitchSettingsRow(
                            icon = Icons.Filled.Restore,
                            title = "Instant Rollback Time-Machine",
                            subtitle = "Automatically snapshots the currently installed `base.apk` before every silent update so you can 1-click downgrade (`pm install -r -d`)",
                            checked = settings.labInstantRollbackBackup,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(labInstantRollbackBackup = enabled) }
                            }
                        )
                    },
                    {
                        SwitchSettingsRow(
                            icon = Icons.Filled.Speed,
                            title = "Post-Install ART Speed-Profile Dexopt",
                            subtitle = "Executes `cmd package compile -m speed-profile -f <pkg>` via Shevery/Shizuku right after silent updates for zero JIT stutter",
                            checked = settings.labPostInstallDexoptSpeedProfile,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(labPostInstallDexoptSpeedProfile = enabled) }
                            }
                        )
                    },
                    {
                        SwitchSettingsRow(
                            icon = Icons.Filled.Security,
                            title = "Rust ABI & ZIP/Signature Guard",
                            subtitle = "Inspects APK Central Directory in Rust (`libobtainmond_core.so`) to block corrupt archives or incompatible CPU ABIs before install",
                            checked = settings.labRustAbiAndSignatureGuard,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(labRustAbiAndSignatureGuard = enabled) }
                            }
                        )
                    },
                    {
                        SwitchSettingsRow(
                            icon = Icons.Filled.VerifiedUser,
                            title = "Auto-Grant Runtime Permissions (-g)",
                            subtitle = "Passes `-g` to privileged `pm install` so updated apps never lose or prompt for runtime permissions",
                            checked = settings.autoGrantRuntimePermissions,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(autoGrantRuntimePermissions = enabled) }
                            }
                        )
                    },
                    {
                        SwitchSettingsRow(
                            icon = Icons.Filled.RocketLaunch,
                            title = "GitHub Mirror Acceleration",
                            subtitle = "Routes GitHub release asset downloads through high-speed mirror edge when rate-limited or throttled",
                            checked = settings.labGitHubMirrorAcceleration,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(labGitHubMirrorAcceleration = enabled) }
                            }
                        )
                    }
                )
            )
        }

        // Installer Source Spoofer Card
        item {
            ExpressiveCard(onClick = { showSpoofDialog = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.SystemUpdateAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Installer Package Spoofer (-i)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Current: ${settings.installerSpoofPreset.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { showSpoofDialog = true }) {
                        Text("Change")
                    }
                }
            }
        }

        // 1-Tap Batch ART Dexopt Boost & Rollback Vault
        item {
            ExpressiveCard {
                Text(
                    text = "Privileged Lab Actions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Execute immediate privileged maintenance across all tracked packages via ${privStatus.activeBackendLabel}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                var compiled = 0
                                for (app in apps) {
                                    if (app.installedVersion.isNotBlank()) {
                                        val res = PrivilegeManager.compilePackageDexopt(app.id, "speed-profile", settings)
                                        if (res.isSuccess) compiled++
                                    }
                                }
                                Toast.makeText(
                                    context,
                                    "ART speed-profile compiled for $compiled app(s)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Dexopt Boost All Apps")
                    }

                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            AutoUpdateWorker.triggerImmediateBackgroundCheck(context)
                            Toast.makeText(
                                context,
                                "Dispatched immediate background auto-update worker",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) {
                        Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Trigger Background Worker Now")
                    }
                }
            }
        }
    }

    if (showSpoofDialog) {
        AlertDialog(
            onDismissRequest = { showSpoofDialog = false },
            title = { Text("Select Installer Source Spoof Preset") },
            text = {
                Column {
                    InstallerSpoofPreset.entries.forEach { preset ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    AppRepository.updateSettings { it.copy(installerSpoofPreset = preset) }
                                    showSpoofDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.installerSpoofPreset == preset,
                                onClick = {
                                    AppRepository.updateSettings { it.copy(installerSpoofPreset = preset) }
                                    showSpoofDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(preset.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    preset.packageId,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpoofDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun SettingsAndLogsPage(
    settings: ObtainmondSettings,
    privStatus: PrivilegeStatus,
    bottomPadding: androidx.compose.ui.unit.Dp
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logs by AppRepository.logs.collectAsState()
    val listState = rememberLazyListState()

    var showBackendDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importJsonText by remember { mutableStateOf("") }
    var githubTokenInput by rememberSaveable(settings.githubPersonalAccessToken) {
        mutableStateOf(settings.githubPersonalAccessToken)
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
            start = 18.dp,
            end = 18.dp,
            top = 16.dp,
            bottom = bottomPadding + 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Settings & Telemetry",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = ObtainmondNative.safeEngineVersion(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace
            )
        }

        // Privilege Backend Selector
        item {
            ExpressiveCard(onClick = { showBackendDialog = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Privilege Execution Backend",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${settings.privilegeMode.title}\nActive: ${privStatus.activeBackendLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { showBackendDialog = true }) {
                        Text("Select")
                    }
                }
            }
        }

        // Material 3 Expressive & Motion Blur Settings
        item {
            SettingsGroup(
                title = "Material 3 Expressive & Motion Blur",
                items = listOf(
                    {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.DarkMode, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Text("Theme Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(0 to "Auto", 1 to "Light", 2 to "Dark").forEach { (mode, label) ->
                                    FilterChip(
                                        selected = settings.themeMode == mode,
                                        onClick = { AppRepository.updateSettings { it.copy(themeMode = mode) } },
                                        shape = CircleShape,
                                        label = {
                                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                                Text(label, maxLines = 1)
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    },
                    {
                        SwitchSettingsRow(
                            icon = Icons.Filled.Palette,
                            title = "Material You Dynamic Color",
                            subtitle = "Adapts expressive color tokens to your Android 12+ wallpaper",
                            checked = settings.dynamicColor,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(dynamicColor = enabled) }
                            }
                        )
                    },
                    {
                        SwitchSettingsRow(
                            icon = Icons.Filled.Contrast,
                            title = "Pure Black OLED Surface",
                            subtitle = "Uses #000000 background in dark theme for AMOLED power savings",
                            checked = settings.pureBlackOled,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(pureBlackOled = enabled) }
                            }
                        )
                    },
                    {
                        SwitchSettingsRow(
                            icon = Icons.Filled.BlurOn,
                            title = "Directional AGSL Scroll Motion Blur",
                            subtitle = "10-sample velocity-driven shader blur on list scrolls & tab swipes",
                            checked = settings.motionBlurEnabled,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(motionBlurEnabled = enabled) }
                            }
                        )
                    },
                    {
                        SliderSettingsRow(
                            icon = Icons.Filled.BlurOn,
                            title = "Motion Blur Intensity",
                            value = settings.motionBlurIntensity,
                            onValueChange = { v ->
                                AppRepository.updateSettings { it.copy(motionBlurIntensity = v) }
                            }
                        )
                    }
                )
            )
        }

        // GitHub API Token & Backup Import/Export
        item {
            ExpressiveCard {
                Text(
                    text = "GitHub Token & Obtainium JSON Migration",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = githubTokenInput,
                    onValueChange = {
                        githubTokenInput = it
                        AppRepository.updateSettings { s -> s.copy(githubPersonalAccessToken = it.trim()) }
                    },
                    label = { Text("GitHub Personal Access Token (Optional)") },
                    placeholder = { Text("ghp_... (raises rate limit to 5,000 req/hr)") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = {
                            val exported = AppRepository.exportConfigurationJson()
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("obtainmond_export.json", exported))
                            Toast.makeText(context, "Copied Obtainmond JSON backup to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Export JSON")
                    }
                    OutlinedButton(
                        onClick = { showImportDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Import Obtainium JSON")
                    }
                }
            }
        }

        // Live Audit & Silent Update Logs
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Privileged Execution & Rust Audit Logs (${logs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (logs.isNotEmpty()) {
                    TextButton(onClick = { AppRepository.clearLogs() }) {
                        Text("Clear")
                    }
                }
            }
        }

        if (logs.isEmpty()) {
            item {
                ExpressiveCard {
                    Text(
                        text = "No silent update or privileged events recorded yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(logs.take(30), key = { "${it.timestampMs}_${it.appId}_${it.action}" }) { entry ->
                val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                ExpressiveCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${entry.appName} • ${entry.action}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (entry.success) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                        Text(
                            text = fmt.format(Date(entry.timestampMs)),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = "Backend: ${entry.backendUsed}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = entry.details,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }

    if (showBackendDialog) {
        AlertDialog(
            onDismissRequest = { showBackendDialog = false },
            title = { Text("Privilege Execution Mode") },
            text = {
                Column {
                    PrivilegeBackendMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    AppRepository.updateSettings { it.copy(privilegeMode = mode) }
                                    showBackendDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.privilegeMode == mode,
                                onClick = {
                                    AppRepository.updateSettings { it.copy(privilegeMode = mode) }
                                    showBackendDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(mode.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(mode.subtitle, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBackendDialog = false }) {
                    Text("Done")
                }
            }
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Obtainium / Obtainmond JSON") },
            text = {
                OutlinedTextField(
                    value = importJsonText,
                    onValueChange = { importJsonText = it },
                    placeholder = { Text("Paste exported Obtainium or Obtainmond JSON here...") },
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
