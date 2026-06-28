package com.pettycash.cashsms.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pettycash.cashsms.ui.theme.SyncError
import com.pettycash.cashsms.ui.theme.SyncOffline
import com.pettycash.cashsms.ui.theme.SyncPending
import com.pettycash.cashsms.ui.theme.SyncSuccess

sealed class SyncUiState {
    data class Success(val lastSync: String) : SyncUiState()
    data object Syncing : SyncUiState()
    data class Error(val message: String) : SyncUiState()
    data object Offline : SyncUiState()
}

@Composable
fun SyncStatusChip(
    state: SyncUiState,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val (label, icon, color) = when (state) {
        is SyncUiState.Success -> Triple(
            "Sync ${state.lastSync}",
            Icons.Default.CheckCircle,
            SyncSuccess
        )
        is SyncUiState.Syncing -> Triple(
            "Synchronisation...",
            Icons.Default.Sync,
            SyncPending
        )
        is SyncUiState.Error -> Triple(
            "Erreur sync",
            Icons.Default.Error,
            SyncError
        )
        is SyncUiState.Offline -> Triple(
            "Hors ligne",
            Icons.Default.CloudOff,
            SyncOffline
        )
    }

    AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = color
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            labelColor = color,
            leadingIconContentColor = color
        ),
        modifier = modifier,
        border = AssistChipDefaults.assistChipBorder(
            enabled = true,
            borderColor = color.copy(alpha = 0.4f)
        )
    )
}
