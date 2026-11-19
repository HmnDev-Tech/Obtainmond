package io.obtainmond.ui.compose

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.entities.Entities
import org.intellij.markdown.parser.MarkdownParser

internal sealed interface ReleaseNotesBlock {
    data class Markdown(val content: String) : ReleaseNotesBlock
    data class Details(
        val summary: String,
        val content: List<ReleaseNotesBlock>,
        val initiallyOpen: Boolean
    ) : ReleaseNotesBlock
    data class Image(val url: String, val alt: String) : ReleaseNotesBlock
    data class Video(val url: String) : ReleaseNotesBlock
}

private val htmlToken = Regex(
    """<!--[\s\S]*?(?:-->|$)|<summary\b[^>]*>([\s\S]*?)</summary\s*>|<(/?)([a-z][a-z0-9-]*)\b((?:"[^"]*"|'[^']*'|[^"'<>])*)>""",
    RegexOption.IGNORE_CASE
)
private val htmlAttribute = Regex(
    """([^\s=/]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>]+)))?"""
)
private val htmlTag = Regex("<[^>]*>")

private class DetailsSection(val initiallyOpen: Boolean) {
    var summary = ""
    val content = mutableListOf<ReleaseNotesBlock>()
}

internal fun releaseNotesBlocks(raw: String): List<ReleaseNotesBlock> {
    val text = raw.replace("\r\n", "\n").replace('\r', '\n')
    val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text)
    val blocks = mutableListOf<ReleaseNotesBlock>()
    val sections = ArrayDeque<DetailsSection>()
    val markdown = StringBuilder()
    var cursor = 0
    var insideVideo = false
    var videoHasSource = false

    fun append(block: ReleaseNotesBlock) {
        (sections.lastOrNull()?.content ?: blocks).add(block)
    }

    fun flushMarkdown() {
        val content = markdown.toString()
        if (content.isNotBlank()) append(ReleaseNotesBlock.Markdown(content))
        markdown.clear()
    }

    // The Markdown AST distinguishes raw HTML from code fences and escaped examples.
    for (node in htmlNodes(root)) {
        val html = text.substring(node.startOffset, node.endOffset)
        // Comments and raw-text elements may contain literal examples of the tags we support.
        if (html.trimStart().let { it.startsWith("<!--") || rawTextElement.containsMatchIn(it) }) continue
        for (match in htmlToken.findAll(html)) {
            val tokenStart = node.startOffset + match.range.first
            markdown.append(text, cursor, tokenStart)
            cursor = node.startOffset + match.range.last + 1
            val (_, summary, slash, tag, attributes) = match.groupValues
            val closing = slash.isNotEmpty()
            val attrs = htmlAttribute.findAll(attributes).associate { attr ->
                val (name, quoted, singleQuoted, unquoted) = attr.destructured
                name.lowercase() to decodeEntities(quoted.ifEmpty { singleQuoted.ifEmpty { unquoted } })
            }
            val src = attrs["src"]?.toHttpUrlOrNull()?.toString()

            when {
                match.groups[1] != null && sections.isNotEmpty() ->
                    sections.last().summary = decodeEntities(summary.replace(htmlTag, "")).trim()
                tag.equals("details", ignoreCase = true) && !closing -> {
                    flushMarkdown()
                    sections.addLast(DetailsSection("open" in attrs))
                }
                tag.equals("details", ignoreCase = true) && sections.isNotEmpty() -> {
                    flushMarkdown()
                    val section = sections.removeLast()
                    append(ReleaseNotesBlock.Details(section.summary, section.content, section.initiallyOpen))
                }
                tag.equals("img", ignoreCase = true) && src != null -> {
                    flushMarkdown()
                    append(ReleaseNotesBlock.Image(src, attrs["alt"].orEmpty()))
                }
                tag.equals("video", ignoreCase = true) || (insideVideo && tag.equals("source", ignoreCase = true)) -> {
                    if (tag.equals("video", ignoreCase = true)) {
                        insideVideo = !closing
                        videoHasSource = false
                    }
                    if (insideVideo && src != null && !videoHasSource) {
                        flushMarkdown()
                        append(ReleaseNotesBlock.Video(src))
                        videoHasSource = true
                    }
                }
                tag.lowercase() in setOf("p", "div", "br", "picture") -> markdown.append("\n\n")
                else -> markdown.append(match.value)
            }
        }
    }

    markdown.append(text, cursor, text.length)
    flushMarkdown()
    while (sections.isNotEmpty()) {
        // A missing closing tag should leave subsequent notes visible.
        val section = sections.removeLast()
        section.content.forEach(::append)
    }
    return blocks
}

private fun htmlNodes(root: ASTNode): Sequence<ASTNode> = sequence {
    val pending = ArrayDeque<ASTNode>()
    pending.addLast(root)
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        when (node.type) {
            MarkdownElementTypes.HTML_BLOCK, MarkdownTokenTypes.HTML_TAG -> yield(node)
            MarkdownElementTypes.CODE_BLOCK, MarkdownElementTypes.CODE_FENCE,
            MarkdownElementTypes.CODE_SPAN -> Unit
            else -> node.children.asReversed().forEach { pending.addLast(it) }
        }
    }
}

private val rawTextElement = Regex("""^<(script|pre|style|textarea)\b""", RegexOption.IGNORE_CASE)

private val htmlEntity = Regex("""&(?:([a-zA-Z][a-zA-Z0-9]*)|#([0-9]+)|#[xX]([a-fA-F0-9]+));""")

private fun decodeEntities(text: String): String = htmlEntity.replace(text) { match ->
    val codePoint = Entities.map[match.value]
        ?: match.groups[2]?.value?.toIntOrNull()
        ?: match.groups[3]?.value?.toIntOrNull(16)
    if (codePoint == null || codePoint !in 1..0x10FFFF || codePoint in 0xD800..0xDFFF) {
        match.value
    } else {
        String(Character.toChars(codePoint))
    }
}
