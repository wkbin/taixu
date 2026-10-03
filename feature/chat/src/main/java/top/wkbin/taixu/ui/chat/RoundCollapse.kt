package top.wkbin.taixu.ui.chat

import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.CapabilityEvent
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.UserMessage

/**
 * 聊天流投影渲染项：
 * 将扁平消息流投影为包含可见消息与折叠控件的渲染结构。
 */
sealed interface ChatRenderItem {
    val stableKey: String

    data class MessageItem(
        val message: HarnessMessage,
        /**
         * 该消息在**原始 messages 列表**中的下标。
         *
         * 由投影阶段一次性算出（O(n) 一次），供组合期内 O(1) 使用。
         * 此前组合期用 `messages.indexOfFirst { it.id == message.id }` 现算，
         * 每个可见项一次 O(n)，多张工具卡同时可见时为 O(n²)，滚动/流式时明显掉帧。
         */
        val rawIndex: Int = -1,
    ) : ChatRenderItem {
        override val stableKey: String get() = message.id
    }

    data class CollapseButtonItem(
        val roundKey: String,
        val hiddenSteps: Int,
        val totalSteps: Int,
        val hiddenDurationMs: Long,
        val isExpanded: Boolean,
        /** 展开态下「隐藏段」包含的渲染条目数；UI 依赖它做分帧揭示（收拢态恒为 0）。 */
        val hiddenItemCount: Int = 0,
    ) : ChatRenderItem {
        override val stableKey: String get() = "collapse_btn_$roundKey"
    }
}

/**
 * 轮次（Round）内部中间表示。仅用于开启自动折叠时的投影中间态。
 */
internal data class ChatRound(
    val roundKey: String,
    val userMessage: UserMessage?,
    val nonResultMessages: List<HarnessMessage>,
    val toolCalls: List<ToolCall>,
    val isLastRound: Boolean,
)

/**
 * 纯函数投影：将原始消息列表投影为 LazyColumn 的渲染项。
 *
 * **默认行为（[collapseEnabled] = false）与既有「自然单行流」完全一致**：
 * 所有思考过程与工具调用按原顺序逐条呈现，不产生任何折叠项、不隐藏任何消息。
 * 开启自动折叠后，历史轮次（非最后一轮）的中间过程会在步数 > 2 时收拢为一条可展开摘要，
 * 进行中的最后一轮始终摊开，保持流式思考实时可见。
 *
 * @param messages 原始 Harness 消息流
 * @param toolResults 工具执行结果映射表（用于提取 durationMs，仅在折叠开启时读取）
 * @param expandedOverrides 手动展开/收起记忆表（roundKey -> isExpanded），仅在折叠开启时生效
 * @param collapseEnabled 是否开启「历史轮次中间过程自动折叠」，默认 false
 */
fun projectChatMessages(
    messages: List<HarnessMessage>,
    toolResults: Map<String, ToolResult> = emptyMap(),
    expandedOverrides: Map<String, Boolean> = emptyMap(),
    collapseEnabled: Boolean = false,
    revealLimits: Map<String, Int> = emptyMap(),
): List<ChatRenderItem> {
    if (messages.isEmpty()) return emptyList()

    // 关闭自动折叠（默认）：过滤掉已被 ToolCard 内部独立消费渲染的 ToolResult，
    // 所有思考过程与工具调用按自然单行流呈现。
    // 同时把「原始下标」一次性算入渲染项：组合期据此做 O(1) 读取，
    // 免除在 Lazy 项内对 messages 反复 indexOfFirst 造成的 O(n²) 扫描。
    if (!collapseEnabled) {
        return messages
            .mapIndexedNotNull { index, message ->
                if (message is ToolResult) null else ChatRenderItem.MessageItem(message, rawIndex = index)
            }
    }

    // 开启自动折叠：一次性建立 id -> 原始下标映射，保证折叠态下 rawIndex 语义与默认态一致。
    val rawIndexOf = HashMap<String, Int>(messages.size * 2)
    messages.forEachIndexed { index, message -> rawIndexOf[message.id] = index }

    val rounds = splitIntoRounds(messages)
    val result = mutableListOf<ChatRenderItem>()

    for (round in rounds) {
        // 用户气泡优先放入，且不受折叠影响（轮次锚点必须始终可见）
        round.userMessage?.let { user ->
            result.add(ChatRenderItem.MessageItem(user, rawIndex = rawIndexOf[user.id] ?: -1))
        }

        val totalSteps = round.toolCalls.size

        // 折叠判定：
        // 1) 步数 ≤ 2 的轮次永不折叠（折叠反而增加噪音）；
        // 2) 手动状态覆盖优先（true -> 摊开，false -> 收拢）；
        // 3) 进行中的最后一轮永不折叠，保证流式思考实时可见；
        // 4) 其余历史轮次收拢。
        val manualOverride = expandedOverrides[round.roundKey]
        val shouldCollapse = when {
            totalSteps <= 2 -> false
            manualOverride != null -> !manualOverride
            round.isLastRound -> false
            else -> true
        }

        if (!shouldCollapse) {
            // 摊开态：若该轮本处于折叠态且被手动展开，在轮顶补一个「收起」按钮。
            // 隐藏段（= 折叠时会消失的那些条目）用于「分帧揭示」：一次性插入 N 张重卡
            // 会在同一帧内完成组合，长轮次必然掉帧，故按帧逐批放出（见 revealLimits）。
            val revealLimit = revealLimits[round.roundKey] ?: Int.MAX_VALUE
            val hiddenToolCallIdSet: Set<String> =
                if (totalSteps > 2) round.toolCalls.take(totalSteps - 2).map { it.id }.toSet() else emptySet()
            val hiddenItemIdSet: Set<String> =
                if (manualOverride == true && hiddenToolCallIdSet.isNotEmpty()) {
                    val stillVisible = filterVisibleMessagesByFollowRule(round.nonResultMessages, hiddenToolCallIdSet)
                        .mapTo(HashSet()) { it.id }
                    round.nonResultMessages
                        .filter { it !is UserMessage && it.id !in stillVisible }
                        .mapTo(HashSet()) { it.id }
                } else {
                    emptySet()
                }
            if (totalSteps > 2 && manualOverride == true) {
                result.add(
                    ChatRenderItem.CollapseButtonItem(
                        roundKey = round.roundKey,
                        hiddenSteps = 0,
                        totalSteps = totalSteps,
                        hiddenDurationMs = 0L,
                        isExpanded = true,
                        hiddenItemCount = hiddenItemIdSet.size,
                    ),
                )
            }
            var revealedItems = 0
            for (msg in round.nonResultMessages) {
                if (msg is UserMessage) continue
                if (msg.id in hiddenItemIdSet) {
                    if (revealedItems >= revealLimit) continue
                    revealedItems++
                }
                result.add(ChatRenderItem.MessageItem(msg, rawIndex = rawIndexOf[msg.id] ?: -1))
            }
        } else {
            // 收拢态：隐藏最旧的 (totalSteps - 2) 步，保留最新 2 步
            val hiddenCount = totalSteps - 2
            val hiddenToolCalls = round.toolCalls.take(hiddenCount)
            val hiddenToolCallIds = hiddenToolCalls.map { it.id }.toSet()

            // 累计被隐藏步骤的执行耗时
            val hiddenDurationMs = hiddenToolCalls.sumOf { toolResults[it.id]?.durationMs ?: 0L }

            result.add(
                ChatRenderItem.CollapseButtonItem(
                    roundKey = round.roundKey,
                    hiddenSteps = hiddenCount,
                    totalSteps = totalSteps,
                    hiddenDurationMs = hiddenDurationMs,
                    isExpanded = false,
                ),
            )

            val visibleMessages = filterVisibleMessagesByFollowRule(
                messages = round.nonResultMessages,
                hiddenToolCallIds = hiddenToolCallIds,
            )
            for (msg in visibleMessages) {
                if (msg !is UserMessage) {
                    result.add(ChatRenderItem.MessageItem(msg, rawIndex = rawIndexOf[msg.id] ?: -1))
                }
            }
        }
    }

    return result
}

/**
 * 将消息流切分为多个轮次（Round）。
 *
 * 以 [UserMessage] 为界切轮，轮次 key 取该用户消息 id；
 * 首个用户消息之前的消息（欢迎语、自愈事件等）归入伪轮 `__initial_round__`。
 */
private fun splitIntoRounds(messages: List<HarnessMessage>): List<ChatRound> {
    val nonResultMessages = messages.filter { it !is ToolResult }
    if (nonResultMessages.isEmpty()) return emptyList()

    val rounds = mutableListOf<ChatRound>()
    var currentRoundKey: String? = null
    var currentUserMessage: UserMessage? = null
    val currentMessages = mutableListOf<HarnessMessage>()

    // 检查是否有首个 UserMessage 之前的初始消息（如自愈/欢迎语）
    val firstUserIndex = nonResultMessages.indexOfFirst { it is UserMessage }

    for (i in nonResultMessages.indices) {
        val msg = nonResultMessages[i]
        if (msg is UserMessage) {
            // 结束上一轮
            if (currentRoundKey != null || currentMessages.isNotEmpty()) {
                val roundKey = currentRoundKey ?: "__initial_round__"
                rounds.add(
                    ChatRound(
                        roundKey = roundKey,
                        userMessage = currentUserMessage,
                        nonResultMessages = currentMessages.toList(),
                        toolCalls = currentMessages.filterIsInstance<ToolCall>(),
                        isLastRound = false,
                    ),
                )
                currentMessages.clear()
            }
            currentRoundKey = msg.id
            currentUserMessage = msg
            currentMessages.add(msg)
        } else {
            if (currentRoundKey == null && firstUserIndex != 0) {
                // 首个 UserMessage 之前
                currentRoundKey = "__initial_round__"
            }
            currentMessages.add(msg)
        }
    }

    // 处理末轮
    if (currentRoundKey != null || currentMessages.isNotEmpty()) {
        val roundKey = currentRoundKey ?: "__initial_round__"
        rounds.add(
            ChatRound(
                roundKey = roundKey,
                userMessage = currentUserMessage,
                nonResultMessages = currentMessages.toList(),
                toolCalls = currentMessages.filterIsInstance<ToolCall>(),
                isLastRound = true,
            ),
        )
    }

    return rounds
}

/**
 * 按照跟随规则过滤单轮内的可见消息：
 * 1. 隐藏属于 [hiddenToolCallIds] 的 ToolCall；
 * 2. 轮内首条正文（出现在任何 ToolCall 之前）永远显示；
 * 3. 轮内最终正文（最后一条 AssistantText）永远显示；
 * 4. 中间正文片段跟随其前方最近的 ToolCall：该 ToolCall 被隐藏则同藏，可见则可见；
 * 5. CapabilityEvent 等事件消息保持可见。
 */
private fun filterVisibleMessagesByFollowRule(
    messages: List<HarnessMessage>,
    hiddenToolCallIds: Set<String>,
): List<HarnessMessage> {
    val itemsWithoutUser = messages.filter { it !is UserMessage }
    if (itemsWithoutUser.isEmpty()) return emptyList()

    val firstToolCallIndex = itemsWithoutUser.indexOfFirst { it is ToolCall }
    val lastAssistantIndex = itemsWithoutUser.indexOfLast { it is AssistantText }

    val visibleList = mutableListOf<HarnessMessage>()
    var latestPrecedingToolCallIsHidden = false

    for (index in itemsWithoutUser.indices) {
        val msg = itemsWithoutUser[index]
        when (msg) {
            is ToolCall -> {
                val isHidden = msg.id in hiddenToolCallIds
                latestPrecedingToolCallIsHidden = isHidden
                if (!isHidden) {
                    visibleList.add(msg)
                }
            }
            is AssistantText -> {
                val isFirstTextBeforeTools = firstToolCallIndex == -1 || index < firstToolCallIndex
                val isFinalText = index == lastAssistantIndex

                if (isFirstTextBeforeTools || isFinalText) {
                    // 首正文与最终正文永远显示
                    visibleList.add(msg)
                } else if (!latestPrecedingToolCallIsHidden) {
                    // 中间正文片段跟随前方最近的工具卡
                    visibleList.add(msg)
                }
            }
            is CapabilityEvent -> {
                visibleList.add(msg)
            }
            else -> {
                visibleList.add(msg)
            }
        }
    }

    return visibleList
}
