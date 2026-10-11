package top.wkbin.taixu.di.harness

import org.koin.dsl.module
import top.wkbin.taixu.core.security.SecretRedactor
import top.wkbin.taixu.harness.AskUserToolBackend
import top.wkbin.taixu.harness.ContextMemoryToolBackend
import top.wkbin.taixu.harness.DownloadToolBackend
import top.wkbin.taixu.harness.HarnessServiceToolBackend
import top.wkbin.taixu.harness.HostCapabilityToolBackend
import top.wkbin.taixu.harness.HostToolRequest
import top.wkbin.taixu.harness.LinuxCommandToolBackend
import top.wkbin.taixu.harness.PromptAssetToolBackend
import top.wkbin.taixu.harness.ToolExecutor
import top.wkbin.taixu.harness.ToolExecutionRequest
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.WorkspaceFileAccess
import top.wkbin.taixu.harness.WorkspaceToolBackend
import top.wkbin.taixu.harness.WorkspaceMutationSnapshots
import top.wkbin.taixu.harness.core.ToolCheckpoints
import top.wkbin.taixu.harness.directory.CapabilityToolGateway

internal val toolBackendModule = module {
    single<top.wkbin.taixu.runtime.environment.ExecutionEnvironmentFactory> {
        top.wkbin.taixu.harness.environment.LocalExecutionEnvironmentFactory(get(), get(), get(), get(), get(), get())
    }
    single { top.wkbin.taixu.harness.environment.SessionExecutionEnvironments(get()) }
    single { ToolCheckpoints<ToolExecutionRequest, ToolResult>() }
    single { WorkspaceMutationSnapshots(store = get(), events = get()) }
    single {
        val files = get<WorkspaceFileAccess>()
        WorkspaceToolBackend(
            operationsFor = { workspace -> if (workspace.isNotBlank()) files.withBase(workspace) else files },
            snapshots = get(),
            environments = get(),
        )
    }
    // 领域能力后端：各域独立持有自己的运行时依赖，ToolExecutor 只做管道编排与路由。
    single {
        HostCapabilityToolBackend(
            privilegeManager = get(),
            androidAppManager = get(),
            androidAppRepository = get(),
            shizukuApis = get(),
            hostGuiController = get(),
            virtualDisplayCoordinator = get(),
            virtualScreenToolkit = get(),
            providerClient = get(),
            settingsDataStore = get(),
            phoneAgentServices = get(),
            embeddedAdbManager = get(),
            secretRedactor = get(),
            eventBus = get(),
        )
    }
    single {
        LinuxCommandToolBackend(
            linuxRuntime = get(),
            pathResolver = get(),
            settingsDataStore = get(),
            workflowSignals = get(),
            environments = get(),
        )
    }
    single {
        DownloadToolBackend(
            fileDownloader = get(),
            fileAccess = get(),
            mutationSnapshots = get(),
            environments = get(),
        )
    }
    single {
        ContextMemoryToolBackend(
            messageStore = get(),
            compactionManager = get(),
            sessionDao = get(),
            providerClient = get(),
        )
    }
    single {
        AskUserToolBackend(
            approvalPolicyEngine = get(),
            approvalRepository = get(),
        )
    }
    single {
        PromptAssetToolBackend(
            skillRepository = get(),
            promptRouter = get(),
        )
    }
    single {
        HarnessServiceToolBackend(
            contextExecutor = get(),
            subagentOrchestrator = get(),
            dualAgentCoordinator = get(),
            buildScriptToolExecutor = get(),
        )
    }
    // 能力统一网关：use_capability 分发 + codemode 脚本装配 + legacy mcp__ 直连回退。
    single {
        CapabilityToolGateway(
            mcpManager = get(),
            argRedactor = { value: String -> get<SecretRedactor>().redact(value) },
            hostExecutor = { args, operationId, sessionId, metadata ->
                get<HostCapabilityToolBackend>().execute(HostToolRequest(args, operationId, sessionId, metadata))
            },
        )
    }
    single<ToolExecutor> {
        ToolExecutor(
            fileAccess = get(),
            pathResolver = get(),
            approvalPolicyEngine = get(),
            secretRedactor = get(),
            hostToolBackend = get(),
            linuxCommandToolBackend = get(),
            downloadToolBackend = get(),
            contextMemoryToolBackend = get(),
            askUserToolBackend = get(),
            promptAssetToolBackend = get(),
            harnessServiceToolBackend = get(),
            capabilityToolGateway = get(),
            linuxEnvironmentManager = get(),
            approvalRepository = get(),
            sessionDao = get(),
            mcpManager = get(),
            eventBus = get(),
            checkpointStore = get(),
            sessionApprovalGrants = get(),
            settingsDataStore = get(),
            toolCheckpoints = get(),
            workspaceToolBackend = get(),
            environments = get(),
        )
    }

}
