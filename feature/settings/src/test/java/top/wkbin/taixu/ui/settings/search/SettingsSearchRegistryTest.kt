package top.wkbin.taixu.ui.settings.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页全局功能选项搜索索引及深度子功能检索算法单元测试
 */
class SettingsSearchRegistryTest {

    @Test
    fun testAllItemsNotEmptyAndHaveValidTargets() {
        val allItems = SettingsSearchRegistry.allItems
        assertTrue("全局功能搜索条目应不少于60项，当前有 ${allItems.size} 项", allItems.size >= 60)

        val idSet = mutableSetOf<String>()
        allItems.forEach { item ->
            assertTrue("ID 不能为空", item.id.isNotBlank())
            assertTrue("ID 不能重复: ${item.id}", idSet.add(item.id))
            assertTrue("标题不能为空: ${item.id}", item.title.isNotBlank())
            assertTrue("副标题不能为空: ${item.id}", item.subtitle.isNotBlank())
            assertNotNull("Category 不能为空: ${item.id}", item.category)
            assertNotNull("Target 不能为空: ${item.id}", item.target)
            assertNotNull("Breadcrumb 必须配置: ${item.id}", item.breadcrumb)
        }
    }

    @Test
    fun testEmptyQueryReturnsAllItems() {
        val resultBlank = SettingsSearchRegistry.search("")
        assertEquals(SettingsSearchRegistry.allItems.size, resultBlank.size)

        val resultSpaces = SettingsSearchRegistry.search("   ")
        assertEquals(SettingsSearchRegistry.allItems.size, resultSpaces.size)
    }

    @Test
    fun testSearchByTitleExactAndPrefix() {
        val results = SettingsSearchRegistry.search("模型档案管理")
        assertFalse(results.isEmpty())
        assertEquals(SettingsSearchTarget.MODEL_PROFILES, results.first().target)

        val prefixResults = SettingsSearchRegistry.search("模型")
        assertTrue(prefixResults.any { it.target == SettingsSearchTarget.MODEL_PROFILES })
    }

    @Test
    fun testDeepSubfeatureSearch() {
        // 1. 深度子参数：思考流翻译
        val translateResults = SettingsSearchRegistry.search("翻译")
        assertTrue(translateResults.any { it.id == "agent_thinking_translate" })

        // 2. 深度子参数：32 进程上限 / 幽灵进程
        val phantomResults = SettingsSearchRegistry.search("32")
        assertTrue(phantomResults.any { it.id == "system_phantom" })

        // 3. 深度子参数：ndkPath / NDK 工具链
        val ndkResults = SettingsSearchRegistry.search("ndk")
        assertTrue(ndkResults.any { it.id == "workshop_ndk_path" || it.id == "workshop_env" })

        // 4. 深度子参数：网络代理 HTTP_PROXY
        val proxyResults = SettingsSearchRegistry.search("proxy")
        assertTrue(proxyResults.any { it.id == "linux_env_proxy" })

        // 5. 深度子参数：Monokai 配色
        val monokaiResults = SettingsSearchRegistry.search("monokai")
        assertTrue(monokaiResults.any { it.id == "appearance_terminal_colorscheme" })

        // 6. 深度子参数：GGUF 导入
        val ggufResults = SettingsSearchRegistry.search("gguf")
        assertTrue(ggufResults.any { it.id == "agent_local_gguf_import" || it.id == "agent_local_llm" })

        // 7. 深度子参数：只读挂载
        val roResults = SettingsSearchRegistry.search("只读")
        assertTrue(roResults.any { it.id == "linux_storage_readonly_toggle" })

        // 8. 深度子参数：连续失败熔断
        val circuitResults = SettingsSearchRegistry.search("熔断")
        assertTrue(circuitResults.any { it.id == "agent_failures_circuit" })
    }

    @Test
    fun testSearchByBreadcrumbPath() {
        // 按面包屑层级名称也能搜索到
        val results = SettingsSearchRegistry.search("执行参数")
        assertTrue(results.any { it.target == SettingsSearchTarget.AGENT_EXECUTION })

        val signingResults = SettingsSearchRegistry.search("APK 签名")
        assertTrue(signingResults.any { it.target == SettingsSearchTarget.WORKSHOP_SIGNING })
    }

    @Test
    fun testMultiWordSearch() {
        // 支持多词组合搜索
        val results = SettingsSearchRegistry.search("终端 字体")
        assertTrue(results.any { it.target == SettingsSearchTarget.TERMINAL_SETTINGS })

        val agentTimeout = SettingsSearchRegistry.search("超时 命令")
        assertTrue(agentTimeout.any { it.id == "agent_cmd_timeout" })
    }

    @Test
    fun testRunningSendModeKeywordsReachApprovalEntry() {
        for (query in listOf("steer", "引导", "排队", "运行中发送")) {
            assertTrue(query, SettingsSearchRegistry.search(query).any { it.id == "agent_approval_mode" })
        }
    }
}
