package top.wkbin.taixu.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 消息反馈服务（DSH message-feedback 借鉴项）：目标校验（仅活动分支上的非空
 * assistant 消息）/ last-write-wins upsert / 会话清理。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageFeedbackServiceTest {

    private lateinit var database: AppDatabase
    private lateinit var service: MessageFeedbackService

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        service = MessageFeedbackService(
            AgentMessageFeedbackRepository(database.agentMessageFeedbackDao()),
            RoomHarnessRuntimeRepository(database.harnessRuntimeDao()),
        )
    }

    @After fun tearDown() { database.close() }

    private suspend fun seedAssistant(sessionId: String, messageId: String, text: String = "回答内容") {
        val dao = database.harnessRuntimeDao()
        val assistantEntry = HarnessEntryEntity(
            id = messageId, sessionId = sessionId, parentId = null, createdAt = 1,
            entryType = "message", customType = "assistant",
            payloadJson = """{"type":"assistant","id":"$messageId","createdAt":1,"text":"$text"}""",
        )
        dao.insertEntry(assistantEntry)
        // 模拟 appendToLane 的完整效果：lane 游标推进到新叶子（校验读取依赖 leafId）
        dao.upsertLane(HarnessLaneEntity(sessionId = sessionId, name = "main", leafId = messageId, updatedAt = 1))
    }

    @Test
    fun `set writes feedback for an assistant message in the active branch`() = runBlocking {
        seedAssistant("s", "m1")
        val saved = service.set("s", "m1", "up", note = " 讲得很清楚 ")
        assertEquals("fb_m1", saved.id)
        assertEquals("up", saved.rating)
        assertEquals("讲得很清楚", saved.note) // trim + 空白规整
        assertEquals(listOf("m1"), service.listForSession("s").map { it.messageId })
    }

    @Test
    fun `set rejects non assistant or missing messages`() = runBlocking {
        // 会话为空
        assertThrows(IllegalArgumentException::class.java) { runBlocking { service.set("s", "ghost", "up", null) } }
        // 用户消息不是合法目标
        val dao = database.harnessRuntimeDao()
        dao.upsertLane(HarnessLaneEntity(sessionId = "s2", name = "main", leafId = null, updatedAt = 1))
        dao.insertEntry(HarnessEntryEntity(
            id = "u1", sessionId = "s2", parentId = null, createdAt = 1,
            entryType = "message", customType = "user",
            payloadJson = """{"type":"user","id":"u1","createdAt":1,"text":"问"}""",
        ))
        dao.upsertLane(HarnessLaneEntity(sessionId = "s2", name = "main", leafId = "u1", updatedAt = 1))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { service.set("s2", "u1", "down", null) } }
        assertTrue(service.listForSession("s2").isEmpty())
    }

    @Test
    fun `set rejects invalid rating`() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { service.set("s", "m1", "meh", null) } }
    }

    @Test
    fun `rewrite is last write wins keeping first createdAt`() = runBlocking {
        seedAssistant("s", "m1")
        val first = service.set("s", "m1", "up", null)
        val second = service.set("s", "m1", "down", "改成不靠谱了")
        assertEquals(first.createdAt, second.createdAt)
        assertEquals("down", second.rating)
        assertEquals(1, service.listForSession("s").size)
        // 空白备注等价于清除
        val third = service.set("s", "m1", "up", "   ")
        assertNull(third.note)
    }

    @Test
    fun `deleteForSession clears feedback`() = runBlocking {
        seedAssistant("s", "m1")
        service.set("s", "m1", "up", null)
        service.deleteForSession("s")
        assertTrue(service.listForSession("s").isEmpty())
    }

    @Test
    fun `find returns saved feedback for prefill and null after cleanup`() = runBlocking {
        seedAssistant("s", "m1")
        assertNull(service.find("m1"))
        service.set("s", "m1", "down", "结论不对")
        assertEquals("down", service.find("m1")?.rating)
        assertEquals("结论不对", service.find("m1")?.note)
        service.deleteForSession("s")
        assertNull(service.find("m1"))
    }

    @Test
    fun `observeRecent lists newest feedback first across sessions with limit`() = runBlocking {
        val dao = database.agentMessageFeedbackDao()
        dao.upsert(feedback("old", "s1", updatedAt = 100))
        dao.upsert(feedback("new", "s2", updatedAt = 200))
        assertEquals(listOf("new", "old"), service.observeRecent(limit = 10).first().map { it.messageId })
        assertEquals(listOf("new"), service.observeRecent(limit = 1).first().map { it.messageId })
    }

    private fun feedback(messageId: String, sessionId: String, updatedAt: Long) = AgentMessageFeedbackEntity(
        id = AgentMessageFeedbackEntity.idOf(messageId),
        sessionId = sessionId,
        messageId = messageId,
        rating = AgentMessageFeedbackEntity.RATING_UP,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )
}
