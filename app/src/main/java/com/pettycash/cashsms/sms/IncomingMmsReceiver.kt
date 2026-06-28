package com.pettycash.cashsms.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Stub receiver so the app is eligible as a default SMS/MMS handler.
 * MMS handling is more complex; this project focuses on SMS forwarding first.
 */
class IncomingMmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("CashSms", "MMS WAP push received (not handled yet)")
    }
}
