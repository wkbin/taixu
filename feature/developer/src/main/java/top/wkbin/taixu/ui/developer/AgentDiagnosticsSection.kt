package top.wkbin.taixu.ui.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.koin.compose.viewmodel.koinViewModel
import top.wkbin.taixu.core.database.AgentMessageFeedbackEntity
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.core.database.MessageFeedbackService
import top.wkbin.taixu.ui.components.IconTile
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.SectionHeader
import top.wkbin.taixu.ui.components.StatusBadge
import top.wkbin.taixu.ui.components.previewOrNull
import top.wkbin.taixu.ui.components.previewStateOf
import top.wkbin.taixu.ui.developer.LocalizedText as Text
import top.wkbin.taixu.ui.components.RuntimeSwitch as Switch
import top.wkbin.taixu.ui.components.RuntimeOutlinedButton as OutlinedButton
import top.wkbin.taixu.ui.components.RuntimeTextButton as TextButton

/**
 * 智能体诊断区：本地调试日志 + 消息反馈只读视图。
 *
 * 反馈在聊天页写入（[top.wkbin.taixu.core.database.MessageFeedbackService.set]），
 * 这里补上读取面，开发者可按会话核对用户评价，不再只写不读。
 */
@Composable
internal fun AgentDiagnosticsSection(
    agentLoggingEnabled: Boolean,
    agentLogSize: Long,
    agentLogLocation: String,
    onToggleLogging: (Boolean) -> Unit,
    onViewLogs: () -> Unit,
    onCopyLogs: () -> Unit,
    onClearLogs: () -> Unit,
    diagnostics: SessionDiagnosticsViewModel? = previewOrNull { koinViewModel() },
) {
    SectionHeader("智能体诊断", "智能体执行流、工具调用与错误详情本地持久化")
    RuntimeCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(RuntimeIconName.Shield)
            Column(Modifier.weight(1f)) {
                Text("智能体本地调试日志", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (agentLoggingEnabled) "已启用（文件大小：${agentLogSize.toReadableSize()}）" else "已关闭（不写入本地日志文件）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (agentLogLocation.isNotBlank()) {
                    Text(
                        "日志文件：$agentLogLocation",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = agentLoggingEnabled, onCheckedChange = onToggleLogging)
        }
        if (agentLoggingEnabled || agentLogSize > 0L) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onViewLogs, modifier = Modifier.weight(1f)) {
                    Text("查看日志")
                }
                OutlinedButton(onClick = onCopyLogs, modifier = Modifier.weight(1f)) {
                    Text("复制全部")
                }
                TextButton(onClick = onClearLogs) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    MessageFeedbackDiagnosticsCard(diagnostics)
}

@Composable
private fun MessageFeedbackDiagnosticsCard(diagnostics: SessionDiagnosticsViewModel?) {
    val groups by diagnostics?.groups?.collectAsStateWithLifecycle()
        ?: previewStateOf<List<FeedbackSessionGroup>>(emptyList())
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    RuntimeCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(RuntimeIconName.Chat)
            Column(Modifier.weight(1f)) {
                Text("消息反馈", style = MaterialTheme.typography.titleMedium)
                Text(
                    "用户对助手消息的评价，只读（最多 50 条）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusBadge("${groups.sumOf { it.entries.size }} 条反馈")
        }
        if (groups.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("暂无反馈", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@RuntimeCard
        }
        groups.forEach { group ->
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(10.dp))
            Text(
                group.sessionTitle,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            group.entries.forEach { entry ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val positive = entry.rating == AgentMessageFeedbackEntity.RATING_UP
                    StatusBadge(
                        if (positive) "有帮助" else "有问题",
                        if (positive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        formatter.format(Date(entry.updatedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                entry.note?.let { note ->
                    Spacer(Modifier.height(4.dp))
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** 一个会话下的反馈条目，保留查询返回的倒序。 */
internal data class FeedbackSessionGroup(
    val sessionId: String,
    val sessionTitle: String,
    val entries: List<AgentMessageFeedbackEntity>,
)

/**
 * 按会话聚合最近反馈：分组顺序取反馈首次出现顺序（即最近反馈所属会话在前）；
 * 会话已不存在时退回占位标题，不隐藏记录。
 */
internal fun groupFeedbackBySession(
    entries: List<AgentMessageFeedbackEntity>,
    sessionTitles: Map<String, String>,
): List<FeedbackSessionGroup> = entries.groupBy { it.sessionId }.map { (sessionId, items) ->
    FeedbackSessionGroup(
        sessionId = sessionId,
        sessionTitle = sessionTitles[sessionId]?.takeIf { it.isNotBlank() } ?: "未知会话",
        entries = items,
    )
}

/** 开发者诊断数据源：最近反馈 × 会话标题。 */
internal class SessionDiagnosticsViewModel(
    feedback: MessageFeedbackService,
    sessions: HarnessSessionRepository,
) : ViewModel() {

    val groups: StateFlow<List<FeedbackSessionGroup>> = combine(
        feedback.observeRecent(),
        sessions.observeAll(),
    ) { entries, allSessions ->
        groupFeedbackBySession(entries, allSessions.associate { it.id to it.title })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

private fun Long.toReadableSize(): String {
    if (this < 1024L) return "$this B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = this.toDouble()
    var index = -1
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index += 1 }
    return "%.1f %s".format(value, units[index])
}
