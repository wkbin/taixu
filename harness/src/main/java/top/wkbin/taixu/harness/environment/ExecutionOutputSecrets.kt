package top.wkbin.taixu.harness.environment

import top.wkbin.taixu.runtime.LinuxEnvironmentManager

/** Bound worlds never consult the phone's active distribution to sanitize their output. */
internal suspend fun executionOutputSecrets(environments: SessionExecutionEnvironments?,
    legacyVariables: LinuxEnvironmentManager?, sessionId: String, workspace: String): Collection<String> {
    if (environments != null) return environments.environment(sessionId, workspace).outputSecrets()
    legacyVariables?.refreshIfNeeded()?.getOrThrow()
    return legacyVariables?.values?.value?.values.orEmpty()
}
