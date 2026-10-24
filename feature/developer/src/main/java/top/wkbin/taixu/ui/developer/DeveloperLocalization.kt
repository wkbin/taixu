package top.wkbin.taixu.ui.developer

import androidx.annotation.StringRes
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.res.stringResource
import top.wkbin.taixu.feature.developer.R

@StringRes
private fun localizedResource(source: String): Int? = when (source) {
        "Ed25519 公钥（Base64）" -> R.string.developer_text_0001
        "Linux 环境已恢复初始状态，工作区工程未删除。" -> R.string.developer_text_0002
        "Linux 环境重置失败" -> R.string.developer_text_0003
        "RootFS 已是最新版本。" -> R.string.developer_text_0004
        "RootFS 更新完成，用户数据已保留。" -> R.string.developer_text_0005
        "Runtime 初始化失败" -> R.string.developer_text_0006
        "Runtime 核心能力已完成检测" -> R.string.developer_text_0007
        "Shell 命令" -> R.string.developer_text_0008
        "下载并初始化" -> R.string.developer_text_0009
        "下载缓存" -> R.string.developer_text_0010
        "保存配置" -> R.string.developer_text_0011
        "停止" -> R.string.developer_text_0012
        "健康检查" -> R.string.developer_text_0013
        "健康检查异常" -> R.string.developer_text_0014
        "健康检查通过" -> R.string.developer_text_0015
        "共享 Runtime 已清理。" -> R.string.developer_text_0016
        "关闭" -> R.string.developer_text_0017
        "准备 PRoot" -> R.string.developer_text_0018
        "准备 PRoot 启动组件" -> R.string.developer_text_0019
        "创建工作区" -> R.string.developer_text_0020
        "创建运行目录" -> R.string.developer_text_0021
        "初始化中" -> R.string.developer_text_0022
        "初始化完成。" -> R.string.developer_text_0023
        "刷新" -> R.string.developer_text_0024
        "取消" -> R.string.developer_text_0025
        "取消下载" -> R.string.developer_text_0026
        "只删除未激活的下载和临时文件，不会影响 Linux 系统、工具或工作区。" -> R.string.developer_text_0027
        "可清理 Runtime" -> R.string.developer_text_0028
        "后台 Linux 进程与磁盘占用" -> R.string.developer_text_0029
        "后台进程" -> R.string.developer_text_0030
        "否" -> R.string.developer_text_0031
        "命令不能为空。" -> R.string.developer_text_0032
        "命令诊断" -> R.string.developer_text_0033
        "在隔离的 Linux 环境中执行一次性 Shell 命令" -> R.string.developer_text_0034
        "复制" -> R.string.developer_text_0035
        "复制全部" -> R.string.developer_text_0036
        "失败" -> R.string.developer_text_0037
        "存储占用" -> R.string.developer_text_0038
        "将删除当前 Linux RootFS、已安装工具和沙箱缓存，但保留 /workspace 工程和手机共享存储。此操作不可撤销。" -> R.string.developer_text_0039
        "将在线下载并校验新版本，保留 /root 与 /opt/taixu。更新期间后台 Linux 进程会停止，失败时自动恢复旧版本。" -> R.string.developer_text_0040
        "将移除未被工具引用的共享 Runtime，此操作无法自动恢复。" -> R.string.developer_text_0041
        "工作区" -> R.string.developer_text_0042
        "工作区可写" -> R.string.developer_text_0043
        "工具数据" -> R.string.developer_text_0044
        "工具清单配置已保存。" -> R.string.developer_text_0045
        "工具源" -> R.string.developer_text_0046
        "工具程序" -> R.string.developer_text_0047
        "已关闭（不写入本地日志文件）" -> R.string.developer_text_0048
        "已就绪" -> R.string.developer_text_0049
        "已是最新" -> R.string.developer_text_0050
        "开发者控制台" -> R.string.developer_text_0051
        "异常" -> R.string.developer_text_0052
        "引用计数为 0" -> R.string.developer_text_0053
        "当前没有可安全清理的共享 Runtime。" -> R.string.developer_text_0054
        "当前没有登记中的后台进程。" -> R.string.developer_text_0055
        "恢复 Linux 初始状态" -> R.string.developer_text_0056
        "恢复 Linux 初始状态？" -> R.string.developer_text_0057
        "执行命令" -> R.string.developer_text_0058
        "提示：能控制清单地址的人也能提供公钥，自填密钥只用于防传输损坏/校验内容一致性，不能提供端到端防篡改。如需真正的信任锚，请在 APK 内置固定公钥。" -> R.string.developer_text_0059
        "日志已复制" -> R.string.developer_text_0060
        "日志已复制到剪贴板" -> R.string.developer_text_0061
        "是" -> R.string.developer_text_0062
        "智能体执行流、工具调用与错误详情本地持久化" -> R.string.developer_text_0063
        "智能体日志已清空。" -> R.string.developer_text_0064
        "智能体本地调试日志" -> R.string.developer_text_0065
        "智能体诊断" -> R.string.developer_text_0066
        "暂无日志" -> R.string.developer_text_0067
        "更新 Linux RootFS？" -> R.string.developer_text_0068
        "更新 RootFS" -> R.string.developer_text_0069
        "未初始化" -> R.string.developer_text_0070
        "架构" -> R.string.developer_text_0071
        "查看日志" -> R.string.developer_text_0072
        "检查可用空间" -> R.string.developer_text_0073
        "检查更新" -> R.string.developer_text_0074
        "检测到 RootFS 新版本，可以更新。" -> R.string.developer_text_0075
        "检测到可用更新" -> R.string.developer_text_0076
        "检测设备架构" -> R.string.developer_text_0077
        "正在下载并验证工具清单…" -> R.string.developer_text_0078
        "正在检查 RootFS 的 OCI manifest…" -> R.string.developer_text_0079
        "正在读取" -> R.string.developer_text_0080
        "正在读取存储信息…" -> R.string.developer_text_0081
        "清单 HTTPS 地址" -> R.string.developer_text_0082
        "清理" -> R.string.developer_text_0083
        "清理下载缓存" -> R.string.developer_text_0084
        "清理下载缓存？" -> R.string.developer_text_0085
        "清空" -> R.string.developer_text_0086
        "版本未知" -> R.string.developer_text_0087
        "确认更新" -> R.string.developer_text_0088
        "确认清理" -> R.string.developer_text_0089
        "确认重置" -> R.string.developer_text_0090
        "签名 HTTPS 地址" -> R.string.developer_text_0091
        "系统" -> R.string.developer_text_0092
        "读取可清理 Runtime 失败" -> R.string.developer_text_0093
        "读取后台进程失败" -> R.string.developer_text_0094
        "读取存储占用失败" -> R.string.developer_text_0095
        "资源管理" -> R.string.developer_text_0096
        "运行健康检查" -> R.string.developer_text_0097
        "运行时基础" -> R.string.developer_text_0098
        "运行时缓存已清理。" -> R.string.developer_text_0099
        "这里只列出未被任何工具引用的共享依赖，清理会在 Linux 沙箱内执行包管理清理。" -> R.string.developer_text_0100
        "远程清单更新：HTTPS + Ed25519 验签（自定义公钥仅防传输损坏，非防篡改信任锚）" -> R.string.developer_text_0101
        "适合快速诊断，不会创建持久终端会话" -> R.string.developer_text_0102
        "配置 DNS" -> R.string.developer_text_0103
        "配置 Linux 系统" -> R.string.developer_text_0104
        "配置环境变量" -> R.string.developer_text_0105
        "重新检查初始化" -> R.string.developer_text_0106
        "重试初始化" -> R.string.developer_text_0107
        "错误" -> R.string.developer_text_0108
        "消息反馈" -> R.string.developer_text_0109
        "用户对助手消息的评价，只读（最多 50 条）" -> R.string.developer_text_0110
        "暂无反馈" -> R.string.developer_text_0111
        "有帮助" -> R.string.developer_text_0112
        "有问题" -> R.string.developer_text_0113
        "未知会话" -> R.string.developer_text_0114
        else -> null
    }

@Composable
private fun resolveLocalizedString(source: String): String {
    localizedResource(source)?.let { return stringResource(it) }
    return when {
        source.endsWith(" 个活动进程") -> stringResource(R.string.developer_active_process_count, source.removeSuffix(" 个活动进程"))
        source.startsWith("RootFS 更新失败：") -> stringResource(R.string.developer_rootfs_update_failed, source.removePrefix("RootFS 更新失败："))
        source.startsWith("RootFS 更新检查失败：") -> stringResource(R.string.developer_rootfs_check_failed, source.removePrefix("RootFS 更新检查失败："))
        source.startsWith("停止进程失败：") -> stringResource(R.string.developer_stop_process_failed, source.removePrefix("停止进程失败："))
        source.startsWith("健康检查失败：") -> stringResource(R.string.developer_health_check_failed, source.removePrefix("健康检查失败："))
        source.startsWith("初始化失败：") -> stringResource(R.string.developer_initialization_failed, source.removePrefix("初始化失败："))
        source.startsWith("命令执行失败：") -> stringResource(R.string.developer_command_failed, source.removePrefix("命令执行失败："))
        source.startsWith("工具清单已更新：") -> stringResource(R.string.developer_manifest_updated, source.removePrefix("工具清单已更新：").removeSuffix(" 个工具。"))
        source.startsWith("已启用（文件大小：") -> stringResource(R.string.developer_log_enabled_size, source.removePrefix("已启用（文件大小：").removeSuffix("）"))
        source.startsWith("更新失败：") -> stringResource(R.string.developer_update_failed, source.removePrefix("更新失败："))
        source.startsWith("清理 ") && source.endsWith("？") -> stringResource(R.string.developer_clean_runtime_confirm, source.removePrefix("清理 ").removeSuffix("？"))
        source.startsWith("清理失败：") -> stringResource(R.string.developer_cleanup_failed, source.removePrefix("清理失败："))
        source.startsWith("设备剩余空间 ") -> stringResource(R.string.developer_device_space_available, source.removePrefix("设备剩余空间 "))
        source.endsWith(" 条反馈") -> stringResource(R.string.developer_feedback_count, source.removeSuffix(" 条反馈"))
        source.startsWith("重置失败：") -> stringResource(R.string.developer_reset_failed, source.removePrefix("重置失败："))
        else -> source
    }
}

@Composable
fun LocalizedText(
    text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified, fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null, fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified, textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE, minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {}, style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(
    text = resolveLocalizedString(text), modifier = modifier, color = color,
    fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight,
    fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration,
    textAlign = textAlign, lineHeight = lineHeight, overflow = overflow,
    softWrap = softWrap, maxLines = maxLines, minLines = minLines,
    onTextLayout = onTextLayout, style = style,
)

@Composable
fun LocalizedText(
    text: AnnotatedString, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified, fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null, fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified, textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE, minLines: Int = 1,
    inlineContent: Map<String, androidx.compose.foundation.text.InlineTextContent> = mapOf(),
    onTextLayout: (TextLayoutResult) -> Unit = {}, style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(
    text = text, modifier = modifier, color = color, fontSize = fontSize,
    fontStyle = fontStyle, fontWeight = fontWeight, fontFamily = fontFamily,
    letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
    lineHeight = lineHeight, overflow = overflow, softWrap = softWrap,
    maxLines = maxLines, minLines = minLines, inlineContent = inlineContent,
    onTextLayout = onTextLayout, style = style,
)
