package com.pettycash.cashsms.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_targets",
    indices = [Index(value = ["base_url"], unique = true)]
)
data class SyncTargetEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "base_url")
    val baseUrl: String,

    @ColumnInfo(name = "token")
    val token: String,

    @ColumnInfo(name = "backend_type", defaultValue = "DJANGO")
    val backendType: String = "DJANGO",

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
