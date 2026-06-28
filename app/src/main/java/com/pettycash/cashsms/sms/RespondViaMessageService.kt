package com.pettycash.cashsms.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Required for eligibility as default SMS app.
 * Minimal stub; implement quick-reply support if needed.
 */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
