package top.wkbin.taixu.harness.subagent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.harness.HarnessTool
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult

/**
 * 完成 claim 的解析与 host 裁定测试：status 是主张不是事实，
 * host 只用自己记录的成功凭据背书，永不升格、只降级。
 */
class SubagentClaimTest {

    private val claimJson = """
        结论正文在此。

        ```json
        {
          "status": "complete",
          "summary": "完成了",
          "acceptance_criteria": [
            {"type": "verification", "claim": "测试通过", "command": "./gradlew test"},
            {"type": "files", "claim": "已写入报告", "paths": ["docs/report.md"]},
            {"type": "manual", "claim": "用户需真机验证"}
          ]
        }
        ```
    """.trimIndent()

    // ---------- 解析 ----------

    @Test
    fun `parses claim block with normalized status and criteria`() {
        val claim = parseSubagentClaim(claimJson)
        assertNotNull(claim)
        assertEquals("complete", claim!!.status)
        assertEquals(3, claim.criteria.size)
        assertEquals("verification", claim.criteria[0].type)
        assertEquals("./gradlew test", claim.criteria[0].command)
        assertEquals(listOf("docs/report.md"), claim.criteria[1].paths)
    }

    @Test
    fun `missing or invalid claim block returns null keeping legacy behavior`() {
        assertNull(parseSubagentClaim("纯文本结论，没有 claim 块。"))
        assertNull(parseSubagentClaim("```json\n{\"status\": \"妄言\"}\n```"))
        assertNull(parseSubagentClaim("```json\n不是 json\n```"))
    }

    @Test
    fun `last valid block retains unknown criterion types as unendorsed`() {
        val text = "```json\n{\"status\": \"failed\"}\n```\n中段\n```json\n{\"status\": \"Completed\", \"acceptance_criteria\": [{\"type\": \"unknown\"}, {\"type\": \"manual\", \"claim\": \"x\"}]}\n```"
        val claim = parseSubagentClaim(text)
        assertNotNull(claim)
        assertEquals("complete", claim!!.status)
        assertEquals(2, claim.criteria.size)
        assertEquals("unknown", claim.criteria[0].type)
    }

    @Test
    fun `complete with empty criteria is downgraded`() {
        val claim = parseSubagentClaim("```json\n{\"status\": \"complete\", \"summary\": \"done\"}\n```")!!
        val adjudication = adjudicateSubagentClaim(claim, SubagentHostReceipts(emptyList(), emptyList()))
        assertEquals("partial", adjudication.adjudicatedStatus)
        assertTrue(adjudication.downgraded)
    }

    @Test
    fun `unknown criterion is explicitly unendorsed`() {
        val claim = parseSubagentClaim("```json\n{\"status\": \"complete\", \"acceptance_criteria\": [{\"type\": \"mystery\", \"claim\": \"x\"}]}\n```")!!
        val adjudication = adjudicateSubagentClaim(claim, SubagentHostReceipts(emptyList(), emptyList()))
        assertFalse(adjudication.verdicts.single().backed)
        assertTrue(adjudication.verdicts.single().reason.contains("未知"))
        assertEquals("partial", adjudication.adjudicatedStatus)
    }

    // ---------- 凭据提取 ----------

    private fun transcript() = listOf(
        ToolCall("c1", 1L, HarnessTool.BASE, buildJsonObject { put("command", JsonPrimitive("cd app && ./gradlew test --tests Foo")) }, rawToolName = "base"),
        ToolResult("r1", 2L, "c1", success = true, output = "BUILD SUCCESSFUL"),
        ToolCall("c2", 3L, HarnessTool.BASE, buildJsonObject { put("command", JsonPrimitive("flaky check")) }, rawToolName = "base"),
        ToolResult("r2", 4L, "c2", success = false, output = "FAILED"),
        ToolCall("c3", 5L, HarnessTool.WRITE, buildJsonObject { put("path", JsonPrimitive("./docs/report.md")) }, rawToolName = "write"),
        ToolResult("r3", 6L, "c3", success = true, output = "ok"),
        ToolCall("c4", 7L, HarnessTool.WRITE, buildJsonObject { put("path", JsonPrimitive("docs/other.md")) }, rawToolName = "write"),
        ToolResult("r4", 8L, "c4", success = false, output = "disk full"),
    )

    @Test
    fun `receipts only count successful commands and writes`() {
        val receipts = extractSubagentReceipts(transcript())
        assertEquals(1, receipts.commands.size)
        assertTrue(receipts.commands.single().contains("gradlew test"))
        assertEquals(listOf("docs/report.md"), receipts.writtenPaths)
    }

    @Test
    fun `structured MCP arguments do not interrupt completion adjudication`() {
        val mcpCall = ToolCall("mcp", 9L, HarnessTool.MCP, buildJsonObject {
            put("command", buildJsonObject { put("operation", JsonPrimitive("query")) })
            put("path", JsonArray(listOf(JsonPrimitive("remote/path"))))
            put("destination", buildJsonObject { put("id", JsonPrimitive("remote")) })
        }, rawToolName = "mcp__demo__query")
        val messages = transcript() + listOf(
            mcpCall, ToolResult("mcp-result", 10L, mcpCall.id, success = true, output = "ok"),
        )
        val receipts = extractSubagentReceipts(messages)
        assertEquals(extractSubagentReceipts(transcript()), receipts)
        val claim = parseSubagentClaim(claimJson)!!.let { parsed ->
            parsed.copy(criteria = parsed.criteria.filter { it.type != "manual" })
        }
        assertEquals("complete", adjudicateSubagentClaim(claim, receipts).adjudicatedStatus)
    }

    @Test
    fun `malformed or null receipt fields are ignored without losing valid receipts`() {
        val invalidValues = listOf(JsonNull, buildJsonObject {}, JsonArray(emptyList()), JsonPrimitive(" "))
        for (value in invalidValues) {
            val messages = transcript() + listOf(
                ToolCall("base-invalid", 9L, HarnessTool.BASE, buildJsonObject { put("command", value) }),
                ToolResult("base-result", 10L, "base-invalid", success = true, output = "ok"),
                ToolCall("write-invalid", 11L, HarnessTool.WRITE, buildJsonObject { put("path", value) }),
                ToolResult("write-result", 12L, "write-invalid", success = true, output = "ok"),
                ToolCall("download-invalid", 13L, HarnessTool.DOWNLOAD, buildJsonObject { put("destination", value) }),
                ToolResult("download-result", 14L, "download-invalid", success = true, output = "ok"),
            )
            assertEquals(extractSubagentReceipts(transcript()), extractSubagentReceipts(messages))
        }
    }

    // ---------- 裁定 ----------

    @Test
    fun `manual criterion is never backed and forces complete downgrade`() {
        val claim = parseSubagentClaim(claimJson)!!
        val adjudication = adjudicateSubagentClaim(claim, extractSubagentReceipts(transcript()))
        assertTrue(adjudication.verdicts[0].backed)
        assertTrue(adjudication.verdicts[1].backed)
        // manual 永不被 host 背书；持有 unsatisfied 条目的 complete 整体降级为 partial
        assertFalse(adjudication.verdicts[2].backed)
        assertEquals("partial", adjudication.adjudicatedStatus)
        assertTrue(adjudication.downgraded)
    }

    @Test
    fun `complete with unbacked verification downgrades to partial`() {
        val text = "```json\n{\"status\": \"complete\", \"acceptance_criteria\": [{\"type\": \"verification\", \"claim\": \"跑了测试\", \"command\": \"./gradlew test\"}]}\n```"
        val claim = parseSubagentClaim(text)!!
        val adjudication = adjudicateSubagentClaim(claim, extractSubagentReceipts(transcript()))
        // transcript 里成功的是 "cd app && ./gradlew test --tests Foo"，包含 claimed 串 → 背书
        assertTrue(adjudication.verdicts.single().backed)
        // 换成 transcript 里没有的命令
        val other = parseSubagentClaim("```json\n{\"status\": \"complete\", \"acceptance_criteria\": [{\"type\": \"verification\", \"claim\": \"x\", \"command\": \"python audit.py\"}]}\n```")!!
        val downgraded = adjudicateSubagentClaim(other, extractSubagentReceipts(transcript()))
        assertEquals("partial", downgraded.adjudicatedStatus)
        assertTrue(downgraded.downgraded)
    }

    @Test
    fun `failed write path can not back a files criterion`() {
        val text = "```json\n{\"status\": \"complete\", \"acceptance_criteria\": [{\"type\": \"files\", \"claim\": \"改了 other\", \"paths\": [\"docs/other.md\"]}]}\n```"
        val claim = parseSubagentClaim(text)!!
        val adjudication = adjudicateSubagentClaim(claim, extractSubagentReceipts(transcript()))
        assertFalse(adjudication.verdicts.single().backed)
        assertEquals("partial", adjudication.adjudicatedStatus)
    }

    @Test
    fun `host never raises partial or failed`() {
        val text = "```json\n{\"status\": \"partial\", \"acceptance_criteria\": []}\n```"
        val claim = parseSubagentClaim(text)!!
        val adjudication = adjudicateSubagentClaim(claim, SubagentHostReceipts(emptyList(), emptyList()))
        assertEquals("partial", adjudication.adjudicatedStatus)
        assertFalse(adjudication.downgraded)
    }

    @Test
    fun `short substring commands are not loosely matched`() {
        val text = "```json\n{\"status\": \"complete\", \"acceptance_criteria\": [{\"type\": \"verification\", \"claim\": \"x\", \"command\": \"test\"}]}\n```"
        val claim = parseSubagentClaim(text)!!
        val adjudication = adjudicateSubagentClaim(claim, extractSubagentReceipts(transcript()))
        // "test" 是 "cd app && ./gradlew test --tests Foo" 的子串，但过短不可靠 → 不背书
        assertFalse(adjudication.verdicts.single().backed)
    }

    @Test
    fun `strip removes the claim block from the summary`() {
        val claim = parseSubagentClaim(claimJson)!!
        val stripped = stripSubagentClaimBlock(claimJson, claim)
        assertTrue(stripped.startsWith("结论正文在此。"))
        assertFalse(stripped.contains("acceptance_criteria"))
        assertFalse(stripped.contains("```"))
    }
}
