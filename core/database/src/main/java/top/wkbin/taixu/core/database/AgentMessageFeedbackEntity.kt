package top.wkbin.taixu.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 单条 assistant 消息的用户反馈（借鉴 DSH message-feedback：log-only、绝不进模型上下文）。
 *
 * 定位：持久化的用户评价元数据（评分 + 可选备注），供事后质量分析与导出；
 * 模型上下文只来自会话树投影，本表天然不可见。
 * 目标约束（写入前校验，见 [MessageFeedbackService]）：messageId 必须是本会话
 * 追加产生、文本非空的 assistant 消息——fork 继承/空占位消息不可作为反馈目标。
 *
 * 并发语义：messageId 唯一 + REPLACE upsert（last-write-wins）。DSH 的严格乐观并发
 * （opaque version + ifVersion）面向多客户端同时写同一反馈的场景；太墟当前单设备
 * 单写者，冲突概率与代价不成立，不做版本字段。
 */
@Entity(
    tableName = "agent_message_feedback",
    indices = [
        Index(value = ["messageId"], unique = true),
        Index(value = ["sessionId", "updatedAt"]),
    ],
)
data class AgentMessageFeedbackEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    /** 反馈目标的 assistant 消息 id。 */
    val messageId: String,
    /** up | down */
    val rating: String,
    /** 用户可选备注；null = 仅评分。 */
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val RATING_UP = "up"
        const val RATING_DOWN = "down"
        fun idOf(messageId: String): String = "fb_$messageId"
    }
}

@Dao
interface AgentMessageFeedbackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: AgentMessageFeedbackEntity)

    @Query("SELECT * FROM agent_message_feedback WHERE sessionId = :sessionId ORDER BY updatedAt DESC")
    fun observeForSession(sessionId: String): Flow<List<AgentMessageFeedbackEntity>>

    @Query("SELECT * FROM agent_message_feedback WHERE sessionId = :sessionId ORDER BY updatedAt DESC")
    suspend fun listForSession(sessionId: String): List<AgentMessageFeedbackEntity>

    @Query("SELECT * FROM agent_message_feedback WHERE messageId = :messageId LIMIT 1")
    suspend fun findByMessage(messageId: String): AgentMessageFeedbackEntity?

    /** 跨会话最近反馈（开发者诊断只读展示）；表很小，不为全局排序新增索引。 */
    @Query("SELECT * FROM agent_message_feedback ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AgentMessageFeedbackEntity>>

    @Query("DELETE FROM agent_message_feedback WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)
}

class AgentMessageFeedbackRepository(private val dao: AgentMessageFeedbackDao) {
    suspend fun upsert(entry: AgentMessageFeedbackEntity) = dao.upsert(entry)
    fun observeForSession(sessionId: String): Flow<List<AgentMessageFeedbackEntity>> = dao.observeForSession(sessionId)
    suspend fun listForSession(sessionId: String): List<AgentMessageFeedbackEntity> = dao.listForSession(sessionId)
    suspend fun findByMessage(messageId: String): AgentMessageFeedbackEntity? = dao.findByMessage(messageId)
    fun observeRecent(limit: Int): Flow<List<AgentMessageFeedbackEntity>> = dao.observeRecent(limit)
    suspend fun deleteForSession(sessionId: String) = dao.deleteForSession(sessionId)
}

/**
 * 反馈读写门面：写入侧校验目标（messageId 必须是本会话非空 assistant 消息）并 upsert 收口，
 * 读取侧提供单条查询与跨会话最近列表（UI 预填 / 开发者诊断）。
 * 校验失败抛 [IllegalArgumentException]，由调用方（Web 桥/UI）转错误响应。
 */
class MessageFeedbackService(
    private val feedback: AgentMessageFeedbackRepository,
    private val runtime: HarnessRuntimeRepository,
) {

    /** @return 写入后的最新记录。 */
    suspend fun set(sessionId: String, messageId: String, rating: String, note: String?): AgentMessageFeedbackEntity {
        require(rating in setOf(AgentMessageFeedbackEntity.RATING_UP, AgentMessageFeedbackEntity.RATING_DOWN)) {
            "rating 必须是 up 或 down"
        }
        // 反馈目标在活动分支尾部查找（branchTail 的 leafId=null 语义是空集，必须先取 lane 锚点）。
        val leafId = runtime.findLane(sessionId, MAIN_LANE)?.leafId
        val message = leafId?.let {
            runtime.branchTail(sessionId, it, MAX_TARGET_SCAN)
                .filter { entry -> entry.entryType == "message" && entry.customType == "assistant" }
                .firstOrNull { entry -> entry.id == messageId }
        } ?: throw IllegalArgumentException("反馈目标不存在或不支持：该会话中没有这条 assistant 消息")
        val existing = feedback.findByMessage(messageId)
        val now = System.currentTimeMillis()
        val entry = AgentMessageFeedbackEntity(
            id = AgentMessageFeedbackEntity.idOf(messageId),
            sessionId = sessionId,
            messageId = messageId,
            rating = rating,
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        feedback.upsert(entry)
        return entry
    }

    suspend fun listForSession(sessionId: String): List<AgentMessageFeedbackEntity> = feedback.listForSession(sessionId)

    /** 单条消息的既有反馈；未评价返回 null（UI 预填用）。 */
    suspend fun find(messageId: String): AgentMessageFeedbackEntity? = feedback.findByMessage(messageId)

    /** 最近反馈（跨会话倒序），供开发者诊断只读展示。 */
    fun observeRecent(limit: Int = RECENT_LIMIT): Flow<List<AgentMessageFeedbackEntity>> =
        feedback.observeRecent(limit)

    suspend fun deleteForSession(sessionId: String) = feedback.deleteForSession(sessionId)

    private companion object {
        /** 主 lane 名（与 harness SessionTreeStore.MAIN_LANE 同值；core 层不依赖 harness）。 */
        const val MAIN_LANE = "main"
        /** 目标扫描窗口：反馈只针对近期 assistant 消息，600 条与投影窗口一致。 */
        const val MAX_TARGET_SCAN = 600
        /** 开发者诊断默认读取窗口。 */
        const val RECENT_LIMIT = 50
    }
}
