package com.pettycash.cashsms

import android.app.Application
import androidx.room.Room
import com.pettycash.cashsms.data.AppDatabase
import com.pettycash.cashsms.sms.SmsNotificationManager

class CashSmsApplication : Application() {
    lateinit var db: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, AppDatabase::class.java, "cash_sms.db")
            .fallbackToDestructiveMigration()
            .build()
        SmsNotificationManager.createChannel(this)
    }
}
