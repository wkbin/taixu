package top.wkbin.taixu.harness.queue

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.common.logging.SensitiveDataRedactor
import top.wkbin.taixu.core.database.AppDatabase
import top.wkbin.taixu.core.database.HarnessRuntimeRepository
import top.wkbin.taixu.core.database.RoomHarnessRuntimeRepository
import top.wkbin.taixu.harness.PendingMessage
import top.wkbin.taixu.harness.core.AgentTurnRunner
import top.wkbin.taixu.harness.core.ProviderTurnResult
import top.wkbin.taixu.harness.core.TurnOutcome
import top.wkbin.taixu.harness.core.TurnResponse
import top.wkbin.taixu.harness.session.SessionTreeStore

/**
 * 引导消息在文本轮结束时必须注入并让循环继续；轮次耗尽或失败后残留的引导按提交顺序成为下一轮。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SteeringContinuationTest {
    private data class Response(
        override val toolCalls: List<String> = emptyList(),
        override val failureMessage: String? = null,
    ) : TurnResponse<String>

    private lateinit var database: AppDatabase
    private lateinit var repository: HarnessRuntimeRepository
    private lateinit var queues: PromptQueueManager
    private val runner = AgentTurnRunner()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomHarnessRuntimeRepository(database.harnessRuntimeDao())
        queues = PromptQueueManager(
            repository,
            Json,
            SessionTreeStore(repository, Json, AppLogger(context, SensitiveDataRedactor { it })),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun turn(
        sessionId: String,
        response: Response = Response(),
        rounds: Int = 4,
        provider: suspend () -> ProviderTurnResult<Response> = { ProviderTurnResult.Success(response) },
        execute: suspend (List<String>, Response) -> Boolean = { _, _ -> true },
    ): Pair<TurnOutcome, List<String>> {
        val injected = mutableListOf<String>()
        val outcome = runner.run(
            callProvider = provider,
            persistAssistant = {},
            consumeFollowUps = {
                val taken = queues.takeTextRoundInput(sessionId)
                injected += taken.followUps.map { it.text }
                injected += taken.steering.map { it.text }
                taken.continuationCount
            },
            enforceToolLimit = { calls, _ -> calls },
            executeTools = execute,
            remainingRounds = rounds,
        )
        return outcome to injected
    }

    @Test
    fun `steer submitted during a final text only round is injected and the loop continues`() = runBlocking {
        val sessionId = "s-text"
        queues.enqueue(sessionId, PromptQueue.STEER, PendingMessage("先改标题", createdAt = 1L))
        queues.enqueue(sessionId, PromptQueue.STEER, PendingMessage("再补测试", createdAt = 2L))

        val (outcome, injected) = turn(sessionId)

        assertEquals(TurnOutcome.Continue(0, toolsHadSuccess = true, followUpCount = 2), outcome)
        assertEquals(listOf("先改标题", "再补测试"), injected)
        assertTrue(queues.list(sessionId, PromptQueue.STEER).isEmpty())
    }

    @Test
    fun `steer left after round limit becomes the next run in submission order`() = runBlocking {
        val sessionId = "s-limit"
        queues.enqueue(sessionId, PromptQueue.STEER, PendingMessage("第二条", createdAt = 20L))
        queues.enqueue(sessionId, PromptQueue.STEER, PendingMessage("第一条", createdAt = 10L))
        queues.enqueue(sessionId, PromptQueue.STEER, PendingMessage("第三条", createdAt = 30L))

        val (outcome, injected) = turn(
            sessionId,
            response = Response(toolCalls = listOf("read")),
            rounds = 1,
            execute = { _, _ -> true },
        )

        assertTrue(outcome is TurnOutcome.RoundLimit)
        assertTrue(injected.isEmpty())
        assertEquals("第一条", queues.nextRunOrPromotedSteer(sessionId)?.second?.text)
        assertEquals(
            listOf("第一条", "第二条", "第三条"),
            queues.list(sessionId, PromptQueue.NEXT_RUN).map { it.second.text },
        )
        assertTrue(queues.list(sessionId, PromptQueue.STEER).isEmpty())
    }

    @Test
    fun `steer left after failure becomes the next run without passing an existing next run`() = runBlocking {
        val sessionId = "s-fail"
        queues.enqueue(sessionId, PromptQueue.STEER, PendingMessage("先修", createdAt = 1L))
        queues.enqueue(sessionId, PromptQueue.STEER, PendingMessage("再补", createdAt = 2L))

        val before = queues.list(sessionId, PromptQueue.STEER)
        val (failed, injected) = turn(sessionId, provider = { ProviderTurnResult.Failed("offline") })
        assertEquals(TurnOutcome.Failed("offline"), failed)
        assertTrue(injected.isEmpty())
        assertEquals(before, queues.list(sessionId, PromptQueue.STEER))
        assertEquals("先修", queues.nextRunOrPromotedSteer(sessionId)?.second?.text)
        assertEquals(before.map { it.first to it.second.text }, queues.list(sessionId, PromptQueue.NEXT_RUN).map { it.first to it.second.text })
        val blocked = "s-blocked"
        queues.enqueue(blocked, PromptQueue.NEXT_RUN, PendingMessage("已排队", createdAt = 1L))
        queues.enqueue(blocked, PromptQueue.STEER, PendingMessage("后来的修正", createdAt = 2L))
        assertEquals(0, queues.promoteSteeringToNextRun(blocked))
        assertEquals(listOf("已排队"), queues.list(blocked, PromptQueue.NEXT_RUN).map { it.second.text })
        assertEquals(listOf("后来的修正"), queues.list(blocked, PromptQueue.STEER).map { it.second.text })
    }

    @Test
    fun `restart recovery can see sessions that only have leftover steer`() = runBlocking {
        queues.enqueue("idle", PromptQueue.STEER, PendingMessage("残留", createdAt = 1L))
        queues.enqueue("busy-next", PromptQueue.NEXT_RUN, PendingMessage("下一条", createdAt = 1L))
        val holding = queues.sessionsHolding(
            listOf("idle", "empty", "busy-next"),
            setOf(PromptQueue.STEER, PromptQueue.NEXT_RUN),
        )
        assertEquals(listOf("idle", "busy-next"), holding)
    }
}
