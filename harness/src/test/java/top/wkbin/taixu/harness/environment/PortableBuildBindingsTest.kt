package top.wkbin.taixu.harness.environment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import top.wkbin.taixu.core.database.*
import top.wkbin.taixu.harness.*
import top.wkbin.taixu.runtime.environment.*

class PortableBuildBindingsTest {
    private class Catalogue : BuildScriptRepository {
        val scripts = mutableMapOf("script" to BuildScriptEntity("script", "Build", projectType = "ANDROID",
            content = "#!/bin/sh\necho build", createdAt = 0, updatedAt = 0))
        val phoneBindings = mutableMapOf<String, String>()
        override fun observeScripts() = flowOf(scripts.values.toList())
        override fun observeBindings() = flowOf(emptyList<ProjectBuildScriptBindingEntity>())
        override suspend fun listScripts() = scripts.values.toList()
        override suspend fun findScript(id: String) = scripts[id]
        override suspend fun findBinding(projectName: String) = phoneBindings[projectName]?.let { ProjectBuildScriptBindingEntity(projectName, it, 0) }
        override suspend fun resolvedScript(projectName: String) = phoneBindings[projectName]?.let { scripts[it] }
        override suspend fun upsertScript(script: BuildScriptEntity) { scripts[script.id] = script }
        override suspend fun deleteScript(id: String) = scripts.remove(id) != null
        override suspend fun bind(projectName: String, scriptId: String) { phoneBindings[projectName] = scriptId }
        override suspend fun unbind(projectName: String) { phoneBindings.remove(projectName) }
        override suspend fun ensureBuiltinScripts(androidScript: String, flutterScript: String) {}
    }
    private fun args(action: String, id: String = "script") = Json.parseToJsonElement(
        """{"action":"$action","id":"$id"}"""
    ) as JsonObject
    private fun registry() = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, workspace, _ ->
        FakeWorld(ExecutionEnvironmentId("remote", "linux", workspace, owner))
    })

    @Test fun `remote template catalogue reads are available without a binding adapter`() = runBlocking {
        val registry = registry()
        val executor = BuildScriptToolExecutor(Catalogue(), registry)
        assertTrue(executor.execute(args("list"), "/workspace/p", "one").first)
        assertTrue(executor.execute(args("get"), "/workspace/p", "one").first)
        registry.closeSession("one")
    }
    @Test fun `remote bind and unbind never change a phone project with the same name`() = runBlocking {
        val catalogue = Catalogue().apply { phoneBindings["p"] = "phone-script" }
        val registry = registry()
        val world = registry.environment("one", "/workspace/p") as FakeWorld
        val remote = mutableMapOf<String, String>()
        world.buildBindings = object : ExecutionBuildBindings {
            override suspend fun bind(project: String, scriptId: String) { remote[project] = scriptId }
            override suspend fun unbind(project: String) { remote.remove(project) }
        }
        val backend = HarnessServiceToolBackend(buildScriptToolExecutor = BuildScriptToolExecutor(catalogue, registry))
        assertTrue(backend.execute(HarnessServiceRequest(HarnessTool.BUILD_SCRIPT, args("bind"), null, "one", "/workspace/p")).first)
        assertEquals("script", remote["p"])
        assertEquals("phone-script", catalogue.phoneBindings["p"])
        assertTrue(backend.execute(HarnessServiceRequest(HarnessTool.BUILD_SCRIPT, args("unbind"), null, "one", "/workspace/p")).first)
        assertTrue(remote.isEmpty())
        assertEquals("phone-script", catalogue.phoneBindings["p"])
        registry.closeSession("one")
    }
    @Test fun `missing remote bindings and unknown scripts fail without local fallback`() = runBlocking {
        val catalogue = Catalogue()
        val registry = registry()
        val executor = BuildScriptToolExecutor(catalogue, registry)
        assertFalse(executor.execute(args("bind"), "/workspace/p", "one").first)
        assertFalse(executor.execute(args("unbind"), "/workspace/p", "one").first)
        assertFalse(executor.execute(args("bind", "unknown"), "/workspace/p", "one").first)
        assertTrue(catalogue.phoneBindings.isEmpty())
        registry.closeSession("one")
    }
    @Test fun `local binding adapter preserves existing project keys`() = runBlocking {
        val catalogue = Catalogue()
        val executor = BuildScriptToolExecutor(catalogue)
        assertTrue(executor.execute(args("bind"), "/workspace/p").first)
        assertEquals("script", catalogue.phoneBindings["p"])
        assertTrue(executor.execute(args("unbind"), "/workspace/p").first)
        assertTrue(catalogue.phoneBindings.isEmpty())
    }
    @Test fun `binding cancellation propagates instead of becoming a tool failure`() = runBlocking {
        val registry = registry()
        val world = registry.environment("one", "/workspace/p") as FakeWorld
        world.buildBindings = object : ExecutionBuildBindings {
            override suspend fun bind(project: String, scriptId: String) { throw CancellationException("cancelled") }
            override suspend fun unbind(project: String) { throw CancellationException("cancelled") }
        }
        val executor = BuildScriptToolExecutor(Catalogue(), registry)
        assertTrue(runCatching { executor.execute(args("bind"), "/workspace/p", "one") }.exceptionOrNull() is CancellationException)
        registry.closeSession("one")
    }
}
