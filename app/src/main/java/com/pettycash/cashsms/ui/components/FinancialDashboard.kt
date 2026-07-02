package com.pettycash.cashsms.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pettycash.cashsms.data.SmsMessageWithSync
import com.pettycash.cashsms.sync.SyncPrefs
import com.pettycash.cashsms.sync.CustomFilterRule
import java.text.NumberFormat
import java.util.Locale

// ═══════════════════════════════════════════════════════════════
// TRANSACTION DATA CLASS & EXTRACTOR
// ═══════════════════════════════════════════════════════════════

data class FinancialTransaction(
    val amount: Double,
    val type: String, // "IN" (Inflow / Entrant), "OUT" (Outflow / Sortant)
    val category: String, // "Dépôt", "Retrait", "Transfert", "Achat", "Remboursement", "Autre"
    val operator: String, // "Orange Money", "Airtel Money", "Wave", "MTN MoMo", "Petty Cash", "Autre"
    val originalMessage: String,
    val date: Long,
    val balance: Double? = null
)

object FinancialTracker {
    fun parseTransaction(
        body: String,
        address: String,
        date: Long,
        customRules: List<CustomFilterRule> = emptyList()
    ): FinancialTransaction? {
        val cleanBody = body.replace("\n", " ").replace("\r", " ").replace("\\s+".toRegex(), " ")
        val normalizedBody = cleanBody.lowercase()
        val addrLower = address.lowercase()

        // 0. Match custom filtering rules first
        for (rule in customRules) {
            val matchesSender = rule.senderContains.isBlank() || address.contains(rule.senderContains, ignoreCase = true)
            val matchesBody = rule.bodyContains.isBlank() || body.contains(rule.bodyContains, ignoreCase = true)
            if (matchesSender && matchesBody) {
                val amountRegex = """([0-9\s.,]{2,12})\s*(?:fcfa|f\s*cfa|xof|xaf|f\b)""".toRegex(RegexOption.IGNORE_CASE)
                val match = amountRegex.find(body)
                val amount = if (match != null) {
                    parseAmountString(match.groupValues[1])
                } else {
                    val anyNumberRegex = """\b([0-9\s.,]{3,10})\b""".toRegex()
                    val allNumbers = anyNumberRegex.findAll(body)
                    var found: Double? = null
                    for (m in allNumbers) {
                        val parsed = parseAmountString(m.groupValues[1])
                        if (parsed != null && parsed >= 100.0) {
                            found = parsed
                            break
                        }
                    }
                    found
                } ?: 0.0

                return FinancialTransaction(
                    amount = amount,
                    type = rule.transactionType,
                    category = if (rule.transactionType == "IN") "Dépôt" else "Retrait",
                    operator = rule.operatorName,
                    originalMessage = body,
                    date = date,
                    balance = null
                )
            }
        }
        
        // Determine Operator
        val operator = when {
            addrLower.contains("orangemoney") || addrLower.contains("orange") || addrLower.contains("om") -> "Orange Money"
            addrLower.contains("airtel") -> "Airtel"
            addrLower.contains("wave") -> "Wave"
            addrLower.contains("waye") -> "Waye"
            addrLower.contains("mtn") || addrLower.contains("momo") -> "MTN MoMo"
            addrLower.contains("petty") -> "Petty Cash"
            normalizedBody.contains("orange money") || normalizedBody.contains("om ") || normalizedBody.contains(" orange") -> "Orange Money"
            normalizedBody.contains("airtel") -> "Airtel"
            normalizedBody.contains("wave") -> "Wave"
            normalizedBody.contains("waye") -> "Waye"
            normalizedBody.contains("mtn") || normalizedBody.contains("momo") -> "MTN MoMo"
            normalizedBody.contains("petty cash") -> "Petty Cash"
            else -> "Autre"
        }

        // Determine direction (IN vs OUT) intelligently based on keywords
        val isWithdrawal = normalizedBody.contains("retrait") || 
                           normalizedBody.contains("retiré") || 
                           normalizedBody.contains("retire") || 
                           normalizedBody.contains("débité") || 
                           normalizedBody.contains("debite") || 
                           normalizedBody.contains("debit de") ||
                           normalizedBody.contains("envoyé") || 
                           normalizedBody.contains("envoye") || 
                           normalizedBody.contains("paiement") || 
                           normalizedBody.contains("payé") || 
                           normalizedBody.contains("paye") || 
                           normalizedBody.contains("achat") || 
                           normalizedBody.contains("facture") || 
                           normalizedBody.contains("transféré") || 
                           normalizedBody.contains("transfere") || 
                           normalizedBody.contains("frais de") ||
                           normalizedBody.contains("vers") || // transferring/sending "vers" (to) someone is OUT
                           (normalizedBody.contains("transfert") && !normalizedBody.contains("recu") && !normalizedBody.contains("reçu") && !normalizedBody.contains("reception") && !normalizedBody.contains("réception"))

        val isDeposit = normalizedBody.contains("dépôt") || 
                        normalizedBody.contains("depot") || 
                        normalizedBody.contains("reçu") || 
                        normalizedBody.contains("recu") || 
                        normalizedBody.contains("crédité") || 
                        normalizedBody.contains("credite") || 
                        normalizedBody.contains("credit de") ||
                        normalizedBody.contains("versement") || 
                        normalizedBody.contains("approvisionnement") || 
                        normalizedBody.contains("réception") || 
                        normalizedBody.contains("reception")

        // Prioritize explicit direction determination
        var foundType = when {
            isWithdrawal && !isDeposit -> "OUT"
            isDeposit && !isWithdrawal -> "IN"
            isWithdrawal && isDeposit -> {
                // Check which keyword comes first or is more prominent
                val depositIdx = minOf(
                    if (normalizedBody.contains("depot")) normalizedBody.indexOf("depot") else Int.MAX_VALUE,
                    if (normalizedBody.contains("recu")) normalizedBody.indexOf("recu") else Int.MAX_VALUE
                )
                val withdrawalIdx = minOf(
                    if (normalizedBody.contains("retrait")) normalizedBody.indexOf("retrait") else Int.MAX_VALUE,
                    if (normalizedBody.contains("envoye")) normalizedBody.indexOf("envoye") else Int.MAX_VALUE
                )
                if (depositIdx < withdrawalIdx) "IN" else "OUT"
            }
            else -> null
        }

        // Patterns to match the main transaction amount
        val transactionPatterns = listOf(
            """(?:depot\s+effectue\s+de|depot\s+de|depot|recu|reçu|remboursement|credit\s+de|crédit\s+de|retrait\s+de|retrait|paiement\s+de|paiement|achat\s+approuve\s+de|achat|debit\s+de|débit\s+de|transfert\s+de|transfert|paye|payé|montant\s+transaction[:\s]*|montant\s+net[:\s]*|montant[:\s]*|montant\s+net\s+debite[:\s]*|montant\s+net\s+débité[:\s]*)\s*([0-9\s.,]+)\s*(?:fcfa|f\s*cfa|xof|xaf|f\b)""".toRegex(),
            """([0-9\s.,]+)\s*(?:fcfa|f\s*cfa|xof|xaf|f\b)\s+(?:recu|reçu|rembourse|envoye|envoyé|retrait|paye|payé|achat)""".toRegex(),
            """([0-9\s.,]{2,12})\s*(?:fcfa|f\s*cfa|xof|xaf|f\b)""".toRegex()
        )

        var foundAmount: Double? = null

        for (pattern in transactionPatterns) {
            val match = pattern.find(normalizedBody)
            if (match != null) {
                val parsed = parseAmountString(match.groupValues[1])
                if (parsed != null && parsed > 0) {
                    val matchedText = match.value
                    val isBalance = matchedText.contains("solde") || normalizedBody.substring(maxOf(0, normalizedBody.indexOf(matchedText) - 15), normalizedBody.indexOf(matchedText)).contains("solde")
                    val isFee = matchedText.contains("frais") || normalizedBody.substring(maxOf(0, normalizedBody.indexOf(matchedText) - 15), normalizedBody.indexOf(matchedText)).contains("frais")
                    if (!isBalance && !isFee) {
                        foundAmount = parsed
                        break
                    }
                }
            }
        }

        // Fallback to any number near "FCFA" or "F"
        if (foundAmount == null) {
            val fallbackRegex = """([0-9\s.,]{2,12})\s*(?:fcfa|f\s*cfa|xof|xaf|f\b)""".toRegex()
            val matches = fallbackRegex.findAll(normalizedBody)
            for (match in matches) {
                val parsed = parseAmountString(match.groupValues[1])
                if (parsed != null && parsed > 50) {
                    foundAmount = parsed
                    break
                }
            }
        }

        if (foundAmount == null) return null

        if (foundType == null) {
            foundType = if (isWithdrawal) "OUT" else "IN"
        }

        // Categorize beautifully
        val category = when {
            normalizedBody.contains("depot") || normalizedBody.contains("dépôt") -> "Dépôt"
            normalizedBody.contains("retrait") -> "Retrait"
            normalizedBody.contains("transfert") || normalizedBody.contains("envoye") || normalizedBody.contains("envoyé") -> "Transfert"
            normalizedBody.contains("achat") || normalizedBody.contains("paiement") || normalizedBody.contains("paye") || normalizedBody.contains("payé") -> "Achat"
            normalizedBody.contains("remboursement") || normalizedBody.contains("rembourse") -> "Remboursement"
            else -> if (foundType == "IN") "Dépôt" else "Retrait"
        }

        // Parse balance
        var foundBalance: Double? = null
        val balancePatterns = listOf(
            """(?:nouveau\s+solde|solde\s+actuel|solde\s+de\s+clôture|solde\s+de\s+cloture|solde|votre\s+solde)(?:\s+de\s+clôture|\s+de\s+cloture|\s+actuel|\s+principal|\s+compte|\s+votre|\s+de|\s+votre\s+compte){0,3}\s*(?:est\s+de|est|:)?\s*([0-9\s.,]+)\s*(?:fcfa|f\s*cfa|xof|xaf|f\b)""".toRegex(),
            """(?:solde|solde\s+de\s+votre\s+compte)(?:\s+est)?\s*[:\s-]\s*([0-9\s.,]+)\s*(?:fcfa|f\s*cfa|xof|xaf|f\b)""".toRegex(),
            """(?:nouveau\s+solde|solde)(?:\s+\w+){1,4}\s+([0-9\s.,]+)\s*(?:fcfa|f\s*cfa|xof|xaf|f\b)""".toRegex()
        )

        for (pattern in balancePatterns) {
            val match = pattern.find(normalizedBody)
            if (match != null) {
                val parsed = parseAmountString(match.groupValues[1])
                if (parsed != null) {
                    foundBalance = parsed
                    break
                }
            }
        }

        return FinancialTransaction(
            amount = foundAmount,
            type = foundType,
            category = category,
            operator = operator,
            originalMessage = body,
            date = date,
            balance = foundBalance
        )
    }

    private fun parseAmountString(str: String): Double? {
        var s = str.trim()
        if (s.endsWith(".") || s.endsWith(",")) {
            s = s.dropLast(1).trim()
        }
        
        // Remove spaces, non-breaking spaces, and thin spaces
        s = s.replace(" ", "")
             .replace("\u00A0", "")
             .replace("\u202F", "")
             .trim()

        // Check if there is a decimal suffix (like .X or .XX or ,X or ,XX) at the end
        val hasDecimalSuffix = s.matches(""".*[\.,]\d{1,2}$""".toRegex())
        return if (hasDecimalSuffix) {
            // Find the last index of dot or comma which acts as the decimal separator
            val separatorIndex = s.lastIndexOfAny(charArrayOf('.', ','))
            val integerPart = s.substring(0, separatorIndex).replace(".", "").replace(",", "")
            val decimalPart = s.substring(separatorIndex + 1)
            val cleanStr = "$integerPart.$decimalPart"
            cleanStr.toDoubleOrNull()
        } else {
            // If no decimal suffix, clean all commas and dots as thousands separators
            val cleanStr = s.replace(",", "").replace(".", "")
            cleanStr.toDoubleOrNull()
        }
    }

    fun formatFCFA(amount: Double): String {
        val format = NumberFormat.getIntegerInstance(Locale.FRANCE)
        return "${format.format(amount)} FCFA"
    }
}

// ═══════════════════════════════════════════════════════════════
// FINANCIAL DASHBOARD COMPONENT (JETPACK COMPOSE)
// ═══════════════════════════════════════════════════════════════

@Composable
fun FinancialDashboardCard(
    messages: List<SmsMessageWithSync>,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val customRules = remember(messages) { SyncPrefs.getCustomRules(context) }

    // Local processing of transactions
    val transactions = remember(messages, customRules) {
        messages.mapNotNull { msgWithSync ->
            val msg = msgWithSync.message
            FinancialTracker.parseTransaction(
                body = msg.body.orEmpty(),
                address = msg.address.orEmpty(),
                date = msg.date ?: 0L,
                customRules = customRules
            )
        }
    }

    if (transactions.isEmpty()) return

    var selectedOperatorFilter by remember { mutableStateOf("Tous") }
    var isExpandedDetails by remember { mutableStateOf(false) }

    // Sort transactions by date descending to find the latest easily
    val operatorLatestBalances = remember(transactions) {
        val operatorsList = transactions.map { it.operator }.distinct()
        operatorsList.associateWith { op ->
            val sortedTx = transactions.filter { it.operator == op }.sortedByDescending { it.date }
            val latestWithBalance = sortedTx.firstOrNull { it.balance != null }
            if (latestWithBalance != null) {
                latestWithBalance.balance!!
            } else {
                // Fallback to computed balance if no SMS has the balance
                val opIn = sortedTx.filter { it.type == "IN" }.sumOf { it.amount }
                val opOut = sortedTx.filter { it.type == "OUT" }.sumOf { it.amount }
                opIn - opOut
            }
        }
    }

    // Filter transactions for details list
    val filteredTx = remember(transactions, selectedOperatorFilter) {
        if (selectedOperatorFilter == "Tous") transactions
        else transactions.filter { it.operator == selectedOperatorFilter }
    }

    // Totals for selected filter
    val totalIn = filteredTx.filter { it.type == "IN" }.sumOf { it.amount }
    val totalOut = filteredTx.filter { it.type == "OUT" }.sumOf { it.amount }
    
    // Net balance according to user's request:
    // "le solde par operateur soit pris en fonction du solde du dernier message financier de l'operateur"
    val netBalance = remember(selectedOperatorFilter, operatorLatestBalances, totalIn, totalOut) {
        if (selectedOperatorFilter == "Tous") {
            operatorLatestBalances.values.sum()
        } else {
            operatorLatestBalances[selectedOperatorFilter] ?: (totalIn - totalOut)
        }
    }

    // Operator list for filter tabs
    val operators = remember(transactions) {
        listOf("Tous") + transactions.map { it.operator }.distinct().sorted()
    }

    // Category breakdown
    val categoryTotals = remember(filteredTx) {
        filteredTx.groupBy { it.category }
            .mapValues { entry -> entry.value.sumOf { it.amount } }
            .toList()
            .sortedByDescending { it.second }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.15f)
                        )
                    )
                )
                .padding(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.TrendingUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Suivi Financier",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Analyse locale en FCFA",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                    }
                }

                Text(
                    text = "${filteredTx.size} Tx",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Main KPI Dashboard Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Inflow
                KpiCard(
                    title = "Total Entrant",
                    amountStr = FinancialTracker.formatFCFA(totalIn),
                    color = Color(0xFF2E7D32), // Custom green
                    icon = Icons.Default.ArrowDownward,
                    modifier = Modifier.weight(1f)
                )

                // Outflow
                KpiCard(
                    title = "Total Sortant",
                    amountStr = FinancialTracker.formatFCFA(totalOut),
                    color = Color(0xFFC62828), // Custom red
                    icon = Icons.Default.ArrowUpward,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Net Balance Card
            val balanceColor = if (netBalance >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        balanceColor.copy(alpha = 0.08f),
                        RoundedCornerShape(16.dp)
                    )
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Solde Net Global",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = (if (netBalance >= 0) "+" else "") + FinancialTracker.formatFCFA(netBalance),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = balanceColor
                        )
                    }
                    Icon(
                        imageVector = if (netBalance >= 0) Icons.Default.AccountBalanceWallet else Icons.Default.MoneyOff,
                        contentDescription = null,
                        tint = balanceColor,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Operator Horizontal Filter Tabs
            Text(
                text = "Filtrer par Opérateur & Solde",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(operators) { op ->
                    val isSelected = selectedOperatorFilter == op
                    val opColor = when (op) {
                        "Orange Money" -> Color(0xFFFF6600)
                        "MTN MoMo" -> Color(0xFFFFCC00)
                        "Wave" -> Color(0xFF00A2E8)
                        "Waye" -> Color(0xFF4A90E2)
                        "Airtel" -> Color(0xFFE11A22)
                        "Petty Cash" -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.secondary
                    }

                    val opBal = if (op == "Tous") {
                        operatorLatestBalances.values.sum()
                    } else {
                        operatorLatestBalances[op] ?: 0.0
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (isSelected) opColor else MaterialTheme.colorScheme.surfaceVariant.copy(
                                    alpha = 0.6f
                                )
                            )
                            .clickable { selectedOperatorFilter = op }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = op,
                                color = if (isSelected) {
                                    if (op == "MTN MoMo") Color.Black else Color.White
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                            Text(
                                text = FinancialTracker.formatFCFA(opBal),
                                color = if (isSelected) {
                                    if (op == "MTN MoMo") Color.Black.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.8f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Categories list and interactive details
            AnimatedVisibility(
                visible = categoryTotals.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = "Répartition par Type",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    categoryTotals.take(4).forEach { (category, value) ->
                        val ratio = if (totalIn + totalOut > 0) (value / (totalIn + totalOut)).toFloat() else 0f
                        CategoryRow(
                            category = category,
                            amountStr = FinancialTracker.formatFCFA(value),
                            ratio = ratio
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Expandable details toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { isExpandedDetails = !isExpandedDetails }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isExpandedDetails) "Masquer les détails" else "Afficher toutes les transactions",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (isExpandedDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // Detailed transaction items
            AnimatedVisibility(
                visible = isExpandedDetails,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    
                    filteredTx.take(8).forEach { tx ->
                        DetailedTransactionItem(tx = tx)
                    }

                    if (filteredTx.size > 8) {
                        Text(
                            text = "+ ${filteredTx.size - 8} autres transactions",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KpiCard(
    title: String,
    amountStr: String,
    color: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, color.copy(alpha = 0.15f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = amountStr,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Black,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CategoryRow(
    category: String,
    amountStr: String,
    ratio: Float
) {
    val animatedProgress by animateFloatAsState(
        targetValue = ratio.coerceIn(0f, 1f),
        label = "progress"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = category,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = amountStr,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    )
            )
        }
    }
}

@Composable
private fun DetailedTransactionItem(tx: FinancialTransaction) {
    val typeColor = if (tx.type == "IN") Color(0xFF2E7D32) else Color(0xFFC62828)
    val logoText = when (tx.operator) {
        "Orange Money" -> "OM"
        "MTN MoMo" -> "MTN"
        "Wave" -> "WV"
        "Airtel Money" -> "AM"
        "Petty Cash" -> "PC"
        else -> "TX"
    }
    val opColor = when (tx.operator) {
        "Orange Money" -> Color(0xFFFF6600)
        "MTN MoMo" -> Color(0xFFFFCC00)
        "Wave" -> Color(0xFF00A2E8)
        "Airtel Money" -> Color(0xFFE11A22)
        "Petty Cash" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp)
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Operator Logo Pill
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(opColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = logoText,
                color = opColor,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Category & Message
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = tx.category,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    text = tx.operator,
                    style = MaterialTheme.typography.bodySmall,
                    color = opColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = tx.originalMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Amount
        Text(
            text = (if (tx.type == "IN") "+" else "-") + FinancialTracker.formatFCFA(tx.amount),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.ExtraBold,
            color = typeColor
        )
    }
}
