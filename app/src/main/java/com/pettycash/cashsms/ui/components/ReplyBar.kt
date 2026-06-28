package com.pettycash.cashsms.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp

@Composable
fun ReplyBar(
    text: String,
    onTextChange: (String) -> Unit,
    isSending: Boolean,
    onSend: () -> Unit,
    placeholder: String = "Écrire un message..."
) {
    val sendButtonScale by animateFloatAsState(
        targetValue = if (text.isNotBlank()) 1f else 0.85f,
        label = "sendButtonScale"
    )

    Surface(
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text(placeholder) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp, max = 140.dp),
                maxLines = 6,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = androidx.compose.material3.MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    focusedContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    unfocusedContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            )

            FilledIconButton(
                onClick = onSend,
                enabled = !isSending && text.isNotBlank(),
                modifier = Modifier
                    .size(52.dp)
                    .scale(sendButtonScale),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    disabledContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Envoyer",
                        tint = if (text.isNotBlank())
                            androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
                        else
                            androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}
