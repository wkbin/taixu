package top.wkbin.taixu.runtime

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.wkbin.taixu.core.common.shell.ShellQuote
import top.wkbin.taixu.core.datastore.RuntimePreferences
import top.wkbin.taixu.core.model.EnvironmentVariable
import top.wkbin.taixu.runtime.shell.ShellCommand

private const val ENV_SNAPSHOT_BEGIN = "__TAIXU_ENV_SNAPSHOT_BEGIN__"
private const val ENV_SNAPSHOT_END = "__TAIXU_ENV_SNAPSHOT_END__"

/**
 * Reads and persists user-managed variables inside the active Linux distribution.
 *
 * The profile fragment is the source of truth. Android preferences intentionally do not keep a
 * second copy, so terminal sessions, agent commands, and the settings screen observe the same data.
 */
class LinuxEnvironmentManager(
    private val linuxRuntime: LinuxRuntime,
    private val runtimePreferences: RuntimePreferences,
) {
    private val mutex = Mutex()
    private val _variables = MutableStateFlow<List<EnvironmentVariable>>(emptyList())
    val variables: StateFlow<List<EnvironmentVariable>> = _variables.asStateFlow()

    private val _values = MutableStateFlow<Map<String, String>>(emptyMap())
    val values: StateFlow<Map<String, String>> = _values.asStateFlow()

    private val _effectiveEnvironment = MutableStateFlow<List<EffectiveEnvironmentVariable>>(emptyList())
    /** Environment visible to a fresh non-interactive command in the active distro. */
    val effectiveEnvironment: StateFlow<List<EffectiveEnvironmentVariable>> = _effectiveEnvironment.asStateFlow()

    private var loadedDistroId: String? = null

    suspend fun refresh(distroId: String = linuxRuntime.activeDistroId.value): Result<Unit> =
        mutex.withLock { refreshLocked(distroId) }

    suspend fun refreshIfNeeded(): Result<Unit> {
        val distroId = linuxRuntime.activeDistroId.value
        return mutex.withLock {
            if (loadedDistroId == distroId) Result.success(Unit) else refreshLocked(distroId)
        }
    }

    /** Refresh and snapshot under one lock so another world's refresh cannot replace these values. */
    suspend fun redactionSecrets(distroId: String): Collection<String> = mutex.withLock {
        if (loadedDistroId != distroId) refreshLocked(distroId).getOrThrow()
        _values.value.values.toList()
    }

    suspend fun add(
        key: String,
        value: String,
        note: String = "",
        distroId: String = linuxRuntime.activeDistroId.value,
    ): Result<Unit> = mutate(distroId) { records ->
        val normalized = normalizeKey(key)
        validateValue(value, allowEmpty = false)
        require(records.none { it.metadata.key == normalized }) { "环境变量 $normalized 已存在" }
        records + LinuxEnvironmentRecord(
            metadata = EnvironmentVariable(
                id = UUID.randomUUID().toString(),
                key = normalized,
                note = note.trim(),
                createdAt = System.currentTimeMillis(),
            ),
            value = value,
        )
    }

    suspend fun update(
        id: String,
        key: String,
        value: String?,
        note: String = "",
        distroId: String = linuxRuntime.activeDistroId.value,
    ): Result<Unit> = mutate(distroId) { records ->
        val normalized = normalizeKey(key)
        require(records.any { it.metadata.id == id }) { "环境变量不存在" }
        require(records.none { it.metadata.id != id && it.metadata.key == normalized }) {
            "环境变量 $normalized 已存在"
        }
        records.map { record ->
            if (record.metadata.id != id) {
                record
            } else {
                value?.takeIf(String::isNotEmpty)?.let { validateValue(it) }
                record.copy(
                    metadata = record.metadata.copy(key = normalized, note = note.trim()),
                    value = value?.takeIf(String::isNotEmpty) ?: record.value,
                )
            }
        }
    }

    suspend fun delete(
        id: String,
        distroId: String = linuxRuntime.activeDistroId.value,
    ): Result<Unit> = mutate(distroId) { records ->
        require(records.any { it.metadata.id == id }) { "环境变量不存在" }
        records.filterNot { it.metadata.id == id }
    }

    private suspend fun mutate(
        distroId: String,
        transform: (List<LinuxEnvironmentRecord>) -> List<LinuxEnvironmentRecord>,
    ): Result<Unit> = mutex.withLock {
        runCatching {
            val records = readRecords(distroId)
            val updated = transform(records)
            writeRecords(distroId, updated)
            publish(distroId, updated)
            _effectiveEnvironment.value = runCatching { readEffectiveEnvironment(distroId) }
                .getOrDefault(_effectiveEnvironment.value)
        }
    }

    private suspend fun refreshLocked(distroId: String): Result<Unit> = runCatching {
        if (loadedDistroId != distroId) {
            loadedDistroId = null
            _variables.value = emptyList()
            _values.value = emptyMap()
            _effectiveEnvironment.value = emptyList()
        }
        val records = readRecords(distroId)
        publish(distroId, records)
        // A broken optional probe must not hide otherwise readable user variables.
        _effectiveEnvironment.value = runCatching {
            readEffectiveEnvironment(distroId)
        }.getOrDefault(emptyList())
    }

    private suspend fun readRecords(distroId: String): List<LinuxEnvironmentRecord> {
        val result = linuxRuntime.execute(
            ShellCommand(
                "if [ ! -f '$PROFILE_PATH' ] && [ -f '$LEGACY_PROFILE_PATH' ]; then " +
                    "mv -f '$LEGACY_PROFILE_PATH' '$PROFILE_PATH'; fi; " +
                    "if [ -f '$PROFILE_PATH' ]; then cat '$PROFILE_PATH'; fi",
            ),
            distroId,
        )
        check(result.isSuccess) { result.stderr.trim().ifBlank { "读取 Linux 环境变量失败" } }
        val records = LinuxEnvironmentProfile.parse(result.stdout)
        if (records.isNotEmpty()) return records

        val legacyRecords = runtimePreferences.readLegacyEnvironmentVariables()
            .filter { it.value.isNotEmpty() }
            .map { LinuxEnvironmentRecord(it.metadata, it.value) }
        if (legacyRecords.isNotEmpty()) {
            writeRecords(distroId, legacyRecords)
            runtimePreferences.clearLegacyEnvironmentVariables()
            return legacyRecords
        }
        return emptyList()
    }

    private suspend fun writeRecords(distroId: String, records: List<LinuxEnvironmentRecord>) {
        val content = LinuxEnvironmentProfile.render(records)
        val command = buildString {
            append("umask 077 && mkdir -p '/etc/profile.d' && ")
            append("printf '%s' ")
            append(ShellQuote.of(content))
            append(" > '$TEMP_PROFILE_PATH' && ")
            append("chmod 600 '$TEMP_PROFILE_PATH' && ")
            append("mv -f '$TEMP_PROFILE_PATH' '$PROFILE_PATH' && ")
            append("rm -f '$LEGACY_PROFILE_PATH'")
        }
        val result = linuxRuntime.execute(ShellCommand(command), distroId)
        check(result.isSuccess) { result.stderr.trim().ifBlank { "写入 Linux 环境变量失败" } }
    }

    private suspend fun readEffectiveEnvironment(distroId: String): List<EffectiveEnvironmentVariable> {
        val result = linuxRuntime.execute(
            ShellCommand(
                "printf '%s\\n' '$ENV_SNAPSHOT_BEGIN'; env; printf '%s\\n' '$ENV_SNAPSHOT_END'",
            ),
            distroId,
        )
        if (!result.isSuccess) return emptyList()
        return LinuxEnvironmentSnapshot.parse(result.stdout)
    }

    private fun publish(distroId: String, records: List<LinuxEnvironmentRecord>) {
        loadedDistroId = distroId
        _variables.value = records.map(LinuxEnvironmentRecord::metadata).sortedBy(EnvironmentVariable::key)
        _values.value = records.associate { it.metadata.key to it.value }
    }

    private fun normalizeKey(key: String): String {
        val normalized = key.trim().uppercase()
        require(ENVIRONMENT_KEY.matches(normalized)) { "环境变量名称格式不正确" }
        require(!isReservedKey(normalized)) { "$normalized 是运行时保留变量，不能修改" }
        return normalized
    }

    private fun validateValue(value: String, allowEmpty: Boolean = true) {
        require(allowEmpty || value.isNotEmpty()) { "环境变量值不能为空" }
        require('\u0000' !in value && '\n' !in value && '\r' !in value) {
            "环境变量值不能包含换行或 NUL 字符"
        }
    }

    private fun isReservedKey(key: String): Boolean = key in RESERVED_KEYS ||
        key.startsWith("TAIXU_") || key.startsWith("ANDROID_")

    private companion object {
        const val PROFILE_PATH = "/etc/profile.d/zz-taixu-user-env.sh"
        const val LEGACY_PROFILE_PATH = "/etc/profile.d/99-taixu-user-env.sh"
        const val TEMP_PROFILE_PATH = "$PROFILE_PATH.tmp"
        val ENVIRONMENT_KEY = Regex("^[A-Z_][A-Z0-9_]*$")
        val RESERVED_KEYS = setOf(
            "HOME", "PATH", "TERM", "PS1", "DEBIAN_FRONTEND", "CI", "NONINTERACTIVE",
        )
    }
}

data class EffectiveEnvironmentVariable(
    val key: String,
    val value: String,
)

internal object LinuxEnvironmentSnapshot {
    private val environmentKey = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun parse(output: String): List<EffectiveEnvironmentVariable> {
        val body = output.substringAfter("$ENV_SNAPSHOT_BEGIN\n", "")
            .substringBefore("\n$ENV_SNAPSHOT_END", "")
        if (body.isBlank()) return emptyList()
        return body.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = line.substring(0, separator)
                if (!environmentKey.matches(key)) return@mapNotNull null
                EffectiveEnvironmentVariable(key, line.substring(separator + 1))
            }
            .distinctBy { it.key }
            .sortedBy { it.key }
            .toList()
    }
}

internal data class LinuxEnvironmentRecord(
    val metadata: EnvironmentVariable,
    val value: String,
)

/** Stable, shell-sourceable representation with base64 metadata for lossless parsing. */
internal object LinuxEnvironmentProfile {
    private const val HEADER = "# Managed by TaiXu. Changes made here are reflected in Settings."
    private const val RECORD_PREFIX = "# TAIXU_ENV_V1|"

    fun parse(content: String): List<LinuxEnvironmentRecord> = content.lineSequence()
        .filter { it.startsWith(RECORD_PREFIX) }
        .mapNotNull { line ->
            val fields = line.removePrefix(RECORD_PREFIX).split('|')
            if (fields.size != 5) return@mapNotNull null
            runCatching {
                val id = fields[0]
                val key = fields[1]
                require(id.isNotBlank() && key.matches(Regex("^[A-Z_][A-Z0-9_]*$")))
                LinuxEnvironmentRecord(
                    metadata = EnvironmentVariable(
                        id = id,
                        key = key,
                        note = decode(fields[3]),
                        createdAt = fields[4].toLongOrNull() ?: 0L,
                    ),
                    value = decode(fields[2]),
                )
            }.getOrNull()
        }
        .distinctBy { it.metadata.key }
        .toList()

    fun render(records: List<LinuxEnvironmentRecord>): String = buildString {
        appendLine(HEADER)
        records.sortedBy { it.metadata.key }.forEach { record ->
            append(RECORD_PREFIX)
            append(record.metadata.id)
            append('|')
            append(record.metadata.key)
            append('|')
            append(encode(record.value))
            append('|')
            append(encode(record.metadata.note))
            append('|')
            appendLine(record.metadata.createdAt)
            append("export ")
            append(record.metadata.key)
            append('=')
            appendLine(ShellQuote.of(record.value))
        }
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        .ifEmpty { "-" }

    private fun decode(value: String): String = if (value == "-") {
        ""
    } else {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }
}
