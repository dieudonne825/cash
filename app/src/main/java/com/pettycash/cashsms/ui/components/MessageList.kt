package com.pettycash.cashsms.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pettycash.cashsms.data.SmsMessageEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageList(
    messages: List<SmsMessageEntity>,
    dateFormat: SimpleDateFormat,
    targetMessageId: Long? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val groupedMessages = remember(messages) { messages.groupByDate() }
    val totalListItemCount = groupedMessages.size + messages.size + 1
    val initialScrollDone = remember { mutableStateOf(false) }
    val scrolledTargetMessageId = remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(messages.size, messages.lastOrNull()?.id, targetMessageId) {
        if (messages.isEmpty()) return@LaunchedEffect

        listState.awaitItems(totalListItemCount)

        val lastMessageListIndex = groupedMessages.lastMessageListIndex()
        val targetListIndex = targetMessageId?.let { groupedMessages.lazyListIndexOfMessage(it) }
        val listIndex = targetListIndex ?: lastMessageListIndex
        val shouldScrollToTarget = targetListIndex != null &&
                scrolledTargetMessageId.value != targetMessageId

        when {
            !initialScrollDone.value -> {
                listState.scrollToItem(listIndex)
                initialScrollDone.value = true
                if (targetListIndex != null) scrolledTargetMessageId.value = targetMessageId
            }
            shouldScrollToTarget -> {
                listState.scrollToItem(listIndex)
                scrolledTargetMessageId.value = targetMessageId
            }
            targetMessageId == null -> {
                listState.animateScrollToItem(lastMessageListIndex)
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        if (messages.isEmpty()) {
            EmptyMessageListState()
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                groupedMessages.forEach { (dateLabel, dayMessages) ->
                    item(key = "label_$dateLabel") {
                        DateSeparator(label = dateLabel)
                    }
                    items(dayMessages, key = { it.id }) { sms ->
                        MessageBubble(sms = sms, dateFormat = dateFormat)
                    }
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

private suspend fun LazyListState.awaitItems(expectedCount: Int) {
    repeat(20) {
        if (layoutInfo.totalItemsCount >= expectedCount) return
        withFrameNanos { }
    }
}

@Composable
private fun EmptyMessageListState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
            modifier = Modifier.size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Démarrer la conversation",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Écrivez votre premier message ci-dessous pour commencer.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

private fun Map<String, List<SmsMessageEntity>>.lazyListIndexOfMessage(messageId: Long): Int? {
    var lazyListIndex = 0
    forEach { (_, dayMessages) ->
        lazyListIndex++
        dayMessages.forEach { sms ->
            if (sms.id == messageId) return lazyListIndex
            lazyListIndex++
        }
    }
    return null
}

private fun Map<String, List<SmsMessageEntity>>.lastMessageListIndex(): Int {
    return (size + values.sumOf { it.size } - 1).coerceAtLeast(0)
}

private fun List<SmsMessageEntity>.groupByDate(): Map<String, List<SmsMessageEntity>> {
    val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
    val yesterday = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        .format(Date(System.currentTimeMillis() - 86_400_000))
    val labelFmt = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())
    val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

    return this.groupBy { sms ->
        val key = dayFmt.format(Date(sms.date))
        when (key) {
            today -> "Aujourd'hui"
            yesterday -> "Hier"
            else -> labelFmt.format(Date(sms.date))
        }
    }
}
