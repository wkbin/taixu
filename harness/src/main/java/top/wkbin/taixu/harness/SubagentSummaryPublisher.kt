package top.wkbin.taixu.harness

import kotlinx.coroutines.flow.first
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.core.security.SecretRedactor
import top.wkbin.taixu.harness.environment.SessionExecutionEnvironments
import top.wkbin.taixu.runtime.LinuxEnvironmentManager

/** Save reports through the parent world's files/artifacts, after the same redaction as tool output. */
class SubagentSummaryPublisher(
    private val localFiles: WorkspaceFileAccess,
    private val environments: SessionExecutionEnvironments? = null,
    private val redactor: SecretRedactor = SecretRedactor(),
    private val linuxEnvironment: LinuxEnvironmentManager? = null,
    private val preferences: AgentPreferences? = null,
) {
    internal suspend fun publish(outcomes: List<SubagentOrchestrator.SubagentExecutionOutcome>,
        sessionId: String, workspace: String): String {
        val environment = environments?.environment(sessionId, workspace)
        val files = environment?.files ?: if (workspace.isBlank()) localFiles else localFiles.withBase(workspace)
        val secretValues = top.wkbin.taixu.harness.environment.executionOutputSecrets(environments, linuxEnvironment, sessionId, workspace)
        val privacy = preferences?.environmentPrivacyMode?.first() ?: true
        val sanitized = outcomes.map { it.copy(summary = redactor.redact(it.summary, secretValues, privacyMode = privacy)) }
        return paginateSubagentSummary(sanitized, environment?.id?.workspace ?: workspace, files, environment?.artifacts)
    }
}
