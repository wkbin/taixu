package top.wkbin.taixu.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.ToolResult

/**
 * 「展开更多」分帧揭示状态机。
 *
 * 一次把 20+ 张工具卡插入列表会在**同一帧**内完成组合，长轮次展开必然掉帧；
 * 因此展开时按帧逐批放出隐藏段（每帧 [REVEAL_PER_FRAME] 条），使单帧组合量有上界。
 * 收起与短隐藏段仍立即生效，不做任何延迟。
 *
 * 独立成文件的原因：承载它的 ChatMessageList.kt 已触及 architecture-policy.json
 * 的文件尺寸棘轮上限（基线只许下调），故状态机与投影缓存入口一并外移。
 */
internal class RoundRevealState {
    var roundKey by mutableStateOf<String?>(null)
        private set
    var total by mutableStateOf(0)
        private set
    var limit by mutableStateOf(Int.MAX_VALUE)
        private set

    /** 仅当存在揭示中的轮次时才有值；交给投影函数决定放出多少隐藏项。 */
    val limits: Map<String, Int> get() = roundKey?.let { mapOf(it to limit) } ?: emptyMap()

    fun start(key: String, totalItems: Int) {
        total = totalItems
        limit = REVEAL_PER_FRAME
        roundKey = key
    }

    fun stop() {
        limit = Int.MAX_VALUE
        roundKey = null
    }

    /** 按帧推进，到达 total 后一次性放开并复位。 */
    suspend fun advanceByFrame() {
        var revealed = REVEAL_PER_FRAME
        while (revealed < total) {
            withFrameNanos { }
            revealed += REVEAL_PER_FRAME
            limit = revealed
        }
        stop()
    }

    /** 折叠条点击的统一入口：需要揭示则启动，否则立即生效（收起 / 隐藏段很短）。 */
    fun onToggled(item: ChatRenderItem.CollapseButtonItem, wasExpanded: Boolean, enabled: Boolean) {
        if (!wasExpanded && enabled && item.hiddenItemCount > REVEAL_PER_FRAME) {
            start(item.roundKey, item.hiddenItemCount)
        } else {
            stop()
        }
    }

    private companion object {
        const val REVEAL_PER_FRAME = 3
    }
}

/** 建立并驱动揭示状态机（`advanceByFrame` 在轮次切换时自动取消重启）。 */
@Composable
internal fun rememberRoundRevealState(): RoundRevealState {
    val state = remember { RoundRevealState() }
    LaunchedEffect(state.roundKey) {
        if (state.roundKey != null) state.advanceByFrame()
    }
    return state
}

/**
 * 投影 + 缓存键的统一入口。
 * 折叠关闭（默认）时投影不读取 toolResults，因此也不把它列为 remember 键，
 * 避免工具结果变化时白白让投影失效重建（与关闭折叠时的历史行为一致）。
 */
@Composable
internal fun rememberChatRenderItems(
    messages: List<HarnessMessage>,
    toolResults: Map<String, ToolResult>,
    expandedOverrides: Map<String, Boolean>,
    chatRoundCollapse: Boolean,
    revealLimits: Map<String, Int>,
): List<ChatRenderItem> {
    val projectionToolResults = if (chatRoundCollapse) toolResults else emptyMap()
    return remember(messages, expandedOverrides, chatRoundCollapse, projectionToolResults, revealLimits) {
        projectChatMessages(messages, projectionToolResults, expandedOverrides, chatRoundCollapse, revealLimits)
    }
}
