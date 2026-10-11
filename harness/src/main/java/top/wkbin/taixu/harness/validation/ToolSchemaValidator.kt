package top.wkbin.taixu.harness.validation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.taixu.core.model.McpToolInfo
import top.wkbin.taixu.harness.ProviderClient
import top.wkbin.taixu.harness.directory.HostCapabilityDirectory
import top.wkbin.taixu.harness.mcp.McpToolApiName
import kotlinx.serialization.json.buildJsonObject

/**
 * 工具参数执行前 JSON Schema 校验（模型参数 → 校验 → 审批 → 执行 链路的第二环）。
 *
 * 只实现工具定义实际用到的 JSON Schema 子集：required / type / enum /
 * minimum / maximum / pattern / anyOf(required) / items。未知关键字一律忽略，
 * 找不到 schema 的工具跳过校验（宽容失败，不阻断执行链路）。
 *
 * 校验失败不抛异常，而是返回可读的问题列表，由调用方写回 ToolResult
 * 让模型自我纠正。
 */
object ToolSchemaValidator {
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_PATTERN_LENGTH = 256
    private const val MAX_PATTERN_INPUT_LENGTH = 512

    /**
     * 智能参数解包、键扁平化还原与别名规整：
     * 1. 自动解包单层包裹参数（params/arguments/input）；
     * 2. 自动把双下划线 `__` 或点号 `.` 分隔的扁平化键（例如 options__timeout 或 config.port）
     *    还原为标准嵌套 JsonObject，兼容小参数量或特定模型打平输出对象的习惯（DeepSeek-Reasonix 规范）；
     * 3. 映射常用字段别名（path/command/oldText/newText/timeout_seconds）。
     *
     * [isMcpTool] = true 时 1、2 全部跳过：MCP 工具的参数名由远端 schema 定义，
     * 可能真有名为 params/arguments/input 的单参数、或含 `__`/`.` 的合法参数名，
     * 解包与拆分都会把参数改写成远端不认识的结构。
     */
    fun normalizeArgs(raw: JsonObject, applyAliases: Boolean = true, isMcpTool: Boolean = false): JsonObject {
        if (isMcpTool) return raw
        // Unwrap multi-level params/arguments/input wrappers (up to 8 levels).
        var base = raw
        var wrapDepth = 0
        while (wrapDepth < 8 && base.size == 1) {
            val inner = base["params"] ?: base["arguments"] ?: base["input"]
            val innerObj = inner as? JsonObject ?: break
            base = innerObj
            wrapDepth++
        }

        val unflattened = unflattenObject(base)
        // MCP 的 target/script/timeout 有自己的协议语义，不能套用内置文件/命令工具别名。
        if (!applyAliases) return unflattened

        return buildJsonObject {
            unflattened.forEach { (k, v) -> put(k, v) }
            if (!unflattened.containsKey("path")) {
                val alias = unflattened["file_path"] ?: unflattened["filePath"] ?: unflattened["file"] ?: unflattened["target"]
                alias?.let { put("path", it) }
            }
            if (!unflattened.containsKey("command")) {
                val alias = unflattened["cmd"] ?: unflattened["script"]
                alias?.let { put("command", it) }
            }
            if (!unflattened.containsKey("oldText")) {
                val alias = unflattened["old_text"] ?: unflattened["original_text"]
                alias?.let { put("oldText", it) }
            }
            if (!unflattened.containsKey("newText")) {
                val alias = unflattened["new_text"] ?: unflattened["replacement"]
                alias?.let { put("newText", it) }
            }
            if (!unflattened.containsKey("timeout_seconds")) {
                val alias = unflattened["timeout"] ?: unflattened["timeoutSeconds"] ?: unflattened["timeout_sec"]
                alias?.let { put("timeout_seconds", it) }
            }
        }
    }

    /**
     * 将带有双下划线（options__timeout）或合规点号路径（options.timeout）的扁平键还原为深层嵌套 JsonObject。
     * 若已存在同名嵌套对象，则自动递归深度合并。
     */
    fun unflattenObject(obj: JsonObject): JsonObject {
        val root = mutableMapOf<String, Any?>()

        fun insert(path: List<String>, value: JsonElement) {
            var current = root
            for (i in 0 until path.size - 1) {
                val part = path[i]
                val existing = current[part]
                val nextMap = when (existing) {
                    null -> {
                        val m = mutableMapOf<String, Any?>()
                        current[part] = m
                        m
                    }
                    is MutableMap<*, *> -> @Suppress("UNCHECKED_CAST") (existing as MutableMap<String, Any?>)
                    is JsonObject -> {
                        val m = mutableMapOf<String, Any?>()
                        existing.forEach { (k, v) -> m[k] = v }
                        current[part] = m
                        m
                    }
                    // 冲突：扁平键的中间路径撞上既有标量/数组值。保留既有值、丢弃该扁平键，
                    // 不再用空对象静默覆盖（否则 {"a":"x","a__b":1} 的 "a" 会被覆盖成 {"b":1}）。
                    else -> return
                }
                current = nextMap
            }
            val leaf = path.last()
            val finalValue = if (value is JsonObject) unflattenObject(value) else value
            val existing = current[leaf]
            if (existing != null && existing !is MutableMap<*, *> && existing !is JsonObject) {
                // 叶层冲突：目标位置已有标量/数组值，保留既有值、丢弃冲突扁平键的值
                return
            }
            if (existing is MutableMap<*, *> && finalValue is JsonObject) {
                @Suppress("UNCHECKED_CAST")
                val m = existing as MutableMap<String, Any?>
                finalValue.forEach { (k, v) -> m[k] = v }
            } else {
                current[leaf] = finalValue
            }
        }

        for ((key, value) in obj) {
            val segments = splitFlattenedKey(key)
            if (segments.size > 1) {
                insert(segments, value)
            } else {
                val processedVal = if (value is JsonObject) unflattenObject(value) else value
                val existing = root[key]
                if (existing is MutableMap<*, *> && processedVal is JsonObject) {
                    @Suppress("UNCHECKED_CAST")
                    val m = existing as MutableMap<String, Any?>
                    processedVal.forEach { (k, v) -> m[k] = v }
                } else {
                    root[key] = processedVal
                }
            }
        }

        return toJsonElement(root) as JsonObject
    }

    private fun splitFlattenedKey(key: String): List<String> {
        if (key.contains("__")) {
            val parts = key.split("__").filter { it.isNotEmpty() }
            if (parts.size > 1) return parts
        }
        if (key.contains(".") && !key.startsWith(".") && !key.endsWith(".")) {
            val parts = key.split(".")
            val allValidIdentifiers = parts.all { it.isNotEmpty() && it.all { ch -> ch.isLetterOrDigit() || ch == '_' } }
            if (allValidIdentifiers && parts.size > 1) return parts
        }
        return listOf(key)
    }

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Map<*, *> -> buildJsonObject {
            value.forEach { (k, v) ->
                put(k.toString(), toJsonElement(v))
            }
        }
        else -> JsonNull
    }


    /** 校验工具参数；空列表 = 通过。 */
    fun problemsFor(
        toolName: String,
        args: JsonObject,
        mcpTools: List<McpToolInfo> = emptyList(),
    ): List<String> {
        val name = toolName.trim()
        val schema = resolveSchema(name, mcpTools) ?: return emptyList()
        val isMcp = name.startsWith("mcp__") || name.equals("use_capability", ignoreCase = true)
        // MCP 工具：既不套用内置别名，也不做单键解包/扁平键还原（见 normalizeArgs 注释）
        val normalized = normalizeArgs(args, applyAliases = !isMcp, isMcpTool = isMcp)
        return validateObject(schema, normalized, prefix = "")
    }

    /** 直接对给定 schema 校验（供自定义 schema 场景与测试使用）。 */
    fun validate(schema: JsonObject, args: JsonObject): List<String> =
        validateObject(schema, normalizeArgs(args, applyAliases = false), prefix = "")

    private fun resolveSchema(toolName: String, mcpTools: List<McpToolInfo>): JsonObject? {
        if (toolName.startsWith("mcp__")) {
            val tool = mcpTools.firstOrNull { McpToolApiName.matches(it, toolName) } ?: return null
            if (tool.parametersJson.isBlank()) return null
            return runCatching { json.parseToJsonElement(tool.parametersJson) as? JsonObject }.getOrNull()
        }
        val apiName = when (val name = toolName.lowercase()) {
            "history.search" -> "history_search"
            "history.read" -> "history_read"
            "subagent" -> "invoke_subagent"
            else -> name
        }
        // host 的校验面 = 执行器接受面（direct ∪ deferred 并集）：provider 声明面只宣告
        // direct 高频动作（prompt 减负），deferred 动作经 use_capability 或旧式直接调用
        // 进入时必须仍然可校验、可执行，否则旧会话重放与模型历史模仿会被 enum 硬拒。
        if (apiName == "host") return HostCapabilityDirectory.validationSchema()
        return ProviderClient.TOOLS.firstOrNull { it.function.name == apiName }?.function?.parameters
    }

    private fun validateObject(
        schema: JsonObject,
        obj: JsonObject,
        prefix: String,
        inheritedProperties: Set<String> = emptySet(),
    ): List<String> {
        val problems = mutableListOf<String>()
        val alternatives = (schema["anyOf"] as? JsonArray).orEmpty()
        val allowedProperties = inheritedProperties +
            (schema["properties"] as? JsonObject).orEmpty().keys +
            alternatives.filterIsInstance<JsonObject>().flatMap { (it["properties"] as? JsonObject).orEmpty().keys }

        (schema["required"] as? JsonArray)?.forEach { element ->
            val name = (element as? JsonPrimitive)?.contentOrNull ?: return@forEach
            if (!obj.containsKey(name)) problems += "缺少必填参数 ${prefix}${name}"
        }

        alternatives.takeIf { it.isNotEmpty() }?.let { alternatives ->
            val satisfied = alternatives.any { alternative ->
                (alternative as? JsonObject)?.let { validateObject(it, obj, prefix, allowedProperties).isEmpty() } == true
            }
            if (!satisfied) {
                val combos = alternatives.mapNotNull { alternative ->
                    (alternative as? JsonObject)?.get("required")?.let { required ->
                        (required as? JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                            ?.takeIf { it.isNotEmpty() }
                            ?.joinToString("/")
                    }
                }.filter { it.isNotBlank() }
                if (combos.isNotEmpty()) {
                    val hint = if (combos.size == 1) {
                        "至少提供 ${combos.single()}"
                    } else {
                        "${combos.joinToString(" 或 ")} 至少提供其中一组"
                    }
                    problems += "参数组合不满足要求：$hint"
                }
            }
        }

        (schema["properties"] as? JsonObject)?.forEach { (name, definition) ->
            val value = obj[name] ?: return@forEach
            val propertySchema = definition as? JsonObject ?: return@forEach
            problems += validateValue(propertySchema, value, label = "${prefix}${name}", prefix = "$prefix$name.")
        }

        // 多传/错传参数检测：模型把其他工具的参数（如 url/query）带进来时，明确指出
        // 本工具接受哪些参数，让模型一次修正到位，而不是反复以错误参数重试。
        val allowExtra = (schema["additionalProperties"] as? JsonPrimitive)?.contentOrNull == "true"
        (schema["properties"] as? JsonObject)?.let { properties ->
            val unknown = obj.keys.filter { it !in allowedProperties }
            if (unknown.isNotEmpty() && !allowExtra) {
                val at = if (prefix.isEmpty()) "" else "（位于 $prefix 层）"
                problems += "不接受参数 ${unknown.joinToString("、")}$at" +
                    "（该工具可用参数: ${properties.keys.joinToString("、").ifEmpty { "无" }}）"
            }
        }
        return problems
    }

    private fun validateValue(schema: JsonObject, value: JsonElement, label: String, prefix: String): List<String> {
        val problems = mutableListOf<String>()
        val type = schema["type"]?.jsonPrimitive?.contentOrNull

        if (type != null && !matchesType(type, value)) {
            problems += "参数 $label 类型错误：应为 $type，实际是${typeName(value)}"
            return problems // 类型不对，后续约束没有意义
        }

        (schema["enum"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { allowed ->
            val content = (value as? JsonPrimitive)?.content
            if (allowed.none { (it as? JsonPrimitive)?.content == content }) {
                problems += "参数 $label 的值 \"$content\" 不在允许范围内：${allowed.joinToString("/") { (it as? JsonPrimitive)?.content.orEmpty() }}"
            }
        }

        if (type == "integer" || type == "number") {
            val number = (value as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            if (number != null) {
                val min = schema["minimum"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                val max = schema["maximum"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                when {
                    min != null && max != null && (number < min || number > max) ->
                        problems += "参数 $label 的值 ${formatNumber(number)} 超出允许范围 [${formatNumber(min)}, ${formatNumber(max)}]"
                    min != null && number < min ->
                        problems += "参数 $label 的值 ${formatNumber(number)} 小于最小允许值 ${formatNumber(min)}"
                    max != null && number > max ->
                        problems += "参数 $label 的值 ${formatNumber(number)} 大于最大允许值 ${formatNumber(max)}"
                }
            }
        }

        if (value is JsonPrimitive && value.isString) {
            schema["pattern"]?.jsonPrimitive?.contentOrNull?.let { pattern ->
                // Java regex has no timeout. Avoid known exponential shapes and huge inputs;
                // unsupported remote patterns are ignored like other unknown schema keywords.
                val safe = pattern.length <= MAX_PATTERN_LENGTH &&
                    value.content.length <= MAX_PATTERN_INPUT_LENGTH &&
                    pattern.none { it == '(' || it == ')' || it == '|' } &&
                    pattern.count { it == '*' || it == '+' || it == '?' || it == '{' } <= 2
                val matches = if (safe) runCatching { Regex(pattern).containsMatchIn(value.content) }.getOrDefault(true) else true
                if (!matches) problems += "参数 $label 格式不符合要求（需匹配 $pattern）"
            }
        }

        if (value is JsonArray) {
            (schema["items"] as? JsonObject)?.let { itemSchema ->
                value.forEachIndexed { index, element ->
                    // prefix 形如 "steps." → 数组元素路径 "steps[0]."（去掉属性名后的点）
                    val itemPrefix = prefix.removeSuffix(".") + "[$index]."
                    when (element) {
                        is JsonObject -> problems += validateObject(itemSchema, element, itemPrefix)
                        else -> problems += validateValue(itemSchema, element, label = "${prefix.removeSuffix(".")}[$index]", prefix = itemPrefix)
                    }
                }
            }
        }
        return problems
    }

    private fun formatNumber(number: Double): String =
        if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()

    private fun matchesType(type: String, value: JsonElement): Boolean = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && (
            (!value.isString && (value.content == "true" || value.content == "false")) ||
                (value.isString && (value.content.equals("true", ignoreCase = true) || value.content.equals("false", ignoreCase = true)))
            )
        "integer" -> value is JsonPrimitive &&
            value.contentOrNull?.toDoubleOrNull()?.let { it % 1.0 == 0.0 } == true
        "number" -> value is JsonPrimitive && value.contentOrNull?.toDoubleOrNull() != null
        "null" -> value is JsonNull
        else -> true // 未知类型声明宽容放行
    }

    private fun typeName(value: JsonElement): String = when (value) {
        is JsonObject -> "对象"
        is JsonArray -> "数组"
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            value.isString -> "字符串"
            value.content == "true" || value.content == "false" -> "布尔值"
            value.contentOrNull?.toDoubleOrNull() != null -> "数字"
            else -> "未知类型"
        }
    }
}
