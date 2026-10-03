package top.wkbin.taixu.feature.a2uipoc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 值型组件（TextField/CheckBox/ChoicePicker/Slider/DateTimeInput）的「常量 value」归一化。
 *
 * 背景（源码级已核实）：官方 A2uiBasicCatalogV1 的这五类组件都用
 * `val onValueChange = properties.bindUpdater(ValueProperty)`、`val isEnabled = onValueChange != null`
 * 判定是否可交互；而 `A2uiComponentScopeImpl.bindUpdater` 只在 `value` 形如
 * `{"path": "/xxx"}` 时才返回可写 updater，写常量则返回 null。
 * ∴ 模型写 `"value": "hello"` 时组件被**静默禁用**：能渲染、不能输入、不回传、且不给任何提示。
 *
 * 本归一化器把常量 `value` 改写为数据绑定 `{"path": "/__taixu_inputs/<组件id>"}`，
 * 并追加一条 `updateDataModel` 把原常量种进数据模型。于是：
 * 1) `isEnabled` 变为 true，组件真正可用；
 * 2) 用户输入由官方 updater 写回数据模型（路径固定可预测）；
 * 3) 数据模型在 `sendDataModel = true` 时会随出站事件自动带回给智能体。
 *
 * 幂等：已是 `{"path": ...}` 的 value 不改写，重复调用结果一致。
 */
internal object TaiXuA2uiInputNormalizer {

    /** 归一化后输入值所在的数据模型路径前缀（JSON Pointer）。 */
    const val PROXY_PATH_PREFIX = "/__taixu_inputs/"

    private val VALUE_COMPONENT_TYPES =
        setOf("TextField", "CheckBox", "ChoicePicker", "Slider", "DateTimeInput")

    /** [messagesJson] 为归一化后的协议消息数组；[paths] 为 surfaceId -> (组件id -> 数据模型路径)。 */
    data class Result(
        val messagesJson: String,
        val paths: Map<String, Map<String, String>>,
        val fixedCount: Int,
        val dataModelForced: Boolean,
    )

    fun normalize(messagesJson: String): Result {
        val array = Json.parseToJsonElement(messagesJson).jsonArray
        val seeds = mutableListOf<JsonElement>()
        val paths = mutableMapOf<String, Map<String, String>>()
        var fixedCount = 0
        var dataModelForced = false

        val rewritten = array.map { element ->
            val message = element.jsonObject.toMutableMap()
            // sendDataModel 默认关闭 → 出站事件不带数据模型，用户输入的值就回不到智能体。
            // 打开后，用户与卡片的任何交互都会把整棵数据模型（含输入值）附在事件里带回。
            message["createSurface"]?.jsonObject?.let { surface ->
                if (surface["sendDataModel"]?.jsonPrimitive?.booleanOrNull != true) {
                    message["createSurface"] = JsonObject(surface + ("sendDataModel" to JsonPrimitive(true)))
                    dataModelForced = true
                }
            }
            val update = message["updateComponents"]?.jsonObject?.toMutableMap() ?: return@map JsonObject(message)
            val surfaceId = update["surfaceId"]?.jsonPrimitive?.contentOrNull ?: return@map element
            val components = update["components"]?.jsonArray ?: return@map element

            val perSurface = mutableMapOf<String, String>()
            val normalized = components.map { component ->
                val obj = component.jsonObject.toMutableMap()
                val type = obj["component"]?.jsonPrimitive?.contentOrNull
                val constant = obj["value"]
                if (type == null || type !in VALUE_COMPONENT_TYPES) return@map component
                if (constant == null || (constant is JsonObject && constant.containsKey("path"))) {
                    return@map component
                }
                val componentId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@map component
                val path = PROXY_PATH_PREFIX + componentId
                obj["value"] = JsonObject(mapOf("path" to JsonPrimitive(path)))
                perSurface[componentId] = path
                seeds.add(
                    JsonObject(
                        mapOf(
                            "updateDataModel" to
                                JsonObject(
                                    mapOf(
                                        "surfaceId" to JsonPrimitive(surfaceId),
                                        "path" to JsonPrimitive(path),
                                        "value" to constant,
                                    )
                                )
                        )
                    )
                )
                fixedCount++
                JsonObject(obj)
            }

            if (perSurface.isEmpty()) return@map element
            update["components"] = JsonArray(normalized)
            message["updateComponents"] = JsonObject(update)
            paths[surfaceId] = perSurface
            JsonObject(message)
        }

        return Result(JsonArray(rewritten + seeds).toString(), paths, fixedCount, dataModelForced)
    }
}
