package top.wkbin.taixu.ui.chat

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.CapabilityEvent
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.HarnessTool
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage

class RoundCollapseTest {

    private fun makeUser(id: String, text: String = "query") =
        UserMessage(id = id, createdAt = 1000L, text = text)

    private fun makeAssistant(id: String, text: String) =
        AssistantText(id = id, createdAt = 1000L, text = text)

    private fun makeToolCall(id: String, tool: HarnessTool = HarnessTool.READ) =
        ToolCall(id = id, createdAt = 1000L, tool = tool, args = buildJsonObject {})

    private fun makeToolResult(toolCallId: String, durationMs: Long? = 1000L) =
        ToolResult(
            id = "res_$toolCallId",
            createdAt = 1000L,
            toolCallId = toolCallId,
            success = true,
            output = "ok",
            durationMs = durationMs,
        )

    private fun makeCapability(id: String) =
        CapabilityEvent(
            id = id,
            createdAt = 1000L,
            kind = CapabilityEvent.Kind.SKILL,
            name = "taixu-custom-iteration",
        )

    private fun messageIds(items: List<ChatRenderItem>): List<String> =
        items.filterIsInstance<ChatRenderItem.MessageItem>().map { it.message.id }

    private fun buttons(items: List<ChatRenderItem>): List<ChatRenderItem.CollapseButtonItem> =
        items.filterIsInstance<ChatRenderItem.CollapseButtonItem>()

    // ==================== 默认行为：关闭自动折叠（自然单行流） ====================

    @Test
    fun `empty messages returns empty list`() {
        val items = projectChatMessages(emptyList())
        assertTrue(items.isEmpty())
    }

    @Test
    fun `round with 2 or fewer steps is never collapsed and shows no button`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeAssistant("a1", "Done"),
            makeUser("u2"), // creates a new round so u1 becomes historical
            makeAssistant("a2", "Hello"),
        )
        val items = projectChatMessages(messages)
        // Check u1 round: should have u1, t1, t2, a1 (no CollapseButtonItem)
        val buttons = items.filterIsInstance<ChatRenderItem.CollapseButtonItem>()
        assertTrue("≤ 2 步的历史轮不应该有折叠按钮", buttons.isEmpty())

        assertEquals(listOf("u1", "t1", "t2", "a1", "u2", "a2"), messageIds(items))
    }

    @Test
    fun `tool results are excluded from direct render items`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1", 1200L),
            makeAssistant("a1", "Done"),
        )
        val items = projectChatMessages(messages)
        assertEquals(listOf("u1", "t1", "a1"), messageIds(items))
    }

    @Test
    fun `all assistant and user messages and tool calls are preserved naturally in order`() {
        val messages = listOf(
            makeAssistant("a_init", "Welcome"),
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeAssistant("a1", "Answer"),
            makeUser("u2"),
            makeAssistant("a2", "Next"),
        )
        val items = projectChatMessages(messages)
        assertEquals(listOf("a_init", "u1", "t1", "t2", "a1", "u2", "a2"), messageIds(items))
    }

    @Test
    fun `disabled collapse keeps flat stream even when a historical round has many steps`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeToolCall("t3"),
            makeToolResult("t3"),
            makeAssistant("a1", "Done"),
            makeUser("u2"),
            makeAssistant("a2", "Hello"),
        )
        // 显式传 false 与使用默认值必须完全等价
        val explicitOff = projectChatMessages(messages, emptyMap(), emptyMap(), collapseEnabled = false)
        val implicitOff = projectChatMessages(messages)
        assertTrue("关闭折叠时不得产生任何折叠按钮", buttons(explicitOff).isEmpty())
        assertEquals(messageIds(implicitOff), messageIds(explicitOff))
        assertEquals(listOf("u1", "t1", "t2", "t3", "a1", "u2", "a2"), messageIds(explicitOff))
    }

    // ==================== 开启自动折叠 ====================

    @Test
    fun `enabled collapse folds historical round keeping the newest two steps`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1", 1200L),
            makeToolCall("t2"),
            makeToolResult("t2", 300L),
            makeToolCall("t3"),
            makeToolResult("t3", 500L),
            makeAssistant("a1", "Done"),
            makeUser("u2"),
            makeAssistant("a2", "Hello"),
        )
        val toolResults = mapOf(
            "t1" to makeToolResult("t1", 1200L),
            "t2" to makeToolResult("t2", 300L),
            "t3" to makeToolResult("t3", 500L),
        )
        val items = projectChatMessages(messages, toolResults, emptyMap(), collapseEnabled = true)

        val foldButtons = buttons(items)
        assertEquals(1, foldButtons.size)
        assertEquals("u1", foldButtons[0].roundKey)
        assertEquals("仅隐藏最旧的 1 步", 1, foldButtons[0].hiddenSteps)
        assertEquals(3, foldButtons[0].totalSteps)
        assertEquals("隐藏步骤耗时应累加对应 ToolResult 的 durationMs", 1200L, foldButtons[0].hiddenDurationMs)
        assertFalse(foldButtons[0].isExpanded)

        // 用户气泡 + 折叠按钮 + 最新 2 步 + 最终正文；下一轮不受影响
        assertEquals(listOf("u1", "t2", "t3", "a1", "u2", "a2"), messageIds(items))
    }

    @Test
    fun `enabled collapse keeps never collapsing a round with two or fewer steps`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeAssistant("a1", "Done"),
            makeUser("u2"),
            makeAssistant("a2", "Hello"),
        )
        val items = projectChatMessages(messages, emptyMap(), emptyMap(), collapseEnabled = true)
        assertTrue(buttons(items).isEmpty())
        assertEquals(listOf("u1", "t1", "t2", "a1", "u2", "a2"), messageIds(items))
    }

    @Test
    fun `last round is never collapsed even when it has many steps`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeToolCall("t3"),
            makeToolResult("t3"),
            makeToolCall("t4"),
            makeToolResult("t4"),
            makeToolCall("t5"),
            makeToolResult("t5"),
            makeAssistant("a1", "Done"),
        )
        val items = projectChatMessages(messages, emptyMap(), emptyMap(), collapseEnabled = true)
        assertTrue("进行中的最后一轮必须始终摊开", buttons(items).isEmpty())
        assertEquals(listOf("u1", "t1", "t2", "t3", "t4", "t5", "a1"), messageIds(items))
    }

    @Test
    fun `manual expand override reveals every step and shows expanded button`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeToolCall("t3"),
            makeToolResult("t3"),
            makeAssistant("a1", "Done"),
            makeUser("u2"),
            makeAssistant("a2", "Hello"),
        )
        val items = projectChatMessages(
            messages,
            emptyMap(),
            mapOf("u1" to true),
            collapseEnabled = true,
        )
        val foldButtons = buttons(items)
        assertEquals(1, foldButtons.size)
        assertTrue(foldButtons[0].isExpanded)
        assertEquals("摊开态不隐藏任何步骤", 0, foldButtons[0].hiddenSteps)
        assertEquals(listOf("u1", "t1", "t2", "t3", "a1", "u2", "a2"), messageIds(items))
    }

    @Test
    fun `manual collapse override wins over the last-round rule`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeToolCall("t3"),
            makeToolResult("t3"),
            makeAssistant("a1", "Done"),
        )
        val items = projectChatMessages(
            messages,
            emptyMap(),
            mapOf("u1" to false),
            collapseEnabled = true,
        )
        val foldButtons = buttons(items)
        assertEquals(1, foldButtons.size)
        assertFalse("用户显式收拢的意图优先于末轮规则", foldButtons[0].isExpanded)
        assertEquals(listOf("u1", "t2", "t3", "a1"), messageIds(items))
    }

    @Test
    fun `middle assistant text follows its preceding hidden tool call`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeAssistant("a_mid", "Let me keep digging"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeToolCall("t3"),
            makeToolResult("t3"),
            makeAssistant("a_final", "Done"),
            makeUser("u2"),
            makeAssistant("a2", "Hello"),
        )
        val items = projectChatMessages(messages, emptyMap(), emptyMap(), collapseEnabled = true)
        // t1 被隐藏 → 紧随其后的中间正文 a_mid 一并隐藏；最终正文永远可见
        assertEquals(listOf("u1", "t2", "t3", "a_final", "u2", "a2"), messageIds(items))
    }

    @Test
    fun `leading assistant text before any tool call always stays visible`() {
        val messages = listOf(
            makeUser("u1"),
            makeAssistant("a_intro", "I will inspect the repo"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeToolCall("t3"),
            makeToolResult("t3"),
            makeAssistant("a_final", "Done"),
            makeUser("u2"),
            makeAssistant("a2", "Hello"),
        )
        val items = projectChatMessages(messages, emptyMap(), emptyMap(), collapseEnabled = true)
        assertEquals(
            listOf("u1", "a_intro", "t2", "t3", "a_final", "u2", "a2"),
            messageIds(items),
        )
    }

    @Test
    fun `capability event stays visible inside a collapsed round`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeCapability("cap1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeToolCall("t3"),
            makeToolResult("t3"),
            makeAssistant("a_final", "Done"),
            makeUser("u2"),
            makeAssistant("a2", "Hello"),
        )
        val items = projectChatMessages(messages, emptyMap(), emptyMap(), collapseEnabled = true)
        assertEquals(
            listOf("u1", "cap1", "t2", "t3", "a_final", "u2", "a2"),
            messageIds(items),
        )
    }

    @Test
    fun `rawIndex always points into the original message list in both modes`() {
        val messages = listOf(
            makeUser("u1"),
            makeToolCall("t1"),
            makeToolResult("t1"),
            makeToolCall("t2"),
            makeToolResult("t2"),
            makeToolCall("t3"),
            makeToolResult("t3"),
            makeAssistant("a1", "Done"),
            makeUser("u2"),
            makeAssistant("a2", "Hello"),
        )

        fun rawIndexById(items: List<ChatRenderItem>, id: String): Int =
            items.filterIsInstance<ChatRenderItem.MessageItem>()
                .first { it.message.id == id }
                .rawIndex

        val flat = projectChatMessages(messages)
        assertEquals(3, rawIndexById(flat, "t2"))
        assertEquals(7, rawIndexById(flat, "a1"))

        val folded = projectChatMessages(messages, emptyMap(), emptyMap(), collapseEnabled = true)
        assertEquals("折叠态下 rawIndex 语义必须与单行流一致", 3, rawIndexById(folded, "t2"))
        assertEquals(7, rawIndexById(folded, "a1"))
    }

    // ==================== 分帧揭示（revealLimits） ====================

    /** 3 步轮次里，被折叠时会消失的渲染条目 = 最旧 1 个工具卡 + 跟随它的中间正文 = 2 条。 */
    private fun revealFixtures(): List<HarnessMessage> = listOf(
        makeUser("u1"),
        makeToolCall("t1"),
        makeToolResult("t1"),
        makeAssistant("a_mid", "Let me keep digging"),
        makeToolCall("t2"),
        makeToolResult("t2"),
        makeToolCall("t3"),
        makeToolResult("t3"),
        makeAssistant("a_final", "Done"),
        makeUser("u2"),
        makeAssistant("a2", "Hello"),
    )

    @Test
    fun `revealLimits caps how many hidden items are released per frame`() {
        val items = projectChatMessages(
            revealFixtures(),
            emptyMap(),
            mapOf("u1" to true),
            collapseEnabled = true,
            revealLimits = mapOf("u1" to 1),
        )
        val button = buttons(items).single()
        assertTrue(button.isExpanded)
        assertEquals("隐藏段总条目数应上报给 UI 用于分帧步长", 2, button.hiddenItemCount)
        // 只放出第 1 条隐藏项（t1），紧随其后的中间正文 a_mid 仍被压住
        assertEquals(listOf("u1", "t1", "t2", "t3", "a_final", "u2", "a2"), messageIds(items))
    }

    @Test
    fun `revealLimits default releases every hidden item at once`() {
        val items = projectChatMessages(
            revealFixtures(),
            emptyMap(),
            mapOf("u1" to true),
            collapseEnabled = true,
        )
        val button = buttons(items).single()
        assertEquals(2, button.hiddenItemCount)
        assertEquals(
            listOf("u1", "t1", "a_mid", "t2", "t3", "a_final", "u2", "a2"),
            messageIds(items),
        )
    }

    @Test
    fun `collapsed round reports zero hidden item count`() {
        val items = projectChatMessages(revealFixtures(), emptyMap(), emptyMap(), collapseEnabled = true)
        val button = buttons(items).single()
        assertFalse(button.isExpanded)
        assertEquals("收拢态不参与分帧揭示，条目数恒为 0", 0, button.hiddenItemCount)
    }
}
