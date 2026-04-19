package app.findeck.mobile.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class RichTextBlocksTest {

    @Test
    fun `parseTextToBlocks parses markdown table into a table block`() {
        val blocks = parseTextToBlocks(
            """
            Intro summary

            | Name | Score |
            | --- | ---: |
            | Alice | 10 |
            | Bob | 12 |
            """.trimIndent(),
        )

        assertEquals(2, blocks.size)
        assertEquals("Intro summary", (blocks[0] as RichTextBlock.Paragraph).text)

        val table = blocks[1] as RichTextBlock.TableBlock
        assertEquals(listOf("Name", "Score"), table.headers)
        assertEquals(
            listOf(
                listOf("Alice", "10"),
                listOf("Bob", "12"),
            ),
            table.rows,
        )
    }

    @Test
    fun `parseTextToBlocks keeps malformed table-like text as paragraph`() {
        val blocks = parseTextToBlocks(
            """
            Name | Score
            Alice | 10
            """.trimIndent(),
        )

        assertEquals(1, blocks.size)
        assertEquals(
            "Name | Score Alice | 10",
            (blocks.single() as RichTextBlock.Paragraph).text,
        )
    }

    @Test
    fun `parseTextToBlocks unescapes table cell pipes`() {
        val blocks = parseTextToBlocks(
            """
            | Step | Status |
            | --- | --- |
            | build\|test | passed |
            """.trimIndent(),
        )

        val table = blocks.single() as RichTextBlock.TableBlock
        assertEquals(listOf("build|test", "passed"), table.rows.single())
    }

    @Test
    fun `parseTextToBlocks keeps multiple tables as separate table blocks`() {
        val blocks = parseTextToBlocks(
            """
            | Name | Score |
            | --- | --- |
            | Alice | 10 |

            | Rank | Choice |
            | --- | --- |
            | 1 | HeyAgent |
            """.trimIndent(),
        )

        assertEquals(2, blocks.size)
        assertEquals(listOf("Name", "Score"), (blocks[0] as RichTextBlock.TableBlock).headers)
        assertEquals(listOf("Rank", "Choice"), (blocks[1] as RichTextBlock.TableBlock).headers)
    }
}
