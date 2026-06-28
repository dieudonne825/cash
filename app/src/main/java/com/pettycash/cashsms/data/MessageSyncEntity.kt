package com.pettycash.cashsms.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "message_sync",
    primaryKeys = ["message_id", "target_id"],
    indices = [
        Index(value = ["target_id", "state"]),
        Index(value = ["message_id"])
    ]
)
data class MessageSyncEntity(
    @ColumnInfo(name = "message_id")
    val messageId: Long,

    @ColumnInfo(name = "target_id")
    val targetId: Long,

    /**
     * PENDING | SYNCED | FAILED
     */
    @ColumnInfo(name = "state")
    val state: String = "PENDING",

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
