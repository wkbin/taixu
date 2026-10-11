package top.wkbin.taixu.core.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import top.wkbin.taixu.core.model.EnvironmentVariable
import top.wkbin.taixu.core.model.ExecutionMode
import top.wkbin.taixu.core.model.RunningSendMode

/** Narrow preference views keep consumers from depending on the complete settings schema. */
class AppearancePreferences(private val store: SettingsDataStore) {
    val themeMode get() = store.themeMode
    val themeStyle get() = store.themeStyle
    val dynamicColorEnabled get() = store.dynamicColorEnabled
    val chengmingBackgroundUri get() = store.chengmingBackgroundUri
    val appFontScale get() = store.appFontScale
    val autoCheckUpdates get() = store.autoCheckUpdates
    val developerMode get() = store.developerMode
    suspend fun setThemeMode(value: String) = store.setThemeMode(value)
    suspend fun setThemeStyle(value: String) = store.setThemeStyle(value)
    suspend fun setDynamicColorEnabled(value: Boolean) = store.setDynamicColorEnabled(value)
    suspend fun setChengmingBackgroundUri(value: String?) = store.setChengmingBackgroundUri(value)
    suspend fun setAppFontScale(value: Float) = store.setAppFontScale(value)
    suspend fun setAutoCheckUpdates(value: Boolean) = store.setAutoCheckUpdates(value)
    suspend fun setDeveloperMode(value: Boolean) = store.setDeveloperMode(value)
}

class TerminalPreferences(private val store: SettingsDataStore) {
    val terminalFontSize get() = store.terminalFontSize
    val terminalColorScheme get() = store.terminalColorScheme
    val terminalHapticsEnabled get() = store.terminalHapticsEnabled
    suspend fun setTerminalFontSize(value: Int) = store.setTerminalFontSize(value)
    suspend fun setTerminalColorScheme(value: String) = store.setTerminalColorScheme(value)
    suspend fun setTerminalHapticsEnabled(value: Boolean) = store.setTerminalHapticsEnabled(value)
}

class RuntimePreferences(private val store: SettingsDataStore) {
    val selectedDistribution get() = store.selectedDistribution
    val mirrorPolicy get() = store.mirrorPolicy
    val mountDownloadEnabled get() = store.mountDownloadEnabled
    val mountDocumentsEnabled get() = store.mountDocumentsEnabled
    val mountSharedStorageEnabled get() = store.mountSharedStorageEnabled
    val executionMode get() = store.executionMode
    val preferredExecutionMode get() = store.preferredExecutionMode
    val effectiveExecutionMode get() = store.effectiveExecutionMode
    val qemuCompatibilityEnabled get() = store.qemuCompatibilityEnabled
    val adbWirelessPort get() = store.adbWirelessPort
    val adbPairedOnce get() = store.adbPairedOnce
    val adbNotificationEnabled get() = store.adbNotificationEnabled
    suspend fun readLegacyEnvironmentVariables() = store.readLegacyEnvironmentVariables()
    suspend fun clearLegacyEnvironmentVariables() = store.clearLegacyEnvironmentVariables()
    suspend fun setSelectedDistribution(value: String) = store.setSelectedDistribution(value)
    suspend fun setMirrorPolicy(value: String) = store.setMirrorPolicy(value)
    suspend fun setExecutionMode(value: ExecutionMode) = store.setExecutionMode(value)
    suspend fun setPreferredExecutionMode(value: ExecutionMode) = store.setPreferredExecutionMode(value)
    suspend fun setEffectiveExecutionMode(value: ExecutionMode) = store.setEffectiveExecutionMode(value)
    suspend fun setExecutionModes(
        preferred: ExecutionMode,
        effective: ExecutionMode,
    ) = store.setExecutionModes(preferred, effective)
    suspend fun setQemuCompatibilityEnabled(value: Boolean) = store.setQemuCompatibilityEnabled(value)
    suspend fun setAdbWirelessPort(value: Int) = store.setAdbWirelessPort(value)
    suspend fun setAdbPairedOnce(value: Boolean) = store.setAdbPairedOnce(value)
    suspend fun setAdbNotificationEnabled(value: Boolean) = store.setAdbNotificationEnabled(value)
    suspend fun setMountDownloadEnabled(value: Boolean) = store.setMountDownloadEnabled(value)
    suspend fun setMountDocumentsEnabled(value: Boolean) = store.setMountDocumentsEnabled(value)
    suspend fun setMountSharedStorageEnabled(value: Boolean) = store.setMountSharedStorageEnabled(value)
}

class WorkshopPreferences(private val store: SettingsDataStore) {
    val androidSdkPath get() = store.workshopAndroidSdkPath
    val ndkPath get() = store.workshopNdkPath
    val flutterSdkPath get() = store.workshopFlutterSdkPath
    val javaPath get() = store.workshopJavaPath
    val gradlePath get() = store.workshopGradlePath
    val cmakePath get() = store.workshopCmakePath
    val ninjaPath get() = store.workshopNinjaPath
    val aapt2Path get() = store.workshopAapt2Path
    val gradleUserHome get() = store.workshopGradleUserHome
    val pubCache get() = store.workshopPubCache
    val toolDir get() = store.workshopToolDir
    val androidScript get() = store.workshopAndroidScript
    val flutterScript get() = store.workshopFlutterScript
    val keystores get() = store.workshopKeystores
    suspend fun setAndroidSdkPath(value: String) = store.setWorkshopAndroidSdkPath(value)
    suspend fun setNdkPath(value: String) = store.setWorkshopNdkPath(value)
    suspend fun setFlutterSdkPath(value: String) = store.setWorkshopFlutterSdkPath(value)
    suspend fun setJavaPath(value: String) = store.setWorkshopJavaPath(value)
    suspend fun setGradlePath(value: String) = store.setWorkshopGradlePath(value)
    suspend fun setCmakePath(value: String) = store.setWorkshopCmakePath(value)
    suspend fun setNinjaPath(value: String) = store.setWorkshopNinjaPath(value)
    suspend fun setAapt2Path(value: String) = store.setWorkshopAapt2Path(value)
    suspend fun setGradleUserHome(value: String) = store.setWorkshopGradleUserHome(value)
    suspend fun setPubCache(value: String) = store.setWorkshopPubCache(value)
    suspend fun setToolDir(value: String) = store.setWorkshopToolDir(value)
    suspend fun setAndroidScript(value: String) = store.setWorkshopAndroidScript(value)
    suspend fun setFlutterScript(value: String) = store.setWorkshopFlutterScript(value)
    suspend fun setKeystores(value: List<WorkshopKeystore>) = store.setWorkshopKeystores(value)
    suspend fun resetEnvironment() = store.resetWorkshopEnvironment()
    suspend fun resetScripts() = store.resetWorkshopScripts()
}

/** Per-distro SSH settings exposed only to the runtime service and its settings UI. */
class SshPreferences(private val store: SettingsDataStore) {
    fun enabled(distroId: String) = store.sshEnabled(distroId)
    fun port(distroId: String) = store.sshPort(distroId)
    fun authorizedKeys(distroId: String) = store.sshAuthorizedKeys(distroId)
    fun passwordAuthEnabled(distroId: String) = store.sshPasswordAuthEnabled(distroId)
    fun passwordConfigured(distroId: String) = store.sshPasswordConfigured(distroId)

    suspend fun setEnabled(distroId: String, enabled: Boolean) = store.setSshEnabled(distroId, enabled)
    suspend fun setPort(distroId: String, port: Int) = store.setSshPort(distroId, port)
    suspend fun setAuthorizedKeys(distroId: String, keys: String) = store.setSshAuthorizedKeys(distroId, keys)
    suspend fun setPasswordAuthEnabled(distroId: String, enabled: Boolean) = store.setSshPasswordAuthEnabled(distroId, enabled)
    suspend fun setPassword(distroId: String, password: String?) = store.setSshPassword(distroId, password)
    suspend fun readPassword(distroId: String) = store.readSshPassword(distroId)
}

/** Per-distro FTP settings exposed to the runtime FTP service and settings UI. */
class FtpPreferences(private val store: SettingsDataStore) {
    fun enabled(distroId: String) = store.ftpEnabled(distroId)
    fun port(distroId: String) = store.ftpPort(distroId)
    fun username(distroId: String) = store.ftpUsername(distroId)
    fun anonymousEnabled(distroId: String) = store.ftpAnonymousEnabled(distroId)
    fun readOnly(distroId: String) = store.ftpReadOnly(distroId)
    fun passwordConfigured(distroId: String) = store.ftpPasswordConfigured(distroId)

    suspend fun setEnabled(distroId: String, enabled: Boolean) = store.setFtpEnabled(distroId, enabled)
    suspend fun setPort(distroId: String, port: Int) = store.setFtpPort(distroId, port)
    suspend fun setUsername(distroId: String, username: String) = store.setFtpUsername(distroId, username)
    suspend fun setAnonymousEnabled(distroId: String, enabled: Boolean) = store.setFtpAnonymousEnabled(distroId, enabled)
    suspend fun setReadOnly(distroId: String, readOnly: Boolean) = store.setFtpReadOnly(distroId, readOnly)
    suspend fun setPassword(distroId: String, password: String?) = store.setFtpPassword(distroId, password)
    suspend fun readPassword(distroId: String) = store.readFtpPassword(distroId)
}

data class LegacyEnvironmentVariable(
    val metadata: EnvironmentVariable,
    val value: String,
)

class AgentPreferences(private val store: SettingsDataStore) {
    companion object {
        const val DEFAULT_BASE_COMMAND_TIMEOUT_SECONDS = SettingsDataStore.DEFAULT_BASE_COMMAND_TIMEOUT_SECONDS
    }

    /** 对话结束后自动建议沉淀/进化技能（默认开启） */
    val skillEvolutionSuggestions: Flow<Boolean> = store.skillEvolutionSuggestions

    suspend fun setSkillEvolutionSuggestions(enabled: Boolean) = store.setSkillEvolutionSuggestions(enabled)

    /** 已忽略或已应用的技能进化建议 id 集合流（持久化） */
    val dismissedSkillSuggestions: Flow<Set<String>> = store.dismissedSkillSuggestions

    suspend fun dismissSkillSuggestion(id: String) = store.dismissSkillSuggestion(id)

    val customSystemPromptEnabled get() = store.customSystemPromptEnabled
    val customSystemPrompt get() = store.customSystemPrompt
    val agentCharName get() = store.agentCharName
    val agentUserName get() = store.agentUserName
    val agentLoggingEnabled get() = store.agentLoggingEnabled
    val selectedDistribution get() = store.selectedDistribution
    val thinkingExpanded get() = store.thinkingExpanded
    val thinkingAutoTranslate get() = store.thinkingAutoTranslate
    val chatRoundCollapse get() = store.chatRoundCollapse
    suspend fun setChatRoundCollapse(value: Boolean) = store.setChatRoundCollapse(value)
    val runningSendMode get() = store.runningSendMode
    suspend fun setRunningSendMode(value: RunningSendMode) = store.setRunningSendMode(value)
    val defaultReasoningDepth get() = store.defaultReasoningDepth
    val contextCompactionEnabled get() = store.contextCompactionEnabled
    val maxToolRounds get() = store.maxToolRounds
    val roundLimitAutoContinuations get() = store.roundLimitAutoContinuations
    val autoWorkspaceCwd get() = store.autoWorkspaceCwd
    val commandOutputCompressionEnabled get() = store.commandOutputCompressionEnabled
    val baseCommandTimeoutSeconds get() = store.baseCommandTimeoutSeconds
    val contextBudgetTokens get() = store.contextBudgetTokens
    val contextFoldingRatioPercent get() = store.contextFoldingRatioPercent
    val maxToolsPerRound get() = store.maxToolsPerRound
    val maxConsecutiveFailures get() = store.maxConsecutiveFailures
    val providerModel get() = store.providerModel
    val phoneAgentConfig get() = store.phoneAgentConfig
    suspend fun setPhoneAgentConfig(value: PhoneAgentEndpoint) = store.setPhoneAgentConfig(value)
    val environmentPrivacyMode get() = store.environmentPrivacyMode
    val allPlugins get() = store.allPlugins
    val maxConcurrentAgentTurns get() = store.maxConcurrentAgentTurns
    val defaultMaxConcurrentAgentTurns get() = SettingsDataStore.DEFAULT_MAX_CONCURRENT_AGENT_TURNS
    val defaultRoundLimitAutoContinuations get() = SettingsDataStore.DEFAULT_ROUND_LIMIT_AUTO_CONTINUATIONS
    val defaultBaseCommandTimeoutSeconds get() = DEFAULT_BASE_COMMAND_TIMEOUT_SECONDS
    suspend fun setMaxConcurrentAgentTurns(value: Int) = store.setMaxConcurrentAgentTurns(value)
    suspend fun setThinkingExpanded(value: Boolean) = store.setThinkingExpanded(value)
    suspend fun setThinkingAutoTranslate(value: Boolean) = store.setThinkingAutoTranslate(value)
    suspend fun setCommandOutputCompressionEnabled(value: Boolean) = store.setCommandOutputCompressionEnabled(value)
    suspend fun removeModelApiKey(secretRef: String) = store.removeModelApiKey(secretRef)
    suspend fun setEnvironmentPrivacyMode(value: Boolean) = store.setEnvironmentPrivacyMode(value)
    suspend fun setCustomSystemPromptEnabled(value: Boolean) = store.setCustomSystemPromptEnabled(value)
    suspend fun setCustomSystemPrompt(value: String) = store.setCustomSystemPrompt(value)
    suspend fun setAgentCharName(value: String) = store.setAgentCharName(value)
    suspend fun setAgentUserName(value: String) = store.setAgentUserName(value)
    suspend fun setAgentLoggingEnabled(value: Boolean) = store.setAgentLoggingEnabled(value)
    suspend fun setDefaultReasoningDepth(value: String) = store.setDefaultReasoningDepth(value)
    suspend fun setContextCompactionEnabled(value: Boolean) = store.setContextCompactionEnabled(value)
    suspend fun setMaxToolRounds(value: Int) = store.setMaxToolRounds(value)
    suspend fun setRoundLimitAutoContinuations(value: Int) = store.setRoundLimitAutoContinuations(value)
    suspend fun setAutoWorkspaceCwd(value: Boolean) = store.setAutoWorkspaceCwd(value)
    suspend fun setBaseCommandTimeoutSeconds(value: Int) = store.setBaseCommandTimeoutSeconds(value)
    suspend fun setContextBudgetTokens(value: Int) = store.setContextBudgetTokens(value)
    suspend fun setContextFoldingRatioPercent(value: Int) = store.setContextFoldingRatioPercent(value)
    suspend fun setMaxToolsPerRound(value: Int) = store.setMaxToolsPerRound(value)
    suspend fun setMaxConsecutiveFailures(value: Int) = store.setMaxConsecutiveFailures(value)
    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean) = store.setPluginEnabled(pluginId, enabled)
}

class OnboardingPreferences(private val store: SettingsDataStore) {
    val onboardingCompleted get() = store.onboardingCompleted
    val selectedDistribution get() = store.selectedDistribution
    val mirrorPolicy get() = store.mirrorPolicy
    suspend fun setSelectedDistribution(value: String) = store.setSelectedDistribution(value)
    suspend fun setMirrorPolicy(value: String) = store.setMirrorPolicy(value)
    suspend fun setModelApiKey(secretRef: String, value: String) = store.setModelApiKey(secretRef, value)
    suspend fun setModelApiKeys(secretRef: String, values: List<String>) = store.setModelApiKeys(secretRef, values)
    suspend fun readModelApiKeys(secretRef: String): List<String> = store.readModelApiKeys(secretRef)
    suspend fun setOnboardingCompleted(value: Boolean) = store.setOnboardingCompleted(value)
}

class ToolPreferences(private val store: SettingsDataStore) {
    fun toolAccessToken(distroId: String, toolId: String) = store.toolAccessToken(distroId, toolId)
    suspend fun setToolAccessToken(distroId: String, toolId: String, token: String?) =
        store.setToolAccessToken(distroId, toolId, token)
}

/** 首次使用引导（插件中心 / 工作坊 / 多会话终端等页面级遮罩），设置页可整体清空重看。 */
class FirstUseGuidePreferences(private val store: SettingsDataStore) {
    val firstUseGuidesShown get() = store.firstUseGuidesShown
    suspend fun markFirstUseGuideShown(id: String) = store.markFirstUseGuideShown(id)
    suspend fun clearFirstUseGuides() = store.clearFirstUseGuides()
}

/** 插件仓库（Registry）签名清单配置，开发者页与工具中心使用。 */
class RegistryPreferences(private val store: SettingsDataStore) {
    val manifestUrl get() = store.registryManifestUrl
    val signatureUrl get() = store.registrySignatureUrl
    val publicKey get() = store.registryPublicKey
    suspend fun setRegistryConfig(manifestUrl: String, signatureUrl: String, publicKey: String) =
        store.setRegistryConfig(manifestUrl, signatureUrl, publicKey)
}

/** 应用级启动计数（数据统计页快照 / Application onCreate 递增）。 */
class AppStatsPreferences(private val store: SettingsDataStore) {
    val appLaunchCount get() = store.appLaunchCount
    suspend fun incrementLaunchCount() = store.incrementLaunchCount()
}

/** AI Provider 端点与密钥偏好（经 ProviderRepository 收口后供 Settings UI 与工具适配层使用）。 */
class ProviderPreferences(private val store: SettingsDataStore) {
    val provider get() = store.provider
    val baseUrl get() = store.providerBaseUrl
    val model get() = store.providerModel
    val apiKeyConfigured get() = store.apiKeyConfigured
    suspend fun setProvider(value: String) = store.setProvider(value)
    suspend fun setBaseUrl(value: String) = store.setProviderBaseUrl(value)
    suspend fun setModel(value: String) = store.setProviderModel(value)
    suspend fun setApiKey(value: String) = store.setApiKey(value)
    suspend fun readApiKey(): String? = store.readApiKey()
    suspend fun setModelApiKey(secretRef: String, value: String) = store.setModelApiKey(secretRef, value)
    suspend fun readModelApiKey(secretRef: String): String? = store.readModelApiKey(secretRef)
    suspend fun setModelApiKeys(secretRef: String, values: List<String>) = store.setModelApiKeys(secretRef, values)
    suspend fun readModelApiKeys(secretRef: String): List<String> = store.readModelApiKeys(secretRef)
    suspend fun removeModelApiKey(secretRef: String) = store.removeModelApiKey(secretRef)
}

class BrowserPreferences(private val store: SettingsDataStore) {
    fun defaultFamily() = store.browserDefaultFamily
    fun homeUrl() = store.browserHomeUrl
    fun coBrowsingEnabled() = store.browserCoBrowsingEnabled
    fun allowRemoteConnect() = store.browserAllowRemoteConnect
    fun allowEvalJs() = store.browserAllowEvalJs
    fun allowHooks() = store.browserAllowHooks
    fun allowCdp() = store.browserAllowCdp
    fun allowVConsole() = store.browserAllowVConsole
    fun desktopUserAgent() = store.browserDesktopUserAgent
    fun maxCaptureBytes() = store.browserMaxCaptureBytes
    suspend fun setDefaultFamily(value: String) = store.setBrowserDefaultFamily(value)
    suspend fun setHomeUrl(value: String) = store.setBrowserHomeUrl(value)
    suspend fun setCoBrowsingEnabled(value: Boolean) = store.setBrowserCoBrowsingEnabled(value)
    suspend fun setAllowRemoteConnect(value: Boolean) = store.setBrowserAllowRemoteConnect(value)
    suspend fun setAllowEvalJs(value: Boolean) = store.setBrowserAllowEvalJs(value)
    suspend fun setAllowHooks(value: Boolean) = store.setBrowserAllowHooks(value)
    suspend fun setAllowCdp(value: Boolean) = store.setBrowserAllowCdp(value)
    suspend fun setAllowVConsole(value: Boolean) = store.setBrowserAllowVConsole(value)
    suspend fun setDesktopUserAgent(value: Boolean) = store.setBrowserDesktopUserAgent(value)
    suspend fun setMaxCaptureBytes(value: Int) = store.setBrowserMaxCaptureBytes(value)
}

/** MCP 被控端（外部 AI 客户端通过 MCP 控制本 App）的偏好键。 */
internal object AgentServerPreferencesKeys {
    val Enabled = booleanPreferencesKey("agent_server_enabled")
    val Port = intPreferencesKey("agent_server_port")
    val AllowRemote = booleanPreferencesKey("agent_server_allow_remote")
    val Token = stringPreferencesKey("agent_server_token")
    val AllowWriteTools = booleanPreferencesKey("agent_server_allow_write_tools")
}

/**
 * MCP 被控端配置：设置页读写、[top.wkbin.taixu.harness.agent.AgentMcpBootstrap] 监听生效。
 *
 * - [token] 是**持久化** Bearer Token（与浏览器自环每次启动随机生成不同），外部客户端可长期复用；
 * - [allowWriteTools] 默认关闭，写入/执行层工具需用户显式开启。
 */
class AgentServerPreferences(private val store: SettingsDataStore) {
    companion object {
        /** 被控端默认端口；首选被占用时由 server 顺延尝试相邻端口。 */
        const val DEFAULT_PORT = 8890
    }

    val enabled: Flow<Boolean> = store.agentServerEnabled
    val port: Flow<Int> = store.agentServerPort
    val allowRemote: Flow<Boolean> = store.agentServerAllowRemote
    val token: Flow<String> = store.agentServerToken
    val allowWriteTools: Flow<Boolean> = store.agentServerAllowWriteTools

    suspend fun setEnabled(value: Boolean) = store.setAgentServerEnabled(value)
    suspend fun setPort(value: Int) = store.setAgentServerPort(value)
    suspend fun setAllowRemote(value: Boolean) = store.setAgentServerAllowRemote(value)
    suspend fun setToken(value: String) = store.setAgentServerToken(value)
    suspend fun setAllowWriteTools(value: Boolean) = store.setAgentServerAllowWriteTools(value)
}
