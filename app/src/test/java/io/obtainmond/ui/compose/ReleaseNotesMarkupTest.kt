package io.obtainmond.ui.compose

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesMarkupTest {
    @Test
    fun crlfImageBlockPreservesFollowingHeadingsAndRendersAnImage() {
        val input = """
            ## Before

            <p align="center">
            <img src="https://example.com/banner.png?size=2&amp;width=4" alt="Banner" />
            </p>

            ## After
            - A fix
        """.trimIndent().replace("\n", "\r\n")

        val blocks = releaseNotesBlocks(input)
        val trailingNotes = (blocks[2] as ReleaseNotesBlock.Markdown).content
        val nodes = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(trailingNotes).children

        assertTrue((blocks[0] as ReleaseNotesBlock.Markdown).content.contains("## Before"))
        assertEquals(ReleaseNotesBlock.Image("https://example.com/banner.png?size=2&width=4", "Banner"), blocks[1])
        assertEquals(1, nodes.count { it.type == MarkdownElementTypes.ATX_2 })
        assertTrue(nodes.any { it.type == MarkdownElementTypes.UNORDERED_LIST })
    }

    @Test
    fun singleLineVideoIsRecognizedAsInlineHtml() {
        val input = """<video src="https://example.com/clip.mp4"></video>"""
        assertEquals(listOf(ReleaseNotesBlock.Video("https://example.com/clip.mp4")), releaseNotesBlocks(input))
    }

    @Test
    fun detailsKeepTheirMarkdownAndTheTextAfterThem() {
        val input = """
            ## Changes

            <details>
            <summary>Translations &amp; fixes</summary>

            - [Polish](https://example.com/translation)

            </details>

            ## More
        """.trimIndent()

        val blocks = releaseNotesBlocks(input)
        val section = blocks[1] as ReleaseNotesBlock.Details

        assertEquals("Translations & fixes", section.summary)
        assertFalse(section.initiallyOpen)
        assertTrue((section.content.single() as ReleaseNotesBlock.Markdown).content.contains("[Polish]"))
        assertTrue((blocks[2] as ReleaseNotesBlock.Markdown).content.contains("## More"))
    }

    @Test
    fun videoSourceKeepsTheRestOfTheDocument() {
        val input = """
            <video controls>
              <source src='https://example.com/clip.mp4' type='video/mp4'>
            </video>
            ## Following heading
        """.trimIndent()

        val blocks = releaseNotesBlocks(input)
        assertEquals(ReleaseNotesBlock.Video("https://example.com/clip.mp4"), blocks.first())
        assertTrue((blocks.last() as ReleaseNotesBlock.Markdown).content.contains("## Following heading"))
    }

    @Test
    fun markupInsideFencedCodeStaysLiteral() {
        val input = """
            ```html
            <details><summary>Example</summary></details>
            <img src="https://example.com/sample.png">
            ```
        """.trimIndent()

        assertEquals(listOf(ReleaseNotesBlock.Markdown(input)), releaseNotesBlocks(input))
    }
}
