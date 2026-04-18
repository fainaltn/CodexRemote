package dev.codexremote.android.ui.sessions

import dev.codexremote.android.data.model.SessionMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDetailHelpersTest {

    @Test
    fun `extract skill mentions keeps unique tokens in order`() {
        val mentions = extractSkillMentions(
            "先用 \$agent-reach 看最新信息，再配合 \$agent-reach 和 \$imagegen 出图",
        )

        assertEquals(listOf("\$agent-reach", "\$imagegen"), mentions)
    }

    @Test
    fun `augment prompt with skill mentions adds a stable preamble`() {
        val augmented = augmentPromptWithSkillMentions(
            "请用 \$agent-reach 查一下这个仓库最新 release",
        )

        assertTrue(
            augmented.startsWith(
                "Use the explicitly mentioned Codex skills for this request when relevant:",
            ),
        )
        assertTrue(augmented.contains("User request: 请用 \$agent-reach 查一下这个仓库最新 release"))
    }

    @Test
    fun `sanitize prompt display unwraps nested attachment and skill wrappers`() {
        val wrapped = """
            You have access to these uploaded session artifacts on the local filesystem.
            Use the exact absolute file paths below directly before answering.
            Do not search the workspace for alternate copies unless a listed path is missing.

            [Attachment 1] id=a report.txt (text/plain)
            Absolute path: /tmp/report.txt

            User request: Use the explicitly mentioned Codex skills for this request when relevant: ${'$'}agent-reach

            User request: 请查一下最新发布说明
        """.trimIndent()

        assertEquals("请查一下最新发布说明", sanitizePromptDisplay(wrapped))
    }

    @Test
    fun `latest canonical assistant reply prefers highest order item in latest turn`() {
        val messages = listOf(
            message(
                id = "u-old",
                role = "user",
                text = "old prompt",
                turnId = "turn-1",
                orderIndex = 0,
            ),
            message(
                id = "a-old",
                role = "assistant",
                text = "old answer",
                turnId = "turn-1",
                orderIndex = 1,
            ),
            message(
                id = "a-new-2",
                role = "assistant",
                text = "final answer",
                turnId = "turn-2",
                orderIndex = 4,
                itemId = "item-2",
            ),
            message(
                id = "u-new",
                role = "user",
                text = "new prompt",
                turnId = "turn-2",
                orderIndex = 2,
            ),
            message(
                id = "a-new-1",
                role = "assistant",
                text = "first answer",
                turnId = "turn-2",
                orderIndex = 3,
                itemId = "item-1",
            ),
        )

        assertEquals("new prompt", latestCanonicalPrompt(messages))
        assertEquals("final answer", latestCanonicalAssistantReply(messages))
    }

    @Test
    fun `current turn projection collapses earlier assistant replies from same turn by order`() {
        val messages = listOf(
            message(
                id = "a2",
                role = "assistant",
                text = "final answer",
                turnId = "turn-2",
                orderIndex = 4,
                itemId = "item-2",
            ),
            message(
                id = "u2",
                role = "user",
                text = "current prompt",
                turnId = "turn-2",
                orderIndex = 2,
            ),
            message(
                id = "a1",
                role = "assistant",
                text = "first answer",
                turnId = "turn-2",
                orderIndex = 3,
                itemId = "item-1",
            ),
        )

        val projection = buildCurrentTurnProjection(
            messages = messages,
            liveRun = null,
            pendingTurnPrompt = "current prompt",
            cleanedOutput = null,
            retainedLiveOutput = null,
            isDraft = false,
        )

        assertEquals(listOf("a1", "a2"), projection.settledAssistantMessages.map { it.id })
        assertEquals(listOf("a1"), projection.collapsedAssistantMessages.map { it.id })
        assertEquals(listOf("a2"), projection.visibleSettledAssistantMessages.map { it.id })
    }

    @Test
    fun `history rounds group by turn id and fold intermediate assistant replies`() {
        val messages = listOf(
            message(
                id = "u2",
                role = "user",
                text = "second prompt",
                turnId = "turn-2",
                orderIndex = 3,
            ),
            message(
                id = "a1b",
                role = "assistant",
                text = "first turn final",
                turnId = "turn-1",
                orderIndex = 2,
                itemId = "item-2",
            ),
            message(
                id = "a1a",
                role = "assistant",
                text = "first turn initial",
                turnId = "turn-1",
                orderIndex = 1,
                itemId = "item-1",
            ),
            message(
                id = "u1",
                role = "user",
                text = "first prompt",
                turnId = "turn-1",
                orderIndex = 0,
            ),
            message(
                id = "a2",
                role = "assistant",
                text = "second turn final",
                turnId = "turn-2",
                orderIndex = 4,
                itemId = "item-3",
            ),
        )

        val rounds = buildHistoryRounds(messages)

        assertEquals(2, rounds.size)
        assertTrue(rounds[0].isHistorical)
        assertEquals(listOf("u1", "a1b"), rounds[0].primaryMessages.map { it.id })
        assertEquals(listOf("a1a"), rounds[0].foldedMessages.map { it.id })
        assertEquals(false, rounds[1].isHistorical)
        assertEquals(listOf("u2", "a2"), rounds[1].primaryMessages.map { it.id })
    }

    @Test
    fun `history rounds fall back to legacy grouping when turn identity is missing`() {
        val messages = listOf(
            message(
                id = "u2",
                role = "user",
                text = "second prompt",
                orderIndex = 3,
            ),
            message(
                id = "a1",
                role = "assistant",
                text = "first answer",
                orderIndex = 1,
            ),
            message(
                id = "u1",
                role = "user",
                text = "first prompt",
                orderIndex = 0,
            ),
            message(
                id = "a2",
                role = "assistant",
                text = "second answer",
                orderIndex = 4,
            ),
        )

        val rounds = buildHistoryRounds(messages)

        assertEquals(2, rounds.size)
        assertEquals(listOf("u1", "a1"), rounds[0].messages.map { it.id })
        assertEquals(listOf("u2", "a2"), rounds[1].messages.map { it.id })
    }

    @Test
    fun `current turn projection stays stable when server returns messages out of order`() {
        val messages = listOf(
            message(
                id = "a-current-final",
                role = "assistant",
                text = "final current answer",
                turnId = "turn-2",
                orderIndex = 5,
                itemId = "item-3",
            ),
            message(
                id = "u-current",
                role = "user",
                text = "current prompt",
                turnId = "turn-2",
                orderIndex = 3,
            ),
            message(
                id = "a-previous",
                role = "assistant",
                text = "previous answer",
                turnId = "turn-1",
                orderIndex = 1,
                itemId = "item-1",
            ),
            message(
                id = "a-current-initial",
                role = "assistant",
                text = "initial current answer",
                turnId = "turn-2",
                orderIndex = 4,
                itemId = "item-2",
            ),
            message(
                id = "u-previous",
                role = "user",
                text = "previous prompt",
                turnId = "turn-1",
                orderIndex = 0,
            ),
        )

        val projection = buildCurrentTurnProjection(
            messages = messages,
            liveRun = null,
            pendingTurnPrompt = "current prompt",
            cleanedOutput = null,
            retainedLiveOutput = null,
            isDraft = false,
        )

        assertEquals("current prompt", projection.userPrompt)
        assertEquals(
            listOf("a-current-initial", "a-current-final"),
            projection.settledAssistantMessages.map { it.id }
        )
        assertEquals(listOf("a-current-initial"), projection.collapsedAssistantMessages.map { it.id })
        assertEquals(listOf("a-current-final"), projection.visibleSettledAssistantMessages.map { it.id })
    }

    @Test
    fun `current turn projection keeps same text assistant messages when item ids differ`() {
        val messages = listOf(
            message(
                id = "a-same-2",
                role = "assistant",
                text = "same text",
                turnId = "turn-3",
                orderIndex = 3,
                itemId = "item-2",
            ),
            message(
                id = "u-same",
                role = "user",
                text = "prompt with repeated replies",
                turnId = "turn-3",
                orderIndex = 1,
            ),
            message(
                id = "a-same-1",
                role = "assistant",
                text = "same text",
                turnId = "turn-3",
                orderIndex = 2,
                itemId = "item-1",
            ),
        )

        val projection = buildCurrentTurnProjection(
            messages = messages,
            liveRun = null,
            pendingTurnPrompt = "prompt with repeated replies",
            cleanedOutput = null,
            retainedLiveOutput = null,
            isDraft = false,
        )

        assertEquals(listOf("a-same-1", "a-same-2"), projection.settledAssistantMessages.map { it.id })
        assertEquals(listOf("a-same-1"), projection.collapsedAssistantMessages.map { it.id })
        assertEquals(listOf("a-same-2"), projection.visibleSettledAssistantMessages.map { it.id })
    }

    private fun message(
        id: String,
        role: String,
        text: String,
        turnId: String? = null,
        itemId: String? = null,
        orderIndex: Int,
        kind: String = "message",
    ): SessionMessage = SessionMessage(
        id = id,
        role = role,
        kind = kind,
        turnId = turnId,
        itemId = itemId,
        orderIndex = orderIndex,
        isStreaming = false,
        text = text,
        createdAt = "2026-04-15T12:${orderIndex.toString().padStart(2, '0')}:00Z",
    )
}
