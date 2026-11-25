package io.obtainmond.ui.screens

import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.obtainmond.R
import io.obtainmond.data.model.ObtainmondSettings
import io.obtainmond.data.model.TrackedApp
import io.obtainmond.data.repo.AppRepository
import io.obtainmond.data.repo.BulkImportCandidate
import io.obtainmond.data.repo.DiscoveredDeviceApp
import io.obtainmond.privilege.PrivilegeManager
import io.obtainmond.privilege.PrivilegeStatus
import io.obtainmond.ui.compose.ExpressiveAppAvatar
import io.obtainmond.ui.compose.ExpressiveButton
import io.obtainmond.ui.compose.ExpressiveShapeBadge
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
    var folderFilter by rememberSaveable { mutableStateOf("All") }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showBulkImportSheet by remember { mutableStateOf(false) }
    var showPrivilegeSheet by remember { mutableStateOf(false) }
    var collapsedCategories by remember { mutableStateOf<Set<String>>(emptySet()) }

    val availableSources = remember(apps) {
        listOf("All") + apps.map { it.sourceType }.distinct().sorted()
    }
    val availableCategories = remember(apps) {
        listOf("All") + apps.flatMap { it.categories }.distinct().sorted()
    }
    val availableFolders = remember(apps) {
        listOf("All") + apps.flatMap { it.folders }.distinct().sorted()
    }

    val filteredAndSorted = remember(
        apps,
        searchQuery,
        statusFilter,
        sourceFilter,
        categoryFilter,
        folderFilter,
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
                "Needs attention" -> app.needsAttention
                "Installed" -> app.installedVersion.isNotBlank()
                "Not Installed" -> app.installedVersion.isBlank()
                "Frozen" -> app.isFrozen
                else -> true
            }
            val matchesSource = sourceFilter == "All" || app.sourceType == sourceFilter
            val matchesCategory = categoryFilter == "All" || categoryFilter in app.categories
            val matchesFolder = folderFilter == "All" || folderFilter in app.folders
            matchesQuery && matchesStatus && matchesSource && matchesCategory && matchesFolder
        }

        val comparator = when (settings.appSortMode) {
            1 -> compareByDescending<TrackedApp> { it.pinned }
                .thenByDescending { it.needsAttention }
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
                .groupBy { app ->
                    app.folders.firstOrNull()?.ifBlank { null }
                        ?: app.categories.firstOrNull()?.ifBlank { null }
                        ?: "Uncategorized"
                }
                .toSortedMap(compareBy<String> { it == "Uncategorized" }.thenBy { it.lowercase() })
        }
    }

    val updatesReadyCount = remember(apps) { apps.count { it.hasUpdate } }
    val needsAttentionCount = remember(apps) { apps.count { it.needsAttention } }
    val activeFilterCount = listOf(
        statusFilter != "All",
        sourceFilter != "All",
        categoryFilter != "All",
        folderFilter != "All"
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
            bottom = bottomPadding + 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp)
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
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.apps_cancel_selection))
                        }
                        Text(
                            text = stringResource(R.string.apps_selection_count, selectedAppIds.size),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onSelectAll(filteredAndSorted.map { it.id }) }
                        ) {
                            Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.apps_select_all))
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.tab_apps),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showBulkImportSheet = true }) {
                            Icon(Icons.Rounded.Upload, stringResource(R.string.action_bulk_import))
                        }
                        FilledTonalToggleButton(
                            checked = settings.groupByCategory,
                            onCheckedChange = { enabled ->
                                AppRepository.updateSettings { it.copy(groupByCategory = enabled) }
                            }
                        ) {
                            Icon(Icons.Rounded.FolderCopy, stringResource(R.string.apps_group_by_folder))
                        }
                        IconButton(onClick = { showFilterSheet = true }) {
                            val description = if (activeFilterCount > 0) {
                                stringResource(R.string.apps_active_filters, activeFilterCount)
                            } else {
                                stringResource(R.string.apps_filter_sort)
                            }
                            Icon(Icons.Rounded.FilterList, description)
                        }
                    }
                    Surface(
                        onClick = { showPrivilegeSheet = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val statusColor = if (privStatus.hasPrivilegedShell || privStatus.hasDhizuku) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Icon(
                                Icons.Rounded.Security,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = privStatus.activeBackendLabel,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.apps_search)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    AnimatedVisibility(
                        visible = searchQuery.isNotBlank(),
                        enter = scaleIn() + fadeIn(),
                        exit = scaleOut() + fadeOut()
                    ) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.apps_clear_search))
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
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
                        trackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.apps_checking),
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
                            Icon(
                                Icons.Rounded.Download,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = pluralStringResource(R.plurals.apps_updates_ready, updatesReadyCount, updatesReadyCount),
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
                            Text(stringResource(R.string.action_update_all))
                        }
                    }
                }
            }
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                listOf("All", "Updates").forEach { quickStatus ->
                    val count = if (quickStatus == "All") apps.size else updatesReadyCount
                    val label = if (quickStatus == "All") stringResource(R.string.filter_all) else stringResource(R.string.filter_updates)
                    FilterChip(
                        selected = statusFilter == quickStatus && categoryFilter == "All" && folderFilter == "All",
                        onClick = {
                            statusFilter = quickStatus
                            categoryFilter = "All"
                            folderFilter = "All"
                        },
                        shape = CircleShape,
                        label = { Text("$label ($count)") }
                    )
                }
                if (needsAttentionCount > 0 || statusFilter == "Needs attention") {
                    FilterChip(
                        selected = statusFilter == "Needs attention",
                        onClick = {
                            statusFilter = if (statusFilter == "Needs attention") "All" else "Needs attention"
                            categoryFilter = "All"
                            folderFilter = "All"
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        shape = CircleShape,
                        label = { Text("${stringResource(R.string.filter_attention)} ($needsAttentionCount)") }
                    )
                }
                availableFolders.drop(1).forEach { folder ->
                    FilterChip(
                        selected = folderFilter == folder,
                        onClick = {
                            folderFilter = if (folderFilter == folder) "All" else folder
                            statusFilter = "All"
                            categoryFilter = "All"
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                        },
                        shape = CircleShape,
                        label = { Text(folder) }
                    )
                }
                availableCategories.drop(1).forEach { cat ->
                    val catColor = categoryAccentColor(cat, MaterialTheme.colorScheme.primary)
                    FilterChip(
                        selected = categoryFilter == cat,
                        onClick = {
                            categoryFilter = if (categoryFilter == cat) "All" else cat
                            statusFilter = "All"
                            folderFilter = "All"
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(catColor)
                            )
                        },
                        shape = CircleShape,
                        label = { Text(cat) }
                    )
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
                            polygon = remember { MaterialShapes.Flower },
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
                        ObtainiumAppRow(
                            app = app,
                            topCorner = if (index == 0) 24.dp else 8.dp,
                            bottomCorner = if (index == catApps.lastIndex) 24.dp else 8.dp,
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
        } else {
            itemsIndexed(filteredAndSorted, key = { _, app -> app.id + app.url }) { _, app ->
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
                            FilledTonalToggleButton(
                                checked = settings.appSortMode == mode,
                                onCheckedChange = {
                                    AppRepository.updateSettings { it.copy(appSortMode = mode) }
                                }
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
                        listOf("All", "Updates", "Needs attention", "Installed", "Not Installed", "Frozen").forEach { st ->
                            FilterChip(
                                selected = statusFilter == st,
                                onClick = { statusFilter = st },
                                shape = CircleShape,
                                label = { Text(st) }
                            )
                        }
                    }
                }

                if (availableFolders.size > 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Folders",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            availableFolders.forEach { folder ->
                                FilterChip(
                                    selected = folderFilter == folder,
                                    onClick = { folderFilter = folder },
                                    shape = CircleShape,
                                    label = { Text(folder) }
                                )
                            }
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
                        Text("Group by Folder / Category", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Expandable folder & category sections", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    if (showBulkImportSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var discovered by remember { mutableStateOf<List<DiscoveredDeviceApp>>(emptyList()) }
        var scanning by remember { mutableStateOf(true) }
        var importing by remember { mutableStateOf(false) }
        var bulkSearch by remember { mutableStateOf("") }
        val checkedMap = remember { mutableStateMapOf<String, Boolean>() }
        val urlMap = remember { mutableStateMapOf<String, String>() }

        LaunchedEffect(showBulkImportSheet) {
            scanning = true
            val list = AppRepository.discoverInstallableDeviceApps()
            discovered = list
            list.forEach { item ->
                urlMap.putIfAbsent(item.packageId, item.suggestedUrl)
            }
            scanning = false
        }

        val visibleDiscovered = remember(discovered, bulkSearch) {
            val q = bulkSearch.trim()
            if (q.isBlank()) discovered else discovered.filter {
                it.appName.contains(q, ignoreCase = true) ||
                    it.packageId.contains(q, ignoreCase = true)
            }
        }
        val selectedCandidates = discovered.mapNotNull { item ->
            if (checkedMap[item.packageId] == true) {
                val url = urlMap[item.packageId].orEmpty().trim()
                if (url.isNotBlank()) BulkImportCandidate(item.packageId, item.appName, url) else null
            } else null
        }

        ModalBottomSheet(
            onDismissRequest = { if (!importing) showBulkImportSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.action_bulk_import),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "Select installed 3rd-party packages and adjust their source URLs before bulk tracking.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = bulkSearch,
                    onValueChange = { bulkSearch = it },
                    placeholder = { Text("Filter installed apps...") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth()
                )
                if (scanning) {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else if (visibleDiscovered.isEmpty()) {
                    Text(
                        text = "All installed 3rd-party apps are already tracked or none match.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(visibleDiscovered, key = { it.packageId }) { item ->
                            val isChecked = checkedMap[item.packageId] == true
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable { checkedMap[item.packageId] = !isChecked }
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { checkedMap[item.packageId] = it }
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(item.appName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                            Text(
                                                text = "${item.packageId} (${item.versionName.ifBlank { "?" }})",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    if (isChecked) {
                                        OutlinedTextField(
                                            value = urlMap[item.packageId] ?: item.suggestedUrl,
                                            onValueChange = { urlMap[item.packageId] = it },
                                            label = { Text("Source URL") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (importing) {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                ExpressiveButton(
                    onClick = {
                        importing = true
                        scope.launch {
                            val importedCount = AppRepository.bulkImportApps(selectedCandidates)
                            importing = false
                            showBulkImportSheet = false
                            Toast.makeText(
                                context,
                                "Imported $importedCount app(s)",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    enabled = !importing && selectedCandidates.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Import ${selectedCandidates.size} Selected App(s)")
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
                        polygon = remember(privStatus.hasPrivilegedShell) {
                            if (privStatus.hasPrivilegedShell) MaterialShapes.Cookie7Sided else MaterialShapes.Diamond
                        },
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
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 20.dp)
                .clip(CircleShape)
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
    topCorner: Dp = 20.dp,
    bottomCorner: Dp = 20.dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onQuickAction: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val categoryColor = categoryAccentColor(
        app.categories.firstOrNull().orEmpty(),
        if (app.hasUpdate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    )

    val colors = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        targetValue = when {
            isSelected -> colors.primaryContainer
            app.needsAttention -> colors.errorContainer
            app.hasUpdate -> colors.surfaceContainerHigh
            else -> colors.surfaceContainerLow
        },
        label = "appRowBg"
    )
    val contentColor = when {
        isSelected -> colors.onPrimaryContainer
        app.needsAttention -> colors.onErrorContainer
        else -> colors.onSurface
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val top by animateDpAsState(if (pressed) 28.dp else topCorner, spring(), label = "rowTop")
    val bottom by animateDpAsState(if (pressed) 28.dp else bottomCorner, spring(), label = "rowBottom")
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)

    Surface(
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = androidx.compose.foundation.LocalIndication.current,
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
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(width = 4.dp, height = 32.dp).clip(CircleShape).background(categoryColor))
            Spacer(Modifier.width(8.dp))
            ExpressiveAppAvatar(
                packageId = app.id,
                appName = app.name,
                hasUpdate = app.hasUpdate,
                isSelected = isSelected,
                isFrozen = app.isFrozen,
                isBusy = isBusy,
                size = 48.dp
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
                            contentDescription = stringResource(R.string.apps_pinned),
                            tint = contentColor,
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
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Text(
                            text = app.sourceType,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            maxLines = 1
                        )
                    }
                }

                Spacer(Modifier.height(3.dp))
                val instVer = app.installedVersion.ifBlank { stringResource(R.string.apps_not_installed) }
                val versionLine = when {
                    app.installedVersion.isBlank() && app.latestVersion.isNotBlank() ->
                        stringResource(R.string.apps_latest_version, app.latestVersion)
                    app.hasUpdate -> stringResource(R.string.apps_version_change, instVer, app.latestVersion)
                    else -> instVer
                }
                Text(
                    text = versionLine,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (app.hasUpdate && !isSelected && !app.needsAttention) colors.primary else contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (app.needsAttention) {
                    Text(
                        text = app.lastError.ifBlank { stringResource(R.string.label_needs_attention) },
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!selectionActive) {
                Spacer(Modifier.width(8.dp))
                when {
                    isBusy -> {
                        Text(
                            text = stringResource(R.string.apps_busy),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    app.needsAttention -> {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = stringResource(R.string.label_needs_attention),
                            tint = contentColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    app.trackOnly -> {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Text(
                                text = stringResource(R.string.apps_tracking),
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
                                text = stringResource(if (app.installedVersion.isBlank()) R.string.apps_install else R.string.apps_update),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = stringResource(R.string.apps_up_to_date),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
