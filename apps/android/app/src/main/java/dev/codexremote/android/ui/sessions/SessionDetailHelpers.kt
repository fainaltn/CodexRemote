package dev.codexremote.android.ui.sessions

import dev.codexremote.android.data.model.SessionMessage
import dev.codexremote.android.data.model.RepoStatus
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ── Formatting helpers ────────────────────────────────────────────

private fun currentLocale(): Locale = Locale.getDefault()

internal fun localizedSessionText(
    zhHans: String,
    en: String,
    locale: Locale = currentLocale(),
): String = if (locale.language.startsWith("zh")) zhHans else en

internal val activeRunStatuses = setOf("pending", "running")
internal val terminalRunStatuses = setOf("completed", "failed", "stopped")

internal fun formatDate(dateStr: String): String {
    return runCatching {
        val locale = currentLocale()
        val formatter = if (locale.language.startsWith("zh")) {
            DateTimeFormatter.ofPattern("M月d日 HH:mm", locale)
        } else {
            DateTimeFormatter.ofPattern("MMM d HH:mm", locale)
        }
        Instant.parse(dateStr).atZone(ZoneId.systemDefault()).format(formatter)
    }.getOrElse { dateStr }
}

internal fun statusLabel(status: String): String = when (status) {
    "pending" -> localizedSessionText("等待中", "Pending")
    "running" -> localizedSessionText("运行中", "Running")
    "completed" -> localizedSessionText("已完成", "Completed")
    "failed" -> localizedSessionText("失败", "Failed")
    "stopped" -> localizedSessionText("已停止", "Stopped")
    else -> status
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

internal fun formatRunElapsed(
    startedAt: String,
    finishedAt: String? = null,
): String {
    return runCatching {
        val locale = currentLocale()
        val started = Instant.parse(startedAt)
        val ended = finishedAt?.let(Instant::parse) ?: Instant.now()
        val rawDuration = Duration.between(started, ended)
        val duration = if (rawDuration.isNegative) Duration.ZERO else rawDuration
        val minutes = duration.toMinutes()
        val seconds = duration.seconds % 60
        if (locale.language.startsWith("zh")) {
            when {
                minutes >= 60 -> "${minutes / 60}小时${minutes % 60}分"
                minutes > 0 -> "${minutes}分${seconds}秒"
                else -> "${seconds}秒"
            }
        } else {
            when {
                minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
                minutes > 0 -> "${minutes}m ${seconds}s"
                else -> "${seconds}s"
            }
        }
    }.getOrElse { localizedSessionText("刚刚", "Just now") }
}

internal fun detailProjectLabel(path: String?): String = when {
    path.isNullOrBlank() -> localizedSessionText("会话详情", "Session Detail")
    else -> java.io.File(path).name.ifBlank { path }
}

internal fun repoStatusVisible(repoStatus: RepoStatus?): Boolean {
    if (repoStatus == null) return false
    if (repoStatus.isRepo == false) return false
    return !repoStatus.branch.isNullOrBlank() ||
        !repoStatus.rootPath.isNullOrBlank() ||
        !repoStatus.cwd.isNullOrBlank() ||
        repoStatus.detached == true ||
        repoStatus.aheadBy != null ||
        repoStatus.behindBy != null ||
        repoStatus.dirtyCount != null ||
        repoStatus.untrackedCount != null
}

internal fun repoBranchLabel(repoStatus: RepoStatus?): String? =
    when {
        repoStatus == null -> null
        repoStatus.detached == true -> "Detached HEAD"
        else -> repoStatus.branch?.trim()?.takeIf { it.isNotBlank() }
    }

internal fun repoRootLabel(repoStatus: RepoStatus?): String? {
    val path = repoStatus?.rootPath?.trim()?.takeIf { it.isNotBlank() }
        ?: repoStatus?.cwd?.trim()?.takeIf { it.isNotBlank() }
        ?: return null
    return java.io.File(path).name.ifBlank { path }
}

internal fun repoDirtyLabel(repoStatus: RepoStatus?): String? {
    val status = repoStatus ?: return null
    val parts = mutableListOf<String>()
    val dirtyCount = status.dirtyCount ?: 0
    val stagedCount = status.stagedCount ?: 0
    val unstagedCount = status.unstagedCount ?: 0
    val untrackedCount = status.untrackedCount ?: 0
    if (stagedCount > 0) {
        parts += localizedSessionText("$stagedCount 处已暂存", "$stagedCount staged")
    }
    if (unstagedCount > 0) {
        parts += localizedSessionText("$unstagedCount 处未暂存", "$unstagedCount unstaged")
    }
    if (untrackedCount > 0) {
        parts += localizedSessionText("$untrackedCount 个未跟踪", "$untrackedCount untracked")
    }
    status.aheadBy?.takeIf { it > 0 }?.let {
        parts += localizedSessionText("领先 $it", "ahead $it")
    }
    status.behindBy?.takeIf { it > 0 }?.let {
        parts += localizedSessionText("落后 $it", "behind $it")
    }
    return when {
        parts.isNotEmpty() -> parts.joinToString(" · ")
        dirtyCount > 0 -> localizedSessionText("$dirtyCount 处已修改", "$dirtyCount modified")
        status.isRepo == true -> localizedSessionText("工作区干净", "Working tree clean")
        else -> null
    }
}

// ── Typewriter helpers ────────────────────────────────────────────

internal fun typewriterStepSize(remaining: Int): Int = when {
    remaining > 800 -> 4
    remaining > 300 -> 2
    else -> 1
}

internal fun typewriterDelayMs(nextChar: Char): Long = when (nextChar) {
    '。', '.', '!', '！', '?', '？' -> 260L
    '\n' -> 180L
    '，', ',', '、', ';', '；', ':' -> 100L
    ')', '）', '」', '"', '\'', '"' -> 60L
    else -> (28L..48L).random()
}

private const val liveTailAnimationWindowChars = 160

private fun commonPrefixLength(first: String, second: String): Int {
    val sharedLength = minOf(first.length, second.length)
    for (index in 0 until sharedLength) {
        if (first[index] != second[index]) return index
    }
    return sharedLength
}

internal fun calmTypewriterBaseline(
    targetText: String,
    currentDisplayedText: String,
): String {
    if (targetText.isEmpty()) return ""
    val preservedPrefixLength = commonPrefixLength(targetText, currentDisplayedText)
    val minimumSettledLength = (targetText.length - liveTailAnimationWindowChars).coerceAtLeast(0)
    val baselineLength = maxOf(preservedPrefixLength, minimumSettledLength)
        .coerceAtMost(targetText.length)
    return targetText.substring(0, baselineLength)
}

// ── Output cleaning ───────────────────────────────────────────────

private val ansiEscapeRegex = Regex("\\u001B\\[[;\\d]*[A-Za-z]")
private val ansiBracketRegex = Regex("\\[(?:\\d{1,3}(?:;\\d{1,3})*)m")
private val internalLogKeywords = listOf(
    "mcp::transport::worker",
    "codex_api::endpoint::responses_websocket",
    "hyper_util::client::legacy::Error",
    "worker quit with fatal",
    "Transport channel closed",
    "failed to connect to websocket",
    "tls handshake eof",
    "backend-api/wham/apps",
    "backend-api/codex/responses",
)

private fun stripAnsiArtifacts(text: String): String {
    return text
        .replace(ansiEscapeRegex, "")
        .replace(ansiBracketRegex, "")
}

internal fun sanitizePromptDisplay(prompt: String): String {
    val trimmed = prompt.trim()
    if (trimmed.startsWith("You have access to these uploaded session artifacts")) {
        val marker = "User request:"
        val idx = trimmed.indexOf(marker)
        if (idx != -1) {
            return trimmed.substring(idx + marker.length).trim()
        }
    }
    return trimmed
}

private fun sanitizeLiveOutputPayload(
    text: String,
    rawPrompt: String?,
): String {
    var sanitized = text.trim()
    val rawPromptTrimmed = rawPrompt?.trim().orEmpty()
    val displayPrompt = rawPrompt?.let(::sanitizePromptDisplay)

    if (sanitized.startsWith("You have access to these uploaded session artifacts")) {
        val marker = "User request:"
        val idx = sanitized.indexOf(marker)
        if (idx != -1) {
            sanitized = sanitized.substring(idx + marker.length).trim()
        }
    }

    if (rawPromptTrimmed.isNotBlank()) {
        sanitized = stripLeadingBlock(sanitized, rawPromptTrimmed)
    }

    if (!displayPrompt.isNullOrBlank()) {
        sanitized = stripLeadingBlock(sanitized, "User request: $displayPrompt")
        sanitized = stripLeadingBlock(sanitized, displayPrompt)
    }

    return sanitized
}

private fun stripLeadingBlock(text: String, block: String): String {
    if (block.isBlank()) return text
    val trimmedText = text.trim()
    val trimmedBlock = block.trim()
    if (trimmedText == trimmedBlock) return ""
    if (trimmedText.startsWith("$trimmedBlock\n")) {
        return trimmedText.removePrefix("$trimmedBlock\n").trim()
    }
    if (trimmedText.startsWith("$trimmedBlock\r\n")) {
        return trimmedText.removePrefix("$trimmedBlock\r\n").trim()
    }
    return trimmedText
}

private fun collapseRepeatedOutputBlocks(text: String): String {
    val normalized = text.trim()
    if (normalized.isBlank()) return normalized

    val paragraphs = normalized
        .split(Regex("\n{2,}"))
        .map { it.trim() }
        .filter { it.isNotBlank() }

    if (paragraphs.size >= 2) {
        val collapsed = mutableListOf<String>()
        for (paragraph in paragraphs) {
            if (collapsed.lastOrNull() == paragraph) continue
            collapsed += paragraph
        }
        return collapsed.joinToString("\n\n")
    }

    val lines = normalized.lines()
    if (lines.size % 2 == 0) {
        val half = lines.size / 2
        val firstHalf = lines.take(half).joinToString("\n").trim()
        val secondHalf = lines.drop(half).joinToString("\n").trim()
        if (firstHalf.isNotBlank() && firstHalf == secondHalf) {
            return firstHalf
        }
    }

    return normalized
}

internal fun cleanLiveOutput(
    output: String?,
    rawPrompt: String? = null,
): String? {
    if (output.isNullOrBlank()) return null
    val filtered = output
        .lineSequence()
        .map { stripAnsiArtifacts(it).trimEnd() }
        .filter { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@filter false
            if (trimmed.startsWith("2026-") && trimmed.contains(" WARN ")) return@filter false
            if (trimmed.startsWith("2026-") && trimmed.contains(" ERROR ")) return@filter false
            if (trimmed.startsWith("Reading prompt from stdin")) return@filter false
            if (trimmed.startsWith("OpenAI Codex")) return@filter false
            if (trimmed == "--------") return@filter false
            if (trimmed.startsWith("workdir: ")) return@filter false
            if (trimmed.startsWith("model: ")) return@filter false
            if (trimmed.startsWith("provider: ")) return@filter false
            if (trimmed.startsWith("approval: ")) return@filter false
            if (trimmed.startsWith("sandbox: ")) return@filter false
            if (trimmed.startsWith("reasoning effort: ")) return@filter false
            if (trimmed.startsWith("reasoning summaries: ")) return@filter false
            if (trimmed.startsWith("session id: ")) return@filter false
            if (trimmed == "mcp: figma starting") return@filter false
            if (trimmed == "mcp: playwright starting") return@filter false
            if (trimmed == "mcp: figma ready") return@filter false
            if (trimmed == "mcp: playwright ready") return@filter false
            if (trimmed.startsWith("mcp startup: ")) return@filter false
            if (trimmed == "user") return@filter false
            if (trimmed == "codex") return@filter false
            if (trimmed == "tokens used") return@filter false
            if (trimmed.matches(Regex("^\\d[\\d,]*$"))) return@filter false
            if (internalLogKeywords.any { keyword -> trimmed.contains(keyword, ignoreCase = true) }) {
                return@filter false
            }
            true
        }
        .toList()

    if (filtered.isEmpty()) return null
    val deduped = mutableListOf<String>()
    for (line in filtered) {
        val normalized = line.replace(Regex("\\s+"), " ").trim()
        val previous = deduped.lastOrNull()
        if (previous != null && previous.replace(Regex("\\s+"), " ").trim() == normalized) {
            continue
        }
        deduped += line
    }
    val sanitized = sanitizeLiveOutputPayload(
        text = deduped.joinToString("\n").trim(),
        rawPrompt = rawPrompt,
    )
    val withoutDiagnostics = sanitized
        .split(Regex("\n{2,}"))
        .map { it.trim() }
        .filterNot { paragraph ->
            internalLogKeywords.any { keyword ->
                paragraph.contains(keyword, ignoreCase = true)
            } || (paragraph.startsWith("2026-") && paragraph.contains("ERROR"))
        }
        .joinToString("\n\n")
        .trim()
    return collapseRepeatedOutputBlocks(withoutDiagnostics).ifBlank { null }
}

// ── Message & history grouping ────────────────────────────────────

private fun SessionMessage.normalizedDisplayText(): String? =
    text.trim().ifBlank { null }

private fun SessionMessage.normalizedPromptText(): String? =
    normalizedDisplayText()?.let(::sanitizePromptDisplay)

private fun SessionMessage.normalizedTurnId(): String? =
    turnId?.trim()?.ifBlank { null }

private fun orderedMessages(messages: List<SessionMessage>): List<SessionMessage> =
    messages.sortedWith(
        compareBy<SessionMessage>({ it.orderIndex }, { it.createdAt }, { it.id })
    )

private fun lastUserMessageForPrompt(
    messages: List<SessionMessage>,
    promptText: String?,
): SessionMessage? {
    val normalizedPrompt = promptText?.trim()?.ifBlank { null } ?: return null
    return messages.lastOrNull { message ->
        message.role == "user" && message.normalizedPromptText() == normalizedPrompt
    }
}

private fun currentTurnMessages(
    messages: List<SessionMessage>,
    currentUserText: String?,
): List<SessionMessage> {
    val ordered = orderedMessages(messages)
    val anchorUser = lastUserMessageForPrompt(ordered, currentUserText) ?: return emptyList()
    val anchorTurnId = anchorUser.normalizedTurnId()
    if (anchorTurnId != null) {
        return ordered.filter { it.normalizedTurnId() == anchorTurnId }
    }

    val anchorIndex = ordered.indexOfLast { it.id == anchorUser.id }
    if (anchorIndex == -1) return emptyList()
    return ordered.drop(anchorIndex)
}

private fun latestCanonicalAssistantMessage(messages: List<SessionMessage>): SessionMessage? {
    val ordered = orderedMessages(messages)
    val latestUser = ordered.lastOrNull { it.role == "user" } ?: return null
    val latestTurnId = latestUser.normalizedTurnId()
    return if (latestTurnId != null) {
        ordered.lastOrNull { message ->
            message.normalizedTurnId() == latestTurnId &&
                message.role == "assistant" &&
                message.kind == "message" &&
                !message.normalizedDisplayText().isNullOrBlank()
        }
    } else {
        val lastUserIndex = ordered.indexOfLast { it.role == "user" }
        if (lastUserIndex == -1) return null
        ordered
            .drop(lastUserIndex + 1)
            .lastOrNull { it.role == "assistant" && it.kind == "message" }
    }
}

internal fun latestCanonicalPrompt(messages: List<SessionMessage>): String? =
    orderedMessages(messages)
        .lastOrNull { it.role == "user" }
        ?.normalizedDisplayText()

internal fun latestCanonicalAssistantReply(messages: List<SessionMessage>): String? {
    return latestCanonicalAssistantMessage(messages)
        ?.normalizedDisplayText()
}

internal data class CurrentTurnProjection(
    val userPrompt: String?,
    val settledAssistantMessages: List<SessionMessage>,
    val collapsedAssistantMessages: List<SessionMessage>,
    val visibleSettledAssistantMessages: List<SessionMessage>,
    val streamingTailText: String?,
    val finalReplyFallbackText: String?,
    val showThinkingState: Boolean,
    val showWaitingPlaceholder: Boolean,
)

internal fun buildCurrentTurnProjection(
    messages: List<SessionMessage>,
    liveRun: dev.codexremote.android.data.model.Run?,
    pendingTurnPrompt: String?,
    cleanedOutput: String?,
    retainedLiveOutput: String?,
    isDraft: Boolean,
): CurrentTurnProjection {
    val isActive = liveRun?.status in activeRunStatuses
    val latestTimelineUserDisplay = latestCanonicalPrompt(messages)?.let(::sanitizePromptDisplay)
    val currentUserText = when {
        liveRun != null && isActive -> sanitizePromptDisplay(liveRun.prompt)
        liveRun != null -> pendingTurnPrompt?.trim()?.ifBlank { null }
            ?: latestTimelineUserDisplay
            ?: sanitizePromptDisplay(liveRun.prompt)
        !pendingTurnPrompt.isNullOrBlank() -> pendingTurnPrompt.trim()
        else -> latestTimelineUserDisplay
    }

    val currentRoundMessages = currentTurnMessages(messages, currentUserText)

    val settledAssistantMessages = currentRoundMessages
        .filter { it.role == "assistant" && it.kind == "message" }
        .filter { !it.normalizedDisplayText().isNullOrBlank() }

    val collapsedAssistantMessages = if (!isActive && settledAssistantMessages.size > 1) {
        settledAssistantMessages.dropLast(1)
    } else {
        emptyList()
    }
    val visibleSettledAssistantMessages = if (isActive) {
        settledAssistantMessages
    } else {
        settledAssistantMessages.takeLast(1)
    }

    val liveOutputCandidate = (cleanedOutput ?: retainedLiveOutput)
        ?.trim()
        ?.ifBlank { null }
    val latestSettledAssistantText = visibleSettledAssistantMessages.lastOrNull()?.normalizedDisplayText()
    val streamingTailText = if (
        isActive &&
        !liveOutputCandidate.isNullOrBlank() &&
        liveOutputCandidate != latestSettledAssistantText
    ) {
        liveOutputCandidate
    } else {
        null
    }

    val finalReplyFallbackText = if (
        !isActive &&
        visibleSettledAssistantMessages.isEmpty() &&
        !liveOutputCandidate.isNullOrBlank()
    ) {
        liveOutputCandidate
    } else {
        null
    }

    val showThinkingState = isActive &&
        streamingTailText.isNullOrBlank() &&
        (liveRun != null || !currentUserText.isNullOrBlank())

    val showWaitingPlaceholder = !showThinkingState &&
        visibleSettledAssistantMessages.isEmpty() &&
        streamingTailText.isNullOrBlank() &&
        finalReplyFallbackText.isNullOrBlank() &&
        (isDraft || liveRun != null || !currentUserText.isNullOrBlank())

    return CurrentTurnProjection(
        userPrompt = currentUserText,
        settledAssistantMessages = settledAssistantMessages,
        collapsedAssistantMessages = collapsedAssistantMessages,
        visibleSettledAssistantMessages = visibleSettledAssistantMessages,
        streamingTailText = streamingTailText,
        finalReplyFallbackText = finalReplyFallbackText,
        showThinkingState = showThinkingState,
        showWaitingPlaceholder = showWaitingPlaceholder,
    )
}

internal fun summarizeGroupTitle(text: String?): String {
    val raw = text.orEmpty().replace(Regex("\\s+"), " ").trim()
    if (raw.isBlank()) return "历史对话"
    return if (raw.length > 40) "${raw.take(40)}…" else raw
}

internal fun summarizeHistoryTitle(group: List<SessionMessage>): String {
    val firstUser = group.firstOrNull { it.role == "user" }
    val lastAssistant = group.asReversed().firstOrNull { it.role == "assistant" && it.kind == "message" }
    return summarizeGroupTitle(firstUser?.text ?: lastAssistant?.text)
}

/**
 * Represents one round of conversation in the history timeline.
 */
internal data class HistoryRound(
    val id: String,
    val messages: List<SessionMessage>,
    val primaryMessages: List<SessionMessage>,
    val foldedMessages: List<SessionMessage>,
    val preview: String,
    val title: String,
    val folded: Boolean,
    val isHistorical: Boolean,
)

internal enum class HistoryRoundTone {
    Reasoning,
    Folded,
    Completed,
    Active,
}

internal fun HistoryRound.isReasoningRound(): Boolean =
    title == "Codex 思考" || (messages.isNotEmpty() && messages.all { it.kind == "reasoning" })

internal fun HistoryRound.tone(): HistoryRoundTone = when {
    isReasoningRound() -> HistoryRoundTone.Reasoning
    foldedMessages.isNotEmpty() -> HistoryRoundTone.Folded
    isHistorical -> HistoryRoundTone.Completed
    else -> HistoryRoundTone.Active
}

internal fun HistoryRound.stateLabel(): String = when (tone()) {
    HistoryRoundTone.Reasoning -> "思考"
    HistoryRoundTone.Folded -> "中间步骤"
    HistoryRoundTone.Completed -> "已完成"
    HistoryRoundTone.Active -> "当前进行"
}

internal fun HistoryRound.messageCountLabel(): String = when (tone()) {
    HistoryRoundTone.Reasoning -> "${messages.count { it.kind == "reasoning" }} 条思考"
    HistoryRoundTone.Folded -> "${foldedMessages.size} 条折叠步骤"
    HistoryRoundTone.Completed, HistoryRoundTone.Active -> "${messages.size} 条消息"
}

internal fun HistoryRound.summaryMetaLabel(): String {
    val parts = mutableListOf<String>()
    parts += stateLabel()
    parts += messageCountLabel()
    messages.firstOrNull()?.createdAt?.let(::formatDate)?.let { parts += it }
    return parts.joinToString(" · ")
}

internal fun HistoryRound.previewLabel(maxChars: Int = 96): String {
    val previewText = preview.replace(Regex("\\s+"), " ").trim()
    if (previewText.length <= maxChars) return previewText
    return "${previewText.take(maxChars)}…"
}

internal fun buildHistoryRounds(messages: List<SessionMessage>): List<HistoryRound> {
    if (messages.isEmpty()) return emptyList()
    val ordered = orderedMessages(messages)
    if (ordered.none { it.normalizedTurnId() != null }) {
        return buildHistoryRoundsLegacy(ordered)
    }

    val groupedMessages = groupMessagesIntoTurns(ordered)
    val rounds = mutableListOf<HistoryRound>()
    groupedMessages.forEachIndexed { index, current ->
        val firstUser = current.firstOrNull { it.role == "user" }
        val assistantMessages = current.filter {
            it.role == "assistant" && it.kind == "message"
        }
        val previewSource = assistantMessages.lastOrNull()?.text
            ?: firstUser?.text
            ?: current.firstOrNull()?.text
            ?: ""
        val isHistorical = index < groupedMessages.lastIndex
        val isReasoningOnly = current.isNotEmpty() && current.all { it.kind == "reasoning" }
        val isLatestVisibleReply = !isReasoningOnly && !isHistorical
        var primaryMessages = current.toList()
        var foldedMessages = emptyList<SessionMessage>()

        if (!isReasoningOnly && assistantMessages.size > 1) {
            val finalAssistant = assistantMessages.last()
            primaryMessages = current.filter { message ->
                message.role != "assistant" || message.kind != "message" || message.id == finalAssistant.id
            }
            foldedMessages = current.filter { message ->
                message.role == "assistant" && message.kind == "message" && message.id != finalAssistant.id
            }
        }

        rounds += HistoryRound(
            id = current.first().id,
            messages = current.toList(),
            primaryMessages = primaryMessages,
            foldedMessages = foldedMessages,
            title = when {
                isReasoningOnly -> "Codex 思考"
                isLatestVisibleReply -> "当前对话"
                firstUser != null -> summarizeHistoryTitle(current)
                else -> "系统上下文"
            },
            folded = isReasoningOnly || isHistorical,
            isHistorical = isHistorical,
            preview = if (previewSource.length > 72) "${previewSource.take(72)}…" else previewSource,
        )
    }

    return rounds
}

private fun groupMessagesIntoTurns(messages: List<SessionMessage>): List<List<SessionMessage>> {
    val groups = mutableListOf<List<SessionMessage>>()
    var current = mutableListOf<SessionMessage>()
    var currentTurnId: String? = null
    var currentHasUser = false

    fun flush() {
        if (current.isEmpty()) return
        groups += current.toList()
        current = mutableListOf()
        currentTurnId = null
        currentHasUser = false
    }

    for (message in messages) {
        val messageTurnId = message.normalizedTurnId()
        if (current.isNotEmpty()) {
            val startsNewTurn = when {
                currentTurnId != null && messageTurnId != null -> messageTurnId != currentTurnId
                currentTurnId != null && messageTurnId == null -> message.role == "user"
                currentTurnId == null && messageTurnId != null -> false
                else -> message.role == "user" && currentHasUser
            }
            if (startsNewTurn) {
                flush()
            }
        }

        if (current.isEmpty()) {
            currentTurnId = messageTurnId
        } else if (currentTurnId == null && messageTurnId != null) {
            currentTurnId = messageTurnId
        }

        current += message
        currentHasUser = currentHasUser || message.role == "user"
    }
    flush()

    return groups
}

private fun buildHistoryRoundsLegacy(messages: List<SessionMessage>): List<HistoryRound> {
    val lastUserIndex = messages.indexOfLast { it.role == "user" }
    val rounds = mutableListOf<HistoryRound>()
    var current = mutableListOf<SessionMessage>()
    var currentKind = "round"

    fun flush() {
        if (current.isEmpty()) return
        val firstUser = current.firstOrNull { it.role == "user" }
        val assistantMessages = current.filter { it.role == "assistant" && it.kind == "message" }
        val previewSource = assistantMessages.lastOrNull()?.text
            ?: firstUser?.text
            ?: current.firstOrNull()?.text
            ?: ""
        val firstIndex = messages.indexOfFirst { it.id == current.first().id }
        val isHistorical = firstIndex in 0 until lastUserIndex
        val isLatestVisibleReply = currentKind != "reasoning" && !isHistorical
        var primaryMessages = current.toList()
        var foldedMessages = emptyList<SessionMessage>()

        if (currentKind != "reasoning" && assistantMessages.size > 1) {
            val finalAssistant = assistantMessages.last()
            primaryMessages = current.filter { message ->
                message.role != "assistant" || message.kind != "message" || message.id == finalAssistant.id
            }
            foldedMessages = current.filter { message ->
                message.role == "assistant" && message.kind == "message" && message.id != finalAssistant.id
            }
        }

        rounds += HistoryRound(
            id = current.first().id,
            messages = current.toList(),
            primaryMessages = primaryMessages,
            foldedMessages = foldedMessages,
            title = when {
                currentKind == "reasoning" -> "Codex 思考"
                isLatestVisibleReply -> "当前对话"
                firstUser != null -> summarizeHistoryTitle(current)
                else -> "系统上下文"
            },
            folded = currentKind == "reasoning" || isHistorical,
            isHistorical = isHistorical,
            preview = if (previewSource.length > 72) "${previewSource.take(72)}…" else previewSource,
        )
        current = mutableListOf()
    }

    for (message in messages) {
        val nextKind = if (message.kind == "reasoning") "reasoning" else "round"
        if (current.isNotEmpty() && (message.role == "user" || nextKind != currentKind)) {
            flush()
        }
        currentKind = nextKind
        current += message
    }
    flush()

    return rounds
}
