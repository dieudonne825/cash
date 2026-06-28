package com.pettycash.cashsms.sms

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.text.TextUtils
import com.pettycash.cashsms.sms.DefaultSmsRole
import com.pettycash.cashsms.data.SmsMessageDao
import com.pettycash.cashsms.data.SmsMessageEntity
import com.pettycash.cashsms.sync.DjangoSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SmsSender {
    const val ACTION_SMS_SENT = "com.pettycash.cashsms.ACTION_SMS_SENT"
    const val ACTION_SMS_DELIVERED = "com.pettycash.cashsms.ACTION_SMS_DELIVERED"
    const val EXTRA_LOCAL_ID = "extra_local_id"

    fun sendText(context: Context, dao: SmsMessageDao, address: String, body: String) {
        val now = System.currentTimeMillis()
        val localId = tryInsertIntoProvider(context, address, body, now) ?: -now
        CoroutineScope(Dispatchers.IO).launch {
            dao.upsert(
                SmsMessageEntity(
                    id = localId,
                    threadId = null,
                    address = address,
                    body = body,
                    date = now,
                    type = 2,
                    status = null,
                    read = 1,
                    seen = 1,
                    subscriptionId = null,
                    sendState = "SENDING"
                )
            )
            // Synchronisation immédiate après l'écriture réussie en base de données
            DjangoSyncWorker.enqueueNow(context)
        }

        val requestCode = (localId and 0x7FFFFFFF).toInt()
        val sentIntent = Intent(ACTION_SMS_SENT).setPackage(context.packageName).putExtra(EXTRA_LOCAL_ID, localId)
        val deliveredIntent = Intent(ACTION_SMS_DELIVERED).setPackage(context.packageName).putExtra(EXTRA_LOCAL_ID, localId)

        val sentPI = PendingIntent.getBroadcast(
            context,
            requestCode,
            sentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val deliveredPI = PendingIntent.getBroadcast(
            context,
            requestCode + 1,
            deliveredIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        SmsManager.getDefault().sendTextMessage(address, null, body, sentPI, deliveredPI)
    }

    /**
     * Best-effort: when we are the default SMS app, we can write to the SMS provider.
     * Returns the provider _id if insertion succeeded.
     */
    private fun tryInsertIntoProvider(context: Context, address: String, body: String, date: Long): Long? {
        if (!DefaultSmsRole.isDefaultSmsApp(context)) return null
        if (TextUtils.isEmpty(address)) return null

        return try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, date)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            }
            val uri: Uri? = context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
            uri?.lastPathSegment?.toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }
}
