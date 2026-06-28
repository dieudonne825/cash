package com.pettycash.cashsms.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import com.pettycash.cashsms.CashSmsApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val localId = intent.getLongExtra(SmsSender.EXTRA_LOCAL_ID, Long.MIN_VALUE)
        val result = resultCode
        val action = intent.action ?: "unknown"

        val newState: String? = when (action) {
            SmsSender.ACTION_SMS_SENT -> if (result == Activity.RESULT_OK) "SENT" else "FAILED"
            SmsSender.ACTION_SMS_DELIVERED -> if (result == Activity.RESULT_OK) "DELIVERED" else null
            else -> null
        }

        if (newState != null && localId != Long.MIN_VALUE) {
            val app = context.applicationContext as CashSmsApplication
            CoroutineScope(Dispatchers.IO).launch {
                app.db.smsDao().updateSendState(localId, newState)
                // Déclencher une synchronisation immédiate après le changement d'état
                com.pettycash.cashsms.sync.DjangoSyncWorker.enqueueNow(context)
            }
        }

        when (result) {
            Activity.RESULT_OK -> Log.d("CashSms", "$action: ok")
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> Log.w("CashSms", "$action: generic failure")
            SmsManager.RESULT_ERROR_NO_SERVICE -> Log.w("CashSms", "$action: no service")
            SmsManager.RESULT_ERROR_NULL_PDU -> Log.w("CashSms", "$action: null pdu")
            SmsManager.RESULT_ERROR_RADIO_OFF -> Log.w("CashSms", "$action: radio off")
            else -> Log.w("CashSms", "$action: result=$result")
        }
    }
}
