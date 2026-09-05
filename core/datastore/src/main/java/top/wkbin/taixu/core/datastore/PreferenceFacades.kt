package top.wkbin.taixu.core.datastore

import javax.inject.Inject
import javax.inject.Singleton

/** Narrow preference views keep consumers from depending on the complete settings schema. */
@Singleton
class AppearancePreferences @Inject constructor(private val store: SettingsDataStore) {
    val themeMode get() = store.themeMode
    val themeStyle get() = store.themeStyle
    val chengmingBackgroundUri get() = store.chengmingBackgroundUri
    val appFontScale get() = store.appFontScale
    val autoCheckUpdates get() = store.autoCheckUpdates
}

@Singleton
class TerminalPreferences @Inject constructor(private val store: SettingsDataStore) {
    val terminalFontSize get() = store.terminalFontSize
    val terminalColorScheme get() = store.terminalColorScheme
    val terminalHapticsEnabled get() = store.terminalHapticsEnabled
    suspend fun setTerminalFontSize(value: Int) = store.setTerminalFontSize(value)
}

@Singleton
class RuntimePreferences @Inject constructor(private val store: SettingsDataStore) {
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
    suspend fun setExecutionMode(value: top.wkbin.taixu.core.model.ExecutionMode) = store.setExecutionMode(value)
    suspend fun setPreferredExecutionMode(value: top.wkbin.taixu.core.model.ExecutionMode) = store.setPreferredExecutionMode(value)
    suspend fun setEffectiveExecutionMode(value: top.wkbin.taixu.core.model.ExecutionMode) = store.setEffectiveExecutionMode(value)
    suspend fun setExecutionModes(
        preferred: top.wkbin.taixu.core.model.ExecutionMode,
        effective: top.wkbin.taixu.core.model.ExecutionMode,
    ) = store.setExecutionModes(preferred, effective)
    suspend fun setQemuCompatibilityEnabled(value: Boolean) = store.setQemuCompatibilityEnabled(value)
    suspend fun setAdbWirelessPort(value: Int) = store.setAdbWirelessPort(value)
    suspend fun setAdbPairedOnce(value: Boolean) = store.setAdbPairedOnce(value)
    suspend fun setAdbNotificationEnabled(value: Boolean) = store.setAdbNotificationEnabled(value)
}

@Singleton
class WorkshopPreferences @Inject constructor(private val store: SettingsDataStore) {
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
@Singleton
class SshPreferences @Inject constructor(private val store: SettingsDataStore) {
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
@Singleton
class FtpPreferences @Inject constructor(private val store: SettingsDataStore) {
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
    val metadata: top.wkbin.taixu.core.model.EnvironmentVariable,
    val value: String,
)

@Singleton
class AgentPreferences @Inject constructor(private val store: SettingsDataStore) {
    val thinkingLanguage get() = store.thinkingLanguage
    val customSystemPromptEnabled get() = store.customSystemPromptEnabled
    val customSystemPrompt get() = store.customSystemPrompt
    val agentLoggingEnabled get() = store.agentLoggingEnabled
    val selectedDistribution get() = store.selectedDistribution
    val thinkingExpanded get() = store.thinkingExpanded
    val defaultReasoningDepth get() = store.defaultReasoningDepth
    val contextCompactionEnabled get() = store.contextCompactionEnabled
    val contextCompactionThreshold get() = store.contextCompactionThreshold
    val maxToolRounds get() = store.maxToolRounds
    val autoWorkspaceCwd get() = store.autoWorkspaceCwd
    val commandOutputCompressionEnabled get() = store.commandOutputCompressionEnabled
    val baseCommandTimeoutSeconds get() = store.baseCommandTimeoutSeconds
    val contextBudgetTokens get() = store.contextBudgetTokens
    val maxToolsPerRound get() = store.maxToolsPerRound
    val maxConsecutiveFailures get() = store.maxConsecutiveFailures
    val providerModel get() = store.providerModel
    val environmentPrivacyMode get() = store.environmentPrivacyMode
    suspend fun setThinkingExpanded(value: Boolean) = store.setThinkingExpanded(value)
    suspend fun setCommandOutputCompressionEnabled(value: Boolean) = store.setCommandOutputCompressionEnabled(value)
    suspend fun removeModelApiKey(secretRef: String) = store.removeModelApiKey(secretRef)
}

@Singleton
class OnboardingPreferences @Inject constructor(private val store: SettingsDataStore) {
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

@Singleton
class ToolPreferences @Inject constructor(private val store: SettingsDataStore) {
    fun toolAccessToken(distroId: String, toolId: String) = store.toolAccessToken(distroId, toolId)
    suspend fun setToolAccessToken(distroId: String, toolId: String, token: String?) =
        store.setToolAccessToken(distroId, toolId, token)
}

@Singleton
class BrowserPreferences @Inject constructor(private val store: SettingsDataStore) {
    fun defaultFamily() = store.browserDefaultFamily
    fun homeUrl() = store.browserHomeUrl
    fun coBrowsingEnabled() = store.browserCoBrowsingEnabled
    fun allowRemoteConnect() = store.browserAllowRemoteConnect
    fun allowEvalJs() = store.browserAllowEvalJs
    fun allowHooks() = store.browserAllowHooks
    fun allowCdp() = store.browserAllowCdp
    fun desktopUserAgent() = store.browserDesktopUserAgent
    fun maxCaptureBytes() = store.browserMaxCaptureBytes
    suspend fun setDefaultFamily(value: String) = store.setBrowserDefaultFamily(value)
    suspend fun setHomeUrl(value: String) = store.setBrowserHomeUrl(value)
    suspend fun setCoBrowsingEnabled(value: Boolean) = store.setBrowserCoBrowsingEnabled(value)
    suspend fun setAllowRemoteConnect(value: Boolean) = store.setBrowserAllowRemoteConnect(value)
    suspend fun setAllowEvalJs(value: Boolean) = store.setBrowserAllowEvalJs(value)
    suspend fun setAllowHooks(value: Boolean) = store.setBrowserAllowHooks(value)
    suspend fun setAllowCdp(value: Boolean) = store.setBrowserAllowCdp(value)
    suspend fun setDesktopUserAgent(value: Boolean) = store.setBrowserDesktopUserAgent(value)
    suspend fun setMaxCaptureBytes(value: Int) = store.setBrowserMaxCaptureBytes(value)
}
