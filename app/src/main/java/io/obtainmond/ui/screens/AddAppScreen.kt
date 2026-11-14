package io.obtainmond.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.ToggleButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.obtainmond.data.model.ObtainmondSettings
import io.obtainmond.data.repo.AppRepository
import io.obtainmond.rust.ObtainmondNative
import io.obtainmond.ui.compose.ExpressiveButton
import io.obtainmond.ui.compose.ExpressiveShapeBadge
import io.obtainmond.ui.compose.ExpressiveTonalButton
import io.obtainmond.ui.compose.categoryAccentColor
import io.obtainmond.ui.compose.scrollMotionBlur
import kotlinx.coroutines.launch

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

private val SUPPORTED_SOURCES = listOf(
    "GitHub",
    "GitLab",
    "Codeberg",
    "F-Droid",
    "IzzyOnDroid",
    "SourceHut",
    "Direct APK",
    "HTML"
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AddAppScreen(
    initialSharedUrl: String,
    settings: ObtainmondSettings,
    bottomPadding: Dp,
    onAdded: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var urlInput by rememberSaveable(initialSharedUrl) { mutableStateOf(initialSharedUrl) }
    var customName by rememberSaveable { mutableStateOf("") }
    var customPkgId by rememberSaveable { mutableStateOf("") }
    var apkRegexFilter by rememberSaveable { mutableStateOf("") }
    var versionRegex by rememberSaveable { mutableStateOf("") }
    var categoryInput by rememberSaveable { mutableStateOf("") }
    var includePrereleases by rememberSaveable { mutableStateOf(false) }
    var silentAutoUpdate by rememberSaveable { mutableStateOf(true) }
    var trackOnly by rememberSaveable { mutableStateOf(false) }
    var installImmediately by rememberSaveable { mutableStateOf(true) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }

    val resolvedPreview = remember(urlInput) {
        if (urlInput.isBlank()) null else ObtainmondNative.resolveSource(urlInput)
    }

    val catalogShapes = remember {
        listOf(
            MaterialShapes.Cookie4Sided,
            MaterialShapes.Clover4Leaf,
            MaterialShapes.SoftBurst,
            MaterialShapes.Arch,
            MaterialShapes.Flower,
            MaterialShapes.Gem,
            MaterialShapes.Sunny
        )
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
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Add App",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    SUPPORTED_SOURCES.forEach { src ->
                        Surface(
                            shape = MaterialShapes.Pill.toShape(),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                text = src,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("App Source URL") },
                        placeholder = { Text("https://github.com/owner/repo") },
                        leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        val clipText = cm?.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
                                        if (clipText.isNotBlank()) {
                                            urlInput = clipText
                                        } else {
                                            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) {
                                    Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste URL")
                                }
                                AnimatedVisibility(
                                    visible = urlInput.isNotBlank(),
                                    enter = scaleIn() + fadeIn(),
                                    exit = scaleOut() + fadeOut()
                                ) {
                                    IconButton(onClick = { urlInput = "" }) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Clear")
                                    }
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                        ),
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
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ExpressiveShapeBadge(
                                        polygon = MaterialShapes.SoftBurst,
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                        size = 40.dp
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.AutoAwesome,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Detected: ${resolvedPreview.sourceType}",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Text(
                                            text = "${resolvedPreview.owner}/${resolvedPreview.repo}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Text(
                                            text = "ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TonalToggleButton(
                            checked = showAdvanced,
                            onCheckedChange = { showAdvanced = it },
                            shapes = ToggleButtonDefaults.shapes()
                        ) {
                            Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (showAdvanced) "Hide Source Options" else "Source Options")
                        }
                        Text(
                            text = if (silentAutoUpdate) "Silent Auto-Update" else "Manual",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AnimatedVisibility(
                        visible = showAdvanced,
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
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = customName,
                                    onValueChange = { customName = it },
                                    label = { Text("Custom Name") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = categoryInput,
                                    onValueChange = { categoryInput = it },
                                    label = { Text("Category") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = customPkgId,
                                    onValueChange = { customPkgId = it },
                                    label = { Text("Package ID Hint") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = apkRegexFilter,
                                    onValueChange = { apkRegexFilter = it },
                                    label = { Text("APK Regex") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            OutlinedTextField(
                                value = versionRegex,
                                onValueChange = { versionRegex = it },
                                label = { Text("Version Extraction Regex (Optional)") },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OptionToggleRow(
                                label = "Silent Background Auto-Update",
                                checked = silentAutoUpdate,
                                onCheckedChange = { silentAutoUpdate = it }
                            )
                            OptionToggleRow(
                                label = "Include Pre-releases / Nightly",
                                checked = includePrereleases,
                                onCheckedChange = { includePrereleases = it }
                            )
                            OptionToggleRow(
                                label = "Track Only (no APK download)",
                                checked = trackOnly,
                                onCheckedChange = { trackOnly = it }
                            )
                            OptionToggleRow(
                                label = "Install Immediately After Adding",
                                checked = installImmediately,
                                onCheckedChange = { installImmediately = it }
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    if (isSubmitting) {
                        LinearWavyProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    ExpressiveButton(
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
                                    trackOnly = trackOnly,
                                    apkFilterRegex = apkRegexFilter,
                                    versionExtractionRegex = versionRegex,
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
                                    customName = ""
                                    customPkgId = ""
                                    apkRegexFilter = ""
                                    versionRegex = ""
                                    categoryInput = ""
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
                            ContainedLoadingIndicator(
                                modifier = Modifier.size(22.dp),
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                indicatorColor = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Resolving & Selecting ABI APK...")
                        } else {
                            Icon(Icons.Rounded.RocketLaunch, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add & Sync Release")
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "Quick-Add Showcase",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }

        val pairs = QUICK_CATALOG.chunked(2)
        items(pairs.size) { rowIdx ->
            val rowItems = pairs[rowIdx]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowItems.forEachIndexed { colIdx, item ->
                    val globalIdx = rowIdx * 2 + colIdx
                    val poly = catalogShapes[globalIdx % catalogShapes.size]
                    val accent = categoryAccentColor(item.category, MaterialTheme.colorScheme.primary)
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .clickable {
                                urlInput = item.url
                                customName = item.name
                                customPkgId = item.pkgHint
                                categoryInput = item.category
                            }
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ExpressiveShapeBadge(
                                    polygon = poly,
                                    containerColor = accent.copy(alpha = 0.2f),
                                    contentColor = accent,
                                    size = 38.dp
                                ) {
                                    Text(
                                        text = item.name.take(2).uppercase(),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                                ) {
                                    Text(
                                        text = item.category,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Column {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.author,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            ExpressiveTonalButton(
                                enabled = !isSubmitting,
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
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Get", maxLines = 1)
                            }
                        }
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun OptionToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
