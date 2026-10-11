package top.wkbin.taixu.harness.environment

import top.wkbin.taixu.harness.core.OwnedResource
import top.wkbin.taixu.harness.core.acquire
import top.wkbin.taixu.harness.core.SessionResourceScope
import top.wkbin.taixu.runtime.shell.LinuxSession

/** Closing a subprocess also revokes its cleanup registration, releasing buffers after MCP reconnect. */
internal class OwnedLinuxSession private constructor(private val resource: OwnedResource<LinuxSession>) :
    LinuxSession by resource.value {
    override suspend fun close() = resource.close()

    companion object {
        suspend fun open(resources: SessionResourceScope, open: suspend () -> LinuxSession): LinuxSession =
            OwnedLinuxSession(resources.acquire(open) { it.close() })
    }
}
