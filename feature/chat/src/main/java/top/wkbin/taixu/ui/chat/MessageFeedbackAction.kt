package top.wkbin.taixu.ui.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.wkbin.taixu.core.database.AgentMessageFeedbackEntity
import top.wkbin.taixu.core.database.MessageFeedbackService
import top.wkbin.taixu.feature.chat.R
import top.wkbin.taixu.harness.projection.CurrentSessionTracker
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName

/**
 * 单条 assistant 消息的反馈入口（DSH message-feedback 借鉴项的 App 内 UI）。
 *
 * 自包含组件：经 koinInject 直取 [MessageFeedbackService] 与 [CurrentSessionTracker]，
 * 不向气泡/列表/Screen 钻孔传参。气泡操作行放一个入口图标，点开对话框选
 * 👍/👎 + 可选备注，提交走 service.set（目标校验失败/服务不可用降级为 Toast 提示）。
 */
@Composable
internal fun MessageFeedbackAction(messageId: String) {
    val service = koinInject<MessageFeedbackService>()
    val tracker = koinInject<CurrentSessionTracker>()
    val context = LocalContext.current
    var showDialog by remember(messageId) { mutableStateOf(false) }
    var existing by remember(messageId) { mutableStateOf<AgentMessageFeedbackEntity?>(null) }
    var prefilled by remember(messageId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 每次打开都重读：用户可能在 Web 端或其他入口改过评价。
    // 首次打开等读取完成再显示对话框，避免先闪一下默认“有帮助”。
    LaunchedEffect(showDialog, messageId) {
        if (showDialog) {
            existing = runCatching { service.find(messageId) }.getOrNull()
            prefilled = true
        }
    }

    IconButton(
        onClick = { showDialog = true },
        modifier = Modifier.size(24.dp),
    ) {
        RuntimeIcon(
            RuntimeIconName.Edit,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showDialog && prefilled) {
        MessageFeedbackDialog(
            existing = existing,
            onDismiss = { showDialog = false },
            onSubmit = { rating, note ->
                val sessionId = tracker.currentSessionId.value
                if (sessionId.isBlank()) {
                    Toast.makeText(context, R.string.chat_message_feedback_unavailable, Toast.LENGTH_SHORT).show()
                    return@MessageFeedbackDialog
                }
                scope.launch {
                    runCatching { service.set(sessionId, messageId, rating, note) }
                        .onSuccess { saved ->
                            existing = saved
                            Toast.makeText(context, R.string.chat_message_feedback_saved, Toast.LENGTH_SHORT).show()
                        }
                        .onFailure { Toast.makeText(context, R.string.chat_message_feedback_failed, Toast.LENGTH_SHORT).show() }
                }
                showDialog = false
            },
        )
    }
}

/** 👍/👎 单选 + 可选备注；[existing] 非空时预填上次评价。 */
@Composable
private fun MessageFeedbackDialog(
    existing: AgentMessageFeedbackEntity?,
    onDismiss: () -> Unit,
    onSubmit: (String, String?) -> Unit,
) {
    var rating by remember(existing?.rating) {
        mutableStateOf(existing?.rating ?: AgentMessageFeedbackEntity.RATING_UP)
    }
    var note by remember(existing?.note) { mutableStateOf(existing?.note.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_message_feedback_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = rating == AgentMessageFeedbackEntity.RATING_UP,
                        onClick = { rating = AgentMessageFeedbackEntity.RATING_UP },
                        label = { Text(stringResource(R.string.chat_message_feedback_up)) },
                    )
                    FilterChip(
                        selected = rating == AgentMessageFeedbackEntity.RATING_DOWN,
                        onClick = { rating = AgentMessageFeedbackEntity.RATING_DOWN },
                        label = { Text(stringResource(R.string.chat_message_feedback_down)) },
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.chat_message_feedback_note_hint)) },
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(rating, note.takeIf { it.isNotBlank() }) }) {
                Text(stringResource(R.string.chat_message_feedback_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_close)) }
        },
    )
}
