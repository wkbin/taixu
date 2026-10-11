package top.wkbin.taixu.runtime.environment

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Bound world and resource ownership follow nested dispatch without changing approval identity. */
class ExecutionEnvironmentContext(
    val environment: ExecutionEnvironment,
    val own: (suspend () -> Unit) -> AutoCloseable,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ExecutionEnvironmentContext>
}
