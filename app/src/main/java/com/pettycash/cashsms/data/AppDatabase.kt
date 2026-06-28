package com.pettycash.cashsms.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SmsMessageEntity::class, SyncTargetEntity::class, MessageSyncEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun smsDao(): SmsMessageDao
    abstract fun syncDao(): SyncDao
}
