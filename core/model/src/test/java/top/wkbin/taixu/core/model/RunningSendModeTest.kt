package top.wkbin.taixu.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RunningSendModeTest {

    @Test
    fun `id 映射可往返`() {
        RunningSendMode.entries.forEach { mode ->
            assertEquals(mode, RunningSendMode.fromId(mode.id))
        }
    }

    @Test
    fun `未知值回落到队列`() {
        assertEquals(RunningSendMode.QUEUE, RunningSendMode.fromId(null))
        assertEquals(RunningSendMode.QUEUE, RunningSendMode.fromId(""))
        assertEquals(RunningSendMode.QUEUE, RunningSendMode.fromId("steering"))
        assertEquals(RunningSendMode.QUEUE, RunningSendMode.fromId("next_run"))
    }

    @Test
    fun `默认模式是队列`() {
        assertEquals(RunningSendMode.QUEUE, RunningSendMode.fromId(null))
    }
}
