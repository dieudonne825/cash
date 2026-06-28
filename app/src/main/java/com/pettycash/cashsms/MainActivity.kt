package com.pettycash.cashsms

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.pettycash.cashsms.sms.DefaultSmsRole
import com.pettycash.cashsms.sms.SmsImportManager
import com.pettycash.cashsms.sync.DjangoSyncWorker
import com.pettycash.cashsms.ui.ConversationScreen
import com.pettycash.cashsms.ui.HomeScreen
import com.pettycash.cashsms.ui.theme.GoogleMessagesTheme

class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* l'état de l'UI réagit via les variables observables */ }

    private var conversationAddress by mutableStateOf<String?>(null)
    private var conversationMessageId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        DjangoSyncWorker.ensurePeriodic(this)
        handleIntent(intent)

        setContent {
            val context = LocalContext.current
            val isDefaultSmsApp = remember { mutableStateOf(DefaultSmsRole.isDefaultSmsApp(context)) }
            var isSyncing by remember { mutableStateOf(false) }
            var syncStatus by remember { mutableStateOf("") }

            LaunchedEffect(Unit) {
                syncStatus = "Vérification des permissions..."

                val basePermissions = arrayOf(
                    Manifest.permission.READ_SMS,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.SEND_SMS
                )

                val allPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    basePermissions + Manifest.permission.POST_NOTIFICATIONS
                } else {
                    basePermissions
                }

                requestPermissions.launch(allPermissions)
                syncStatus = ""
            }

            GoogleMessagesTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val address = conversationAddress
                    if (address != null) {
                        ConversationScreen(
                            address = address,
                            targetMessageId = conversationMessageId,
                            onBack = {
                                conversationAddress = null
                                conversationMessageId = null
                            }
                        )
                    } else {
                        HomeScreen(
                            isDefaultSmsApp = isDefaultSmsApp.value,
                            onRequestDefaultRole = {
                                DefaultSmsRole.requestDefaultSmsRole(this@MainActivity) {
                                    isDefaultSmsApp.value = DefaultSmsRole.isDefaultSmsApp(this@MainActivity)
                                }
                            },
                            onImportHistory = {
                                isSyncing = true
                                syncStatus = "Importation des messages..."
                                val app = application as CashSmsApplication
                                SmsImportManager.importAllSms(context, app.db.smsDao()) {
                                    syncStatus = "Envoi vers Django..."
                                    DjangoSyncWorker.enqueueNow(context, isManual = true)
                                    isSyncing = false
                                    syncStatus = "Synchronisation démarrée !"
                                }
                            },
                            isSyncing = isSyncing,
                            syncStatus = syncStatus,
                            onOpenConversation = { clickedAddress, messageId ->
                                conversationAddress = clickedAddress
                                conversationMessageId = messageId
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        conversationAddress = intent.getStringExtra("address")
        conversationMessageId = intent
            .takeIf { it.hasExtra("message_id") }
            ?.getLongExtra("message_id", 0L)
    }
}
