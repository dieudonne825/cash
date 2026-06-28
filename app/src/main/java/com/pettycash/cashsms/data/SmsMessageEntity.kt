package com.pettycash.cashsms.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sms_messages")
data class SmsMessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "_id")
    val id: Long,

    @ColumnInfo(name = "thread_id")
    val threadId: Long?,

    @ColumnInfo(name = "address")
    val address: String?,

    @ColumnInfo(name = "body")
    val body: String,

    @ColumnInfo(name = "date")
    val date: Long,

    /**
     * 1 = inbox, 2 = sent, etc (Telephony.TextBasedSmsColumns.MESSAGE_TYPE_*)
     */
    @ColumnInfo(name = "type")
    val type: Int,

    /**
     * Optional status (mainly for outgoing). Values can be device/vendor dependent.
     */
    @ColumnInfo(name = "status")
    val status: Int?,

    @ColumnInfo(name = "read")
    val read: Int?,

    @ColumnInfo(name = "seen")
    val seen: Int?,

    @ColumnInfo(name = "subscription_id")
    val subscriptionId: Int?,

    /**
     * For outgoing delivery tracking (best-effort):
     * UNKNOWN | SENDING | SENT | DELIVERED | FAILED
     */
    @ColumnInfo(name = "send_state")
    val sendState: String = "UNKNOWN"
)
