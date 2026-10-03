package top.wkbin.taixu.feature.a2uipoc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaiXuA2uiInputNormalizerTest {

    private fun payload(component: String, valueJson: String, id: String = "c1") =
        "[{\"createSurface\":{\"surfaceId\":\"s1\",\"catalogId\":\"catalog.json\"}}," +
            "{\"updateComponents\":{\"surfaceId\":\"s1\",\"components\":[" +
            "{\"id\":\"root\",\"component\":\"Column\",\"children\":[\"$id\"]}," +
            "{\"id\":\"$id\",\"component\":\"$component\",\"label\":\"L\",\"value\":$valueJson}]}}]"

    /** 取「非 root」的那个组件（与消息条数无关，避免用固定下标）。 */
    private fun component(json: String) =
        Json.parseToJsonElement(json).jsonArray
            .first { it.jsonObject.containsKey("updateComponents") }
            .jsonObject["updateComponents"]!!.jsonObject["components"]!!.jsonArray
            .first { it.jsonObject["id"]!!.jsonPrimitive.content != "root" }
            .jsonObject

    private fun seed(json: String) =
        Json.parseToJsonElement(json).jsonArray.last().jsonObject["updateDataModel"]!!.jsonObject

    @Test
    fun `constant value is rewritten to a proxy path and seeded into the data model`() {
        val result = TaiXuA2uiInputNormalizer.normalize(payload("TextField", "\"hello\""))

        assertEquals(1, result.fixedCount)
        assertEquals(
            "/__taixu_inputs/c1",
            component(result.messagesJson)["value"]!!.jsonObject["path"]!!.jsonPrimitive.content,
        )
        assertEquals(setOf("c1"), result.paths["s1"]!!.keys)
        assertEquals("s1", seed(result.messagesJson)["surfaceId"]!!.jsonPrimitive.content)
        assertEquals("hello", seed(result.messagesJson)["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `all five value component types are normalized`() {
        listOf("TextField", "CheckBox", "ChoicePicker", "Slider", "DateTimeInput").forEach { type ->
            val result = TaiXuA2uiInputNormalizer.normalize(payload(type, "\"v\""))
            assertEquals("$type 应被归一化", 1, result.fixedCount)
        }
    }

    @Test
    fun `already data bound value is left untouched and normalize is idempotent`() {
        val result = TaiXuA2uiInputNormalizer.normalize(payload("TextField", "{\"path\":\"/form/name\"}"))

        assertEquals(0, result.fixedCount)
        assertTrue(result.paths.isEmpty())
        assertEquals(
            "/form/name",
            component(result.messagesJson)["value"]!!.jsonObject["path"]!!.jsonPrimitive.content,
        )
        // 幂等：重复归一化结果一致
        assertEquals(result.messagesJson, TaiXuA2uiInputNormalizer.normalize(result.messagesJson).messagesJson)
    }

    @Test
    fun `createSurface has sendDataModel forced on so values return with events`() {
        val result = TaiXuA2uiInputNormalizer.normalize(payload("TextField", "\"v\""))

        assertTrue(result.dataModelForced)
        val createSurface =
            Json.parseToJsonElement(result.messagesJson).jsonArray.first()
                .jsonObject["createSurface"]!!.jsonObject
        assertTrue(createSurface["sendDataModel"]!!.jsonPrimitive.content.toBoolean())
        // 已显式打开时不再重复标记
        assertFalse(TaiXuA2uiInputNormalizer.normalize(result.messagesJson).dataModelForced)
    }

    @Test
    fun `non value components and unrelated messages are untouched`() {
        val text = "[{\"updateComponents\":{\"surfaceId\":\"s1\",\"components\":[" +
            "{\"id\":\"root\",\"component\":\"Column\",\"children\":[\"t1\"]}," +
            "{\"id\":\"t1\",\"component\":\"Text\",\"text\":\"hi\",\"value\":\"keep\"}]}}]"
        val result = TaiXuA2uiInputNormalizer.normalize(text)

        assertEquals(0, result.fixedCount)
        assertEquals("keep", component(result.messagesJson)["value"]!!.jsonPrimitive.content)
    }
}
