package io.obtainmond.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import io.obtainmond.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val releaseNotesImages = object : ImageTransformer by Coil3ImageTransformerImpl {
    private val placeholder = NoOpImageTransformerImpl()

    @Composable
    override fun transform(link: String): ImageData? {
        val url = link.removeSurrounding("<", ">").toHttpUrlOrNull()
        return if (url != null) {
            Coil3ImageTransformerImpl.transform(url.toString())
        } else {
            placeholder.transform(link)
        }
    }
}

@Composable
internal fun ReleaseNotes(content: String, modifier: Modifier = Modifier) {
    val blocks by produceState(emptyList<ReleaseNotesBlock>(), content) {
        value = withContext(Dispatchers.Default) { releaseNotesBlocks(content) }
    }
    ReleaseNotesContent(blocks = blocks, modifier = modifier)
}

@Composable
private fun ReleaseNotesContent(blocks: List<ReleaseNotesBlock>, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is ReleaseNotesBlock.Markdown -> Markdown(
                    content = block.content,
                    imageTransformer = releaseNotesImages,
                    modifier = Modifier.fillMaxWidth(),
                    error = { Text(block.content, modifier = it) }
                )
                is ReleaseNotesBlock.Details -> ReleaseNotesDetails(block)
                is ReleaseNotesBlock.Image -> AsyncImage(
                    model = block.url,
                    contentDescription = block.alt.ifBlank { null },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 320.dp)
                )
                is ReleaseNotesBlock.Video -> TextButton(
                    onClick = { runCatching { uriHandler.openUri(block.url) } }
                ) {
                    Text(stringResource(R.string.action_open_release_video))
                }
            }
        }
    }
}

@Composable
private fun ReleaseNotesDetails(block: ReleaseNotesBlock.Details) {
    var expanded by rememberSaveable(block.summary, block.initiallyOpen) { mutableStateOf(block.initiallyOpen) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "releaseNotesDetailsChevron")
    val stateLabel = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .semantics { stateDescription = stateLabel }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = block.summary.ifBlank { stringResource(R.string.label_release_notes_details) },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = null,
                modifier = Modifier.graphicsLayer { rotationZ = rotation }
            )
        }
        if (expanded) {
            ReleaseNotesContent(block.content, Modifier.padding(start = 12.dp))
        }
    }
}
