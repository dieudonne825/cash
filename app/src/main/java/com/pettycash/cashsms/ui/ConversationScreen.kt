package com.pettycash.cashsms.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pettycash.cashsms.CashSmsApplication
import com.pettycash.cashsms.contacts.ContactInfo
import com.pettycash.cashsms.contacts.ContactResolver
import com.pettycash.cashsms.sms.SmsSender
import com.pettycash.cashsms.sync.DjangoSyncWorker
import com.pettycash.cashsms.ui.components.ConversationTopBar
import com.pettycash.cashsms.ui.components.MessageList
import com.pettycash.cashsms.ui.components.ReplyBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    address: String,
    targetMessageId: Long? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as CashSmsApplication
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var replyText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var contactInfo by remember(address) { mutableStateOf<ContactInfo?>(null) }

    val df = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    val messages by app.db.smsDao()
        .watchConversation(address)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val isEmpty by remember(messages) { derivedStateOf { messages.isEmpty() } }

    LaunchedEffect(address) {
        contactInfo = withContext(Dispatchers.IO) {
            ContactResolver.resolve(context, listOf(address))[address]
        }
    }

    Scaffold(
        topBar = {
            // ── Ancrage visuel : séparation subtile sous la top bar ──
            Column {
                ConversationTopBar(
                    address = address,
                    contactName = contactInfo?.displayName,
                    messageCount = messages.size,
                    onBack = onBack
                )
            }
        },
        bottomBar = {
            // ── ReplyBar flottante : ombre + coins arrondis en haut ──
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    ReplyBar(
                        text = replyText,
                        onTextChange = { replyText = it },
                        isSending = isSending,
                        onSend = {
                            val msg = replyText.trim()
                            if (msg.isEmpty()) return@ReplyBar
                            isSending = true
                            scope.launch {
                                try {
                                    SmsSender.sendText(
                                        context = context,
                                        dao = app.db.smsDao(),
                                        address = address,
                                        body = msg
                                    )
                                    replyText = ""
                                    DjangoSyncWorker.enqueueNow(context)
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar(
                                        message = "Erreur d'envoi : ${e.message}",
                                        actionLabel = "OK",
                                        duration = SnackbarDuration.Long
                                    )
                                } finally {
                                    isSending = false
                                }
                            }
                        }
                    )
                }
            }
        },
        snackbarHost = {
            // ── Snackbar positionnée au-dessus de la zone de saisie ──
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = 80.dp)
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(16.dp)
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    )
                )
        ) {
            // ── Liste avec animation de fondu ──
            AnimatedVisibility(
                visible = !isEmpty,
                enter = fadeIn(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(200))
            ) {
                MessageList(
                    messages = messages,
                    dateFormat = df,
                    targetMessageId = targetMessageId,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ── État vide designé et animé ──
            AnimatedVisibility(
                visible = isEmpty,
                enter = fadeIn(animationSpec = tween(400)) + slideInVertically(
                    initialOffsetY = { it / 4 },
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                ),
                exit = fadeOut(animationSpec = tween(200))
            ) {
                EmptyConversationState(address = address)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// ÉTAT VIDE DESIGNÉ
// ═══════════════════════════════════════════════════════════════

@Composable
private fun EmptyConversationState(address: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
            modifier = Modifier.size(80.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Nouvelle conversation",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Aucun message avec $address pour l'instant.\nÉcrivez votre premier message ci-dessous.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
