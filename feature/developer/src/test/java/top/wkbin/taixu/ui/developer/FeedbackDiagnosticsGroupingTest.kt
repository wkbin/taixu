package top.wkbin.taixu.ui.developer

import org.junit.Assert.assertEquals
import org.junit.Test
import top.wkbin.taixu.core.database.AgentMessageFeedbackEntity

class FeedbackDiagnosticsGroupingTest {

    @Test
    fun `groups keep first seen session order entry order and title fallback`() {
        val groups = groupFeedbackBySession(
            entries = listOf(
                feedback("m1", "s1", updatedAt = 300),
                feedback("m2", "s2", updatedAt = 200),
                feedback("m3", "s1", updatedAt = 100),
            ),
            sessionTitles = mapOf("s1" to "会话一"),
        )

        assertEquals(listOf("s1", "s2"), groups.map { it.sessionId })
        assertEquals(listOf("m1", "m3"), groups[0].entries.map { it.messageId })
        assertEquals("会话一", groups[0].sessionTitle)
        assertEquals("未知会话", groups[1].sessionTitle)
    }

    @Test
    fun `blank title falls back to unknown and empty input yields no groups`() {
        val groups = groupFeedbackBySession(
            entries = listOf(feedback("m1", "s1", updatedAt = 1)),
            sessionTitles = mapOf("s1" to "   "),
        )
        assertEquals("未知会话", groups.single().sessionTitle)
        assertEquals(emptyList<FeedbackSessionGroup>(), groupFeedbackBySession(emptyList(), emptyMap()))
    }
}

private fun feedback(messageId: String, sessionId: String, updatedAt: Long) = AgentMessageFeedbackEntity(
    id = AgentMessageFeedbackEntity.idOf(messageId),
    sessionId = sessionId,
    messageId = messageId,
    rating = AgentMessageFeedbackEntity.RATING_UP,
    createdAt = updatedAt,
    updatedAt = updatedAt,
)
