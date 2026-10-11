package top.wkbin.taixu.harness.core

/** Keeps every failure across coroutine stack recovery, which may copy ordinary exceptions. */
class ResourceCleanupException(failures: List<Throwable>) : IllegalStateException(failures.first().message, failures.first()) {
    val failures: List<Throwable> = failures.toList()
    init { this.failures.drop(1).forEach(::addSuppressed) }
}
