package com.pettycash.cashsms.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface SyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTarget(target: SyncTargetEntity): Long

    @Query("SELECT * FROM sync_targets WHERE base_url = :baseUrl LIMIT 1")
    suspend fun getTargetByBaseUrl(baseUrl: String): SyncTargetEntity?

    @Query("UPDATE sync_targets SET token = :token, backend_type = :backendType WHERE id = :id")
    suspend fun updateTargetDetails(id: Long, token: String, backendType: String)

    @Transaction
    suspend fun ensureTarget(baseUrl: String, token: String, backendType: String = "DJANGO"): SyncTargetEntity {
        val existing = getTargetByBaseUrl(baseUrl)
        if (existing != null) {
            if (existing.token != token || existing.backendType != backendType) {
                updateTargetDetails(existing.id, token, backendType)
            }
            return existing.copy(token = token, backendType = backendType)
        }
        val newId = upsertTarget(SyncTargetEntity(baseUrl = baseUrl, token = token, backendType = backendType, isActive = true))
        return SyncTargetEntity(id = newId, baseUrl = baseUrl, token = token, backendType = backendType, isActive = true)
    }

    @Query("SELECT * FROM sync_targets ORDER BY created_at DESC")
    suspend fun listTargets(): List<SyncTargetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessageSync(sync: MessageSyncEntity)

    @Query(
        """
        SELECT m.*
        FROM sms_messages m
        LEFT JOIN message_sync s
            ON s.message_id = m._id AND s.target_id = :targetId
        WHERE s.state IS NULL OR s.state != 'SYNCED'
        ORDER BY m.date DESC
        LIMIT :limit
        """
    )
    suspend fun getPendingForTarget(targetId: Long, limit: Int = 300): List<SmsMessageEntity>

    @Query("INSERT OR REPLACE INTO message_sync(message_id, target_id, state, last_error, updated_at) VALUES(:messageId, :targetId, :state, :lastError, :updatedAt)")
    suspend fun setSyncState(
        messageId: Long,
        targetId: Long,
        state: String,
        lastError: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Transaction
    suspend fun markBatch(targetId: Long, ids: List<Long>, state: String, lastError: String?) {
        val now = System.currentTimeMillis()
        ids.forEach { id ->
            setSyncState(id, targetId, state, lastError, now)
        }
    }

    @Query(
        """
        SELECT m.*,
               COALESCE(s.state, 'PENDING') AS sync_state
        FROM sms_messages m
        LEFT JOIN message_sync s
            ON s.message_id = m._id AND s.target_id = :targetId
        ORDER BY m.date DESC
        LIMIT :limit
        """
    )
    fun watchLatestWithSync(targetId: Long, limit: Int = 100): kotlinx.coroutines.flow.Flow<List<SmsMessageWithSync>>
}
