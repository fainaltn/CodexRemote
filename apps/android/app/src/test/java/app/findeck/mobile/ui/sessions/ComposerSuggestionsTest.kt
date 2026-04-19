package app.findeck.mobile.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

class ComposerSuggestionsTest {

    @Test
    fun `detect composer token context covers the whole slash token`() {
        val context = detectComposerTokenContext("please /archivx now", cursorPosition = 11)

        assertEquals('/', context?.prefix)
        assertEquals("archivx", context?.query)
        assertEquals(7, context?.replaceStart)
        assertEquals(15, context?.replaceEnd)
    }

    @Test
    fun `apply composer suggestion replaces the active token and keeps the tail`() {
        val context = detectComposerTokenContext("please /archivx now", cursorPosition = 11)
        val suggestion = ComposerSuggestion(
            id = "slash-archive",
            label = "/archive",
            insertText = "/archive ",
            kind = ComposerSuggestionKind.Command,
        )

        val nextText = applyComposerSuggestion("please /archivx now", context!!, suggestion)

        assertEquals("please /archive now", nextText)
    }

    @Test
    fun `detect composer token context tolerates surrounding punctuation`() {
        val text = "try (\$sourceweave) please"
        val context = detectComposerTokenContext(text, cursorPosition = 17)

        assertEquals('$', context?.prefix)
        assertEquals("sourceweave", context?.query)
        assertEquals(5, context?.replaceStart)
        assertEquals(17, context?.replaceEnd)
    }

    @Test
    fun `filter composer suggestions respects the current query`() {
        val context = ComposerTokenContext(
            prefix = '/',
            query = "ar",
            replaceStart = 0,
            replaceEnd = 3,
        )
        val suggestions = listOf(
            ComposerSuggestion(
                id = "slash-status",
                label = "/status",
                kind = ComposerSuggestionKind.Command,
            ),
            ComposerSuggestion(
                id = "slash-archive",
                label = "/archive",
                kind = ComposerSuggestionKind.Command,
            ),
        )

        val filtered = filterComposerSuggestions(suggestions, context)

        assertEquals(listOf("/archive"), filtered.map { it.label })
    }

    @Test
    fun `filter composer suggestions normalizes skill names for matching`() {
        val context = ComposerTokenContext(
            prefix = '$',
            query = "source weave",
            replaceStart = 0,
            replaceEnd = 13,
        )
        val suggestions = listOf(
            ComposerSuggestion(
                id = "repo-local:source-weave",
                label = "\$Source Weave",
                kind = ComposerSuggestionKind.Skill,
            ),
            ComposerSuggestion(
                id = "repo-local:archive",
                label = "\$Archive",
                kind = ComposerSuggestionKind.Skill,
            ),
        )

        val filtered = filterComposerSuggestions(suggestions, context)

        assertEquals(listOf("\$Source Weave"), filtered.map { it.label })
    }

    @Test
    fun `blank query keeps only a compact suggestion set`() {
        val context = ComposerTokenContext(
            prefix = '$',
            query = "",
            replaceStart = 0,
            replaceEnd = 1,
        )
        val suggestions = (1..12).map { index ->
            ComposerSuggestion(
                id = "skill-$index",
                label = "\$skill-$index",
                kind = ComposerSuggestionKind.Skill,
            )
        }

        val filtered = filterComposerSuggestions(suggestions, context)

        assertEquals(8, filtered.size)
        assertEquals("\$skill-1", filtered.first().label)
    }

    @Test
    fun `apply composer suggestion normalizes skill mentions before punctuation`() {
        val text = "try (\$sourceweave) please"
        val context = detectComposerTokenContext(text, cursorPosition = 17)
        val suggestion = ComposerSuggestion(
            id = "repo-local:Source Weave",
            label = "\$Source Weave",
            insertText = "\$Source Weave ",
            kind = ComposerSuggestionKind.Skill,
        )

        val nextText = applyComposerSuggestion(text, context!!, suggestion)

        assertEquals("try (\$source-weave) please", nextText)
    }

    @Test
    fun `replace composer selection uses normalized skill insertion when no context is active`() {
        val suggestion = ComposerSuggestion(
            id = "repo-local:Source Weave",
            label = "\$Source Weave",
            insertText = "\$Source Weave ",
            kind = ComposerSuggestionKind.Skill,
        )

        val nextValue = replaceComposerSelection(
            value = TextFieldValue(text = "", selection = TextRange(0)),
            suggestion = suggestion,
        )

        assertEquals("\$source-weave ", nextValue.text)
        assertEquals(nextValue.text.length, nextValue.selection.end)
    }

    @Test
    fun `detect composer token context ignores plain prose`() {
        assertNull(detectComposerTokenContext("hello world", cursorPosition = 5))
    }
}
