package com.curvemaps.app.licences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reader's block model: markdown headings and reflowed hard-wrapped paragraphs. */
class LicenceBlocksTest {
    private val odbl = """
        ## Open Data Commons Open Database License (ODbL)

        ### Preamble

        The Open Database License (ODbL) is a license agreement intended to
        allow users to freely share, modify, and use this Database while
        maintaining this same freedom for others. Many databases are covered by
        copyright, and therefore this document licenses these rights.
    """.trimIndent()

    @Test fun markdownHeadingsLoseTheirMarkers() {
        val blocks = licenceBlocks(odbl)
        assertEquals(LicenceTextBlock.Heading(2, "Open Data Commons Open Database License (ODbL)"), blocks[0])
        assertEquals(LicenceTextBlock.Heading(3, "Preamble"), blocks[1])
        assertTrue(blocks.filterIsInstance<LicenceTextBlock.Body>().none { it.text.contains("#") })
    }

    @Test fun hardWrappedProseFlowsAsOneParagraph() {
        val body = licenceBlocks(odbl).filterIsInstance<LicenceTextBlock.Body>().single()
        assertFalse(body.text.contains('\n'))
        assertTrue(body.text.startsWith("The Open Database License (ODbL) is a license agreement intended to allow users"))
    }

    @Test fun listItemsIndentsAndShortLinesKeepTheirBreaks() {
        val text = "1. First clause that is long enough to be a full line of text here\n" +
            "2. Second clause\n  indented continuation\nSigned,\nThe Authors"
        val body = licenceBlocks(text).single() as LicenceTextBlock.Body
        assertEquals(
            "1. First clause that is long enough to be a full line of text here\n" +
                "2. Second clause\nindented continuation\nSigned,\nThe Authors",
            body.text,
        )
    }

    @Test fun noWordIsLostOrInvented() {
        val words = { s: String -> s.split(Regex("\\s+")).filter { it.isNotEmpty() && !it.startsWith("#") } }
        val out = licenceBlocks(odbl).joinToString(" ") {
            when (it) { is LicenceTextBlock.Heading -> it.text; is LicenceTextBlock.Body -> it.text }
        }
        assertEquals(words(odbl), words(out))
    }
}
