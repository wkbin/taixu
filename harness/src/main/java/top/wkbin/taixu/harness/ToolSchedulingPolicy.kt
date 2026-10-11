package top.wkbin.taixu.harness

/** Only tools without shared-state mutations may overlap within a round. */
internal object ToolSchedulingPolicy {
    private val readOnlyTools = setOf(
        HarnessTool.READ,
        HarnessTool.HISTORY_SEARCH,
        HarnessTool.HISTORY_READ,
        HarnessTool.LOAD_RULE,
    )

    fun isParallelSafe(tool: HarnessTool): Boolean = tool in readOnlyTools

    fun needsMutationLock(tool: HarnessTool): Boolean =
        tool !in readOnlyTools && tool != HarnessTool.SUBAGENT

    // Memory/plan/scratchpad actions can mutate state; MCP and unknown capabilities are exclusive.
    // Subagents form a round barrier but coordinate their own write leases. Holding the parent
    // workspace lock for their entire batch would obstruct child execution and other sessions.
}
