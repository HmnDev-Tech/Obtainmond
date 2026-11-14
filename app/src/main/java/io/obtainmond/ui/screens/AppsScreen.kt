package io.obtainmond.ui.screens

import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.obtainmond.data.model.ObtainmondSettings
import io.obtainmond.data.model.TrackedApp
import io.obtainmond.data.repo.AppRepository
import io.obtainmond.privilege.PrivilegeManager
import io.obtainmond.privilege.PrivilegeStatus
import io.obtainmond.ui.compose.ExpressiveAppAvatar
import io.obtainmond.ui.compose.ExpressiveButton
import io.obtainmond.ui.compose.ExpressivePulsingDot
import io.obtainmond.ui.compose.ExpressiveShapeBadge
import io.obtainmond.ui.compose.ExpressiveStaggeredEntrance
import io.obtainmond.ui.compose.ExpressiveTonalButton
import io.obtainmond.ui.compose.categoryAccentColor
import io.obtainmond.ui.compose.scrollMotionBlur
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun AppsDashboardScreen(
    apps: List<TrackedApp>,
    busyIds: Set<String>,
    privStatus: PrivilegeStatus,
    settings: ObtainmondSettings,
    isRefreshingAll: Boolean,
    selectedAppIds: Set<String>,
    bottomPadding: Dp,
    onOpenAppDetail: (String) -> Unit,
    onToggleSelectApp: (String) -> Unit,
    onSelectAll: (List<String>) -> Unit,
    onClearSelection: () -> Unit,
    onRefreshAll: () -> Unit,
    onJumpToAdd: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var statusFilter by rememberSaveable { mutableStateOf("All") }
    var sourceFilter by rememberSaveable { mutableStateOf("All") }
    var categoryFilter by rememberSaveable { mutableStateOf("All") }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showPrivilegeSheet by remember { mutableStateOf(false) }
    var collapsedCategories by remember { mutableStateOf<Set<String>>(emptySet()) }

    val availableSources = remember(apps) {
        listOf("All") + apps.map { it.sourceType }.distinct().sorted()
    }
    val availableCategories = remember(apps) {
        listOf("All") + apps.flatMap { it.categories }.distinct().sorted()
    }

    val filteredAndSorted = remember(
        apps,
        searchQuery,
        statusFilter,
        sourceFilter,
        categoryFilter,
        settings.appSortMode
    ) {
        val base = apps.filter { app ->
            val q = searchQuery.trim()
            val matchesQuery = q.isBlank() ||
                app.name.contains(q, ignoreCase = true) ||
                app.id.contains(q, ignoreCase = true) ||
                app.author.contains(q, ignoreCase = true)
            val matchesStatus = when (statusFilter) {
                "Updates" -> app.hasUpdate
                "Installed" -> app.installedVersion.isNotBlank()
                "Not Installed" -> app.installedVersion.isBlank()
                "Frozen" -> app.isFrozen
                else -> true
            }
            val matchesSource = sourceFilter == "All" || app.sourceType == sourceFilter
            val matchesCategory = categoryFilter == "All" || categoryFilter in app.categories
            matchesQuery && matchesStatus && matchesSource && matchesCategory
        }

        val comparator = when (settings.appSortMode) {
            1 -> compareByDescending<TrackedApp> { it.pinned }
                .thenByDescending { it.hasUpdate }
                .thenBy { it.name.lowercase() }
            2 -> compareByDescending<TrackedApp> { it.pinned }
                .thenByDescending { it.lastUpdatedEpochMs }
                .thenBy { it.name.lowercase() }
            3 -> compareByDescending<TrackedApp> { it.pinned }
                .thenBy { it.sourceType }
                .thenBy { it.name.lowercase() }
            else -> compareByDescending<TrackedApp> { it.pinned }
                .thenBy { it.name.lowercase() }
        }
        base.sortedWith(comparator)
    }

    val groupedApps = remember(filteredAndSorted, settings.groupByCategory) {
        if (!settings.groupByCategory) {
            emptyMap()
        } else {
            filteredAndSorted
                .groupBy { it.categories.firstOrNull()?.ifBlank { null } ?: "Uncategorized" }
                .toSortedMap(compareBy<String> { it == "Uncategorized" }.thenBy { it.lowercase() })
        }
    }

    val updatesReadyCount = remember(apps) { apps.count { it.hasUpdate } }
    val activeFilterCount = listOf(
        statusFilter != "All",
        sourceFilter != "All",
        categoryFilter != "All"
    ).count { it }

    val outlineFallback = MaterialTheme.colorScheme.outline

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
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            if (selectedAppIds.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onClearSelection) {
                            Icon(Icons.Rounded.Close, contentDescription = "Cancel selection")
                        }
                        Text(
                            text = "${selectedAppIds.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onSelectAll(filteredAndSorted.map { it.id }) }
                        ) {
                            Icon(Icons.Rounded.SelectAll, contentDescription = "Select all")
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Apps",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-0.5).sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            onClick = { showPrivilegeSheet = true },
                            shape = CircleShape,
                            color = if (privStatus.hasPrivilegedShell) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
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
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TonalToggleButton(
                            checked = settings.groupByCategory,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(groupByCategory = enabled) }
                            },
                            shapes = ToggleButtonDefaults.shapes()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FolderCopy,
                                contentDescription = "Group by category",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        ExpressiveTonalButton(
                            onClick = { showFilterSheet = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FilterList,
                                contentDescription = "Filter and sort",
                                modifier = Modifier.size(18.dp)
                            )
                            if (activeFilterCount > 0) {
                                Spacer(Modifier.width(4.dp))
                                Text(activeFilterCount.toString())
                            }
                        }
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search apps, package IDs, authors...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    AnimatedVisibility(
                        visible = searchQuery.isNotBlank(),
                        enter = scaleIn() + fadeIn(),
                        exit = scaleOut() + fadeOut()
                    ) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (isRefreshingAll) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LinearWavyProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Syncing releases via Rust engine...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (updatesReadyCount > 0 && !isRefreshingAll) {
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            ExpressiveShapeBadge(
                                polygon = MaterialShapes.Sunny,
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary,
                                size = 32.dp
                            ) {
                                Text(
                                    text = updatesReadyCount.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "$updatesReadyCount update(s) ready",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        ExpressiveButton(
                            onClick = onRefreshAll,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Update All")
                        }
                    }
                }
            }
        }

        if (availableCategories.size > 2) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    listOf("All", "Updates").forEach { quickStatus ->
                        val count = if (quickStatus == "All") apps.size else updatesReadyCount
                        FilterChip(
                            selected = statusFilter == quickStatus && categoryFilter == "All",
                            onClick = {
                                statusFilter = quickStatus
                                categoryFilter = "All"
                            },
                            shape = CircleShape,
                            label = { Text("$quickStatus ($count)") }
                        )
                    }
                    availableCategories.drop(1).forEach { cat ->
                        val catColor = categoryAccentColor(cat, MaterialTheme.colorScheme.primary)
                        FilterChip(
                            selected = categoryFilter == cat,
                            onClick = {
                                categoryFilter = if (categoryFilter == cat) "All" else cat
                                statusFilter = "All"
                            },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(MaterialShapes.Cookie4Sided.toShape())
                                        .background(catColor)
                                )
                            },
                            shape = CircleShape,
                            label = { Text(cat) }
                        )
                    }
                }
            }
        }

        if (filteredAndSorted.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ExpressiveShapeBadge(
                            polygon = MaterialShapes.Flower,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            size = 72.dp
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AddCircle,
                                contentDescription = null,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = if (apps.isEmpty()) "No apps tracked yet" else "No apps match active filters",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Track GitHub, GitLab, Codeberg, F-Droid, or Direct APKs and let Obtainmond auto-select your device ABI.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        ExpressiveButton(onClick = onJumpToAdd) {
                            Icon(Icons.Rounded.AddCircle, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add App")
                        }
                    }
                }
            }
        } else if (settings.groupByCategory) {
            groupedApps.forEach { (categoryName, catApps) ->
                val isCollapsed = categoryName in collapsedCategories
                val catColor = categoryAccentColor(
                    if (categoryName == "Uncategorized") "" else categoryName,
                    outlineFallback
                )
                item(key = "header_$categoryName") {
                    CategorySectionHeader(
                        title = categoryName,
                        count = catApps.size,
                        accentColor = catColor,
                        collapsed = isCollapsed,
                        onToggle = {
                            collapsedCategories = if (isCollapsed) {
                                collapsedCategories - categoryName
                            } else {
                                collapsedCategories + categoryName
                            }
                        }
                    )
                }
                if (!isCollapsed) {
                    itemsIndexed(catApps, key = { _, app -> "${categoryName}_${app.id}_${app.url}" }) { index, app ->
                        ExpressiveStaggeredEntrance(index = index) {
                            ObtainiumAppRow(
                                app = app,
                                isBusy = app.id in busyIds,
                                isSelected = app.id in selectedAppIds,
                                selectionActive = selectedAppIds.isNotEmpty(),
                                onClick = {
                                    if (selectedAppIds.isNotEmpty()) {
                                        onToggleSelectApp(app.id)
                                    } else {
                                        onOpenAppDetail(app.id)
                                    }
                                },
                                onLongClick = { onToggleSelectApp(app.id) },
                                onQuickAction = {
                                    scope.launch {
                                        if (app.matchedApkUrl.isBlank()) {
                                            val refreshed = AppRepository.checkSingleApp(app.id).getOrNull()
                                            if (refreshed != null && refreshed.matchedApkUrl.isNotBlank()) {
                                                AppRepository.downloadAndInstallAppSilently(refreshed.id, refreshed.matchedApkUrl)
                                            }
                                        } else {
                                            AppRepository.downloadAndInstallAppSilently(app.id)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        } else {
            itemsIndexed(filteredAndSorted, key = { _, app -> app.id + app.url }) { index, app ->
                ExpressiveStaggeredEntrance(index = index) {
                    ObtainiumAppRow(
                        app = app,
                        isBusy = app.id in busyIds,
                        isSelected = app.id in selectedAppIds,
                        selectionActive = selectedAppIds.isNotEmpty(),
                        onClick = {
                            if (selectedAppIds.isNotEmpty()) {
                                onToggleSelectApp(app.id)
                            } else {
                                onOpenAppDetail(app.id)
                            }
                        },
                        onLongClick = { onToggleSelectApp(app.id) },
                        onQuickAction = {
                            scope.launch {
                                if (app.matchedApkUrl.isBlank()) {
                                    val refreshed = AppRepository.checkSingleApp(app.id).getOrNull()
                                    if (refreshed != null && refreshed.matchedApkUrl.isNotBlank()) {
                                        AppRepository.downloadAndInstallAppSilently(refreshed.id, refreshed.matchedApkUrl)
                                    }
                                } else {
                                    AppRepository.downloadAndInstallAppSilently(app.id)
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if (showFilterSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Filter & Sort",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold
                    )
                    TextButton(
                        onClick = {
                            statusFilter = "All"
                            sourceFilter = "All"
                            categoryFilter = "All"
                            AppRepository.updateSettings { it.copy(appSortMode = 0, groupByCategory = false) }
                        }
                    ) {
                        Text("Reset")
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Sort Order",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            0 to "Name",
                            1 to "Updates First",
                            2 to "Last Updated",
                            3 to "Source"
                        ).forEach { (mode, label) ->
                            TonalToggleButton(
                                checked = settings.appSortMode == mode,
                                onCheckedChange = {
                                    AppRepository.updateSettings { it.copy(appSortMode = mode) }
                                },
                                shapes = ToggleButtonDefaults.shapes()
                            ) {
                                Text(label)
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Status",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("All", "Updates", "Installed", "Not Installed", "Frozen").forEach { st ->
                            FilterChip(
                                selected = statusFilter == st,
                                onClick = { statusFilter = st },
                                shape = CircleShape,
                                label = { Text(st) }
                            )
                        }
                    }
                }

                if (availableSources.size > 2) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Source",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            availableSources.forEach { src ->
                                FilterChip(
                                    selected = sourceFilter == src,
                                    onClick = { sourceFilter = src },
                                    shape = CircleShape,
                                    label = { Text(src) }
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Group by Category", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Expandable Obtainium category sections", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settings.groupByCategory,
                        onCheckedChange = { enabled ->
                            AppRepository.updateSettings { it.copy(groupByCategory = enabled) }
                        }
                    )
                }
            }
        }
    }

    if (showPrivilegeSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showPrivilegeSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ExpressiveShapeBadge(
                        polygon = if (privStatus.hasPrivilegedShell) MaterialShapes.Cookie7Sided else MaterialShapes.Diamond,
                        containerColor = if (privStatus.hasPrivilegedShell) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                        contentColor = if (privStatus.hasPrivilegedShell) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                        size = 48.dp
                    ) {
                        Icon(
                            imageVector = if (privStatus.hasPrivilegedShell) Icons.Rounded.Bolt else Icons.Rounded.Security,
                            contentDescription = null
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = privStatus.activeBackendLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"} • Doze: ${if (privStatus.batteryOptimizationIgnored) "Exempt" else "Active"}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ExpressiveTonalButton(
                        onClick = {
                            PrivilegeManager.refreshStatus()
                            Toast.makeText(context, "Binder status refreshed", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Refresh")
                    }

                    if (!privStatus.hasPrivilegedShell) {
                        ExpressiveButton(
                            onClick = { PrivilegeManager.requestPrivilegePermission(context) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Grant")
                        }
                    } else if (!privStatus.batteryOptimizationIgnored) {
                        ExpressiveButton(
                            onClick = {
                                scope.launch {
                                    val res = PrivilegeManager.enforceZeroBatteryRestriction(context, settings)
                                    if (!res.isSuccess) {
                                        PrivilegeManager.requestBatteryExemptionDialogIfNeeded(context)
                                    } else {
                                        Toast.makeText(context, "Doze bypassed via ${res.backendUsed}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Bypass Doze")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CategorySectionHeader(
    title: String,
    count: Int,
    accentColor: androidx.compose.ui.graphics.Color,
    collapsed: Boolean,
    onToggle: () -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (collapsed) -90f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "catChevron"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(MaterialShapes.Cookie6Sided.toShape())
                .background(accentColor)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.weight(1f)
        )
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = null,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer { rotationZ = rotation }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ObtainiumAppRow(
    app: TrackedApp,
    isBusy: Boolean,
    isSelected: Boolean,
    selectionActive: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onQuickAction: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val categoryColor = categoryAccentColor(
        app.categories.firstOrNull().orEmpty(),
        if (app.hasUpdate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    )

    val containerColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
            app.hasUpdate -> MaterialTheme.colorScheme.surfaceContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "appRowBg"
    )

    val cornerRadius = if (isSelected) 28.dp else 20.dp

    Surface(
        shape = RoundedCornerShape(cornerRadius),
        color = containerColor,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(RoundedCornerShape(cornerRadius))
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .padding(vertical = 4.dp)
                    .clip(CircleShape)
                    .background(categoryColor)
            )
            Spacer(Modifier.width(12.dp))

            ExpressiveAppAvatar(
                packageId = app.id,
                appName = app.name,
                hasUpdate = app.hasUpdate,
                isSelected = isSelected,
                isFrozen = app.isFrozen,
                isBusy = isBusy,
                size = 50.dp
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (app.pinned) {
                        Icon(
                            imageVector = Icons.Rounded.PushPin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = app.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        shape = MaterialShapes.Slanted.toShape(),
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
                }

                Spacer(Modifier.height(3.dp))
                val instVer = app.installedVersion.ifBlank { "Not installed" }
                val remVer = app.latestVersion.ifBlank { "Unchecked" }
                val versionLine = if (app.latestVersion.isNotBlank() && app.installedVersion != app.latestVersion) {
                    "$instVer → $remVer"
                } else {
                    instVer
                }
                Text(
                    text = "$versionLine  •  ${app.matchedAbi.ifBlank { app.installedAbi.ifBlank { "auto" } }}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (app.hasUpdate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!selectionActive) {
                Spacer(Modifier.width(8.dp))
                when {
                    isBusy -> {
                        Text(
                            text = "Busy",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    app.trackOnly -> {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Text(
                                text = "Track",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                    app.hasUpdate || app.installedVersion.isBlank() -> {
                        ExpressiveButton(
                            onClick = onQuickAction,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (app.hasUpdate) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                                contentColor = if (app.hasUpdate) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            )
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (app.installedVersion.isBlank()) "Get" else "Update",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Up to date",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
