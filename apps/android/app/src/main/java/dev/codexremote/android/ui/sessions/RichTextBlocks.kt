package dev.codexremote.android.ui.sessions

sealed interface RichTextBlock {
    val id: String

    data class Paragraph(
        override val id: String,
        val text: String,
    ) : RichTextBlock

    data class CodeBlock(
        override val id: String,
        val language: String?,
        val code: String,
        val isOpenEnded: Boolean,
    ) : RichTextBlock

    data class ListBlock(
        override val id: String,
        val ordered: Boolean,
        val items: List<String>,
    ) : RichTextBlock

    data class MemoryCitation(
        override val id: String,
        val entries: List<MemoryCitationEntry>,
    ) : RichTextBlock
}

data class MemoryCitationEntry(
    val file: String,
    val lineStart: Int?,
    val lineEnd: Int?,
    val note: String?,
)

private val fencedCodeStartRegex = Regex("^```\\s*([\\w#+.-]+)?\\s*$")
private val fencedCodeEndRegex = Regex("^```\\s*$")
private val orderedListRegex = Regex("""^(\d+)\.\s+(.+)$""")
private val bulletListPrefixes = listOf("- ", "* ", "+ ")
private val memoryCitationRegex = Regex(
    """(?s)<oai-mem-citation>\s*<citation_entries>\s*(.*?)\s*</citation_entries>\s*<rollout_ids>.*?</rollout_ids>\s*</oai-mem-citation>"""
)
private val memoryCitationEntryRegex = Regex(
    """^(.+?)(?::(\d+)-(\d+))?\|note=\[(.*)]$"""
)

fun parseTextToBlocks(text: String?): List<RichTextBlock> {
    val normalizedSource = text
        ?.replace("\r\n", "\n")
        ?.replace('\r', '\n')
        ?.trimEnd()
        .orEmpty()

    if (normalizedSource.isBlank()) return emptyList()

    val memoryCitationBlock = parseMemoryCitationBlock(normalizedSource)
    val source = memoryCitationRegex.replace(normalizedSource, "").trimEnd()

    if (source.isBlank()) {
        return buildList {
            memoryCitationBlock?.let { add(it) }
        }
    }

    val lines = source.lineSequence().toList()
    val blocks = mutableListOf<RichTextBlock>()
    val paragraphLines = mutableListOf<String>()
    val listItems = mutableListOf<String>()
    var listOrdered = false
    var codeLanguage: String? = null
    val codeLines = mutableListOf<String>()
    var inCodeBlock = false

    fun blockId(prefix: String, index: Int, content: String): String {
        return "$prefix-$index"
    }

    fun flushParagraph() {
        val paragraphText = paragraphLines
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (paragraphText.isNotBlank()) {
            blocks += RichTextBlock.Paragraph(
                id = blockId("paragraph", blocks.size, paragraphText),
                text = paragraphText,
            )
        }
        paragraphLines.clear()
    }

    fun flushList() {
        if (listItems.isNotEmpty()) {
            val listText = listItems.joinToString("\n")
            blocks += RichTextBlock.ListBlock(
                id = blockId("list", blocks.size, listText),
                ordered = listOrdered,
                items = listItems.toList(),
            )
        }
        listItems.clear()
        listOrdered = false
    }

    fun flushCodeBlock(isOpenEnded: Boolean) {
        val codeText = codeLines.joinToString("\n").trimEnd()
        if (codeText.isNotBlank() || isOpenEnded) {
            blocks += RichTextBlock.CodeBlock(
                id = blockId("code", blocks.size, codeText),
                language = codeLanguage,
                code = codeText,
                isOpenEnded = isOpenEnded,
            )
        }
        codeLines.clear()
        codeLanguage = null
    }

    fun appendListItem(rawLine: String) {
        val trimmed = rawLine.trimStart()
        val orderedMatch = orderedListRegex.matchEntire(trimmed)
        if (orderedMatch != null) {
            val nextOrdered = true
            if (listItems.isNotEmpty() && !listOrdered) flushList()
            listOrdered = nextOrdered
            listItems += orderedMatch.groupValues[2].trim()
            return
        }

        val bulletPrefix = bulletListPrefixes.firstOrNull { trimmed.startsWith(it) }
        if (bulletPrefix != null) {
            if (listItems.isNotEmpty() && listOrdered) flushList()
            listOrdered = false
            listItems += trimmed.removePrefix(bulletPrefix).trim()
        }
    }

    for (line in lines) {
        val trimmedEnd = line.trimEnd()
        val trimmed = trimmedEnd.trim()

        if (inCodeBlock) {
            if (fencedCodeEndRegex.matches(trimmed)) {
                inCodeBlock = false
                flushCodeBlock(isOpenEnded = false)
            } else {
                codeLines += line
            }
            continue
        }

        if (trimmed.startsWith("```")) {
            flushParagraph()
            flushList()
            inCodeBlock = true
            codeLanguage = fencedCodeStartRegex.matchEntire(trimmed)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            continue
        }

        if (trimmed.isBlank()) {
            flushParagraph()
            flushList()
            continue
        }

        val isListLine = orderedListRegex.matches(trimmed) || bulletListPrefixes.any { trimmed.startsWith(it) }
        if (isListLine) {
            flushParagraph()
            appendListItem(trimmed)
            continue
        }

        if (listItems.isNotEmpty()) {
            flushList()
        }

        paragraphLines += trimmed
    }

    if (inCodeBlock) {
        flushCodeBlock(isOpenEnded = true)
    }

    flushParagraph()
    flushList()

    memoryCitationBlock?.let { blocks += it }

    return blocks
}

private fun parseMemoryCitationBlock(source: String): RichTextBlock.MemoryCitation? {
    val match = memoryCitationRegex.find(source) ?: return null
    val rawEntries = match.groupValues.getOrNull(1).orEmpty()
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()

    if (rawEntries.isEmpty()) return null

    val entries = rawEntries.map { rawEntry ->
        val parsed = memoryCitationEntryRegex.matchEntire(rawEntry)
        if (parsed == null) {
            MemoryCitationEntry(
                file = rawEntry,
                lineStart = null,
                lineEnd = null,
                note = null,
            )
        } else {
            MemoryCitationEntry(
                file = parsed.groupValues[1],
                lineStart = parsed.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull(),
                lineEnd = parsed.groupValues[3].takeIf { it.isNotBlank() }?.toIntOrNull(),
                note = parsed.groupValues[4].takeIf { it.isNotBlank() },
            )
        }
    }

    return RichTextBlock.MemoryCitation(
        id = "memory-citation-${entries.joinToString(separator = "|") { it.file }}",
        entries = entries,
    )
}
