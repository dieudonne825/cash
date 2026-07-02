package com.pettycash.cashsms.ui

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.pettycash.cashsms.sync.LaravelClient
import com.pettycash.cashsms.sync.DjangoClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.lazy.LazyRow
import kotlinx.coroutines.delay
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
            app.db.syncDao().watchLatestWithSync(targetId = activeTargetId, limit = 500)
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
                        scope.launch { showMessage("Synchronisation lancée !") }
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
                    Triple("Clients", Icons.Outlined.People, Icons.Filled.People),
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
                    },
                    onOpenConversation = onOpenConversation,
                    latestMessages = latest
                )
                2 -> ClientsTabRedesigned(
                    messages = latest,
                    baseUrl = baseUrl,
                    token = token,
                    backendType = backendType
                )
                3 -> SettingsTabRedesigned(
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
                            loginStatus = "Veuillez remplir tous les champs"
                        } else {
                            loginStatus = "Connexion..."
                            isLoggingIn = true
                            scope.launch(Dispatchers.IO) {
                                try {
                                    // Try Django first
                                    var res = com.pettycash.cashsms.sync.DjangoAuthClient.login(base, u, p)
                                    var detectedType = "DJANGO"
                                    
                                    // If Django fails, try Laravel
                                    if (res.token == null) {
                                        val laravelRes = com.pettycash.cashsms.sync.LaravelAuthClient.login(base, u, p)
                                        if (laravelRes.token != null) {
                                            res = com.pettycash.cashsms.sync.DjangoAuthClient.LoginResult(
                                                token = laravelRes.token,
                                                errorMessage = laravelRes.errorMessage,
                                                errorDetails = laravelRes.errorDetails,
                                                errorUrl = laravelRes.errorUrl,
                                                httpCode = laravelRes.httpCode
                                            )
                                            detectedType = "LARAVEL"
                                        }
                                    }
                                    
                                    if (res.token != null) {
                                        backendType = detectedType
                                        token = res.token
                                        loginStatus = "✅ Connecté"
                                        SyncPrefs.setBaseUrl(context, base)
                                        SyncPrefs.setToken(context, res.token)
                                        SyncPrefs.setBackendType(context, detectedType)
                                        SyncPrefs.setActiveBaseUrl(context, base)
                                        val target = app.db.syncDao().ensureTarget(base, res.token, detectedType)
                                        activeTargetId = target.id
                                        targets = app.db.syncDao().listTargets()
                                        DjangoSyncWorker.ensurePeriodic(context)
                                    } else {
                                        loginStatus = "Échec de connexion"
                                        errorDetails = res.errorMessage
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
                        scope.launch {
                            val detectedType = autoDetectBackendType(base, token.trim())
                            backendType = detectedType
                            SyncPrefs.setBackendType(context, detectedType)
                            val target = app.db.syncDao().ensureTarget(base, token.trim(), detectedType)
                            activeTargetId = target.id
                            targets = app.db.syncDao().listTargets()
                            DjangoSyncWorker.ensurePeriodic(context)
                            showMessage("Token sauvegardé et configuré")
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
    var filterOperator by remember { mutableStateOf("Tous") }
    var filterSyncStatus by remember { mutableStateOf("Tous") }

    fun getMessageOperator(body: String, address: String): String {
        val text = (body + " " + address).uppercase()
        return when {
            text.contains("MTN") || text.contains("MOMO") -> "MTN"
            text.contains("ORANGE") || text.contains("OM") -> "Orange"
            text.contains("WAVE") -> "Wave"
            text.contains("MOOV") -> "Moov"
            else -> "Autre"
        }
    }

    val filteredList = remember(messages, filterOperator, filterSyncStatus) {
        messages.filter { item ->
            val matchesOperator = when (filterOperator) {
                "Tous" -> true
                else -> getMessageOperator(item.message.body, item.message.address.orEmpty()) == filterOperator
            }
            
            val matchesStatus = when (filterSyncStatus) {
                "Tous" -> true
                "En attente" -> !item.syncState.equals("SYNCED", ignoreCase = true) && !item.syncState.equals("OK", ignoreCase = true)
                "Synchronisés" -> item.syncState.equals("SYNCED", ignoreCase = true) || item.syncState.equals("OK", ignoreCase = true)
                else -> true
            }
            
            matchesOperator && matchesStatus
        }
    }

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
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    "Filtrer par Opérateur",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    val operators = listOf("Tous", "MTN", "Orange", "Wave", "Moov")
                    items(operators) { op ->
                        SegmentedChip(
                            selected = filterOperator == op,
                            label = op,
                            onClick = { filterOperator = op }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Filtrer par Statut",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    val statuses = listOf("Tous", "En attente", "Synchronisés")
                    items(statuses) { status ->
                        SegmentedChip(
                            selected = filterSyncStatus == status,
                            label = status,
                            onClick = { filterSyncStatus = status }
                        )
                    }
                }

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
                                "${filteredList.size}",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
            items(filteredList, key = { it.message.id }) { message ->
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
    onSend: () -> Unit,
    onOpenConversation: (String, Long?) -> Unit,
    latestMessages: List<SmsMessageWithSync>
) {
    val context = LocalContext.current
    var contactsList by remember { mutableStateOf<List<ContactInfo>>(emptyList()) }
    var contactSearchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    var showContactPickerSheet by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            contactsList = ContactResolver.fetchAllContacts(context)
        }
    }

    // Filter contacts based on either the input text or a search query
    val filteredContacts = remember(contactsList, phone, contactSearchQuery) {
        val query = if (contactSearchQuery.isNotBlank()) contactSearchQuery else phone
        if (query.isBlank()) {
            contactsList
        } else {
            contactsList.filter {
                it.displayName.contains(query, ignoreCase = true) ||
                it.phoneNumber.contains(query, ignoreCase = true)
            }
        }
    }

    // Sort and Group contacts alphabetically A-Z
    val groupedContacts = remember(filteredContacts) {
        filteredContacts
            .sortedBy { it.displayName.lowercase() }
            .groupBy { it.displayName.firstOrNull()?.uppercaseChar() ?: '#' }
    }

    // Extract recent contact entries from existing threads
    val recentAddresses = remember(latestMessages) {
        latestMessages.mapNotNull { it.message.address?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .take(5)
    }

    val recentContacts = remember(recentAddresses, contactsList) {
        recentAddresses.map { addr ->
            contactsList.find { it.phoneNumber == addr } ?: ContactInfo(displayName = addr, phoneNumber = addr)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
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
                                "Envoyez un SMS ou démarrez un chat",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }

        // Form Card
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
                    onValueChange = {
                        onPhoneChange(it)
                        if (contactSearchQuery.isNotBlank()) contactSearchQuery = ""
                    },
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
                    trailingIcon = {
                        IconButton(onClick = { showContactPickerSheet = true }) {
                            Icon(
                                imageVector = Icons.Default.Contacts,
                                contentDescription = "Choisir depuis les contacts",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                )

                // Inline match suggestion (if typing match)
                val activeSuggestions = remember(phone, contactsList) {
                    if (phone.isNotBlank() && phone.length >= 2) {
                        contactsList.filter {
                            (it.displayName.contains(phone, ignoreCase = true) ||
                             it.phoneNumber.contains(phone, ignoreCase = true)) &&
                            it.phoneNumber != phone
                        }.take(3)
                    } else {
                        emptyList()
                    }
                }

                AnimatedVisibility(visible = activeSuggestions.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Suggestions de contacts",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        activeSuggestions.forEach { contact ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onPhoneChange(contact.phoneNumber)
                                        focusRequester.requestFocus()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = contact.displayName.take(1).uppercase(),
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = contact.displayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = contact.phoneNumber,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = body,
                    onValueChange = onBodyChange,
                    label = { Text("Votre message") },
                    placeholder = { Text("Tapez votre message ici...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    minLines = 4,
                    maxLines = 6,
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

        // Contact Directory Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Contacts,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Contacts de l'appareil",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "${contactsList.size} au total",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Recent Chats row
                if (recentContacts.isNotEmpty() && contactSearchQuery.isBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Récents",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            items(recentContacts) { contact ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .width(64.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            onPhoneChange(contact.phoneNumber)
                                            focusRequester.requestFocus()
                                        }
                                        .padding(vertical = 4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(
                                                Brush.linearGradient(
                                                    colors = listOf(
                                                        MaterialTheme.colorScheme.primaryContainer,
                                                        MaterialTheme.colorScheme.tertiaryContainer
                                                    )
                                                )
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = contact.displayName.take(1).uppercase(),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        // Quick chat thread shortcut overlay
                                        Box(
                                            modifier = Modifier
                                                .size(16.dp)
                                                .align(Alignment.BottomEnd)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary)
                                                .clickable {
                                                    onOpenConversation(contact.phoneNumber, null)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Chat,
                                                contentDescription = "Ouvrir",
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(10.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = contact.displayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                        Divider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                            thickness = 1.dp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }

                // Search Bar for Contacts
                OutlinedTextField(
                    value = contactSearchQuery,
                    onValueChange = { contactSearchQuery = it },
                    placeholder = { Text("Rechercher un contact...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                )

                if (filteredContacts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SearchOff,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Text(
                                text = if (contactsList.isEmpty()) "Aucun contact trouvé" else "Aucun résultat pour \"$contactSearchQuery\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        groupedContacts.forEach { (char, contactsForChar) ->
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = char.toString(),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            items(contactsForChar) { contact ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (phone == contact.phoneNumber) 
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) 
                                            else 
                                                Color.Transparent
                                        )
                                        .clickable {
                                            onPhoneChange(contact.phoneNumber)
                                            focusRequester.requestFocus()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Avatar with beautiful initials and dynamic distinct colors
                                    val avatarColor = remember(contact.displayName) {
                                        val colors = listOf(
                                            Color(0xFFE57373), Color(0xFFF06292), Color(0xFFBA68C8),
                                            Color(0xFF9575CD), Color(0xFF7986CB), Color(0xFF64B5F6),
                                            Color(0xFF4FC3F7), Color(0xFF4DB6AC), Color(0xFF81C784),
                                            Color(0xFFD4E157), Color(0xFFFFD54F), Color(0xFFFFB74D)
                                        )
                                        val index = Math.abs(contact.displayName.hashCode()) % colors.size
                                        colors[index]
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(avatarColor.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = contact.displayName.take(1).uppercase(),
                                            color = avatarColor,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = contact.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = contact.phoneNumber,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Quick Native Actions: Instant Navigation to Chat Conversation
                                        IconButton(
                                            onClick = {
                                                onOpenConversation(contact.phoneNumber, null)
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Chat,
                                                contentDescription = "Ouvrir la discussion",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        if (phone == contact.phoneNumber) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Sélectionné",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        } else {
                                            IconButton(
                                                onClick = {
                                                    onPhoneChange(contact.phoneNumber)
                                                    focusRequester.requestFocus()
                                                },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Create,
                                                    contentDescription = "Remplir",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showContactPickerSheet) {
            var dialogSearchQuery by remember { mutableStateOf("") }
            val dialogFilteredContacts = remember(contactsList, dialogSearchQuery) {
                if (dialogSearchQuery.isBlank()) {
                    contactsList
                } else {
                    contactsList.filter {
                        it.displayName.contains(dialogSearchQuery, ignoreCase = true) ||
                        it.phoneNumber.contains(dialogSearchQuery, ignoreCase = true)
                    }
                }
            }
            val dialogGroupedContacts = remember(dialogFilteredContacts) {
                dialogFilteredContacts
                    .sortedBy { it.displayName.lowercase() }
                    .groupBy { it.displayName.firstOrNull()?.uppercaseChar() ?: '#' }
            }

            Dialog(onDismissRequest = { showContactPickerSheet = false }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.85f),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "Contacts du téléphone",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "${contactsList.size} contacts enregistrés",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { showContactPickerSheet = false }) {
                                Icon(Icons.Default.Close, contentDescription = "Fermer")
                            }
                        }

                        // Search Input
                        OutlinedTextField(
                            value = dialogSearchQuery,
                            onValueChange = { dialogSearchQuery = it },
                            placeholder = { Text("Rechercher un contact...") },
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            trailingIcon = {
                                if (dialogSearchQuery.isNotBlank()) {
                                    IconButton(onClick = { dialogSearchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Effacer")
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        )

                        // Contacts List
                        if (dialogGroupedContacts.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "Aucun contact trouvé",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                dialogGroupedContacts.forEach { (letter, contactsInGroup) ->
                                    item {
                                        Text(
                                            text = letter.toString(),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 8.dp)
                                        )
                                    }
                                    items(contactsInGroup) { contact ->
                                        val initials = contact.displayName.take(2).uppercase()
                                        val avatarBgColor = remember(contact.displayName) {
                                            val colors = listOf(
                                                Color(0xFF2196F3), Color(0xFFE91E63), Color(0xFF4CAF50),
                                                Color(0xFFFF9800), Color(0xFF9C27B0), Color(0xFF00BCD4),
                                                Color(0xFF3F51B5), Color(0xFF009688)
                                            )
                                            val idx = contact.displayName.hashCode().coerceAtLeast(0) % colors.size
                                            colors[idx]
                                        }

                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    onPhoneChange(contact.phoneNumber)
                                                    showContactPickerSheet = false
                                                    focusRequester.requestFocus()
                                                },
                                            shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Surface(
                                                        shape = CircleShape,
                                                        color = avatarBgColor.copy(alpha = 0.15f),
                                                        modifier = Modifier.size(40.dp)
                                                    ) {
                                                        Box(contentAlignment = Alignment.Center) {
                                                            Text(
                                                                text = initials,
                                                                color = avatarBgColor,
                                                                style = MaterialTheme.typography.titleSmall,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }
                                                    }
                                                    Column {
                                                        Text(
                                                            text = contact.displayName,
                                                            style = MaterialTheme.typography.bodyLarge,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                        Text(
                                                            text = contact.phoneNumber,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }

                                                IconButton(
                                                    onClick = {
                                                        onPhoneChange(contact.phoneNumber)
                                                        showContactPickerSheet = false
                                                        onOpenConversation(contact.phoneNumber, null)
                                                    },
                                                    modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Chat,
                                                        contentDescription = "Discuter",
                                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
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
    val context = LocalContext.current
    var isSecEnabled by remember { mutableStateOf(SyncPrefs.isSecurityEnabled(context)) }
    var secType by remember { mutableStateOf(SyncPrefs.getSecurityType(context)) }
    var savedPinValue by remember { mutableStateOf(SyncPrefs.getSavedPin(context)) }
    
    var showPinDialog by remember { mutableStateOf(false) }
    var pinInputValue by remember { mutableStateOf("") }

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
            ExpandableCard(
                title = "Sécurité & Accès",
                icon = Icons.Default.Lock,
                isExpanded = expandedSection == "security",
                onToggle = { onToggleSection("security") }
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Verrouiller l'application", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text("Sécuriser l'accès à Cash à l'ouverture", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = isSecEnabled,
                            onCheckedChange = { enabled ->
                                isSecEnabled = enabled
                                SyncPrefs.setSecurityEnabled(context, enabled)
                            }
                        )
                    }

                    if (isSecEnabled) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Méthode de verrouillage", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        secType = "PIN"
                                        SyncPrefs.setSecurityType(context, "PIN")
                                    }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = secType == "PIN",
                                    onClick = {
                                        secType = "PIN"
                                        SyncPrefs.setSecurityType(context, "PIN")
                                    }
                                )
                                Column {
                                    Text("Code PIN uniquement", style = MaterialTheme.typography.bodyLarge)
                                    Text("Demander le code PIN de 4 chiffres", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        secType = "BIOMETRIC"
                                        SyncPrefs.setSecurityType(context, "BIOMETRIC")
                                    }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = secType == "BIOMETRIC",
                                    onClick = {
                                        secType = "BIOMETRIC"
                                        SyncPrefs.setSecurityType(context, "BIOMETRIC")
                                    }
                                )
                                Column {
                                    Text("Empreinte digitale + Code PIN", style = MaterialTheme.typography.bodyLarge)
                                    Text("Empreinte biométrique par défaut, code PIN en secours", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Code PIN d'accès", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text("Code actuel : $savedPinValue", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(
                                onClick = {
                                    pinInputValue = ""
                                    showPinDialog = true
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Modifier le PIN", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
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
                            "v1.0 • Synchronisation sécurisée",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text("Modifier le code PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Saisissez un nouveau code PIN à 4 chiffres :")
                    OutlinedTextField(
                        value = pinInputValue,
                        onValueChange = { input ->
                            if (input.length <= 4 && input.all { it.isDigit() }) {
                                pinInputValue = input
                            }
                        },
                        label = { Text("Nouveau PIN") },
                        placeholder = { Text("Saisissez 4 chiffres") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pinInputValue.length == 4) {
                            savedPinValue = pinInputValue
                            SyncPrefs.setSavedPin(context, pinInputValue)
                            showPinDialog = false
                        }
                    },
                    enabled = pinInputValue.length == 4
                ) {
                    Text("Enregistrer")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) {
                    Text("Annuler")
                }
            }
        )
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

// CLIENTS TAB MODELS & COMPOSABLES
// ═══════════════════════════════════════════════════════════════

data class BackendClient(
    val id: Long,
    val name: String,
    val phone: String,
    val email: String,
    val balance: Double,
    val service: String? = null
)

data class SmsDepositor(
    val phoneNumber: String,
    val displayName: String,
    val totalDeposits: Double,
    val lastDepositDate: Long,
    val transactionCount: Int
)

data class GeminiMatchResult(
    val matchedClientId: Long?,
    val confidence: Int,
    val reason: String
)

fun extractDepositorFromSms(body: String): Pair<String, String>? {
    val clean = body.replace("\n", " ").replace("\r", " ").replace("\\s+".toRegex(), " ")
    if (clean.isBlank()) return null

    // Helper: check if a name/phone is an operator name
    fun isOp(s: String): Boolean {
        val c = s.uppercase().trim().replace(" ", "").replace("_", "")
        return c.isEmpty() || c.contains("ORANGE") || c.contains("MTN") || c.contains("MOMO") || 
               c.contains("WAVE") || c.contains("WAYE") || c.contains("AIRTEL") || 
               c.contains("FLOOZ") || c.contains("MOOV")
    }

    // Pattern 1: de NAME (+PHONE) or reçu de NAME (PHONE)
    val pat1 = """(?:de|reçu de|recu de)\s+([A-Z\s.-]{3,35})\s*\(([^)]+)\)""".toRegex(RegexOption.IGNORE_CASE)
    val m1 = pat1.find(clean)
    if (m1 != null) {
        val name = m1.groupValues[1].trim()
        val phone = m1.groupValues[2].trim()
        if (name.length > 2 && phone.length > 5 && !isOp(name) && !isOp(phone)) {
            return Pair(phone, name)
        }
    }

    // Pattern 1b: de NAME, téléphone/numéro PHONE
    val pat1bSpaces = """(?:de|reçu de|recu de)\s+([A-Z\s.-]{3,35})\s+(?:tél|tel|téléphone|num|no)?\s*:?\s*(\+?[0-9\s]{8,20})""".toRegex(RegexOption.IGNORE_CASE)
    val m1bSpaces = pat1bSpaces.find(clean)
    if (m1bSpaces != null) {
        val name = m1bSpaces.groupValues[1].trim()
        val phone = m1bSpaces.groupValues[2].replace(" ", "").trim()
        if (name.length > 2 && phone.length >= 7 && !isOp(name) && !isOp(phone)) {
            return Pair(phone, name)
        }
    }

    // Pattern 2: de +PHONE (e.g., Transfert recu de 20 000 FCFA de +22997123456)
    val pat2 = """de\s+(\+?[0-9]{7,15})""".toRegex()
    val m2 = pat2.find(clean.replace(" ", ""))
    if (m2 != null) {
        val phone = m2.groupValues[1].trim()
        if (phone.length >= 7 && !isOp(phone)) {
            return Pair(phone, phone)
        }
    }

    // Pattern 3: via l'agent AGENT_NAME
    val pat3 = """via\s+l'agent\s+([A-Z0-9\s.-]{3,30})""".toRegex(RegexOption.IGNORE_CASE)
    val m3 = pat3.find(clean)
    if (m3 != null) {
        val agent = m3.groupValues[1].trim()
        if (agent.length > 2 && !isOp(agent)) {
            return Pair(agent, agent)
        }
    }

    // Pattern 4: Generic phone and capitalized name extractor
    val phoneRegex = """(\+?[0-9]{8,15})""".toRegex()
    val phoneMatches = phoneRegex.findAll(clean.replace(" ", ""))
    for (pm in phoneMatches) {
        val phone = pm.value
        if (phone.length >= 8 && !isOp(phone)) {
            val phoneIndex = clean.indexOf(phone)
            if (phoneIndex > 10) {
                val preText = clean.substring(0, phoneIndex).trim()
                val nameMatch = """(?:de|reçu de|recu de|expéditeur:?|expediteur:?|par)\s+([A-Z][A-Z\s.-]{2,30})""".toRegex(RegexOption.IGNORE_CASE).find(preText)
                if (nameMatch != null) {
                    val name = nameMatch.groupValues[1].trim()
                    if (name.length > 2 && !isOp(name)) {
                        return Pair(phone, name)
                    }
                }
            }
            return Pair(phone, phone)
        }
    }

    return null
}

suspend fun autoDetectBackendType(baseUrl: String, token: String): String {
    return withContext(Dispatchers.IO) {
        // Try Django first
        val djangoUrl = baseUrl.trimEnd('/') + "/api/v1/home/clients/"
        try {
            val resp = com.pettycash.cashsms.sync.DjangoClient.getJson(djangoUrl, token)
            resp.use {
                if (it.isSuccessful) {
                    return@withContext "DJANGO"
                }
            }
        } catch (_: Exception) {}

        // Try Laravel next
        val laravelUrl = baseUrl.trimEnd('/') + "/api/clients"
        try {
            val resp = com.pettycash.cashsms.sync.LaravelClient.getJson(laravelUrl, token)
            resp.use {
                if (it.isSuccessful) {
                    return@withContext "LARAVEL"
                }
            }
        } catch (_: Exception) {}

        "DJANGO"
    }
}

suspend fun fetchClientsFromBackend(baseUrl: String, token: String, backendType: String): List<BackendClient> {
    return withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trimEnd('/')
        val url = if (backendType == "LARAVEL") {
            "$cleanUrl/api/clients"
        } else {
            "$cleanUrl/api/v1/home/clients/"
        }
        
        try {
            val response = if (backendType == "LARAVEL") {
                LaravelClient.getJson(url, token)
            } else {
                DjangoClient.getJson(url, token)
            }
            
            response.use { resp ->
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string() ?: "[]"
                    val jsonArray = org.json.JSONArray(bodyStr)
                    val list = mutableListOf<BackendClient>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val srv = when {
                            !obj.isNull("service") -> obj.optString("service")
                            !obj.isNull("payment_service") -> obj.optString("payment_service")
                            !obj.isNull("operator") -> obj.optString("operator")
                            else -> null
                        }
                        list.add(
                            BackendClient(
                                id = obj.optLong("id", i.toLong() + 1),
                                name = obj.optString("name", obj.optString("username", "Inconnu")),
                                phone = obj.optString("phone", obj.optString("phone_number", "")),
                                email = obj.optString("email", ""),
                                balance = obj.optDouble("balance", obj.optDouble("solde", 0.0)),
                                service = srv
                            )
                        )
                    }
                    list
                } else {
                    Log.e("ClientsTab", "Request failed with code ${resp.code}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e("ClientsTab", "Error fetching clients: ${e.message}", e)
            emptyList()
        }
    }
}

suspend fun askGeminiForMatch(
    depositor: SmsDepositor,
    unlinkedClients: List<BackendClient>
): GeminiMatchResult? {
    return withContext(Dispatchers.IO) {
        val apiKey = try { com.pettycash.cashsms.BuildConfig.GEMINI_API_KEY } catch (e: Exception) { "" }
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            delay(1500) // simulation
            val bestMatch = unlinkedClients.firstOrNull { 
                it.name.contains(depositor.displayName.take(3), ignoreCase = true) ||
                depositor.displayName.contains(it.name.take(3), ignoreCase = true)
            }
            if (bestMatch != null) {
                GeminiMatchResult(
                    matchedClientId = bestMatch.id,
                    confidence = 94,
                    reason = "IA Suggestion (Démonstration): Correspondance étroite trouvée sur le nom '${depositor.displayName}' et '${bestMatch.name}' (ID: ${bestMatch.id})."
                )
            } else {
                GeminiMatchResult(
                    matchedClientId = null,
                    confidence = 0,
                    reason = "Aucune correspondance automatique évidente trouvée dans les données clients."
                )
            }
        } else {
            val clientsJson = unlinkedClients.map { 
                mapOf("id" to it.id, "name" to it.name, "phone" to it.phone, "email" to it.email, "service" to it.service)
            }.let { org.json.JSONArray(it).toString() }

            val prompt = """
                Tu es un expert en réconciliation de transactions financières pour une application de transfert d'argent.
                Nous avons reçu un message SMS d'un client ayant effectué un dépôt :
                Nom affiché dans le SMS: "${depositor.displayName}"
                Numéro ou identifiant du SMS: "${depositor.phoneNumber}"
                
                Voici la liste des clients enregistrés sur notre serveur avec leurs comptes et leurs services de paiement associés :
                $clientsJson
                
                Trouve le client enregistré qui correspond le mieux à cet expéditeur de SMS (en gérant les indicatifs comme +225, +221, l'écriture du nom comme ALBERT ESSOMBA vs Albert E., ainsi que le service de paiement s'il est mentionné).
                Retourne UNIQUEMENT un objet JSON avec les clés suivantes :
                - "matched_client_id": l'identifiant (ID) du client correspondant, ou null si aucun ne correspond.
                - "confidence": le taux de confiance en pourcentage (0 à 100).
                - "reason": une explication courte en français de ton choix expliquant la liaison ou l'absence de liaison.
                Ne mets aucun texte explicatif avant ou après le JSON. Retourne uniquement l'objet JSON brut.
            """.trimIndent()

            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
            val requestBodyJson = """
                {
                  "contents": [
                    {
                      "parts": [
                        {
                          "text": ${org.json.JSONObject.quote(prompt)}
                        }
                      ]
                    }
                  ],
                  "generationConfig": {
                    "responseMimeType": "application/json"
                  }
                }
            """.trimIndent()

            val client = okhttp3.OkHttpClient()
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = okhttp3.Request.Builder()
                .url(url)
                .post(requestBodyJson.toRequestBody(mediaType))
                .build()

            try {
                val response = client.newCall(request).execute()
                response.use { resp ->
                    if (resp.isSuccessful) {
                        val respBody = resp.body?.string() ?: ""
                        val responseJson = org.json.JSONObject(respBody)
                        val candidates = responseJson.optJSONArray("candidates")
                        if (candidates != null && candidates.length() > 0) {
                            val content = candidates.getJSONObject(0).getJSONObject("content")
                            val parts = content.getJSONArray("parts")
                            if (parts.length() > 0) {
                                val textResult = parts.getJSONObject(0).getString("text")
                                val matchJson = org.json.JSONObject(textResult.trim())
                                val idVal = if (matchJson.isNull("matched_client_id")) null else matchJson.getLong("matched_client_id")
                                val confVal = matchJson.optInt("confidence", 0)
                                val reasonVal = matchJson.optString("reason", "Analyse complétée.")
                                GeminiMatchResult(idVal, confVal, reasonVal)
                            } else null
                        } else null
                    } else null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

@Composable
fun ServiceBadge(service: String?) {
    if (service.isNullOrBlank()) return
    val (label, color) = remember(service) {
        val clean = service.uppercase().trim()
        when {
            clean.contains("MTN") || clean.contains("MOMO") -> Pair("MTN MoMo", Color(0xFFFFCC00))
            clean.contains("ORANGE") || clean.contains("OM") -> Pair("Orange Money", Color(0xFFFF6600))
            clean.contains("WAVE") -> Pair("Wave", Color(0xFF1D9BF0))
            clean.contains("MOOV") || clean.contains("FLOOZ") -> Pair("Moov Money", Color(0xFF00875A))
            else -> Pair(service, Color(0xFF888888))
        }
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        modifier = Modifier.padding(start = 6.dp)
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun ClientsTabRedesigned(
    messages: List<SmsMessageWithSync>,
    baseUrl: String,
    token: String,
    backendType: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun showMessage(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    var backendClients by remember { mutableStateOf<List<BackendClient>>(emptyList()) }
    var isSyncingClients by remember { mutableStateOf(false) }
    var syncError by remember { mutableStateOf<String?>(null) }
    var activeSubTab by remember { mutableStateOf(0) } // 0 = Rapprochements SMS, 1 = Base de Données

    var clientSearchQuery by remember { mutableStateOf("") }
    var clientOperatorFilter by remember { mutableStateOf("Tous") }
    var clientStatusFilter by remember { mutableStateOf("Tous") } // "Tous", "À associer", "Liés"

    // Local state for manual mapping (SMS phone number -> Backend Client ID)
    var manualMappings by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    
    // AI analyzer state
    var selectedDepositorForAi by remember { mutableStateOf<SmsDepositor?>(null) }
    var isAnalyzingAi by remember { mutableStateOf(false) }
    var aiResult by remember { mutableStateOf<GeminiMatchResult?>(null) }

    // Client creation states (la creation se fait a partir des messages)
    var showCreateClientDialog by remember { mutableStateOf(false) }
    var depositorToCreateClientFor by remember { mutableStateOf<SmsDepositor?>(null) }
    var newClientName by remember { mutableStateOf("") }
    var newClientPhone by remember { mutableStateOf("") }
    var newClientEmail by remember { mutableStateOf("") }
    var newClientService by remember { mutableStateOf("MTNMOMO") }
    var newClientBalance by remember { mutableStateOf("0") }

    // Custom filtering rules states
    var customRules by remember { mutableStateOf(com.pettycash.cashsms.sync.SyncPrefs.getCustomRules(context)) }
    var showAddRuleDialog by remember { mutableStateOf(false) }
    var ruleSenderContains by remember { mutableStateOf("") }
    var ruleBodyContains by remember { mutableStateOf("") }
    var ruleTransactionType by remember { mutableStateOf("IN") } // "IN" or "OUT"
    var ruleOperatorName by remember { mutableStateOf("Orange Money") }

    val displayClients = backendClients

    // Load clients
    fun syncClients() {
        if (baseUrl.isBlank() || token.isBlank()) {
            backendClients = emptyList()
            return
        }
        isSyncingClients = true
        syncError = null
        scope.launch {
            val list = fetchClientsFromBackend(baseUrl, token, backendType)
            if (list.isNotEmpty()) {
                backendClients = list
            } else {
                backendClients = emptyList()
                syncError = "Aucun client trouvé sur le serveur ou impossible de joindre le point de terminaison."
            }
            isSyncingClients = false
        }
    }

    // Trigger sync on open
    LaunchedEffect(baseUrl, token, backendType) {
        syncClients()
    }

    // Periodic synchronization (every 30 seconds)
    LaunchedEffect(Unit) {
        while (true) {
            delay(30000)
            if (!isSyncingClients) {
                val list = fetchClientsFromBackend(baseUrl, token, backendType)
                if (list.isNotEmpty()) {
                    backendClients = list
                }
            }
        }
    }

    // Extract SmsDepositors using customRules
    val smsDepositors = remember(messages, customRules) {
        val transactions = messages.mapNotNull { msgWithSync ->
            val msg = msgWithSync.message
            FinancialTracker.parseTransaction(
                body = msg.body.orEmpty(),
                address = msg.address.orEmpty(),
                date = msg.date ?: 0L,
                customRules = customRules
            )
        }.filter { it.type == "IN" }

        val depositorsMap = mutableMapOf<String, Triple<String, Double, Long>>()
        
        transactions.forEach { tx ->
            val match = extractDepositorFromSms(tx.originalMessage)
            if (match != null) {
                val (phone, name) = match
                // Double check to ensure we never treat an operator itself as a client
                val cleanPhone = phone.uppercase().trim().replace(" ", "").replace("_", "")
                val cleanName = name.uppercase().trim().replace(" ", "").replace("_", "")
                val isOp = cleanPhone.contains("ORANGE") || cleanPhone.contains("MTN") || cleanPhone.contains("MOMO") ||
                           cleanPhone.contains("WAVE") || cleanPhone.contains("WAYE") || cleanPhone.contains("AIRTEL") ||
                           cleanPhone.contains("FLOOZ") || cleanPhone.contains("MOOV") ||
                           cleanName.contains("ORANGE") || cleanName.contains("MTN") || cleanName.contains("MOMO") ||
                           cleanName.contains("WAVE") || cleanName.contains("WAYE") || cleanName.contains("AIRTEL") ||
                           cleanName.contains("FLOOZ") || cleanName.contains("MOOV")

                if (!isOp) {
                    val existing = depositorsMap[phone]
                    if (existing != null) {
                        depositorsMap[phone] = Triple(
                            if (name != phone) name else existing.first,
                            existing.second + tx.amount,
                            maxOf(existing.third, tx.date)
                        )
                    } else {
                        depositorsMap[phone] = Triple(name, tx.amount, tx.date)
                    }
                }
            }
        }

        depositorsMap.map { (phone, data) ->
            SmsDepositor(
                phoneNumber = phone,
                displayName = data.first,
                totalDeposits = data.second,
                lastDepositDate = data.third,
                transactionCount = transactions.count { 
                    val m = extractDepositorFromSms(it.originalMessage)
                    m != null && m.first == phone
                }
            )
        }.sortedByDescending { it.lastDepositDate }
    }

    // Map helpers
    fun isMatched(depositor: SmsDepositor): Boolean {
        if (manualMappings.containsKey(depositor.phoneNumber)) return true
        val depClean = depositor.phoneNumber.replace("""[^0-9]""".toRegex(), "").takeLast(8)
        if (depClean.isEmpty()) return false
        return displayClients.any { 
            it.phone.replace("""[^0-9]""".toRegex(), "").takeLast(8) == depClean
        }
    }

    fun getMatchedClient(depositor: SmsDepositor): BackendClient? {
        val manualId = manualMappings[depositor.phoneNumber]
        if (manualId != null) {
            return displayClients.find { it.id == manualId }
        }
        val depClean = depositor.phoneNumber.replace("""[^0-9]""".toRegex(), "").takeLast(8)
        if (depClean.isEmpty()) return null
        return displayClients.find { 
            it.phone.replace("""[^0-9]""".toRegex(), "").takeLast(8) == depClean
        }
    }

    val unlinkedDepositors = smsDepositors.filter { !isMatched(it) }
    val linkedDepositors = smsDepositors.filter { isMatched(it) }

    val filteredSmsDepositors = remember(smsDepositors, clientSearchQuery, clientOperatorFilter, clientStatusFilter, manualMappings) {
        smsDepositors.filter { dep ->
            val matchesSearch = clientSearchQuery.isBlank() || 
                dep.displayName.contains(clientSearchQuery, ignoreCase = true) || 
                dep.phoneNumber.contains(clientSearchQuery, ignoreCase = true)

            val opText = (dep.displayName + " " + dep.phoneNumber).uppercase()
            val matchesOperator = when (clientOperatorFilter) {
                "Tous" -> true
                "MTN" -> opText.contains("MTN") || opText.contains("MOMO")
                "Orange" -> opText.contains("ORANGE") || opText.contains("OM")
                "Wave" -> opText.contains("WAVE")
                "Moov" -> opText.contains("MOOV")
                else -> true
            }

            val matched = isMatched(dep)
            val matchesStatus = when (clientStatusFilter) {
                "Tous" -> true
                "À associer" -> !matched
                "Liés" -> matched
                else -> true
            }

            matchesSearch && matchesOperator && matchesStatus
        }
    }

    val filteredBackendClients = remember(backendClients, clientSearchQuery, clientOperatorFilter) {
        backendClients.filter { client ->
            val matchesSearch = clientSearchQuery.isBlank() || 
                client.name.contains(clientSearchQuery, ignoreCase = true) || 
                client.phone.contains(clientSearchQuery, ignoreCase = true)

            val srv = client.service.orEmpty().uppercase()
            val matchesOperator = when (clientOperatorFilter) {
                "Tous" -> true
                "MTN" -> srv.contains("MTN") || srv.contains("MOMO")
                "Orange" -> srv.contains("ORANGE") || srv.contains("OM")
                "Wave" -> srv.contains("WAVE")
                "Moov" -> srv.contains("MOOV")
                else -> true
            }

            matchesSearch && matchesOperator
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary
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
                                        Icons.Default.People,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = Color.White
                                    )
                                }
                            }
                            Column {
                                Text(
                                    "Gestion Clients",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    "Liaison intelligente & Synchronisation Client / Serveur",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Metrics Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Total Base", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text("${displayClients.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("À Lier", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        Text("${unlinkedDepositors.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Liés", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        Text("${linkedDepositors.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Sync Info / Error bar
        syncError?.let { err ->
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        // Tabs row
        item {
            val pendingSyncMessages = remember(messages) {
                messages.filter { it.syncState.equals("PENDING", ignoreCase = true) || it.syncState.equals("FAILED", ignoreCase = true) }
            }
            ScrollableTabRow(
                selectedTabIndex = activeSubTab,
                containerColor = Color.Transparent,
                edgePadding = 0.dp,
                divider = {}
            ) {
                Tab(
                    selected = activeSubTab == 0,
                    onClick = { activeSubTab = 0 },
                    text = { Text("Clients / Serveur (${smsDepositors.size})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium) }
                )
                Tab(
                    selected = activeSubTab == 1,
                    onClick = { activeSubTab = 1 },
                    text = { Text("Base de Données (${displayClients.size})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium) }
                )
                Tab(
                    selected = activeSubTab == 2,
                    onClick = { activeSubTab = 2 },
                    text = { Text("Historique & Audit (${linkedDepositors.size})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium) }
                )
                Tab(
                    selected = activeSubTab == 3,
                    onClick = { activeSubTab = 3 },
                    text = { Text("Filtres Custom (${customRules.size})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium) }
                )
                Tab(
                    selected = activeSubTab == 4,
                    onClick = { activeSubTab = 4 },
                    text = { Text("File de Synchro (${pendingSyncMessages.size})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium) }
                )
            }
        }

        // Search bar & Filter row
        if (activeSubTab == 0 || activeSubTab == 1) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = clientSearchQuery,
                        onValueChange = { clientSearchQuery = it },
                        placeholder = { Text("Rechercher par nom ou téléphone...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (clientSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { clientSearchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = null)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        singleLine = true
                    )

                    // Operator Row
                    Text("Opérateur", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val operators = listOf("Tous", "MTN", "Orange", "Wave", "Moov")
                        items(operators) { op ->
                            SegmentedChip(
                                selected = clientOperatorFilter == op,
                                label = op,
                                onClick = { clientOperatorFilter = op }
                            )
                        }
                    }

                    // Status Row (only for subtab 0)
                    if (activeSubTab == 0) {
                        Text("Statut", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val statuses = listOf("Tous", "À associer", "Liés")
                            items(statuses) { status ->
                                SegmentedChip(
                                    selected = clientStatusFilter == status,
                                    label = status,
                                    onClick = { clientStatusFilter = status }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Active selection lists
        when (activeSubTab) {
            0 -> {
                if (displayClients.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Text("Mode local activé", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    "Aucun client n'est synchronisé depuis votre serveur. Vous pouvez créer des clients directement à partir des messages ci-dessous pour les lier, ou configurer vos accès dans l'onglet Paramètres.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                
                if (filteredSmsDepositors.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("Aucun client ou SMS correspondant trouvé.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(filteredSmsDepositors) { dep ->
                        val matched = isMatched(dep)
                        
                        if (matched) {
                            val matchedClient = getMatchedClient(dep)
                            val mainDisplayName = matchedClient?.name ?: dep.displayName
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.size(40.dp)) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = mainDisplayName.take(1).uppercase(),
                                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                        style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(mainDisplayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                                    ServiceBadge(service = matchedClient?.service)
                                                }
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                                                    Text("SMS: ${dep.displayName} (${dep.phoneNumber})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                                }
                                            }
                                        }

                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                FinancialTracker.formatFCFA(dep.totalDeposits),
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Black,
                                                color = MaterialTheme.colorScheme.tertiary
                                            )
                                            Text("${dep.transactionCount} dépôts", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    
                                    if (manualMappings.containsKey(dep.phoneNumber)) {
                                        OutlinedButton(
                                            onClick = {
                                                manualMappings = manualMappings - dep.phoneNumber
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                        ) {
                                            Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Délier / Dissocier", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        } else {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), modifier = Modifier.size(40.dp)) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = dep.displayName.take(1).uppercase(),
                                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                                        style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                            Column {
                                                Text(dep.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                                Text(dep.phoneNumber, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                FinancialTracker.formatFCFA(dep.totalDeposits),
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Black,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text("${dep.transactionCount} dépôts", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                selectedDepositorForAi = dep
                                                isAnalyzingAi = true
                                                aiResult = null
                                                scope.launch {
                                                    val res = askGeminiForMatch(dep, displayClients)
                                                    aiResult = res
                                                    isAnalyzingAi = false
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                        ) {
                                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Analyse IA", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                                        }

                                        Button(
                                            onClick = {
                                                depositorToCreateClientFor = dep
                                                newClientName = dep.displayName
                                                newClientPhone = dep.phoneNumber
                                                newClientEmail = ""
                                                newClientService = when {
                                                    dep.displayName.uppercase().contains("MTN") || dep.phoneNumber.contains("97") || dep.phoneNumber.contains("96") || dep.phoneNumber.contains("61") || dep.phoneNumber.contains("62") -> "MTNMOMO"
                                                    dep.displayName.uppercase().contains("ORANGE") || dep.phoneNumber.contains("95") || dep.phoneNumber.contains("94") || dep.phoneNumber.contains("07") || dep.phoneNumber.contains("08") -> "ORANGE"
                                                    dep.displayName.uppercase().contains("WAVE") || dep.phoneNumber.contains("wave") -> "WAVE"
                                                    dep.displayName.uppercase().contains("MOOV") -> "MOOV"
                                                    else -> "MTNMOMO"
                                                }
                                                newClientBalance = dep.totalDeposits.toInt().toString()
                                                showCreateClientDialog = true
                                            },
                                            modifier = Modifier.weight(1.2f),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                        ) {
                                            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Créer Client", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                                        }

                                        var showManualSelect by remember { mutableStateOf(false) }
                                        Box(modifier = Modifier.weight(1.1f)) {
                                            OutlinedButton(
                                                onClick = { showManualSelect = true },
                                                shape = RoundedCornerShape(12.dp),
                                                contentPadding = PaddingValues(horizontal = 4.dp)
                                            ) {
                                                Text("Lier", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                                            }
                                            DropdownMenu(
                                                expanded = showManualSelect,
                                                onDismissRequest = { showManualSelect = false }
                                            ) {
                                                displayClients.forEach { c ->
                                                    DropdownMenuItem(
                                                        text = { Text("${c.name} (${c.phone})") },
                                                        onClick = {
                                                            manualMappings = manualMappings + (dep.phoneNumber to c.id)
                                                            showManualSelect = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                if (displayClients.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                )
                                Text(
                                    "Aucun client connecté",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "Veuillez configurer votre serveur dans l'onglet Paramètres pour synchroniser vos clients réels.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = { syncClients() },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Actualiser la connexion", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else if (filteredBackendClients.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("Aucun client correspondant trouvé.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(filteredBackendClients) { client ->
                        val initials = client.name.take(2).uppercase()
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(initials, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(client.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                            ServiceBadge(service = client.service)
                                        }
                                        Text(client.phone, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        FinancialTracker.formatFCFA(client.balance),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text("Solde Base", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                if (linkedDepositors.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("Aucun historique d'association trouvé.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(linkedDepositors) { dep ->
                        val matchedClient = getMatchedClient(dep)
                        val isManual = manualMappings.containsKey(dep.phoneNumber)
                        val mainDisplayName = matchedClient?.name ?: dep.displayName
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = CircleShape,
                                            color = if (isManual) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    if (isManual) Icons.Default.Person else Icons.Default.AutoAwesome,
                                                    contentDescription = null,
                                                    tint = if (isManual) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        Column {
                                            Text(mainDisplayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(
                                                    if (isManual) "Liaison Manuelle (Validée)" else "Liaison Automatique",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (isManual) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            FinancialTracker.formatFCFA(dep.totalDeposits),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text("${dep.transactionCount} dépôts", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("Source SMS: ${dep.displayName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                        Text("Tél: ${dep.phoneNumber}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (isManual) {
                                        OutlinedButton(
                                            onClick = { manualMappings = manualMappings - dep.phoneNumber },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                        ) {
                                            Text("Dissocier", style = MaterialTheme.typography.labelSmall)
                                        }
                                    } else {
                                        Text("Rapproché via No.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            3 -> {
                item {
                    Button(
                        onClick = {
                            ruleSenderContains = ""
                            ruleBodyContains = ""
                            ruleTransactionType = "IN"
                            ruleOperatorName = "Orange Money"
                            showAddRuleDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Créer une Règle Customisée", fontWeight = FontWeight.Bold)
                    }
                }

                if (customRules.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("Aucune règle de filtrage personnalisée.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(customRules) { rule ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (rule.transactionType == "IN") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                                        ) {
                                            Text(
                                                text = if (rule.transactionType == "IN") "DÉPÔT (IN)" else "RETRAIT (OUT)",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (rule.transactionType == "IN") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                        Text(rule.operatorName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    }
                                    IconButton(
                                        onClick = {
                                            val updated = customRules.filter { it.id != rule.id }
                                            customRules = updated
                                            com.pettycash.cashsms.sync.SyncPrefs.saveCustomRules(context, updated)
                                            showMessage("Règle supprimée")
                                        }
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Supprimer la règle", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                                
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (rule.senderContains.isNotBlank()) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("Expéditeur contient :", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                            Text("'${rule.senderContains}'", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    if (rule.bodyContains.isNotBlank()) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("Message contient :", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                            Text("'${rule.bodyContains}'", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            4 -> {
                val pendingSyncMessages = messages.filter { it.syncState.equals("PENDING", ignoreCase = true) || it.syncState.equals("FAILED", ignoreCase = true) }
                val df = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("File d'Attente Hors-Ligne", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Text(
                                        "${pendingSyncMessages.size} message(s) en attente",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Button(
                                    onClick = {
                                        com.pettycash.cashsms.sync.DjangoSyncWorker.enqueueNow(context, isManual = true)
                                        showMessage("Force de synchronisation demandée !")
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Synchroniser", fontWeight = FontWeight.Bold)
                                }
                            }
                            Text(
                                "Le système envoie automatiquement ces messages vers votre serveur web dès qu'une connexion Internet stable est détectée.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (pendingSyncMessages.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                                Text("File d'attente vide", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Tous les SMS locaux ont été parfaitement synchronisés avec votre backend.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                } else {
                    items(pendingSyncMessages) { msg ->
                        val isFailed = msg.syncState.equals("FAILED", ignoreCase = true)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isFailed) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(msg.message.address.orEmpty(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                        Text(
                                            df.format(java.util.Date(msg.message.date ?: 0L)),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isFailed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    ) {
                                        Text(
                                            text = if (isFailed) "ÉCHEC (RETRY)" else "EN ATTENTE",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isFailed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                                Text(
                                    msg.message.body.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                if (isFailed) {
                                    Text(
                                        "Raison de l'échec : Connexion instable ou serveur injoignable.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
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

    // AI dialog
    selectedDepositorForAi?.let { dep ->
        Dialog(onDismissRequest = { selectedDepositorForAi = null }) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Analyse de Liaison par IA", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    
                    Text(
                        "Recherche du meilleur client pour :\n${dep.displayName} (${dep.phoneNumber})",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isAnalyzingAi) {
                        CircularProgressIndicator()
                        Text("Analyse en cours avec Gemini...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    } else {
                        aiResult?.let { res ->
                            val matched = displayClients.find { it.id == res.matchedClientId }
                            
                            if (matched != null) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                                            Text("Suggéré : ${matched.name}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                        }
                                        LinearProgressIndicator(
                                            progress = { res.confidence / 100f },
                                            modifier = Modifier.fillMaxWidth().clip(CircleShape),
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                        Text("Confiance : ${res.confidence}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                        Text(res.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                    }
                                }

                                Button(
                                    onClick = {
                                        manualMappings = manualMappings + (dep.phoneNumber to matched.id)
                                        selectedDepositorForAi = null
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                                ) {
                                    Text("Confirmer la liaison", fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                            Text("Aucune correspondance", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                        }
                                        Text(res.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                                    }
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = { selectedDepositorForAi = null },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Fermer")
                        }
                    }
                }
            }
        }
    }

    // Client creation dialog (la creation se fait a partir des messages)
    if (showCreateClientDialog && depositorToCreateClientFor != null) {
        val dep = depositorToCreateClientFor!!
        Dialog(onDismissRequest = { showCreateClientDialog = false }) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Text(
                            "Créer et Lier un Client",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }

                    item {
                        OutlinedTextField(
                            value = newClientName,
                            onValueChange = { newClientName = it },
                            label = { Text("Nom d'utilisateur / Username") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    item {
                        OutlinedTextField(
                            value = newClientPhone,
                            onValueChange = { newClientPhone = it },
                            label = { Text("Téléphone") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    item {
                        OutlinedTextField(
                            value = newClientEmail,
                            onValueChange = { newClientEmail = it },
                            label = { Text("E-mail (Optionnel)") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Service de Paiement Associé", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            val servicesList = listOf(
                                "MTNMOMO" to "MTN MoMo",
                                "ORANGE" to "Orange Money",
                                "WAVE" to "Wave",
                                "MOOV" to "Moov Money",
                                "AUTRE" to "Autre"
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                servicesList.forEach { (srvCode, srvLabel) ->
                                    val isSel = newClientService == srvCode
                                    val bgCol = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    val borderCol = if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent
                                    val textCol = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    Surface(
                                        modifier = Modifier.clickable { newClientService = srvCode },
                                        shape = RoundedCornerShape(12.dp),
                                        color = bgCol,
                                        border = if (isSel) androidx.compose.foundation.BorderStroke(1.5.dp, borderCol) else null
                                    ) {
                                        Text(
                                            text = srvLabel,
                                            color = textCol,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = newClientBalance,
                            onValueChange = { newClientBalance = it },
                            label = { Text("Solde Initial (FCFA)") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showCreateClientDialog = false },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Annuler")
                            }
                            Button(
                                onClick = {
                                    val solde = newClientBalance.toDoubleOrNull() ?: 0.0
                                    val newId = (backendClients.maxOfOrNull { it.id } ?: 0L) + 1L
                                    val newClient = BackendClient(
                                        id = newId,
                                        name = newClientName,
                                        phone = newClientPhone,
                                        email = newClientEmail,
                                        balance = solde,
                                        service = newClientService
                                    )
                                    // Add to local list of backend clients
                                    backendClients = backendClients + newClient
                                    // Map them immediately!
                                    manualMappings = manualMappings + (dep.phoneNumber to newId)
                                    showCreateClientDialog = false
                                },
                                modifier = Modifier.weight(1.5f),
                                shape = RoundedCornerShape(12.dp),
                                enabled = newClientName.isNotBlank() && newClientPhone.isNotBlank()
                            ) {
                                Text("Créer & Lier", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddRuleDialog) {
        AlertDialog(
            onDismissRequest = { showAddRuleDialog = false },
            title = { Text("Nouvelle Règle de Filtrage", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "Configurez des mots-clés locaux pour classer automatiquement les messages provenant de passerelles spécifiques.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = ruleSenderContains,
                        onValueChange = { ruleSenderContains = it },
                        label = { Text("Expéditeur (ex: Money, MTN, Orange)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = ruleBodyContains,
                        onValueChange = { ruleBodyContains = it },
                        label = { Text("Mots-clés dans le texte (ex: Reçu, crédité)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    // Transaction Type Selection
                    Column {
                        Text("Type de Transaction", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { ruleTransactionType = "IN" }) {
                                RadioButton(selected = ruleTransactionType == "IN", onClick = { ruleTransactionType = "IN" })
                                Text("Dépôt / Entrée (IN)", style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { ruleTransactionType = "OUT" }) {
                                RadioButton(selected = ruleTransactionType == "OUT", onClick = { ruleTransactionType = "OUT" })
                                Text("Retrait / Sortie (OUT)", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    // Operator Selection
                    Column {
                        Text("Opérateur Associé", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        val operators = listOf("Orange Money", "MTN MoMo", "Wave", "Moov", "Airtel Money", "Autre")
                        var expandedOpDropdown by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.padding(top = 4.dp)) {
                            OutlinedButton(
                                onClick = { expandedOpDropdown = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(ruleOperatorName)
                                Spacer(modifier = Modifier.weight(1f))
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = expandedOpDropdown,
                                onDismissRequest = { expandedOpDropdown = false }
                            ) {
                                operators.forEach { op ->
                                    DropdownMenuItem(
                                        text = { Text(op) },
                                        onClick = {
                                            ruleOperatorName = op
                                            expandedOpDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (ruleSenderContains.isBlank() && ruleBodyContains.isBlank()) {
                            return@Button
                        }
                        val newRule = com.pettycash.cashsms.sync.CustomFilterRule(
                            id = java.util.UUID.randomUUID().toString(),
                            senderContains = ruleSenderContains.trim(),
                            bodyContains = ruleBodyContains.trim(),
                            transactionType = ruleTransactionType,
                            operatorName = ruleOperatorName
                        )
                        val updated = customRules + newRule
                        customRules = updated
                        com.pettycash.cashsms.sync.SyncPrefs.saveCustomRules(context, updated)
                        showAddRuleDialog = false
                        showMessage("Règle ajoutée avec succès !")
                    }
                ) {
                    Text("Créer la règle")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddRuleDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }
}

@Composable
fun SegmentedChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}
