package top.wkbin.taixu.core.model

/**
 * Agent 运行中再次发送时的投递语义。
 *
 * 决定「当前这一轮还没跑完时，新输入怎么进去」：
 * - [QUEUE]：进持久化队列，等当前运行结束后作为下一轮启动（默认，也是历史行为）；
 * - [STEER]：作为修正指令入 steering 队列，在当前这批工具调用完成后、下一轮推理开始前注入。
 *
 * 两者都不打断当前轮的进行；想立刻中止当前轮请用取消运行。
 * 与 [ApprovalMode]、[RunMode] 正交：本模式只描述投递时机，不涉及授权范围与执行意图。
 */
enum class RunningSendMode(val id: String) {
    QUEUE("queue"),
    STEER("steer");

    companion object {
        /** 未知/空值回落到 [QUEUE]：默认行为保持不变，失败方向朝「不打断用户既有习惯」。 */
        fun fromId(id: String?): RunningSendMode = entries.firstOrNull { it.id == id } ?: QUEUE
    }
}
