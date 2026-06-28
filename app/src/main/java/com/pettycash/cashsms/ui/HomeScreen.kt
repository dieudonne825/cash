package com.pettycash.cashsms.ui

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pettycash.cashsms.CashSmsApplication
import com.pettycash.cashsms.contacts.ContactInfo
import com.pettycash.cashsms.contacts.ContactResolver
import com.pettycash.cashsms.data.SmsMessageWithSync
import com.pettycash.cashsms.data.SyncTargetEntity
import com.pettycash.cashsms.sms.SmsSender
import com.pettycash.cashsms.sync.DjangoAuthClient
import com.pettycash.cashsms.sync.DjangoSyncWorker
import com.pettycash.cashsms.sync.SyncPrefs
import com.pettycash.cashsms.ui.components.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue

private const val TAG = "HomeScreen"

// ═══════════════════════════════════════════════════════════════
// HOME SCREEN PRINCIPAL
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    isDefaultSmsApp: Boolean,
    onRequestDefaultRole: () -> Unit,
    onImportHistory: () -> Unit,
    onOpenConversation: (String, Long?) -> Unit = { _, _ -> },
    isSyncing: Boolean = false,
    syncStatus: String = ""
) {
    val context = LocalContext.current
    val app = context.applicationContext as CashSmsApplication
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // ── États ──
    var activeTargetId by remember { mutableLongStateOf(0L) }
    var targets by remember { mutableStateOf<List<SyncTargetEntity>>(emptyList()) }
    var baseUrl by remember { mutableStateOf(SyncPrefs.getBaseUrl(context) ?: "") }
    var selectedTab by remember { mutableIntStateOf(0) }
    var token by remember { mutableStateOf(SyncPrefs.getToken(context) ?: "") }
    var backendType by remember { mutableStateOf(SyncPrefs.getBackendType(context)) }
    var loginUsername by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }
    var loginStatus by remember { mutableStateOf<String?>(null) }
    var errorDetails by remember { mutableStateOf<String?>(null) }
    var isLoggingIn by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var expandedSection by remember { mutableStateOf<String?>("connection") }
    var isRefreshing by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var contactsByAddress by remember { mutableStateOf<Map<String, ContactInfo>>(emptyMap()) }

    var refreshTrigger by remember { mutableIntStateOf(0) }
    val df = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    // Initialisation
    LaunchedEffect(Unit) {
        val base = (SyncPrefs.getActiveBaseUrl(context) ?: SyncPrefs.getBaseUrl(context))?.trim()
        val tok = SyncPrefs.getToken(context)?.trim()
        val btype = SyncPrefs.getBackendType(context)
        if (!base.isNullOrBlank() && !tok.isNullOrBlank()) {
            val target = app.db.syncDao().ensureTarget(base.trimEnd('/'), tok, btype)
            activeTargetId = target.id
            backendType = target.backendType
            Log.d(TAG, "activeTargetId initialisé = $activeTargetId, backendType = ${target.backendType}")
        }
        targets = app.db.syncDao().listTargets()
    }

    LaunchedEffect(activeTargetId) {
        if (activeTargetId != 0L) targets = app.db.syncDao().listTargets()
    }

    LaunchedEffect(syncStatus) {
        if (syncStatus == "Synchronisation démarrée !") {
            refreshTrigger++
            Log.d(TAG, "Refresh déclenché, trigger = $refreshTrigger")
        }
        if (syncStatus.isNotBlank() && syncStatus != "Synchronisation démarrée !") {
            snackbarHostState.showSnackbar(syncStatus, duration = SnackbarDuration.Short)
        }
    }

    val flow: Flow<List<SmsMessageWithSync>> = remember(activeTargetId, refreshTrigger) {
        if (activeTargetId == 0L) flowOf(emptyList())
        else {
            Log.d(TAG, "Nouveau flow pour targetId=$activeTargetId, trigger=$refreshTrigger")
            app.db.syncDao().watchLatestWithSync(targetId = activeTargetId, limit = 50)
        }
    }
    val latest by flow.collectAsStateWithLifecycle(initialValue = emptyList())

    val latestAddresses = remember(latest) {
        latest.mapNotNull { it.message.address?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
    }

    LaunchedEffect(latestAddresses) {
        contactsByAddress = withContext(Dispatchers.IO) {
            ContactResolver.resolve(context, latestAddresses)
        }
    }

    // Filtrage par recherche
    val filteredMessages = remember(latest, searchQuery, contactsByAddress) {
        if (searchQuery.isBlank()) latest
        else latest.filter {
            val address = it.message.address.orEmpty()
            val contactName = contactsByAddress[address]?.displayName.orEmpty()
            address.contains(searchQuery, ignoreCase = true) ||
                    contactName.contains(searchQuery, ignoreCase = true) ||
                    it.message.body.contains(searchQuery, ignoreCase = true)
        }
    }

    suspend fun showMessage(message: String) {
        snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
    }

    Scaffold(
        topBar = {
            CashTopAppBar(
                title = "Cash SMS",
                subtitle = if (activeTargetId != 0L) {
                    SyncPrefs.getActiveBaseUrl(context)?.removePrefix("https://")
                } else null,
                isSyncing = isSyncing || isRefreshing,
                onSyncClick = {
                    isRefreshing = true
                    DjangoSyncWorker.enqueueNow(context)
                    scope.launch {
                        showMessage("Synchronisation lancée")
                        kotlinx.coroutines.delay(1000)
                        isRefreshing = false
                    }
                },
                onSearchClick = { }
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = selectedTab == 0 && filteredMessages.isNotEmpty(),
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                FloatingActionButton(
                    onClick = {
                        DjangoSyncWorker.enqueueNow(context)
                        scope.launch { showMessage("Synchronisation lancée vers Django") }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape,
                    elevation = FloatingActionButtonDefaults.elevation(8.dp)
                ) {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 0.dp
            ) {
                val tabs = listOf(
                    Triple("Messages", Icons.Outlined.Chat, Icons.Filled.Chat),
                    Triple("Envoyer", Icons.Outlined.Send, Icons.Filled.Send),
                    Triple("Paramètres", Icons.Outlined.Settings, Icons.Filled.Settings)
                )
                tabs.forEachIndexed { index, (label, iconOutlined, iconFilled) ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        label = {
                            AnimatedVisibility(visible = selectedTab == index) {
                                Text(label)
                            }
                        },
                        icon = {
                            BadgedBox(badge = {
                                if (index == 0 && filteredMessages.any {
                                        it.syncState.equals("PENDING", ignoreCase = true)
                                    }) {
                                    Badge(containerColor = MaterialTheme.colorScheme.error)
                                }
                            }) {
                                Icon(
                                    if (selectedTab == index) iconFilled else iconOutlined,
                                    contentDescription = label
                                )
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        ),
                        alwaysShowLabel = false
                    )
                }
            }
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            AnimatedVisibility(
                visible = selectedTab == 0,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                SearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            AnimatedVisibility(
                visible = isSyncing,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer
                )
            }

            when (selectedTab) {
                0 -> MessagesTabRedesigned(
                    messages = filteredMessages,
                    dateFormat = df,
                    isEmpty = filteredMessages.isEmpty() && searchQuery.isBlank(),
                    isSearchEmpty = filteredMessages.isEmpty() && searchQuery.isNotBlank(),
                    searchQuery = searchQuery,
                    contactsByAddress = contactsByAddress,
                    onRefresh = {
                        DjangoSyncWorker.enqueueNow(context)
                        scope.launch { showMessage("Synchronisation lancée") }
                    },
                    onOpenConversation = onOpenConversation
                )
                1 -> SendTabRedesigned(
                    phone = phone,
                    onPhoneChange = { phone = it },
                    body = body,
                    onBodyChange = { body = it },
                    isSending = isSending,
                    onSend = {
                        val addr = phone.trim()
                        val msg = body.trim()
                        if (addr.isEmpty() || msg.isEmpty()) {
                            scope.launch { showMessage("Numéro et message requis") }
                            return@SendTabRedesigned
                        }
                        isSending = true
                        SmsSender.sendText(context, app.db.smsDao(), addr, msg)
                        body = ""
                        DjangoSyncWorker.enqueueNow(context)
                        isSending = false
                        scope.launch { showMessage("SMS envoyé à $addr") }
                    }
                )
                2 -> SettingsTabRedesigned(
                    backendType = backendType,
                    onBackendTypeChange = {
                        backendType = it
                        SyncPrefs.setBackendType(context, it)
                    },
                    isDefaultSmsApp = isDefaultSmsApp,
                    onRequestDefaultRole = onRequestDefaultRole,
                    onImportHistory = onImportHistory,
                    onGenerateTestSms = {
                        isRefreshing = true
                        scope.launch {
                            com.pettycash.cashsms.sms.SmsImportManager.generateTestMessages(context, app.db.smsDao()) {
                                isRefreshing = false
                                scope.launch { showMessage("✅ SMS de test générés !") }
                                DjangoSyncWorker.enqueueNow(context)
                            }
                        }
                    },
                    baseUrl = baseUrl,
                    onBaseUrlChange = { baseUrl = it },
                    loginUsername = loginUsername,
                    onUsernameChange = { loginUsername = it },
                    loginPassword = loginPassword,
                    onPasswordChange = { loginPassword = it },
                    token = token,
                    onTokenChange = { token = it },
                    loginStatus = loginStatus,
                    errorDetails = errorDetails,
                    isLoggingIn = isLoggingIn,
                    onLogin = {
                        var base = baseUrl.trim().removeSuffix("/")
                        if (base.endsWith("/admin")) base = base.removeSuffix("/admin")
                        val u = loginUsername.trim()
                        val p = loginPassword
                        if (base.isEmpty() || u.isEmpty() || p.isEmpty()) {
                            loginStatus = if (backendType == "LARAVEL") "Base URL / email / mot de passe requis" else "Base URL / username / password requis"
                        } else {
                            loginStatus = "Connexion..."
                            isLoggingIn = true
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val res = if (backendType == "LARAVEL") {
                                        val laravelRes = com.pettycash.cashsms.sync.LaravelAuthClient.login(base, u, p)
                                        com.pettycash.cashsms.sync.DjangoAuthClient.LoginResult(
                                            token = laravelRes.token,
                                            errorMessage = laravelRes.errorMessage,
                                            errorDetails = laravelRes.errorDetails,
                                            errorUrl = laravelRes.errorUrl,
                                            httpCode = laravelRes.httpCode
                                        )
                                    } else {
                                        com.pettycash.cashsms.sync.DjangoAuthClient.login(base, u, p)
                                    }
                                    if (res.token != null) {
                                        token = res.token
                                        loginStatus = "✅ Connecté"
                                        SyncPrefs.setBaseUrl(context, base)
                                        SyncPrefs.setToken(context, res.token)
                                        SyncPrefs.setBackendType(context, backendType)
                                        SyncPrefs.setActiveBaseUrl(context, base)
                                        val target = app.db.syncDao().ensureTarget(base, res.token, backendType)
                                        activeTargetId = target.id
                                        targets = app.db.syncDao().listTargets()
                                        DjangoSyncWorker.ensurePeriodic(context)
                                    } else {
                                        loginStatus = "Échec: ${res.errorMessage}"
                                        errorDetails = res.errorDetails
                                    }
                                } catch (e: Exception) {
                                    loginStatus = "Erreur: ${e.message}"
                                } finally {
                                    isLoggingIn = false
                                }
                            }
                        }
                    },
                    onSaveToken = {
                        var base = baseUrl.trim().removeSuffix("/")
                        if (base.endsWith("/admin")) base = base.removeSuffix("/admin")
                        SyncPrefs.setBaseUrl(context, base)
                        SyncPrefs.setToken(context, token)
                        SyncPrefs.setBackendType(context, backendType)
                        scope.launch {
                            val target = app.db.syncDao().ensureTarget(base, token.trim(), backendType)
                            activeTargetId = target.id
                            targets = app.db.syncDao().listTargets()
                            DjangoSyncWorker.ensurePeriodic(context)
                            showMessage("Token sauvegardé")
                        }
                    },
                    onSyncNow = { DjangoSyncWorker.enqueueNow(context) },
                    targets = targets,
                    activeTargetId = activeTargetId,
                    onSelectTarget = { t ->
                        SyncPrefs.setActiveBaseUrl(context, t.baseUrl)
                        SyncPrefs.setBackendType(context, t.backendType)
                        baseUrl = t.baseUrl
                        token = t.token
                        backendType = t.backendType
                        activeTargetId = t.id
                    },
                    expandedSection = expandedSection,
                    onToggleSection = { section ->
                        expandedSection = if (expandedSection == section) null else section
                    }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// MESSAGES TAB REDESIGNÉ
// ═══════════════════════════════════════════════════════════════

@Composable
fun MessagesTabRedesigned(
    messages: List<SmsMessageWithSync>,
    dateFormat: SimpleDateFormat,
    isEmpty: Boolean,
    isSearchEmpty: Boolean,
    searchQuery: String,
    contactsByAddress: Map<String, ContactInfo>,
    onRefresh: () -> Unit,
    onOpenConversation: (String, Long?) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isEmpty) {
            item {
                EmptyStateRedesigned(
                    icon = Icons.Default.Inbox,
                    title = "Aucun message",
                    subtitle = "Les messages synchronisés apparaîtront ici après la première connexion.",
                    actionLabel = "Synchroniser maintenant",
                    onAction = onRefresh
                )
            }
        } else if (isSearchEmpty) {
            item {
                EmptyStateRedesigned(
                    icon = Icons.Default.SearchOff,
                    title = "Aucun résultat",
                    subtitle = "Aucune conversation ne correspond à \"$searchQuery\""
                )
            }
        } else {
            item {
                val pendingCount = messages.count {
                    !it.syncState.equals("SYNCED", ignoreCase = true) &&
                    !it.syncState.equals("OK", ignoreCase = true)
                }
                RecentMessagesHeader(
                    messageCount = messages.size,
                    conversationCount = messages.mapNotNull { it.message.address }.distinct().size,
                    contactCount = contactsByAddress.size,
                    pendingCount = pendingCount
                )
                Spacer(modifier = Modifier.height(16.dp))
                FinancialDashboardCard(messages = messages)
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Messages récents",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (pendingCount > 0) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                            ) {
                                Text(
                                    "$pendingCount en attente",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                "${messages.size}",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
            items(messages, key = { it.message.id }) { message ->
                val address = message.message.address.orEmpty()
                ConversationCardRedesigned(
                    message = message,
                    dateFormat = dateFormat,
                    contactInfo = contactsByAddress[address],
                    onClick = {
                        onOpenConversation(
                            address,
                            message.message.id
                        )
                    }
                )
            }
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// CONVERSATION CARD REDESIGNÉE
// ═══════════════════════════════════════════════════════════════

@Composable
fun ConversationCardRedesigned(
    message: SmsMessageWithSync,
    dateFormat: SimpleDateFormat,
    contactInfo: ContactInfo? = null,
    onClick: () -> Unit
) {
    val msg = message.message
    val isSynced = message.syncState.equals("SYNCED", ignoreCase = true) ||
            message.syncState.equals("OK", ignoreCase = true)
    val isIncoming = msg.type == 1
    val address = msg.address ?: "Inconnu"
    val displayName = contactInfo?.displayName ?: address
    val subtitle = contactInfo?.phoneNumber?.takeIf { it != displayName }

    val avatarColor = remember(displayName) {
        val colors = listOf(
            Color(0xFF1B6EF3), Color(0xFF00BFA5), Color(0xFFFF6D00),
            Color(0xFF7C4DFF), Color(0xFF00C853), Color(0xFFFF3D00),
            Color(0xFF2962FF), Color(0xFF00B8D4)
        )
        colors[displayName.hashCode().absoluteValue % colors.size]
    }
    val initial = displayName.filter { it.isLetterOrDigit() }.take(1).uppercase().ifEmpty { "?" }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSynced) 0.dp else 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Indicateur latéral : subtil mais immédiatement visible si non sync ──
            if (!isSynced) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                )
            } else {
                Spacer(modifier = Modifier.width(3.dp))
            }

            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(avatarColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = dateFormat.format(Date(msg.date)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(5.dp))

                Text(
                    text = msg.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Badge direction (Reçu / Envoyé)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isIncoming)
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        else
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ) {
                        Text(
                            if (isIncoming) "Reçu" else "Envoyé",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isIncoming)
                                MaterialTheme.colorScheme.onSecondaryContainer
                            else
                                MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // ── Badge sync / non-sync ──
                    if (!isSynced) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.CloudOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    "En attente",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    } else {
                        // Sync : bleu primaire comme demandé
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.CloudDone,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Sync",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// EMPTY STATE REDESIGNÉ
// ═══════════════════════════════════════════════════════════════

@Composable
private fun RecentMessagesHeader(
    messageCount: Int,
    conversationCount: Int,
    contactCount: Int,
    pendingCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.78f)
                        )
                    ),
                    shape = RoundedCornerShape(28.dp)
                )
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Boîte de réception",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "Messages récents, contacts et synchronisation",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                        modifier = Modifier.size(50.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Chat,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    InboxStatPill("$messageCount messages")
                    InboxStatPill("$conversationCount fils")
                    if (contactCount > 0) InboxStatPill("$contactCount contacts")
                    if (pendingCount > 0) InboxStatPill("$pendingCount en attente", isWarning = true)
                }
            }
        }
    }
}

@Composable
private fun InboxStatPill(
    text: String,
    isWarning: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (isWarning) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
        }
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun EmptyStateRedesigned(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }

        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .height(48.dp)
            ) {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(actionLabel, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// SEND TAB REDESIGNÉ
// ═══════════════════════════════════════════════════════════════

@Composable
fun SendTabRedesigned(
    phone: String,
    onPhoneChange: (String) -> Unit,
    body: String,
    onBodyChange: (String) -> Unit,
    isSending: Boolean,
    onSend: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(24.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.2f),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = Color.White
                                )
                            }
                        }
                        Column {
                            Text(
                                "Nouveau Message",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                "Envoyez un SMS rapidement",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = phone,
                    onValueChange = onPhoneChange,
                    label = { Text("Numéro de téléphone") },
                    placeholder = { Text("+33 6 12 34 56 78") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Phone,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                )

                OutlinedTextField(
                    value = body,
                    onValueChange = onBodyChange,
                    label = { Text("Votre message") },
                    placeholder = { Text("Tapez votre message ici...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 8,
                    leadingIcon = {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Message,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                )

                Button(
                    onClick = onSend,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = !isSending && phone.isNotBlank() && body.isNotBlank(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Envoi en cours...", fontWeight = FontWeight.SemiBold)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Envoyer le SMS", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// SETTINGS TAB REDESIGNÉ
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTabRedesigned(
    backendType: String,
    onBackendTypeChange: (String) -> Unit,
    isDefaultSmsApp: Boolean,
    onRequestDefaultRole: () -> Unit,
    onImportHistory: () -> Unit,
    onGenerateTestSms: () -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    loginUsername: String,
    onUsernameChange: (String) -> Unit,
    loginPassword: String,
    onPasswordChange: (String) -> Unit,
    token: String,
    onTokenChange: (String) -> Unit,
    loginStatus: String?,
    errorDetails: String?,
    isLoggingIn: Boolean,
    onLogin: () -> Unit,
    onSaveToken: () -> Unit,
    onSyncNow: () -> Unit,
    targets: List<SyncTargetEntity>,
    activeTargetId: Long,
    onSelectTarget: (SyncTargetEntity) -> Unit,
    expandedSection: String?,
    onToggleSection: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            StatusCardRedesigned(
                isDefaultSmsApp = isDefaultSmsApp,
                onRequestDefaultRole = onRequestDefaultRole,
                onImportHistory = onImportHistory,
                onGenerateTestSms = onGenerateTestSms
            )
        }

        item {
            ExpandableCard(
                title = "Configuration du serveur",
                icon = Icons.Default.Link,
                isExpanded = expandedSection == "connection",
                onToggle = { onToggleSection("connection") }
            ) {
                ConnectionSection(
                    backendType = backendType,
                    onBackendTypeChange = onBackendTypeChange,
                    baseUrl = baseUrl,
                    onBaseUrlChange = onBaseUrlChange,
                    loginUsername = loginUsername,
                    onUsernameChange = onUsernameChange,
                    loginPassword = loginPassword,
                    onPasswordChange = onPasswordChange,
                    token = token,
                    onTokenChange = onTokenChange,
                    loginStatus = loginStatus,
                    errorDetails = errorDetails,
                    isLoggingIn = isLoggingIn,
                    onLogin = onLogin,
                    onSaveToken = onSaveToken,
                    onSyncNow = onSyncNow
                )
            }
        }

        if (targets.isNotEmpty()) {
            item {
                ExpandableCard(
                    title = "Domaines enregistrés (${targets.size})",
                    icon = Icons.Default.Storage,
                    isExpanded = expandedSection == "targets",
                    onToggle = { onToggleSection("targets") }
                ) {
                    TargetsList(
                        targets = targets,
                        activeTargetId = activeTargetId,
                        onSelect = onSelectTarget
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Column {
                        Text(
                            "Cash SMS",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "v1.0 • Synchronisation sécurisée avec Django",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// STATUS CARD REDESIGNÉ
// ═══════════════════════════════════════════════════════════════

@Composable
fun StatusCardRedesigned(
    isDefaultSmsApp: Boolean,
    onRequestDefaultRole: () -> Unit,
    onImportHistory: () -> Unit,
    onGenerateTestSms: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = if (isDefaultSmsApp) {
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        } else {
                            listOf(
                                MaterialTheme.colorScheme.error,
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                            )
                        }
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.2f),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isDefaultSmsApp) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = Color.White
                            )
                        }
                    }
                    Column {
                        Text(
                            if (isDefaultSmsApp) "App SMS par défaut" else "Configuration requise",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            if (isDefaultSmsApp) "Prêt à recevoir et envoyer des SMS"
                            else "Définissez Cash SMS comme application par défaut",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }

                if (!isDefaultSmsApp) {
                    Button(
                        onClick = onRequestDefaultRole,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Définir par défaut", fontWeight = FontWeight.SemiBold)
                    }
                }

                OutlinedButton(
                    onClick = onImportHistory,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    ),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        width = 1.dp,
                        brush = SolidColor(Color.White.copy(alpha = 0.5f))
                    )
                ) {
                    Icon(Icons.Default.History, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Importer l'historique", fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onGenerateTestSms,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    ),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        width = 1.dp,
                        brush = SolidColor(Color.White.copy(alpha = 0.5f))
                    )
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Générer des SMS de test", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
