package com.pettycash.cashsms.sms

import android.content.Context
import android.net.Uri
import com.pettycash.cashsms.data.SmsMessageDao
import com.pettycash.cashsms.data.SmsMessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SmsImportManager {
    private val SMS_URI: Uri = Uri.parse("content://sms")

    fun importAllSms(context: Context, dao: SmsMessageDao, onComplete: () -> Unit = {}) {
        CoroutineScope(Dispatchers.IO).launch {
            val resolver = context.contentResolver
            val projection = arrayOf(
                "_id",
                "thread_id",
                "address",
                "body",
                "date",
                "type",
                "status",
                "read",
                "seen",
                "sub_id"
            )

            val cursor = resolver.query(SMS_URI, projection, null, null, "date DESC")
            if (cursor == null) return@launch

            cursor.use {
                val idIdx = it.getColumnIndex("_id")
                val threadIdx = it.getColumnIndex("thread_id")
                val addressIdx = it.getColumnIndex("address")
                val bodyIdx = it.getColumnIndex("body")
                val dateIdx = it.getColumnIndex("date")
                val typeIdx = it.getColumnIndex("type")
                val statusIdx = it.getColumnIndex("status")
                val readIdx = it.getColumnIndex("read")
                val seenIdx = it.getColumnIndex("seen")
                val subIdIdx = it.getColumnIndex("sub_id")

                val batch = ArrayList<SmsMessageEntity>(500)
                while (it.moveToNext()) {
                    val body = it.getString(bodyIdx) ?: ""
                    batch.add(
                        SmsMessageEntity(
                            id = it.getLong(idIdx),
                            threadId = if (threadIdx >= 0) it.getLong(threadIdx) else null,
                            address = if (addressIdx >= 0) it.getString(addressIdx) else null,
                            body = body,
                            date = it.getLong(dateIdx),
                            type = it.getInt(typeIdx),
                            status = if (statusIdx >= 0 && !it.isNull(statusIdx)) it.getInt(statusIdx) else null,
                            read = if (readIdx >= 0 && !it.isNull(readIdx)) it.getInt(readIdx) else null,
                            seen = if (seenIdx >= 0 && !it.isNull(seenIdx)) it.getInt(seenIdx) else null,
                            subscriptionId = if (subIdIdx >= 0 && !it.isNull(subIdIdx)) it.getInt(subIdIdx) else null,
                            sendState = "UNKNOWN"
                        )
                    )

                    if (batch.size >= 500) {
                        dao.upsertAll(batch)
                        batch.clear()
                    }
                }
                if (batch.isNotEmpty()) dao.upsertAll(batch)
            }
            
            launch(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    fun generateTestMessages(context: Context, dao: SmsMessageDao, onComplete: () -> Unit = {}) {
        CoroutineScope(Dispatchers.IO).launch {
            val now = System.currentTimeMillis()
            val testSms = listOf(
                // Petty Cash Messages in FCFA
                Triple("PettyCash", "Achat approuve de 12 500 FCFA chez Petty Cash. ID transaction: TX-9921.", now - 3600000 * 12),
                Triple("+33687654321", "Votre code de confirmation Petty Cash est: 4892. Ne le partagez pas.", now - 3600000 * 11),
                Triple("PettyCash", "Remboursement Petty Cash de 25 000 FCFA recu avec succes.", now - 3600000 * 10),
                Triple("PettyCash", "Felicitations! Votre compte est actif et la synchronisation fonctionne parfaitement.", now - 3600000 * 9),
                Triple("PettyCash", "Alerte Petty Cash: Connexion detectee sur un nouvel appareil a Abidjan.", now - 3600000 * 8),
                
                // Orange Money Messages
                Triple("OrangeMoney", "Transfert de 656569918 EFFALA AMOUGOU vers 688137007 BELOBO SANANG reussi. Details: ID transaction: PP260701.1220.C86384, Montant Transaction: 3054FCFA, Frais: 0 FCFA, Commission: 0 FCFA, Montant Net: 3054 FCFA, Nouveau Solde: 28056.4 FCFA.", now - 3600000 * 7),
                Triple("OrangeMoney", "Retrait d'argent reussi par le 695973770 avec le Code : 266364. Informations detaillees : Montant: 2500 FCFA, Frais: 54 FCFA, No de transaction CO260701.1245.B16503, montant net debite 2554 FCFA, Nouveau solde: 25502.4 FCFA.", now - 3600000 * 6),
                
                // Airtel Money Messages
                Triple("AirtelMoney", "Depot effectue de 30 000 FCFA chez Petty Cash Agent. Nouveau solde Airtel Money: 34 200 FCFA. ID Trans: TXN7728391.", now - 3600000 * 5),
                Triple("AirtelMoney", "Vous avez recu 10 000 FCFA de ALBERT ESSOMBA (+24107123456). Reference: TXN8829103. Nouveau solde Airtel: 44 200 FCFA.", now - 3600000 * 4),

                // Wave Messages
                Triple("Wave", "Vous avez envoye 5 000 FCFA a +221771234567. Sans frais. Nouveau solde: 12 800 FCFA. ID: W-9283921-20", now - 3600000 * 3),
                Triple("Wave", "Paiement de 18 500 FCFA effectue a Supermarche Dakar. Nouveau solde: 45 000 FCFA. ID: W-1029302-39", now - 3600000 * 2),

                // MTN Mobile Money Messages
                Triple("MTNMoMo", "Transfert recu de 20 000 FCFA de +22997123456. ID transaction: 12903829. Votre nouveau solde Mobile Money est de 24 500 FCFA.", now - 3600000 * 1),
                Triple("MTNMoMo", "Retrait de 10 000 FCFA effectue avec succes. Frais: 100 FCFA. Nouveau solde: 14 400 FCFA. ID: MTN-89102-X", now)
            )

            // 1. Essayer d'insérer dans le ContentResolver du système
            try {
                val resolver = context.contentResolver
                testSms.forEach { (sender, body, time) ->
                    val values = android.content.ContentValues().apply {
                        put("address", sender)
                        put("body", body)
                        put("date", time)
                        put("type", 1) // Inbox
                        put("read", 0) // Unread
                    }
                    resolver.insert(Uri.parse("content://sms/inbox"), values)
                }
            } catch (e: Exception) {
                android.util.Log.w("SmsImportManager", "Impossible d'inserer dans la base systeme: ${e.message}")
            }

            // 2. Insérer directement dans notre base de données Room locale pour s'assurer qu'ils apparaissent et se synchronisent
            val entities = testSms.mapIndexed { index, (sender, body, time) ->
                SmsMessageEntity(
                    id = now + index, // unique IDs
                    threadId = 100L + (sender.hashCode().let { if (it == Int.MIN_VALUE) 0 else java.lang.Math.abs(it) } % 1000L),
                    address = sender,
                    body = body,
                    date = time,
                    type = 1, // Inbox
                    status = null,
                    read = 0,
                    seen = 0,
                    subscriptionId = null,
                    sendState = "UNKNOWN"
                )
            }
            dao.upsertAll(entities)

            launch(Dispatchers.Main) {
                onComplete()
            }
        }
    }
}
