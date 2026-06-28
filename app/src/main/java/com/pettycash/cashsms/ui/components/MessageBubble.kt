package com.pettycash.cashsms.ui.components

import android.provider.Telephony
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pettycash.cashsms.data.SmsMessageEntity
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun MessageBubble(
    sms: SmsMessageEntity,
    dateFormat: SimpleDateFormat
) {
    val isOutgoing = sms.type == Telephony.Sms.MESSAGE_TYPE_SENT

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.82f),
            horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
        ) {
            // ── Bulle avec ombre et coins adaptatifs ──
            Surface(
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = if (isOutgoing) 20.dp else 4.dp,
                    bottomEnd = if (isOutgoing) 4.dp else 20.dp
                ),
                color = if (isOutgoing)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surface,
                tonalElevation = if (isOutgoing) 0.dp else 1.dp,
                shadowElevation = if (isOutgoing) 2.dp else 1.dp,
                modifier = Modifier.animateContentSize()
            ) {
                Text(
                    text = sms.body,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isOutgoing)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(3.dp))

            // ── Heure + état d'envoi matérial ──
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = dateFormat.format(Date(sms.date)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                if (isOutgoing) {
                    SendStateIndicator(sendState = sms.sendState)
                }
            }
        }
    }
}

@Composable
private fun SendStateIndicator(sendState: String) {
    when (sendState) {
        "SENT" -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Envoyé",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        "DELIVERED" -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Livré",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        "FAILED" -> {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = "Échec",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
        "SENDING" -> {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = "Envoi en cours",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        else -> { /* État inconnu : aucun indicateur */ }
    }
}
