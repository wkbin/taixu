package top.wkbin.taixu.ui.developer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.taixu.runtime.bridge.adb.EmbeddedAdbManager
import top.wkbin.taixu.ui.components.IconTile
import top.wkbin.taixu.ui.components.NoticeBanner
import top.wkbin.taixu.ui.components.RuntimeButton as Button
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeFilledTonalButton as FilledTonalButton
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.RuntimeLinearProgressIndicator as LinearProgressIndicator
import top.wkbin.taixu.ui.components.RuntimeOutlinedButton as OutlinedButton
import top.wkbin.taixu.ui.components.RuntimeTopBar
import top.wkbin.taixu.ui.components.SectionHeader
import top.wkbin.taixu.ui.components.StatusBadge
import top.wkbin.taixu.ui.developer.LocalizedText as Text

/**
 * 独立的无线 ADB 与日志抓取工作台页面。
 *
 * 提供：
 * 1. mDNS 自动发现端点展示与自动重连状态；
 * 2. TLS + SPAKE2 一键安全配对（只需在系统无线调试输入一次配对码）；
 * 3. Logcat 多维过滤（包名/Tag/优先级/关键词/行数）与实时抓取预览；
 * 4. 日志复制与设备缓冲区清空。
 */
@Composable
fun AdbLogcatScreen(
    onBack: () -> Unit,
    viewModel: DeveloperViewModel = hiltViewModel(),
) {
    val adbState by viewModel.adbState.collectAsStateWithLifecycle()
    val adbDiscovery by viewModel.adbDiscovery.collectAsStateWithLifecycle()
    val adbBusy by viewModel.adbBusy.collectAsStateWithLifecycle()
    val adbMessage by viewModel.adbMessage.collectAsStateWithLifecycle()
    val logcatOutput by viewModel.logcatOutput.collectAsStateWithLifecycle()

    var pairingCode by rememberSaveable { mutableStateOf("") }
    var logcatPackage by rememberSaveable { mutableStateOf("top.wkbin.taixu") }
    var logcatTag by rememberSaveable { mutableStateOf("") }
    var logcatKeyword by rememberSaveable { mutableStateOf("") }
    var logcatLines by rememberSaveable { mutableStateOf("200") }
    var logcatPriority by rememberSaveable { mutableStateOf('V') }

    val context = LocalContext.current
    val connected = adbState as? EmbeddedAdbManager.ConnectionState.Connected

    LaunchedEffect(adbState) {
        if (adbState is EmbeddedAdbManager.ConnectionState.Connected) {
            pairingCode = ""
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RuntimeTopBar("无线 ADB 与日志抓取", onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ── 1. 连接状态与概览 ──────────────────────────────────────────
            RuntimeCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconTile(
                        icon = RuntimeIconName.Terminal,
                        color = if (connected != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("内置无线 ADB 客户端", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (val current = adbState) {
                                EmbeddedAdbManager.ConnectionState.Disconnected -> "未连接"
                                EmbeddedAdbManager.ConnectionState.Discovering -> "正在通过 mDNS 自动发现调试端口…"
                                EmbeddedAdbManager.ConnectionState.Pairing -> "正在安全配对…"
                                EmbeddedAdbManager.ConnectionState.Connecting -> "正在建立 ADB 连接…"
                                is EmbeddedAdbManager.ConnectionState.Connected -> "已连接：${current.host}:${current.port}"
                                is EmbeddedAdbManager.ConnectionState.Failed -> current.message
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (adbState is EmbeddedAdbManager.ConnectionState.Failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    StatusBadge(
                        text = when (adbState) {
                            is EmbeddedAdbManager.ConnectionState.Connected -> "已连接"
                            EmbeddedAdbManager.ConnectionState.Discovering -> "探测中"
                            EmbeddedAdbManager.ConnectionState.Pairing -> "配对中"
                            EmbeddedAdbManager.ConnectionState.Connecting -> "连接中"
                            is EmbeddedAdbManager.ConnectionState.Failed -> "异常"
                            EmbeddedAdbManager.ConnectionState.Disconnected -> "未就绪"
                        },
                        color = when (adbState) {
                            is EmbeddedAdbManager.ConnectionState.Connected -> MaterialTheme.colorScheme.secondary
                            is EmbeddedAdbManager.ConnectionState.Failed -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        },
                    )
                }

                if (adbBusy || adbState == EmbeddedAdbManager.ConnectionState.Discovering || adbState == EmbeddedAdbManager.ConnectionState.Connecting) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "mDNS 自动探测：发现配对端点 ${adbDiscovery.pairingEndpoints.size} 个 · 连接端点 ${adbDiscovery.connectEndpoints.size} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── 2. 配对与重连控制台 ──────────────────────────────────────────
            SectionHeader("安全配对与连接", "密钥持久化保存在应用私有目录；完成一次配对后无需再查端口")
            RuntimeCard(Modifier.fillMaxWidth()) {
                NoticeBanner(
                    text = "💡 通知栏快捷配对推荐：Android 系统在开启「使用配对码配对设备」弹窗时，切出设置会关闭弹窗并使配对码失效。太墟已在系统通知栏提供快捷配对常驻通知，您可在系统开发者选项弹窗中直接下拉通知栏输入 6 位配对码并提交，无需切回！",
                    isError = false,
                )
                Spacer(Modifier.height(10.dp))

                OutlinedButton(
                    onClick = {
                        runCatching {
                            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        }.onFailure {
                            Toast.makeText(context, "无法直接打开开发者选项，请在系统设置中手动开启", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("前往系统开发者选项（开启无线调试）")
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = { value -> pairingCode = value.filter(Char::isDigit).take(6) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("6 位配对码（亦可直接在通知栏输入）") },
                    placeholder = { Text("例如：123456") },
                    supportingText = { Text("通过 mDNS 自动解析端口，无需手动查找填入") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.pairWirelessAdb(pairingCode) },
                        enabled = !adbBusy && pairingCode.length == 6 && adbDiscovery.pairingEndpoints.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("完成配对并连接")
                    }
                    OutlinedButton(
                        onClick = viewModel::connectWirelessAdb,
                        enabled = !adbBusy && adbDiscovery.connectEndpoints.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("重新连接")
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    FilledTonalButton(
                        onClick = viewModel::restartAdbDiscovery,
                        enabled = !adbBusy,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("重新探测端口", style = MaterialTheme.typography.labelSmall)
                    }
                }

                adbMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── 3. 日志抓取工作台 ───────────────────────────────────────────
            SectionHeader("Logcat 日志抓取", "指定目标应用包名、Tag 与过滤条件，就地抓取与分析运行日志")
            RuntimeCard(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = logcatPackage,
                    onValueChange = { logcatPackage = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("目标应用包名（留空抓取全系统）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(
                        onClick = { logcatPackage = "top.wkbin.taixu" },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("填入太墟", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = { logcatPackage = "" },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("全系统日志", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(10.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = logcatTag,
                        onValueChange = { logcatTag = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Tag 过滤（可选）") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = logcatLines,
                        onValueChange = { logcatLines = it.filter(Char::isDigit).take(4) },
                        modifier = Modifier.weight(0.6f),
                        label = { Text("行数") },
                        singleLine = true,
                    )
                }
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = logcatKeyword,
                    onValueChange = { logcatKeyword = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("关键词过滤（可选，忽略大小写）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))

                Text("日志等级", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf('V' to "Verbose", 'D' to "Debug", 'I' to "Info", 'W' to "Warn", 'E' to "Error", 'F' to "Fatal").forEach { (priority, _) ->
                        if (priority == logcatPriority) {
                            Button(
                                onClick = { logcatPriority = priority },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                            ) {
                                Text(priority.toString())
                            }
                        } else {
                            OutlinedButton(
                                onClick = { logcatPriority = priority },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                            ) {
                                Text(priority.toString())
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.captureLogcat(
                                logcatPackage,
                                logcatTag,
                                logcatPriority,
                                logcatKeyword,
                                logcatLines.toIntOrNull() ?: 200,
                            )
                        },
                        enabled = connected != null && !adbBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("抓取日志")
                    }
                    OutlinedButton(
                        onClick = viewModel::clearDeviceLogcat,
                        enabled = connected != null && !adbBusy,
                    ) {
                        Text("清空缓冲区")
                    }
                }

                if (logcatOutput.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text("抓取结果", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        SelectionContainer {
                            Column(
                                modifier = Modifier
                                    .padding(10.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Text(
                                    logcatOutput,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Logcat", logcatOutput))
                            Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("复制完整日志")
                    }
                }
            }

            // ── 4. 沙箱 CLI 与 Agent 联动说明 ──────────────────────────────
            SectionHeader("沙箱终端与 Agent 联动", "在 Linux 命令行或 AI 对话中直接调用")
            RuntimeCard(Modifier.fillMaxWidth()) {
                Text(
                    "• 终端命令：已预置 logcat-grabber <包名>、logcat-tail <包名> 与 logcat-export <包名> <路径>\n" +
                    "• 宿主接口：通过 taixu-host logcat 跨沙箱直接调用\n" +
                    "• AI 协同：在智枢对话中直接吩咐 Agent“帮我抓取崩溃日志”，将自动调用 host.logcat 工具分析排查",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
