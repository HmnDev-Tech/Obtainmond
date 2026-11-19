package io.obtainmond.ui

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.obtainmond.R
import io.obtainmond.data.repo.AppRepository
import io.obtainmond.privilege.PrivilegeManager
import io.obtainmond.ui.compose.ExpressiveFloatingNavigationBar
import io.obtainmond.ui.compose.ExpressiveNavDestination
import io.obtainmond.ui.compose.ObtainmondExpressiveTheme
import io.obtainmond.ui.compose.scrollMotionBlur
import io.obtainmond.ui.screens.AddAppScreen
import io.obtainmond.ui.screens.AppDetailScreen
import io.obtainmond.ui.screens.AppsDashboardScreen
import io.obtainmond.ui.screens.ObtainedLabsScreen
import io.obtainmond.ui.screens.SettingsScreen
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ObtainmondRootApp(sharedUrlFromIntent: String? = null) {
    val context = LocalContext.current
    val settings by AppRepository.settings.collectAsState()
    val apps by AppRepository.apps.collectAsState()
    val busyIds by AppRepository.busyAppIds.collectAsState()
    val privStatus by PrivilegeManager.status.collectAsState()
    val scope = rememberCoroutineScope()

    var openedAppId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAppIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isRefreshingAll by remember { mutableStateOf(false) }

    val updateBadgeCount = apps.count { it.hasUpdate }
    val destinations = listOf(
        ExpressiveNavDestination(
            label = stringResource(R.string.tab_apps),
            selectedIcon = Icons.Rounded.Widgets,
            unselectedIcon = Icons.Outlined.Widgets,
            badgeCount = updateBadgeCount
        ),
        ExpressiveNavDestination(
            label = stringResource(R.string.tab_add),
            selectedIcon = Icons.Rounded.AddCircle,
            unselectedIcon = Icons.Outlined.AddCircleOutline
        ),
        ExpressiveNavDestination(
            label = stringResource(R.string.tab_labs),
            selectedIcon = Icons.Rounded.Science,
            unselectedIcon = Icons.Outlined.Science
        ),
        ExpressiveNavDestination(
            label = stringResource(R.string.tab_settings),
            selectedIcon = Icons.Rounded.Settings,
            unselectedIcon = Icons.Outlined.Settings
        )
    )

    val pagerState = rememberPagerState(
        initialPage = if (!sharedUrlFromIntent.isNullOrBlank()) 1 else 0,
        pageCount = { destinations.size }
    )

    LaunchedEffect(sharedUrlFromIntent) {
        if (!sharedUrlFromIntent.isNullOrBlank()) {
            openedAppId = null
            pagerState.animateScrollToPage(1)
        }
    }

    BackHandler(enabled = selectedAppIds.isNotEmpty() && openedAppId == null) {
        selectedAppIds = emptySet()
    }

    BackHandler(enabled = selectedAppIds.isEmpty() && openedAppId == null && pagerState.currentPage != 0) {
        scope.launch {
            pagerState.animateScrollToPage(
                page = 0,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    fun triggerSyncAll() {
        if (isRefreshingAll) return
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

    ObtainmondExpressiveTheme(
        themeMode = settings.themeMode,
        dynamicColor = settings.dynamicColor,
        pureBlack = settings.pureBlackOled,
        autoScaleUi = settings.autoScaleUi,
        uiScalePercent = settings.uiScalePercent
    ) {
        val openedApp = apps.firstOrNull { it.id == openedAppId }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp
            val useTwoPane = maxWidth >= 840.dp

            AnimatedContent(
                targetState = if (!useTwoPane) openedApp else null,
                contentKey = { it?.id },
                transitionSpec = {
                    (scaleIn(
                        initialScale = 0.94f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) + fadeIn()) togetherWith (scaleOut(targetScale = 0.96f) + fadeOut())
                },
                label = "rootDetailTransition"
            ) { detailApp ->
                if (detailApp != null && !useTwoPane) {
                    AppDetailScreen(
                        app = detailApp,
                        isBusy = detailApp.id in busyIds,
                        settings = settings,
                        onBack = { openedAppId = null }
                    )
                } else {
                    Row(modifier = Modifier.fillMaxSize()) {
                        if (useRail) {
                            NavigationRail(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                header = {
                                    if (pagerState.currentPage == 0) {
                                        FloatingActionButton(
                                            onClick = { if (!isRefreshingAll) triggerSyncAll() }
                                        ) {
                                            if (isRefreshingAll) {
                                                CircularWavyProgressIndicator(modifier = Modifier.size(22.dp))
                                            } else {
                                                Icon(Icons.Rounded.Refresh, contentDescription = "Sync all apps")
                                            }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                    }
                                }
                            ) {
                                destinations.forEachIndexed { index, dest ->
                                    val selected = pagerState.currentPage == index
                                    NavigationRailItem(
                                        selected = selected,
                                        onClick = {
                                            scope.launch {
                                                pagerState.animateScrollToPage(
                                                    page = index,
                                                    animationSpec = spring(
                                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                                        stiffness = Spring.StiffnessMediumLow
                                                    )
                                                )
                                            }
                                        },
                                        icon = {
                                            BadgedBox(
                                                badge = {
                                                    if (dest.badgeCount > 0) {
                                                        Badge { Text(dest.badgeCount.toString()) }
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                                    contentDescription = dest.label
                                                )
                                            }
                                        },
                                        label = { Text(dest.label) }
                                    )
                                }
                            }
                        }

                        Scaffold(
                            containerColor = MaterialTheme.colorScheme.background,
                            modifier = Modifier.weight(1f),
                            bottomBar = {
                                val showBottomSelection = selectedAppIds.isNotEmpty() && pagerState.currentPage == 0
                                if (!useRail || showBottomSelection) {
                                    AnimatedContent(
                                        targetState = showBottomSelection,
                                        transitionSpec = {
                                            (scaleIn(
                                                initialScale = 0.9f,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow
                                                )
                                            ) + fadeIn()) togetherWith (scaleOut(targetScale = 0.92f) + fadeOut())
                                        },
                                        label = "bottomBarMode"
                                    ) { selectionMode ->
                                        if (selectionMode) {
                                            MultiSelectFloatingToolbar(
                                                onUpdateSelected = {
                                                    val targets = selectedAppIds
                                                    selectedAppIds = emptySet()
                                                    scope.launch {
                                                        val updated = AppRepository.batchUpdateApps(targets)
                                                        Toast.makeText(
                                                            context,
                                                            "Updated $updated selected app(s)",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                },
                                                onTogglePinSelected = {
                                                    val targets = selectedAppIds
                                                    targets.forEach { id ->
                                                        AppRepository.updateApp(id) { it.copy(pinned = !it.pinned) }
                                                    }
                                                    selectedAppIds = emptySet()
                                                },
                                                onToggleFreezeSelected = {
                                                    val targets = selectedAppIds
                                                    selectedAppIds = emptySet()
                                                    scope.launch {
                                                        targets.forEach { AppRepository.toggleFreezeApp(it) }
                                                    }
                                                },
                                                onDeleteSelected = {
                                                    val targets = selectedAppIds
                                                    AppRepository.removeApps(targets)
                                                    selectedAppIds = emptySet()
                                                    Toast.makeText(
                                                        context,
                                                        "Removed ${targets.size} app(s)",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                },
                                                onClear = { selectedAppIds = emptySet() }
                                            )
                                        } else if (!useRail) {
                                            ExpressiveFloatingNavigationBar(
                                                destinations = destinations,
                                                selectedIndex = pagerState.targetPage,
                                                onSelect = { page ->
                                                    scope.launch {
                                                        pagerState.animateScrollToPage(
                                                            page = page,
                                                            animationSpec = spring(
                                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                                stiffness = Spring.StiffnessMediumLow
                                                            )
                                                        )
                                                    }
                                                },
                                                fabIcon = if (pagerState.targetPage == 0) Icons.Rounded.Refresh else null,
                                                fabDescription = "Sync all apps",
                                                fabBusy = isRefreshingAll,
                                                onFabClick = if (pagerState.targetPage == 0) ({ triggerSyncAll() }) else null
                                            )
                                        }
                                    }
                                }
                            }
                        ) { innerPadding ->
                            HorizontalPager(
                                state = pagerState,
                                beyondViewportPageCount = 1,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .scrollMotionBlur(
                                        pagerState = pagerState,
                                        enabled = settings.motionBlurEnabled,
                                        intensity = settings.motionBlurIntensity,
                                        strength = settings.motionBlurStrength
                                    )
                            ) { page ->
                                val pageOffset = (
                                    (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                                ).absoluteValue.coerceIn(0f, 1f)
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            alpha = 1f - pageOffset * 0.22f
                                            val s = 1f - pageOffset * 0.035f
                                            scaleX = s
                                            scaleY = s
                                        }
                                ) {
                                    when (page) {
                                        0 -> {
                                            if (useTwoPane) {
                                                Row(modifier = Modifier.fillMaxSize()) {
                                                    Box(modifier = Modifier.weight(0.46f).fillMaxHeight()) {
                                                        AppsDashboardScreen(
                                                            apps = apps,
                                                            busyIds = busyIds,
                                                            privStatus = privStatus,
                                                            settings = settings,
                                                            isRefreshingAll = isRefreshingAll,
                                                            selectedAppIds = selectedAppIds,
                                                            bottomPadding = innerPadding.calculateBottomPadding(),
                                                            onOpenAppDetail = { id -> openedAppId = id },
                                                            onToggleSelectApp = { id ->
                                                                selectedAppIds = if (id in selectedAppIds) {
                                                                    selectedAppIds - id
                                                                } else {
                                                                    selectedAppIds + id
                                                                }
                                                            },
                                                            onSelectAll = { ids -> selectedAppIds = ids.toSet() },
                                                            onClearSelection = { selectedAppIds = emptySet() },
                                                            onRefreshAll = { triggerSyncAll() },
                                                            onJumpToAdd = {
                                                                scope.launch {
                                                                    pagerState.animateScrollToPage(page = 1)
                                                                }
                                                            }
                                                        )
                                                    }
                                                    VerticalDivider()
                                                    Box(
                                                        modifier = Modifier.weight(0.54f).fillMaxHeight(),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        if (openedApp != null) {
                                                            AppDetailScreen(
                                                                app = openedApp,
                                                                isBusy = openedApp.id in busyIds,
                                                                settings = settings,
                                                                onBack = { openedAppId = null }
                                                            )
                                                        } else {
                                                            Text(
                                                                text = stringResource(R.string.msg_select_app_detail),
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                textAlign = TextAlign.Center,
                                                                modifier = Modifier.padding(24.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            } else {
                                                AppsDashboardScreen(
                                                    apps = apps,
                                                    busyIds = busyIds,
                                                    privStatus = privStatus,
                                                    settings = settings,
                                                    isRefreshingAll = isRefreshingAll,
                                                    selectedAppIds = selectedAppIds,
                                                    bottomPadding = innerPadding.calculateBottomPadding(),
                                                    onOpenAppDetail = { id -> openedAppId = id },
                                                    onToggleSelectApp = { id ->
                                                        selectedAppIds = if (id in selectedAppIds) {
                                                            selectedAppIds - id
                                                        } else {
                                                            selectedAppIds + id
                                                        }
                                                    },
                                                    onSelectAll = { ids -> selectedAppIds = ids.toSet() },
                                                    onClearSelection = { selectedAppIds = emptySet() },
                                                    onRefreshAll = { triggerSyncAll() },
                                                    onJumpToAdd = {
                                                        scope.launch {
                                                            pagerState.animateScrollToPage(
                                                                page = 1,
                                                                animationSpec = spring(
                                                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                                                    stiffness = Spring.StiffnessMediumLow
                                                                )
                                                            )
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                        1 -> AddAppScreen(
                                            initialSharedUrl = sharedUrlFromIntent.orEmpty(),
                                            settings = settings,
                                            bottomPadding = innerPadding.calculateBottomPadding(),
                                            onAdded = {
                                                scope.launch {
                                                    pagerState.animateScrollToPage(
                                                        page = 0,
                                                        animationSpec = spring(
                                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                                            stiffness = Spring.StiffnessMediumLow
                                                        )
                                                    )
                                                }
                                            }
                                        )
                                        2 -> ObtainedLabsScreen(
                                            apps = apps,
                                            privStatus = privStatus,
                                            settings = settings,
                                            bottomPadding = innerPadding.calculateBottomPadding()
                                        )
                                        3 -> SettingsScreen(
                                            settings = settings,
                                            privStatus = privStatus,
                                            bottomPadding = innerPadding.calculateBottomPadding()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MultiSelectFloatingToolbar(
    onUpdateSelected: () -> Unit,
    onTogglePinSelected: () -> Unit,
    onToggleFreezeSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onClear: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(
                toolbarContainerColor = MaterialTheme.colorScheme.primaryContainer,
                toolbarContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onUpdateSelected) {
                    Icon(Icons.Rounded.Download, contentDescription = "Update selected")
                }
                IconButton(onClick = onTogglePinSelected) {
                    Icon(Icons.Rounded.PushPin, contentDescription = "Pin or unpin selected")
                }
                IconButton(onClick = onToggleFreezeSelected) {
                    Icon(Icons.Rounded.AcUnit, contentDescription = "Freeze or unfreeze selected")
                }
                IconButton(onClick = onDeleteSelected) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Remove selected",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                IconButton(onClick = onClear) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear selection")
                }
            }
        }
    }
}
