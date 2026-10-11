package top.wkbin.taixu.runtime.environment

import kotlinx.coroutines.currentCoroutineContext

/** Protect legacy local consumers that have not yet been converted to the environment port. */
internal suspend fun boundLocalDistribution(explicit: String?, fallback: String): String {
    val requested = explicit?.lowercase()?.trim()?.takeIf { it.isNotBlank() }
    val bound = currentCoroutineContext()[ExecutionEnvironmentContext]?.environment?.id
        ?: return requested ?: fallback
    require(bound.backend == "local-proot") { "当前执行环境不能调用手机本地 Linux 运行时" }
    require(requested == null || requested == bound.distribution) { "不能跨越绑定的发行版执行" }
    return bound.distribution
}
