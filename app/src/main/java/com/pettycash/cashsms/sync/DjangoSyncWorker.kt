package com.pettycash.cashsms.sync

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pettycash.cashsms.CashSmsApplication
import com.pettycash.cashsms.data.SyncTargetEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DjangoSyncWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "DjangoSyncWorker"
    override suspend fun doWork(): Result {
        val baseUrl = (SyncPrefs.getActiveBaseUrl(appContext) ?: SyncPrefs.getBaseUrl(appContext))?.trimEnd('/')
            ?: return Result.failure()

        val app = appContext.applicationContext as CashSmsApplication
        val existing = app.db.syncDao().getTargetByBaseUrl(baseUrl)
        val token = existing?.token?.takeIf { it.isNotBlank() } ?: (SyncPrefs.getToken(appContext) ?: return Result.failure())
        
        val backendType = existing?.backendType ?: SyncPrefs.getBackendType(appContext)
        val target: SyncTargetEntity = app.db.syncDao().ensureTarget(baseUrl, token, backendType)
        Log.d(TAG, "Worker target ID = ${target.id}, Backend = ${target.backendType}")

        Log.d(TAG, "Starting sync for target ID: ${target.id}, Base URL: $baseUrl")
        val pending = app.db.syncDao().getPendingForTarget(target.id, limit = 500)
        
        if (pending.isEmpty()) {
            Log.d(TAG, "No pending messages found for sync.")
            return Result.success()
        }
        
        // Force include ALL messages if any have UNKNOWN state and never synced
        val allMessages = app.db.syncDao().getPendingForTarget(target.id, limit = 2000)
        val forceSync = if (allMessages.any { it.sendState == "UNKNOWN" }) {
            Log.w(TAG, "⚠️ FORCE SYNC: Found ${allMessages.count { it.sendState == "UNKNOWN" }} messages with UNKNOWN state")
            allMessages.distinctBy { it.id }  // Get unique messages
        } else {
            pending
        }
        
        Log.d(TAG, "Found ${forceSync.size} messages for sync (${forceSync.count { it.sendState == "UNKNOWN" }} UNKNOWN, ${forceSync.count { it.sendState == "PENDING" }} PENDING).")
        forceSync.take(5).forEachIndexed { index, m ->
            Log.d(TAG, "  [$index] ID: ${m.id}, Addr: ${m.address}, Body: '${m.body.take(50)}...', Type: ${m.type}, State: ${m.sendState}")
            
            // Alert spécifique pour les services financiers
            val addr = m.address?.lowercase() ?: ""
            val body = m.body.lowercase()
            if (addr.contains("orange") || addr.contains("momo") || addr.contains("money") ||
                body.contains("orange") || body.contains("orangemoney")) {
                Log.i(TAG, "!!! MESSAGE FINANCIER DÉTECTÉ !!! Expéditeur: ${m.address} | Body: ${m.body.take(30)}")
            }
        }

        val deviceId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val payload = JSONObject().apply {
            put("device_id", deviceId)
            put(
                "messages",
                JSONArray().apply {
                    forceSync.forEach { m ->
                        put(
                            JSONObject().apply {
                                put("local_id", m.id)
                                put("thread_id", m.threadId)
                                put("address", m.address)
                                put("body", m.body)
                                put("date", m.date)
                                put("type", m.type)
                                put("status", m.status)
                                put("read", m.read)
                                put("seen", m.seen)
                                put("subscription_id", m.subscriptionId)
                                put("send_state", m.sendState)
                            }
                        )
                    }
                }
            )
        }

        val url = if (target.backendType == "LARAVEL") {
            "$baseUrl/api/sms/messages/bulk"
        } else {
            "$baseUrl/api/v1/home/sms/messages/bulk/"
        }
        Log.d(TAG, "Sending bulk SMS payload to URL: $url")
        Log.d(TAG, "Payload content: ${payload.toString().take(500)}...") // Log first 500 chars of payload

        return try {
            val resp = if (target.backendType == "LARAVEL") {
                LaravelClient.postJson(url, token, payload.toString())
            } else {
                DjangoClient.postJson(url, token, payload.toString())
            }
            resp.use { resp ->
                if (resp.isSuccessful) {
                    try {
                        // Parse response to check actual sync status
                        val responseBody = resp.body?.string() ?: "{}"
                        val responseJson = org.json.JSONObject(responseBody)
                        
                        val created = responseJson.optInt("created", 0)
                        val skipped = responseJson.optInt("skipped", 0)
                        val duplicates = responseJson.optInt("duplicates", 0)
                        val financialCount = responseJson.optInt("financial_count", 0)
                        val orangeMoneyCount = responseJson.optInt("orange_money_count", 0)
                        val errors = responseJson.optJSONArray("errors")
                        
                        Log.i(TAG, "Sync response: created=$created, skipped=$skipped, duplicates=$duplicates, financial=$financialCount, orangeMoney=$orangeMoneyCount")
                        
                        if (orangeMoneyCount > 0) {
                            Log.i(TAG, "✅ ORANGEMONEY SYNCED: $orangeMoneyCount messages successfully saved to server")
                        }
                        
                        // Log any validation errors for debugging
                        if (errors != null && errors.length() > 0) {
                            Log.w(TAG, "Validation errors from server:")
                            for (i in 0 until minOf(3, errors.length())) {
                                Log.w(TAG, "  - ${errors.getString(i)}")
                            }
                        }
                        
                        // Separate successfully created from failed
                        if (created > 0 || duplicates > 0) {
                            // Mark only the created ones as OK
                            // In reality, we should know which specific IDs were created
                            // For now, mark all as OK since successful response means server accepted them
                            app.db.syncDao().markBatch(target.id, forceSync.map { it.id }, "SYNCED", null)
                            Log.i(TAG, "Successfully synced $created messages. Skipped: $skipped")
                            val check = app.db.syncDao().getPendingForTarget(target.id, limit = 10)
                            Log.d(TAG, "After markBatch, pending count = ${check.size}")
                            Result.success()
                        } else {
                            Log.w(TAG, "All messages were rejected/skipped by server")
                            Result.retry()
                        }
                    } catch (parseError: Exception) {
                        Log.e(TAG, "Failed to parse response", parseError)
                        // Mark as OK anyway since HTTP was successful
                        app.db.syncDao().markBatch(target.id, forceSync.map { it.id }, "SYNCED", null)
                        Result.success()
                    }
                } else {
                    Log.e(TAG, "HTTP error: ${resp.code}")
                    app.db.syncDao().markBatch(target.id, forceSync.map { it.id }, "FAILED", "HTTP ${resp.code}")
                    Result.retry()
                }
            }
        } catch (it: Exception) {
            Log.e(TAG, "Network or unexpected error during sync", it)
            app.db.syncDao().markBatch(target.id, forceSync.map { it.id }, "FAILED", "network_error")
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NOW = "cash_sms_sync_now"
        private const val UNIQUE_PERIODIC = "cash_sms_sync_periodic"
        const val KEY_IS_MANUAL = "is_manual"

        fun enqueueNow(context: Context, isManual: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val data = Data.Builder().putBoolean(KEY_IS_MANUAL, isManual).build()

            val req = OneTimeWorkRequestBuilder<DjangoSyncWorker>()
                .setConstraints(constraints)
                .setInputData(data)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.REPLACE, req)
        }

        fun ensurePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val req = PeriodicWorkRequestBuilder<DjangoSyncWorker>(3, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        }
    }
}
