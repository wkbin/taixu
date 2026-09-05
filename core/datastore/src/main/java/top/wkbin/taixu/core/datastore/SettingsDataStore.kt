package top.wkbin.taixu.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import top.wkbin.taixu.core.security.SecretManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secretManager: SecretManager,
) {
    // 工坊 Android/Flutter 工具链覆盖配置。空值表示使用当前套件的标准路径。
    private val workshopAndroidSdkPathKey = stringPreferencesKey("workshop_android_sdk_path")
    private val workshopNdkPathKey = stringPreferencesKey("workshop_ndk_path")
    private val workshopFlutterSdkPathKey = stringPreferencesKey("workshop_flutter_sdk_path")
    private val workshopJavaPathKey = stringPreferencesKey("workshop_java_path")
    private val workshopGradlePathKey = stringPreferencesKey("workshop_gradle_path")
    private val workshopCmakePathKey = stringPreferencesKey("workshop_cmake_path")
    private val workshopNinjaPathKey = stringPreferencesKey("workshop_ninja_path")
    private val workshopAapt2PathKey = stringPreferencesKey("workshop_aapt2_path")
    private val workshopGradleUserHomeKey = stringPreferencesKey("workshop_gradle_user_home")
    private val workshopPubCacheKey = stringPreferencesKey("workshop_pub_cache")
    private val workshopToolDirKey = stringPreferencesKey("workshop_tool_dir")
    private val workshopAndroidScriptKey = stringPreferencesKey("workshop_android_script")
    private val workshopFlutterScriptKey = stringPreferencesKey("workshop_flutter_script")
    // 工坊 Android 签名（keystore）注册表；整表 JSON 密文存储，口令不落明文。
    private val workshopKeystoresKey = stringPreferencesKey("workshop_keystores_ciphertext")
    // ===== 内置浏览器偏好：数据源（datastore key 集中放在 BrowserPreferencesKeys） =====
    private val browserDefaultFamilyKey = BrowserPreferencesKeys.DefaultFamily
    private val browserHomeUrlKey = BrowserPreferencesKeys.HomeUrl
    private val browserCoBrowsingEnabledKey = BrowserPreferencesKeys.CoBrowsingEnabled
    private val browserAllowRemoteConnectKey = BrowserPreferencesKeys.AllowRemoteConnect
    private val browserAllowEvalJsKey = BrowserPreferencesKeys.AllowEvalJs
    private val browserAllowHooksKey = BrowserPreferencesKeys.AllowHooks
    private val browserAllowCdpKey = BrowserPreferencesKeys.AllowCdp
    private val browserDesktopUserAgentKey = BrowserPreferencesKeys.DesktopUserAgent
    private val browserMaxCaptureBytesKey = BrowserPreferencesKeys.MaxCaptureBytes

    val browserDefaultFamily: Flow<String> = context.settingsDataStore.data.map { it[browserDefaultFamilyKey].orEmpty() }
    suspend fun setBrowserDefaultFamily(value: String) { context.settingsDataStore.edit { it[browserDefaultFamilyKey] = value } }
    val browserHomeUrl: Flow<String> = context.settingsDataStore.data.map { it[browserHomeUrlKey].orEmpty() }
    suspend fun setBrowserHomeUrl(value: String) { context.settingsDataStore.edit { it[browserHomeUrlKey] = value } }
    val browserCoBrowsingEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[browserCoBrowsingEnabledKey] ?: true }
    suspend fun setBrowserCoBrowsingEnabled(value: Boolean) { context.settingsDataStore.edit { it[browserCoBrowsingEnabledKey] = value } }
    val browserAllowRemoteConnect: Flow<Boolean> = context.settingsDataStore.data.map { it[browserAllowRemoteConnectKey] ?: false }
    suspend fun setBrowserAllowRemoteConnect(value: Boolean) { context.settingsDataStore.edit { it[browserAllowRemoteConnectKey] = value } }
    val browserAllowEvalJs: Flow<Boolean> = context.settingsDataStore.data.map { it[browserAllowEvalJsKey] ?: false }
    suspend fun setBrowserAllowEvalJs(value: Boolean) { context.settingsDataStore.edit { it[browserAllowEvalJsKey] = value } }
    val browserAllowHooks: Flow<Boolean> = context.settingsDataStore.data.map { it[browserAllowHooksKey] ?: false }
    suspend fun setBrowserAllowHooks(value: Boolean) { context.settingsDataStore.edit { it[browserAllowHooksKey] = value } }
    val browserAllowCdp: Flow<Boolean> = context.settingsDataStore.data.map { it[browserAllowCdpKey] ?: false }
    suspend fun setBrowserAllowCdp(value: Boolean) { context.settingsDataStore.edit { it[browserAllowCdpKey] = value } }
    val browserDesktopUserAgent: Flow<Boolean> = context.settingsDataStore.data.map { it[browserDesktopUserAgentKey] ?: false }
    suspend fun setBrowserDesktopUserAgent(value: Boolean) { context.settingsDataStore.edit { it[browserDesktopUserAgentKey] = value } }
    val browserMaxCaptureBytes: Flow<Int> = context.settingsDataStore.data.map { it[browserMaxCaptureBytesKey] ?: (6 * 1024 * 1024) }
    suspend fun setBrowserMaxCaptureBytes(value: Int) { context.settingsDataStore.edit { it[browserMaxCaptureBytesKey] = value } }


    val workshopAndroidSdkPath: Flow<String> = context.settingsDataStore.data.map { it[workshopAndroidSdkPathKey].orEmpty() }
    val workshopNdkPath: Flow<String> = context.settingsDataStore.data.map { it[workshopNdkPathKey].orEmpty() }
    val workshopFlutterSdkPath: Flow<String> = context.settingsDataStore.data.map { it[workshopFlutterSdkPathKey].orEmpty() }
    val workshopJavaPath: Flow<String> = context.settingsDataStore.data.map { it[workshopJavaPathKey].orEmpty() }
    val workshopGradlePath: Flow<String> = context.settingsDataStore.data.map { it[workshopGradlePathKey].orEmpty() }
    val workshopCmakePath: Flow<String> = context.settingsDataStore.data.map { it[workshopCmakePathKey].orEmpty() }
    val workshopNinjaPath: Flow<String> = context.settingsDataStore.data.map { it[workshopNinjaPathKey].orEmpty() }
    val workshopAapt2Path: Flow<String> = context.settingsDataStore.data.map { it[workshopAapt2PathKey].orEmpty() }
    val workshopGradleUserHome: Flow<String> = context.settingsDataStore.data.map { it[workshopGradleUserHomeKey].orEmpty() }
    val workshopPubCache: Flow<String> = context.settingsDataStore.data.map { it[workshopPubCacheKey].orEmpty() }
    val workshopToolDir: Flow<String> = context.settingsDataStore.data.map { it[workshopToolDirKey].orEmpty() }
    val workshopAndroidScript: Flow<String> = context.settingsDataStore.data.map { it[workshopAndroidScriptKey].orEmpty() }
    val workshopFlutterScript: Flow<String> = context.settingsDataStore.data.map { it[workshopFlutterScriptKey].orEmpty() }

    suspend fun setWorkshopAndroidSdkPath(value: String) = setWorkshopValue(workshopAndroidSdkPathKey, value)
    suspend fun setWorkshopNdkPath(value: String) = setWorkshopValue(workshopNdkPathKey, value)
    suspend fun setWorkshopFlutterSdkPath(value: String) = setWorkshopValue(workshopFlutterSdkPathKey, value)
    suspend fun setWorkshopJavaPath(value: String) = setWorkshopValue(workshopJavaPathKey, value)
    suspend fun setWorkshopGradlePath(value: String) = setWorkshopValue(workshopGradlePathKey, value)
    suspend fun setWorkshopCmakePath(value: String) = setWorkshopValue(workshopCmakePathKey, value)
    suspend fun setWorkshopNinjaPath(value: String) = setWorkshopValue(workshopNinjaPathKey, value)
    suspend fun setWorkshopAapt2Path(value: String) = setWorkshopValue(workshopAapt2PathKey, value)
    suspend fun setWorkshopGradleUserHome(value: String) = setWorkshopValue(workshopGradleUserHomeKey, value)
    suspend fun setWorkshopPubCache(value: String) = setWorkshopValue(workshopPubCacheKey, value)
    suspend fun setWorkshopToolDir(value: String) = setWorkshopValue(workshopToolDirKey, value)
    suspend fun setWorkshopAndroidScript(value: String) = setWorkshopValue(workshopAndroidScriptKey, value)
    suspend fun setWorkshopFlutterScript(value: String) = setWorkshopValue(workshopFlutterScriptKey, value)

    /** 签名注册表（解密后的明文记录，仅本机内存中使用）。解密失败视为空表。 */
    val workshopKeystores: Flow<List<WorkshopKeystore>> = context.settingsDataStore.data.map { prefs ->
        val ciphertext = prefs[workshopKeystoresKey]
        if (ciphertext.isNullOrBlank()) {
            emptyList()
        } else {
            WorkshopKeystoreCodec.decode(secretManager.decrypt(ciphertext))
        }
    }

    suspend fun setWorkshopKeystores(value: List<WorkshopKeystore>) {
        context.settingsDataStore.edit { prefs ->
            if (value.isEmpty()) {
                prefs.remove(workshopKeystoresKey)
            } else {
                prefs[workshopKeystoresKey] = secretManager.encrypt(WorkshopKeystoreCodec.encode(value))
            }
        }
    }

    suspend fun resetWorkshopEnvironment() {
        context.settingsDataStore.edit {
            it.remove(workshopAndroidSdkPathKey)
            it.remove(workshopNdkPathKey)
            it.remove(workshopFlutterSdkPathKey)
            it.remove(workshopJavaPathKey)
            it.remove(workshopGradlePathKey)
            it.remove(workshopCmakePathKey)
            it.remove(workshopNinjaPathKey)
            it.remove(workshopAapt2PathKey)
            it.remove(workshopGradleUserHomeKey)
            it.remove(workshopPubCacheKey)
            it.remove(workshopToolDirKey)
        }
    }

    suspend fun resetWorkshopScripts() {
        context.settingsDataStore.edit {
            it.remove(workshopAndroidScriptKey)
            it.remove(workshopFlutterScriptKey)
        }
    }

    private suspend fun setWorkshopValue(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        context.settingsDataStore.edit { prefs ->
            if (value.isBlank()) prefs.remove(key) else prefs[key] = value
        }
    }
    private val developerModeKey = booleanPreferencesKey("developer_mode")
    private val qemuCompatibilityEnabledKey = booleanPreferencesKey("qemu_compatibility_enabled")
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val themeStyleKey = stringPreferencesKey("theme_style")
    private val chengmingBackgroundUriKey = stringPreferencesKey("chengming_background_uri")
    private val onboardingCompletedKey = booleanPreferencesKey("onboarding_completed")
    private val selectedDistributionKey = stringPreferencesKey("selected_distribution")
    private val mirrorPolicyKey = stringPreferencesKey("mirror_policy")
    private val providerKey = stringPreferencesKey("provider")
    private val providerBaseUrlKey = stringPreferencesKey("provider_base_url")
    private val providerModelKey = stringPreferencesKey("provider_model")
    private val apiKeyCiphertextKey = stringPreferencesKey("api_key_ciphertext")
    private val registryManifestUrlKey = stringPreferencesKey("registry_manifest_url")
    private val registrySignatureUrlKey = stringPreferencesKey("registry_signature_url")
    private val registryPublicKeyKey = stringPreferencesKey("registry_public_key")
    private val thinkingExpandedKey = booleanPreferencesKey("thinking_blocks_expanded")
    private val defaultReasoningDepthKey = stringPreferencesKey("agent_default_reasoning_depth")
    private val agentLoggingEnabledKey = booleanPreferencesKey("agent_local_logging_enabled")
    private val executionModeKey = stringPreferencesKey("execution_mode")
    private val preferredExecutionModeKey = stringPreferencesKey("preferred_execution_mode")
    private val thinkingLanguageKey = stringPreferencesKey("thinking_language")
    private val customSystemPromptEnabledKey = booleanPreferencesKey("custom_system_prompt_enabled")
    private val customSystemPromptKey = stringPreferencesKey("custom_system_prompt")
    private val legacyEnvironmentVariablesKey = stringPreferencesKey("environment_variables_json")
    private val environmentPrivacyModeKey = booleanPreferencesKey("environment_privacy_mode")
    private val environmentJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val environmentPrivacyMode: Flow<Boolean> = context.settingsDataStore.data.map { it[environmentPrivacyModeKey] ?: true }

    suspend fun setEnvironmentPrivacyMode(enabled: Boolean) {
        context.settingsDataStore.edit { it[environmentPrivacyModeKey] = enabled }
    }

    /** Upgrade-only reader. Runtime deletes this Android-side copy after Linux persistence succeeds. */
    suspend fun readLegacyEnvironmentVariables(): List<LegacyEnvironmentVariable> {
        val encoded = context.settingsDataStore.data.map { it[legacyEnvironmentVariablesKey] }.first().orEmpty()
        if (encoded.isBlank()) return emptyList()
        return runCatching {
            environmentJson.decodeFromString<List<StoredEnvironmentVariable>>(encoded)
        }.getOrDefault(emptyList()).map { record ->
            LegacyEnvironmentVariable(
                metadata = record.metadata,
                value = secretManager.decrypt(record.encryptedValue).orEmpty(),
            )
        }
    }

    suspend fun clearLegacyEnvironmentVariables() {
        context.settingsDataStore.edit { it.remove(legacyEnvironmentVariablesKey) }
    }

    @kotlinx.serialization.Serializable
    private data class StoredEnvironmentVariable(
        val metadata: top.wkbin.taixu.core.model.EnvironmentVariable,
        val encryptedValue: String,
    )


    val thinkingLanguage: Flow<String> = context.settingsDataStore.data.map { preferences ->
        preferences[thinkingLanguageKey] ?: "zh"
    }

    suspend fun setThinkingLanguage(lang: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[thinkingLanguageKey] = lang
        }
    }

    val customSystemPromptEnabled: Flow<Boolean> = context.settingsDataStore.data.map { preferences ->
        preferences[customSystemPromptEnabledKey] ?: false
    }

    suspend fun setCustomSystemPromptEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[customSystemPromptEnabledKey] = enabled
        }
    }

    val customSystemPrompt: Flow<String> = context.settingsDataStore.data.map { preferences ->
        preferences[customSystemPromptKey].orEmpty()
    }

    suspend fun setCustomSystemPrompt(prompt: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[customSystemPromptKey] = prompt
        }
    }

    /** 实际生效模式；高权限失效时可临时回落为 PRoot。 */
    val effectiveExecutionMode: Flow<top.wkbin.taixu.core.model.ExecutionMode> = context.settingsDataStore.data.map { preferences ->
        top.wkbin.taixu.core.model.ExecutionMode.fromId(preferences[executionModeKey] ?: top.wkbin.taixu.core.model.ExecutionMode.PROOT.id)
    }

    /** 用户最后一次成功选择的模式；缺少新键时从旧 execution_mode 无损迁移。 */
    val preferredExecutionMode: Flow<top.wkbin.taixu.core.model.ExecutionMode> = context.settingsDataStore.data.map { preferences ->
        top.wkbin.taixu.core.model.ExecutionMode.fromId(
            preferences[preferredExecutionModeKey]
                ?: preferences[executionModeKey]
                ?: top.wkbin.taixu.core.model.ExecutionMode.PROOT.id,
        )
    }

    /** 兼容旧消费者：executionMode 始终表示实际生效模式。 */
    val executionMode: Flow<top.wkbin.taixu.core.model.ExecutionMode> = effectiveExecutionMode

    suspend fun setExecutionMode(mode: top.wkbin.taixu.core.model.ExecutionMode) {
        context.settingsDataStore.edit { preferences ->
            preferences[executionModeKey] = mode.id
            preferences[preferredExecutionModeKey] = mode.id
        }
    }

    suspend fun setPreferredExecutionMode(mode: top.wkbin.taixu.core.model.ExecutionMode) {
        context.settingsDataStore.edit { it[preferredExecutionModeKey] = mode.id }
    }

    suspend fun setEffectiveExecutionMode(mode: top.wkbin.taixu.core.model.ExecutionMode) {
        context.settingsDataStore.edit { it[executionModeKey] = mode.id }
    }

    suspend fun setExecutionModes(
        preferred: top.wkbin.taixu.core.model.ExecutionMode,
        effective: top.wkbin.taixu.core.model.ExecutionMode,
    ) {
        context.settingsDataStore.edit {
            it[preferredExecutionModeKey] = preferred.id
            it[executionModeKey] = effective.id
        }
    }

    val agentLoggingEnabled: Flow<Boolean> = context.settingsDataStore.data.map { preferences ->
        preferences[agentLoggingEnabledKey] ?: false
    }

    suspend fun setAgentLoggingEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[agentLoggingEnabledKey] = enabled
        }
    }

    val themeMode: Flow<String> = context.settingsDataStore.data.map { preferences ->
        preferences[themeModeKey] ?: "system"
    }

    suspend fun setThemeMode(mode: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[themeModeKey] = mode
        }
    }

    /** 主题风格（玄同 / 澄明），默认玄同。 */
    val themeStyle: Flow<String> = context.settingsDataStore.data.map { preferences ->
        preferences[themeStyleKey] ?: "xuantong"
    }

    suspend fun setThemeStyle(style: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[themeStyleKey] = style
        }
    }

    val chengmingBackgroundUri: Flow<String?> = context.settingsDataStore.data.map { preferences ->
        preferences[chengmingBackgroundUriKey]
    }

    suspend fun setChengmingBackgroundUri(uri: String?) {
        context.settingsDataStore.edit { preferences ->
            if (uri.isNullOrBlank()) preferences.remove(chengmingBackgroundUriKey)
            else preferences[chengmingBackgroundUriKey] = uri
        }
    }

    // 终端外观与显示定制
    private val terminalFontSizeKey = androidx.datastore.preferences.core.intPreferencesKey("terminal_font_size")
    private val terminalColorSchemeKey = stringPreferencesKey("terminal_color_scheme")
    private val terminalHapticsEnabledKey = booleanPreferencesKey("terminal_haptics_enabled")
    private val appFontScaleKey = androidx.datastore.preferences.core.floatPreferencesKey("app_font_scale")

    val terminalFontSize: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[terminalFontSizeKey] ?: 13
    }

    suspend fun setTerminalFontSize(sizeSp: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[terminalFontSizeKey] = sizeSp.coerceIn(10, 24)
        }
    }

    val terminalColorScheme: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[terminalColorSchemeKey] ?: "obsidian"
    }

    suspend fun setTerminalColorScheme(scheme: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[terminalColorSchemeKey] = scheme
        }
    }

    val terminalHapticsEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[terminalHapticsEnabledKey] ?: true
    }

    suspend fun setTerminalHapticsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[terminalHapticsEnabledKey] = enabled
        }
    }

    val appFontScale: Flow<Float> = context.settingsDataStore.data.map { prefs ->
        (prefs[appFontScaleKey] ?: 1.0f).coerceIn(0.8f, 1.3f)
    }

    suspend fun setAppFontScale(scale: Float) {
        context.settingsDataStore.edit { prefs ->
            prefs[appFontScaleKey] = scale.coerceIn(0.8f, 1.3f)
        }
    }

    private val autoCheckUpdatesKey = booleanPreferencesKey("auto_check_updates")

    val autoCheckUpdates: Flow<Boolean> = context.settingsDataStore.data.map { preferences ->
        preferences[autoCheckUpdatesKey] ?: true
    }

    suspend fun setAutoCheckUpdates(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[autoCheckUpdatesKey] = enabled
        }
    }

    val developerMode: Flow<Boolean> = context.settingsDataStore.data.map { preferences ->
        preferences[developerModeKey] ?: false
    }

    /** Optional x86_64 compatibility mode; ARM64 remains the default. */
    val qemuCompatibilityEnabled: Flow<Boolean> = context.settingsDataStore.data.map { preferences ->
        preferences[qemuCompatibilityEnabledKey] ?: false
    }

    val onboardingCompleted: Flow<Boolean> = context.settingsDataStore.data.map { it[onboardingCompletedKey] ?: false }
    suspend fun setOnboardingCompleted(value: Boolean) { context.settingsDataStore.edit { it[onboardingCompletedKey] = value } }

    /** 首次使用引导统一登记：已展示过的引导 ID 集合（设置页「重看引导」可整体清空）。 */
    private val firstUseGuidesShownKey = stringSetPreferencesKey("first_use_guides_shown")

    val firstUseGuidesShown: Flow<Set<String>> = context.settingsDataStore.data.map {
        it[firstUseGuidesShownKey] ?: emptySet()
    }

    suspend fun markFirstUseGuideShown(id: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[firstUseGuidesShownKey] = (prefs[firstUseGuidesShownKey] ?: emptySet()) + id
        }
    }

    suspend fun clearFirstUseGuides() {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(firstUseGuidesShownKey)
        }
    }
    val selectedDistribution: Flow<String> = context.settingsDataStore.data.map { it[selectedDistributionKey] ?: "ubuntu" }
    suspend fun setSelectedDistribution(value: String) { context.settingsDataStore.edit { it[selectedDistributionKey] = value } }

    val mirrorPolicy: Flow<String> = context.settingsDataStore.data.map { it[mirrorPolicyKey] ?: "auto" }
    suspend fun setMirrorPolicy(value: String) { context.settingsDataStore.edit { it[mirrorPolicyKey] = value } }

    suspend fun setDeveloperMode(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[developerModeKey] = enabled
        }
    }

    suspend fun setQemuCompatibilityEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[qemuCompatibilityEnabledKey] = enabled
        }
    }

    val provider: Flow<String> = context.settingsDataStore.data.map { it[providerKey] ?: "OpenAI" }
    val apiKeyConfigured: Flow<Boolean> = context.settingsDataStore.data.map {
        !it[apiKeyCiphertextKey].isNullOrBlank()
    }

    suspend fun setProvider(value: String) {
        context.settingsDataStore.edit { it[providerKey] = value }
    }

    val providerBaseUrl: Flow<String> = context.settingsDataStore.data.map {
        it[providerBaseUrlKey].orEmpty()
    }

    suspend fun setProviderBaseUrl(value: String) {
        context.settingsDataStore.edit {
            if (value.isBlank()) it.remove(providerBaseUrlKey) else it[providerBaseUrlKey] = value.trim()
        }
    }

    val providerModel: Flow<String> = context.settingsDataStore.data.map {
        it[providerModelKey].orEmpty()
    }

    suspend fun setProviderModel(value: String) {
        context.settingsDataStore.edit {
            if (value.isBlank()) it.remove(providerModelKey) else it[providerModelKey] = value.trim()
        }
    }

    suspend fun setApiKey(value: String) {
        context.settingsDataStore.edit {
            if (value.isBlank()) it.remove(apiKeyCiphertextKey)
            else it[apiKeyCiphertextKey] = secretManager.encrypt(value.trim())
        }
    }

    suspend fun readApiKey(): String? = context.settingsDataStore.data
        .map { it[apiKeyCiphertextKey] }
        .first()
        ?.let(secretManager::decrypt)

    suspend fun setModelApiKey(secretRef: String, value: String) {
        setModelApiKeys(secretRef, listOf(value))
    }

    /**
     * 保存模型档案的 Key 池。整个列表作为一个加密载荷写入 DataStore，
     * 明文 Key 不进入 Room；重复项和空项会在加密前移除。
     */
    suspend fun setModelApiKeys(secretRef: String, values: List<String>) {
        require(secretRef.matches(Regex("[a-zA-Z0-9_-]{8,80}"))) { "Invalid secret reference" }
        val key = stringPreferencesKey("model_api_key_$secretRef")
        val normalized = values.map(String::trim).filter(String::isNotEmpty).distinct()
        context.settingsDataStore.edit {
            if (normalized.isEmpty()) {
                it.remove(key)
            } else {
                it[key] = secretManager.encrypt(environmentJson.encodeToString(normalized))
            }
        }
    }

    suspend fun readModelApiKey(secretRef: String): String? {
        return readModelApiKeys(secretRef).firstOrNull()
    }

    /** 兼容旧版本直接加密单个 Key 的载荷，并在读取时统一返回 Key 池。 */
    suspend fun readModelApiKeys(secretRef: String): List<String> {
        if (secretRef.isBlank()) return emptyList()
        val key = stringPreferencesKey("model_api_key_$secretRef")
        val plaintext = context.settingsDataStore.data.map { it[key] }.first()
            ?.let(secretManager::decrypt)
            ?.trim()
            .orEmpty()
        if (plaintext.isBlank()) return emptyList()
        return runCatching { environmentJson.decodeFromString<List<String>>(plaintext) }
            .getOrElse { listOf(plaintext) }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
    }

    suspend fun removeModelApiKey(secretRef: String) {
        if (secretRef.isBlank()) return
        context.settingsDataStore.edit { it.remove(stringPreferencesKey("model_api_key_$secretRef")) }
    }

    val registryManifestUrl: Flow<String> = context.settingsDataStore.data.map {
        it[registryManifestUrlKey].orEmpty()
    }
    val registrySignatureUrl: Flow<String> = context.settingsDataStore.data.map {
        it[registrySignatureUrlKey].orEmpty()
    }
    val registryPublicKey: Flow<String> = context.settingsDataStore.data.map {
        it[registryPublicKeyKey].orEmpty()
    }

    suspend fun setRegistryConfig(manifestUrl: String, signatureUrl: String, publicKey: String) {
        context.settingsDataStore.edit {
            it[registryManifestUrlKey] = manifestUrl.trim()
            it[registrySignatureUrlKey] = signatureUrl.trim()
            it[registryPublicKeyKey] = publicKey.trim()
        }
    }

    /** 聊天里“思考过程”块是否默认展开（记忆用户上次的选择）。 */
    val thinkingExpanded: Flow<Boolean> = context.settingsDataStore.data.map { it[thinkingExpandedKey] ?: false }

    suspend fun setThinkingExpanded(value: Boolean) {
        context.settingsDataStore.edit { it[thinkingExpandedKey] = value }
    }

    /** 全局推理深度：auto / disabled / low / medium / high（作用于未单独设置强度的模型）。 */
    val defaultReasoningDepth: Flow<String> = context.settingsDataStore.data.map { it[defaultReasoningDepthKey] ?: "auto" }
    suspend fun setDefaultReasoningDepth(value: String) {
        context.settingsDataStore.edit { it[defaultReasoningDepthKey] = value }
    }

    // ==================== Agent 智能体核心配置 ====================

    private val contextCompactionEnabledKey = booleanPreferencesKey("agent_context_compaction_enabled")
    private val contextCompactionThresholdKey = androidx.datastore.preferences.core.intPreferencesKey("agent_context_compaction_threshold")
    private val maxToolRoundsKey = androidx.datastore.preferences.core.intPreferencesKey("agent_max_tool_rounds")
    private val autoWorkspaceCwdKey = booleanPreferencesKey("agent_auto_workspace_cwd")
    private val commandOutputCompressionEnabledKey = booleanPreferencesKey("agent_command_output_compression_enabled")
    private val baseCommandTimeoutSecondsKey = androidx.datastore.preferences.core.intPreferencesKey("agent_base_command_timeout_seconds")

    val contextCompactionEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[contextCompactionEnabledKey] ?: true }
    suspend fun setContextCompactionEnabled(value: Boolean) { context.settingsDataStore.edit { it[contextCompactionEnabledKey] = value } }

    /** 触发上下文压缩的历史轮数阈值（默认 15 轮） */
    val contextCompactionThreshold: Flow<Int> = context.settingsDataStore.data.map { it[contextCompactionThresholdKey] ?: 15 }
    suspend fun setContextCompactionThreshold(value: Int) { context.settingsDataStore.edit { it[contextCompactionThresholdKey] = value.coerceIn(5, 50) } }

    /** 最大工具执行轮次（默认 100 轮） */
    val maxToolRounds: Flow<Int> = context.settingsDataStore.data.map { it[maxToolRoundsKey] ?: 100 }
    suspend fun setMaxToolRounds(value: Int) { context.settingsDataStore.edit { it[maxToolRoundsKey] = value.coerceIn(10, 300) } }

    /** 执行命令时是否自动注入工作区路径为 cwd */
    val autoWorkspaceCwd: Flow<Boolean> = context.settingsDataStore.data.map { it[autoWorkspaceCwdKey] ?: true }
    suspend fun setAutoWorkspaceCwd(value: Boolean) { context.settingsDataStore.edit { it[autoWorkspaceCwdKey] = value } }

    /** 是否使用内置 RTK 精简 Agent 的安全前台命令输出；默认开启。 */
    val commandOutputCompressionEnabled: Flow<Boolean> = context.settingsDataStore.data.map {
        it[commandOutputCompressionEnabledKey] ?: true
    }
    suspend fun setCommandOutputCompressionEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[commandOutputCompressionEnabledKey] = value }
    }

    /** Agent base 命令的默认前台等待时间；单次工具调用可在安全范围内覆盖。 */
    val baseCommandTimeoutSeconds: Flow<Int> = context.settingsDataStore.data.map {
        (it[baseCommandTimeoutSecondsKey] ?: DEFAULT_BASE_COMMAND_TIMEOUT_SECONDS)
            .coerceIn(MIN_BASE_COMMAND_TIMEOUT_SECONDS, MAX_BASE_COMMAND_TIMEOUT_SECONDS)
    }

    suspend fun setBaseCommandTimeoutSeconds(value: Int) {
        context.settingsDataStore.edit {
            it[baseCommandTimeoutSecondsKey] = value.coerceIn(
                MIN_BASE_COMMAND_TIMEOUT_SECONDS,
                MAX_BASE_COMMAND_TIMEOUT_SECONDS,
            )
        }
    }

    /**
     * 上下文 Token 预算（默认 128000）。当模型未单独配置 contextTokens 时作为兜底，
     * apiMessages 据此做滑动窗口裁剪，防止长会话撞上下文窗口上限。
     */
    private val contextBudgetTokensKey = androidx.datastore.preferences.core.intPreferencesKey("agent_context_budget_tokens")
    val contextBudgetTokens: Flow<Int> = context.settingsDataStore.data.map { it[contextBudgetTokensKey] ?: 128_000 }
    suspend fun setContextBudgetTokens(value: Int) { context.settingsDataStore.edit { it[contextBudgetTokensKey] = value.coerceIn(4_000, 2_000_000) } }

    /**
     * 单轮最多允许执行的工具调用数量（默认 12）。超过则本轮回填占位结果并提示模型，
     * 防止一次爆发大量工具调用耗尽上下文或陷入失控循环。
     */
    private val maxToolsPerRoundKey = androidx.datastore.preferences.core.intPreferencesKey("agent_max_tools_per_round")
    val maxToolsPerRound: Flow<Int> = context.settingsDataStore.data.map { it[maxToolsPerRoundKey] ?: 12 }
    suspend fun setMaxToolsPerRound(value: Int) { context.settingsDataStore.edit { it[maxToolsPerRoundKey] = value.coerceIn(1, 50) } }

    /**
     * 连续失败熔断阈值（默认 8）。当连续 N 轮工具调用全部失败时，主动终止循环并提示用户，
     * 避免模型在"调用→失败→再调用"中死循环空转。
     */
    private val maxConsecutiveFailuresKey = androidx.datastore.preferences.core.intPreferencesKey("agent_max_consecutive_failures")
    val maxConsecutiveFailures: Flow<Int> = context.settingsDataStore.data.map { it[maxConsecutiveFailuresKey] ?: 8 }
    suspend fun setMaxConsecutiveFailures(value: Int) { context.settingsDataStore.edit { it[maxConsecutiveFailuresKey] = value.coerceIn(1, 50) } }

    // ==================== 内置 ADB（宿主桥接插件） ====================

    private val adbWirelessPortKey = androidx.datastore.preferences.core.intPreferencesKey("adb_wireless_debug_port")
    private val adbPairedOnceKey = booleanPreferencesKey("adb_wireless_paired_once")

    /** 无线调试主端口（开发者选项里"无线调试"显示的 IP:PORT），0 表示未配置。 */
    val adbWirelessPort: Flow<Int> = context.settingsDataStore.data.map { it[adbWirelessPortKey] ?: 0 }
    suspend fun setAdbWirelessPort(port: Int) {
        context.settingsDataStore.edit { it[adbWirelessPortKey] = port.coerceIn(0, 65535) }
    }

    /** 是否完成过一次无线调试配对（用于启动引导与状态展示）。 */
    val adbPairedOnce: Flow<Boolean> = context.settingsDataStore.data.map { it[adbPairedOnceKey] ?: false }
    suspend fun setAdbPairedOnce(value: Boolean) {
        context.settingsDataStore.edit { it[adbPairedOnceKey] = value }
    }

    // ==================== Linux SSH 远程访问 ====================

    /** SSH 配置按发行版隔离，避免切换 RootFS 后复用错误的端口或授权密钥。 */
    fun sshEnabled(distroId: String): Flow<Boolean> = context.settingsDataStore.data.map {
        it[booleanPreferencesKey("ssh_${normalizedDistroId(distroId)}_enabled")] ?: false
    }

    suspend fun setSshEnabled(distroId: String, enabled: Boolean) {
        context.settingsDataStore.edit {
            it[booleanPreferencesKey("ssh_${normalizedDistroId(distroId)}_enabled")] = enabled
        }
    }

    fun sshPort(distroId: String): Flow<Int> = context.settingsDataStore.data.map {
        (it[intPreferencesKey("ssh_${normalizedDistroId(distroId)}_port")] ?: 8022)
            .coerceIn(1024, 65535)
    }

    suspend fun setSshPort(distroId: String, port: Int) {
        require(port in 1024..65535) { "SSH 端口必须在 1024..65535 之间" }
        context.settingsDataStore.edit {
            it[intPreferencesKey("ssh_${normalizedDistroId(distroId)}_port")] = port
        }
    }

    fun sshAuthorizedKeys(distroId: String): Flow<String> = context.settingsDataStore.data.map {
        it[stringPreferencesKey("ssh_${normalizedDistroId(distroId)}_authorized_keys")].orEmpty()
    }

    suspend fun setSshAuthorizedKeys(distroId: String, keys: String) {
        val preferenceKey = stringPreferencesKey("ssh_${normalizedDistroId(distroId)}_authorized_keys")
        context.settingsDataStore.edit {
            if (keys.isBlank()) it.remove(preferenceKey) else it[preferenceKey] = keys.trim()
        }
    }

    fun sshPasswordAuthEnabled(distroId: String): Flow<Boolean> = context.settingsDataStore.data.map {
        it[booleanPreferencesKey("ssh_${normalizedDistroId(distroId)}_password_auth_enabled")] ?: false
    }

    suspend fun setSshPasswordAuthEnabled(distroId: String, enabled: Boolean) {
        context.settingsDataStore.edit {
            it[booleanPreferencesKey("ssh_${normalizedDistroId(distroId)}_password_auth_enabled")] = enabled
        }
    }

    fun sshPasswordConfigured(distroId: String): Flow<Boolean> = context.settingsDataStore.data.map {
        !it[stringPreferencesKey("ssh_${normalizedDistroId(distroId)}_password")].isNullOrBlank()
    }

    suspend fun setSshPassword(distroId: String, password: String?) {
        val preferenceKey = stringPreferencesKey("ssh_${normalizedDistroId(distroId)}_password")
        context.settingsDataStore.edit {
            if (password == null) it.remove(preferenceKey) else it[preferenceKey] = encodeProtectedValue(password)
        }
    }

    suspend fun readSshPassword(distroId: String): String? = context.settingsDataStore.data
        .map { it[stringPreferencesKey("ssh_${normalizedDistroId(distroId)}_password")] }
        .first()
        ?.let(::decodeProtectedValue)

    // ==================== Linux FTP 远程访问 ====================

    /** FTP 配置按发行版隔离。 */
    fun ftpEnabled(distroId: String): Flow<Boolean> = context.settingsDataStore.data.map {
        it[booleanPreferencesKey("ftp_${normalizedDistroId(distroId)}_enabled")] ?: false
    }

    suspend fun setFtpEnabled(distroId: String, enabled: Boolean) {
        context.settingsDataStore.edit {
            it[booleanPreferencesKey("ftp_${normalizedDistroId(distroId)}_enabled")] = enabled
        }
    }

    fun ftpPort(distroId: String): Flow<Int> = context.settingsDataStore.data.map {
        (it[intPreferencesKey("ftp_${normalizedDistroId(distroId)}_port")] ?: 2121)
            .coerceIn(1024, 65535)
    }

    suspend fun setFtpPort(distroId: String, port: Int) {
        require(port in 1024..65535) { "FTP 端口必须在 1024..65535 之间" }
        context.settingsDataStore.edit {
            it[intPreferencesKey("ftp_${normalizedDistroId(distroId)}_port")] = port
        }
    }

    fun ftpUsername(distroId: String): Flow<String> = context.settingsDataStore.data.map {
        it[stringPreferencesKey("ftp_${normalizedDistroId(distroId)}_username")] ?: "root"
    }

    suspend fun setFtpUsername(distroId: String, username: String) {
        val normalized = username.trim().ifBlank { "root" }
        context.settingsDataStore.edit {
            it[stringPreferencesKey("ftp_${normalizedDistroId(distroId)}_username")] = normalized
        }
    }

    fun ftpAnonymousEnabled(distroId: String): Flow<Boolean> = context.settingsDataStore.data.map {
        it[booleanPreferencesKey("ftp_${normalizedDistroId(distroId)}_anonymous_enabled")] ?: false
    }

    suspend fun setFtpAnonymousEnabled(distroId: String, enabled: Boolean) {
        context.settingsDataStore.edit {
            it[booleanPreferencesKey("ftp_${normalizedDistroId(distroId)}_anonymous_enabled")] = enabled
        }
    }

    fun ftpReadOnly(distroId: String): Flow<Boolean> = context.settingsDataStore.data.map {
        it[booleanPreferencesKey("ftp_${normalizedDistroId(distroId)}_read_only")] ?: false
    }

    suspend fun setFtpReadOnly(distroId: String, readOnly: Boolean) {
        context.settingsDataStore.edit {
            it[booleanPreferencesKey("ftp_${normalizedDistroId(distroId)}_read_only")] = readOnly
        }
    }

    fun ftpPasswordConfigured(distroId: String): Flow<Boolean> = context.settingsDataStore.data.map {
        !it[stringPreferencesKey("ftp_${normalizedDistroId(distroId)}_password")].isNullOrBlank()
    }

    suspend fun setFtpPassword(distroId: String, password: String?) {
        val preferenceKey = stringPreferencesKey("ftp_${normalizedDistroId(distroId)}_password")
        context.settingsDataStore.edit {
            if (password == null) it.remove(preferenceKey) else it[preferenceKey] = encodeProtectedValue(password)
        }
    }

    suspend fun readFtpPassword(distroId: String): String? = context.settingsDataStore.data
        .map { it[stringPreferencesKey("ftp_${normalizedDistroId(distroId)}_password")] }
        .first()
        ?.let(::decodeProtectedValue)

    /** 同步读取插件启用状态（供运行时启停判断）。 */
    suspend fun isPluginEnabled(pluginId: String): Boolean = allPlugins.first().any { it.id == pluginId && it.isEnabled }

    /** 插件启用状态的响应式流。 */
    fun isPluginEnabledFlow(pluginId: String): Flow<Boolean> = allPlugins.map { list -> list.any { it.id == pluginId && it.isEnabled } }

    /** 获取所有 Plugin（预置），根据用户启用状态计算 isEnabled */
    private val enabledPluginsKey = stringSetPreferencesKey("agent_enabled_plugin_ids")

    val allPlugins: Flow<List<top.wkbin.taixu.core.model.AgentPlugin>> = context.settingsDataStore.data.map { prefs ->
        val defaults = top.wkbin.taixu.core.model.BuiltinPlugins.presets
            .filter { it.isEnabled }
            .mapTo(mutableSetOf()) { it.id }
        val enabledIds = prefs[enabledPluginsKey] ?: defaults
        top.wkbin.taixu.core.model.BuiltinPlugins.presets.map { plugin -> plugin.copy(isEnabled = plugin.id in enabledIds) }
    }

    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            val defaults = top.wkbin.taixu.core.model.BuiltinPlugins.presets
                .filter { it.isEnabled }
                .mapTo(mutableSetOf()) { it.id }
            val current = (prefs[enabledPluginsKey] ?: defaults).toMutableSet()
            if (enabled) current.add(pluginId) else current.remove(pluginId)
            prefs[enabledPluginsKey] = current
        }
    }

    // 存储挂载配置 (PRoot -b 挂载点)
    private val mountDownloadKey = booleanPreferencesKey("mount_download_enabled")
    private val mountDocumentsKey = booleanPreferencesKey("mount_documents_enabled")
    private val mountSharedStorageKey = booleanPreferencesKey("mount_shared_storage_enabled")

    val mountDownloadEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[mountDownloadKey] ?: true }
    suspend fun setMountDownloadEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[mountDownloadKey] = enabled }
    }

    val mountDocumentsEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[mountDocumentsKey] ?: true }
    suspend fun setMountDocumentsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[mountDocumentsKey] = enabled }
    }

    val mountSharedStorageEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[mountSharedStorageKey] ?: false }
    suspend fun setMountSharedStorageEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[mountSharedStorageKey] = enabled }
    }

    /** Persisted access token for generating shareable URLs to web-type tool services. */
    fun toolAccessToken(distroId: String, toolId: String): Flow<String?> = context.settingsDataStore.data.map { prefs ->
        prefs[stringPreferencesKey("tool_${distroId}_${toolId}_access_token")]?.let(::decodeProtectedValue)
    }

    suspend fun setToolAccessToken(distroId: String, toolId: String, token: String?) {
        val key = stringPreferencesKey("tool_${distroId}_${toolId}_access_token")
        context.settingsDataStore.edit { prefs ->
            if (token == null) {
                prefs.remove(key)
            } else {
                prefs[key] = encodeProtectedValue(token)
            }
        }
    }

    private val appLaunchCountKey = intPreferencesKey("app_launch_count")
    val appLaunchCount: Flow<Int> = context.settingsDataStore.data.map { it[appLaunchCountKey] ?: 1 }

    suspend fun incrementLaunchCount() {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[appLaunchCountKey] ?: 0
            prefs[appLaunchCountKey] = current + 1
        }
    }

    private fun encodeProtectedValue(value: String): String = PROTECTED_VALUE_PREFIX + secretManager.encrypt(value)

    /** 兼容升级前的明文值；下一次保存时会自动转为 Keystore 密文。 */
    private fun decodeProtectedValue(value: String): String? =
        if (value.startsWith(PROTECTED_VALUE_PREFIX)) {
            secretManager.decrypt(value.removePrefix(PROTECTED_VALUE_PREFIX))
        } else {
            value
        }

    private fun normalizedDistroId(value: String): String {
        val normalized = value.lowercase().trim()
        require(normalized.matches(Regex("[a-z0-9][a-z0-9_-]{0,31}"))) { "Linux 发行版 ID 无效" }
        return normalized
    }

    companion object {
        private const val PROTECTED_VALUE_PREFIX = "enc:v1:"
        const val DEFAULT_BASE_COMMAND_TIMEOUT_SECONDS = 10 * 60
        const val MIN_BASE_COMMAND_TIMEOUT_SECONDS = 60
        const val MAX_BASE_COMMAND_TIMEOUT_SECONDS = 60 * 60
    }
}
