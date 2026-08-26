package top.wkbin.taixu.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import top.wkbin.taixu.core.model.ApprovalMode
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "agent_approval_requests")
data class AgentApprovalRequestEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val toolCallId: String,
    val toolName: String,
    val argumentsJson: String,
    val workspace: String,
    val riskLevel: String,
    val reason: String,
    val summary: String,
    val status: String = STATUS_PENDING,
    val createdAt: Long,
    val resolvedAt: Long? = null,
    /** 审批创建时所属的 harness operation；恢复执行前校验归属，防止跨运行重放。 */
    val operationId: String? = null,
    /** argumentsJson 的 SHA-256 摘要；执行前复核，防止“批准的是旧参数、执行的是新参数”。 */
    val argsHash: String = "",
    /** 审批过期时间（epoch ms）；到期未决的请求自动失效。旧数据默认永不过期。 */
    val expiresAt: Long = Long.MAX_VALUE,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_APPROVED = "approved"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_EXECUTED = "executed"
        const val STATUS_FAILED = "failed"
        const val STATUS_EXPIRED = "expired"
    }
}

@Entity(tableName = "agent_approval_settings")
data class AgentApprovalSettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val mode: String = ApprovalMode.ASSISTED.id,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Dao
interface AgentApprovalDao {
    @Query("SELECT * FROM agent_approval_requests WHERE sessionId = :sessionId AND status = 'pending' ORDER BY createdAt ASC")
    fun observePendingForSession(sessionId: String): Flow<List<AgentApprovalRequestEntity>>

    @Query("SELECT * FROM agent_approval_requests WHERE sessionId = :sessionId AND status = 'pending' ORDER BY createdAt ASC")
    suspend fun listPendingForSession(sessionId: String): List<AgentApprovalRequestEntity>

    @Query("SELECT * FROM agent_approval_requests WHERE id = :id LIMIT 1")
    suspend fun findRequest(id: String): AgentApprovalRequestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRequest(request: AgentApprovalRequestEntity)

    @Query("UPDATE agent_approval_requests SET status = :status, resolvedAt = :resolvedAt WHERE id = :id")
    suspend fun updateRequestStatus(id: String, status: String, resolvedAt: Long)

    /** Atomically claims a pending request so duplicate approve taps cannot execute it twice. */
    @Query("UPDATE agent_approval_requests SET status = :status, resolvedAt = :resolvedAt WHERE id = :id AND status = 'pending'")
    suspend fun claimPendingRequest(id: String, status: String, resolvedAt: Long): Int

    /** 将到期未决的 pending 请求整体置为 expired（惰性清扫，读写路径都会触发）。 */
    @Query("UPDATE agent_approval_requests SET status = 'expired', resolvedAt = :now WHERE status = 'pending' AND expiresAt <= :now")
    suspend fun expirePendingApprovals(now: Long): Int



    @Query("DELETE FROM agent_approval_requests WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("SELECT * FROM agent_approval_settings WHERE id = 1 LIMIT 1")
    fun observeSettings(): Flow<AgentApprovalSettingsEntity?>

    @Query("SELECT * FROM agent_approval_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettings(): AgentApprovalSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: AgentApprovalSettingsEntity)
}

@Singleton
class AgentApprovalRepository @Inject constructor(
    private val dao: AgentApprovalDao,
) {
    val mode: Flow<ApprovalMode> = dao.observeSettings().map { ApprovalMode.fromId(it?.mode) }

    fun pendingForSession(sessionId: String): Flow<List<AgentApprovalRequestEntity>> =
        dao.observePendingForSession(sessionId).map { requests ->
            // 到期的请求先从展示层过滤掉；终态写入由 sweepExpired / resolveApproval 完成。
            val now = System.currentTimeMillis()
            requests.filter { it.expiresAt > now }
        }

    /** 读取前先清扫到期请求，保证 pending 视图与过期语义一致。 */
    suspend fun pendingNow(sessionId: String): List<AgentApprovalRequestEntity> {
        dao.expirePendingApprovals(System.currentTimeMillis())
        return dao.listPendingForSession(sessionId)
    }

    suspend fun currentMode(): ApprovalMode = ApprovalMode.fromId(dao.getSettings()?.mode)

    suspend fun setMode(mode: ApprovalMode) {
        dao.upsertSettings(AgentApprovalSettingsEntity(mode = mode.id))
    }

    suspend fun create(request: AgentApprovalRequestEntity) = dao.upsertRequest(request)

    suspend fun find(id: String): AgentApprovalRequestEntity? = dao.findRequest(id)

    suspend fun mark(id: String, status: String) =
        dao.updateRequestStatus(id, status, System.currentTimeMillis())

    suspend fun claimPending(id: String, status: String): Boolean =
        dao.claimPendingRequest(id, status, System.currentTimeMillis()) > 0



    suspend fun deleteForSession(sessionId: String) = dao.deleteForSession(sessionId)
}
