package top.wkbin.taixu.harness

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.harness.core.ToolBackend
import java.util.Base64

data class WorkspaceToolRequest(
    val tool: HarnessTool,
    val args: JsonObject,
    val sessionId: String,
    val workspace: String,
)

data class WorkspaceToolOutcome(
    val success: Boolean,
    val output: String,
    val metadata: Map<String, String> = emptyMap(),
)

/** File-tool semantics independent of the concrete filesystem, still inside ToolExecutor policy. */
class WorkspaceToolBackend(
    private val operationsFor: (String) -> WorkspaceToolOperations,
    private val snapshots: WorkspaceMutationSnapshots = WorkspaceMutationSnapshots(),
    private val imagePayload: (String) -> String = ImagePayloadCompressor::downscaleDataUrl,
    private val environments: top.wkbin.taixu.harness.environment.SessionExecutionEnvironments? = null,
) : ToolBackend<WorkspaceToolRequest, WorkspaceToolOutcome> {
    override suspend fun execute(request: WorkspaceToolRequest): WorkspaceToolOutcome {
        currentCoroutineContext().ensureActive()
        val operations = environments?.environment(request.sessionId, request.workspace)?.files ?: operationsFor(request.workspace)
        val path = JsonArgs.requireString(request.args, "path")
        val outcome = when (request.tool) {
            HarnessTool.READ -> read(operations, path, request.args)
            HarnessTool.WRITE -> {
                val content = JsonArgs.requireString(request.args, "content")
                snapshots.before(request.sessionId, operations, path)
                currentCoroutineContext().ensureActive()
                val written = operations.write(path, content)
                currentCoroutineContext().ensureActive()
                if (written is AppResult.Success) snapshots.after(request.sessionId, operations, path, content)
                written.output("已写入 $path\nDIFF_STAT: +${content.lines().size} -0", "write")
            }
            HarnessTool.EDIT -> {
                val oldText = JsonArgs.requireString(request.args, "oldText")
                val newText = JsonArgs.requireString(request.args, "newText")
                snapshots.before(request.sessionId, operations, path)
                currentCoroutineContext().ensureActive()
                val edited = operations.editDetailed(path, oldText, newText)
                currentCoroutineContext().ensureActive()
                if (edited is AppResult.Success) {
                    snapshots.after(request.sessionId, operations, path, null)
                    WorkspaceToolOutcome(
                        true, "已修改 $path（匹配策略：${edited.data.strategy}，替换 ${edited.data.replacements} 处）\n" +
                            "DIFF_STAT: +${newText.lines().size} -${oldText.lines().size}",
                        edited.data.diff?.let { mapOf("diff" to it) }.orEmpty(),
                    )
                } else edited.output(actionName = "edit")
            }
            else -> throw IllegalArgumentException("Unsupported workspace tool: ${request.tool}")
        }
        currentCoroutineContext().ensureActive()
        return outcome
    }

    private suspend fun read(operations: WorkspaceToolOperations, path: String, args: JsonObject): WorkspaceToolOutcome {
        val mime = imageMime(path)
        if (mime == null) {
            val offset = args["offset"]?.jsonPrimitive?.content?.trim()?.toIntOrNull()
            val limit = args["limit"]?.jsonPrimitive?.content?.trim()?.toIntOrNull()
            return operations.read(path, offset, limit).output(actionName = "read")
        }
        return when (val bytes = operations.readRawBytes(path)) {
            is AppResult.Success -> {
                currentCoroutineContext().ensureActive()
                val base64 = Base64.getEncoder().encodeToString(bytes.data)
                WorkspaceToolOutcome(
                    true, "已读取图片文件 $path（${bytes.data.size} 字节，$mime）。" +
                        "图像已作为多模态附件随本次工具结果提供。" +
                        "若这是虚拟屏截图，点击坐标用 0–1000 相对位置，不要用图上的像素。" +
                        "若当前模型不支持视觉，请改用文字/脚本方式描述图片内容。",
                    mapOf("image_payload" to imagePayload("data:$mime;base64,$base64")),
                )
            }
            is AppResult.Failure -> bytes.output(actionName = "read")
        }
    }

    private fun AppResult<*>.output(successMessage: String = "", actionName: String): WorkspaceToolOutcome = when (this) {
        is AppResult.Success -> WorkspaceToolOutcome(true, successMessage.ifBlank { data.toString() })
        is AppResult.Failure -> {
            val hint = when (actionName) {
                "edit" -> "\n\n【文本替换失败反思与纠错要求】\n未在目标文件中找到唯一匹配的 oldText。请立即调用 read 查看该文件的最新真实内容与行号，获取精确匹配的内容后再发起 edit，严禁盲目猜测或重复相同内容！"
                "read" -> "\n\n【文件读取失败反思与纠错要求】\n无法读取指定路径文件。请调用 base 执行 ls 或 find 确定文件的真实存在路径，切勿盲目重复错误路径！"
                else -> ""
            }
            WorkspaceToolOutcome(false, error.message + hint)
        }
    }

    private fun imageMime(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "ico" -> "image/x-icon"
        else -> null
    }
}
