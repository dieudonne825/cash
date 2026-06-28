package com.pettycash.cashsms.sms

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.pettycash.cashsms.CashSmsApplication
import com.pettycash.cashsms.data.SmsMessageEntity
import com.pettycash.cashsms.sync.DjangoSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.absoluteValue

private const val TAG = "IncomingSmsReceiver"

class IncomingSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            Log.w(TAG, "Received empty SMS intent")
            return
        }

        val first = messages.first()
        val originatingAddress = first.originatingAddress?.takeIf { it.isNotBlank() }
        
        if (originatingAddress == null) {
            Log.w(TAG, "Ignoring SMS with null/blank originating address")
            return
        }

        // Reconstruction du corps complet (SMS multi-parties)
        val fullBody = buildString(messages.size) {
            messages.forEach { append(it.messageBody ?: "") }
        }

        // 1. Tentative d'écriture dans le Provider natif (si app par défaut)
        val providerId = tryInsertIntoProvider(
            context = context,
            address = originatingAddress,
            body = fullBody,
            date = first.timestampMillis
        )

        // 2. Génération d'un ID local stable si le provider n'a pas pu écrire (doit être un Long)
        val localId = providerId ?: UUID.randomUUID().mostSignificantBits

        // Extract subscription ID from intent if available
        val subscriptionId = intent.getIntExtra("subscription", -1).takeIf { it >= 0 }

        val entity = SmsMessageEntity(
            id = localId,
            threadId = null,
            address = originatingAddress,
            body = fullBody,
            date = first.timestampMillis,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            status = null,
            read = 0,
            seen = 0,
            subscriptionId = subscriptionId,
            sendState = "UNKNOWN"
        )

        val app = context.applicationContext as CashSmsApplication
        
        // 3. Traitement asynchrome sécurisé avec goAsync() pour respecter le budget BroadcastReceiver
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

        scope.launch {
            try {
                app.db.smsDao().upsert(entity)
                Log.i(TAG, "Persisted SMS from $originatingAddress (providerId=$providerId, localId=$localId)")
                SmsNotificationManager.notify(
                    context   = context,
                    address   = originatingAddress,
                    body      = fullBody,
                    notifId   = (localId % Int.MAX_VALUE).absoluteValue.toInt(),
                    messageId = localId
                )
                // 🚀 Déclencher une synchronisation immédiate
                DjangoSyncWorker.enqueueNow(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist incoming SMS", e)
            } finally {
                scope.cancel()
                pendingResult.finish()
            }
        }
    }

    private fun tryInsertIntoProvider(
        context: Context,
        address: String,
        body: String,
        date: Long
    ): Long? {
        if (!DefaultSmsRole.isDefaultSmsApp(context)) {
            Log.d(TAG, "Not default SMS app, skipping provider insert")
            return null
        }

        return try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, date)
                put(Telephony.Sms.DATE_SENT, date) // Utile pour la cohérence
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }

            val uri: Uri? = context.contentResolver.insert(
                Telephony.Sms.Inbox.CONTENT_URI, 
                values
            )
            
            val insertedId = uri?.lastPathSegment?.toLongOrNull()
            if (insertedId != null) {
                Log.d(TAG, "Inserted SMS into provider with id=$insertedId")
            } else {
                Log.w(TAG, "Provider insert returned null or invalid URI: $uri")
            }
            insertedId
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException writing to SMS provider", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "IllegalArgumentException writing to SMS provider", e)
            null
        }
    }
}
