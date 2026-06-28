package com.pettycash.cashsms.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SmsMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<SmsMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: SmsMessageEntity)

    @Update
    suspend fun update(message: SmsMessageEntity)

    @Query("SELECT * FROM sms_messages ORDER BY date DESC LIMIT :limit")
    fun watchLatest(limit: Int = 100): Flow<List<SmsMessageEntity>>

    @Query("UPDATE sms_messages SET send_state = :newState WHERE _id = :id")
    suspend fun updateSendState(id: Long, newState: String)

    @Query("SELECT * FROM sms_messages WHERE address = :address ORDER BY date ASC")
    fun watchConversation(address: String): Flow<List<SmsMessageEntity>>

    @Query("""
    SELECT address FROM sms_messages
    GROUP BY address
    ORDER BY MAX(date) DESC
    """)
    fun watchThreadAddresses(): Flow<List<String>>
}
