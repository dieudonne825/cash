package com.pettycash.cashsms

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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

    private val requestDefaultSmsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkDefaultSmsWithRetries()
    }

    private var conversationAddress by mutableStateOf<String?>(null)
    private var conversationMessageId by mutableStateOf<Long?>(null)
    private var isDefaultSmsApp by mutableStateOf(false)

    private fun checkDefaultSmsWithRetries() {
        lifecycleScope.launch {
            for (i in 1..8) {
                val current = DefaultSmsRole.isDefaultSmsApp(this@MainActivity)
                isDefaultSmsApp = current
                if (current) break
                delay(350)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkDefaultSmsWithRetries()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        isDefaultSmsApp = DefaultSmsRole.isDefaultSmsApp(this)
        DjangoSyncWorker.ensurePeriodic(this)
        handleIntent(intent)

        setContent {
            val context = LocalContext.current
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
                    var isAppUnlocked by remember { mutableStateOf(!com.pettycash.cashsms.sync.SyncPrefs.isSecurityEnabled(context)) }

                    if (!isAppUnlocked) {
                        LockScreenOverlay(
                            onUnlockSuccess = { isAppUnlocked = true }
                        )
                    } else {
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
                                isDefaultSmsApp = isDefaultSmsApp,
                                onRequestDefaultRole = {
                                    val intent = DefaultSmsRole.getDefaultSmsIntent(this@MainActivity)
                                    if (intent != null) {
                                        requestDefaultSmsLauncher.launch(intent)
                                    } else {
                                        isDefaultSmsApp = true
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

@Composable
fun LockScreenOverlay(
    onUnlockSuccess: () -> Unit
) {
    val context = LocalContext.current
    val savedPin = remember { com.pettycash.cashsms.sync.SyncPrefs.getSavedPin(context) }
    val securityType = remember { com.pettycash.cashsms.sync.SyncPrefs.getSecurityType(context) }
    var enteredPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showBiometricAuth by remember { mutableStateOf(securityType == "BIOMETRIC") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Text(
                "Cash Sécurisé",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                if (securityType == "BIOMETRIC") "Utilisez l'empreinte digitale ou entrez votre code PIN" else "Entrez votre code PIN pour déverrouiller",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 12.dp)
            ) {
                for (i in 0 until 4) {
                    val active = i < enteredPin.length
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                shape = CircleShape
                            )
                            .border(
                                width = 1.dp,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                shape = CircleShape
                            )
                    )
                }
            }

            errorMessage?.let { msg ->
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                val keys = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("BIO", "0", "DEL")
                )

                keys.forEach { row ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        row.forEach { key ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1.2f),
                                contentAlignment = Alignment.Center
                            ) {
                                if (key == "BIO") {
                                    if (securityType == "BIOMETRIC") {
                                        IconButton(
                                            onClick = { showBiometricAuth = true },
                                            modifier = Modifier
                                                .size(60.dp)
                                                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                                        ) {
                                            Icon(
                                                Icons.Default.Fingerprint,
                                                contentDescription = "Empreinte",
                                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }
                                } else if (key == "DEL") {
                                    IconButton(
                                        onClick = {
                                            if (enteredPin.isNotEmpty()) {
                                                enteredPin = enteredPin.dropLast(1)
                                                errorMessage = null
                                            }
                                        },
                                        modifier = Modifier
                                            .size(60.dp)
                                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Backspace,
                                            contentDescription = "Retour",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            if (enteredPin.length < 4) {
                                                enteredPin += key
                                                errorMessage = null
                                                if (enteredPin.length == 4) {
                                                    if (enteredPin == savedPin) {
                                                        onUnlockSuccess()
                                                    } else {
                                                        errorMessage = "Code PIN incorrect"
                                                        enteredPin = ""
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        shape = CircleShape
                                    ) {
                                        Text(
                                            key,
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showBiometricAuth) {
            AlertDialog(
                onDismissRequest = { showBiometricAuth = false },
                icon = {
                    Icon(
                        Icons.Default.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = { Text("Authentification par empreinte") },
                text = { Text("Placez votre doigt sur le capteur d'empreinte digitale pour déverrouiller Cash.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showBiometricAuth = false
                            onUnlockSuccess()
                        }
                    ) {
                        Text("Déverrouiller", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBiometricAuth = false }) {
                        Text("Annuler")
                    }
                }
            )
        }
    }
}
