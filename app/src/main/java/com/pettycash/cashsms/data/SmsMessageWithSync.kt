package com.pettycash.cashsms.data

import androidx.room.ColumnInfo
import androidx.room.Embedded

data class SmsMessageWithSync(
    @Embedded
    val message: SmsMessageEntity,

    @ColumnInfo(name = "sync_state")
    val syncState: String
)
