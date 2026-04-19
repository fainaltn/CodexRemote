package app.findeck.mobile.ui.sessions

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

    data class TableBlock(
        override val id: String,
        val headers: List<String>,
        val rows: List<List<String>>,
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
private val markdownTableSeparatorCellRegex = Regex("^:?-{3,}:?$")
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

    fun appendTableBlock(parsed: ParsedMarkdownTable) {
        val signature = buildString {
            append(parsed.headers.joinToString("|"))
            if (parsed.rows.isNotEmpty()) {
                append("::")
                append(parsed.rows.joinToString("||") { row -> row.joinToString("|") })
            }
        }
        blocks += RichTextBlock.TableBlock(
            id = blockId("table", blocks.size, signature),
            headers = parsed.headers,
            rows = parsed.rows,
        )
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

    var lineIndex = 0
    while (lineIndex < lines.size) {
        val line = lines[lineIndex]
        val trimmedEnd = line.trimEnd()
        val trimmed = trimmedEnd.trim()

        if (inCodeBlock) {
            if (fencedCodeEndRegex.matches(trimmed)) {
                inCodeBlock = false
                flushCodeBlock(isOpenEnded = false)
            } else {
                codeLines += line
            }
            lineIndex += 1
            continue
        }

        if (trimmed.startsWith("```")) {
            flushParagraph()
            flushList()
            inCodeBlock = true
            codeLanguage = fencedCodeStartRegex.matchEntire(trimmed)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            lineIndex += 1
            continue
        }

        if (trimmed.isBlank()) {
            flushParagraph()
            flushList()
            lineIndex += 1
            continue
        }

        val parsedTable = parseMarkdownTable(lines, lineIndex)
        if (parsedTable != null) {
            flushParagraph()
            flushList()
            appendTableBlock(parsedTable)
            lineIndex += parsedTable.consumedLineCount
            continue
        }

        val isListLine = orderedListRegex.matches(trimmed) || bulletListPrefixes.any { trimmed.startsWith(it) }
        if (isListLine) {
            flushParagraph()
            appendListItem(trimmed)
            lineIndex += 1
            continue
        }

        if (listItems.isNotEmpty()) {
            flushList()
        }

        paragraphLines += trimmed
        lineIndex += 1
    }

    if (inCodeBlock) {
        flushCodeBlock(isOpenEnded = true)
    }

    flushParagraph()
    flushList()

    memoryCitationBlock?.let { blocks += it }

    return blocks
}

private data class ParsedMarkdownTable(
    val headers: List<String>,
    val rows: List<List<String>>,
    val consumedLineCount: Int,
)

private fun parseMarkdownTable(
    lines: List<String>,
    startIndex: Int,
): ParsedMarkdownTable? {
    if (startIndex + 1 >= lines.size) return null

    val headerLine = lines[startIndex].trim()
    val separatorLine = lines[startIndex + 1].trim()
    val headers = parseMarkdownTableRow(headerLine)
    val separatorCells = parseMarkdownTableRow(separatorLine)

    if (headers == null || separatorCells == null) return null
    if (headers.size < 2 || separatorCells.size != headers.size) return null
    if (!separatorCells.all { markdownTableSeparatorCellRegex.matches(it) }) return null

    val rows = mutableListOf<List<String>>()
    var consumed = 2
    var index = startIndex + 2

    while (index < lines.size) {
        val rowLine = lines[index].trim()
        if (rowLine.isBlank()) break
        val parsedRow = parseMarkdownTableRow(rowLine) ?: break
        if (parsedRow.size != headers.size) break
        rows += parsedRow
        consumed += 1
        index += 1
    }

    return ParsedMarkdownTable(
        headers = headers,
        rows = rows,
        consumedLineCount = consumed,
    )
}

private fun parseMarkdownTableRow(line: String): List<String>? {
    val trimmed = line.trim()
    if ('|' !in trimmed) return null

    val content = trimmed
        .removePrefix("|")
        .removeSuffix("|")

    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var escaping = false

    for (char in content) {
        when {
            escaping -> {
                current.append(char)
                escaping = false
            }
            char == '\\' -> escaping = true
            char == '|' -> {
                cells += current.toString().trim()
                current.clear()
            }
            else -> current.append(char)
        }
    }

    if (escaping) {
        current.append('\\')
    }
    cells += current.toString().trim()

    return cells.takeIf { it.size >= 2 }
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
