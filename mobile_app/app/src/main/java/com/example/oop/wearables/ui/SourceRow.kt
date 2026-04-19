package com.example.oop.wearables.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.oop.wearables.model.SourceStatus
import com.example.oop.wearables.model.WearableSource

@Composable
fun SourceRow(
    source: WearableSource,
    status: SourceStatus,
    enabled: Boolean,
    canToggle: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.padding(end = 12.dp)) {
                Text(
                    text = source.friendlyLabel(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusChip(status)
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                enabled = canToggle,
            )
        }
    }
}

@Composable
private fun StatusChip(status: SourceStatus) {
    val (label, tint) = when (status) {
        SourceStatus.Uninitialized -> "Idle" to MaterialTheme.colorScheme.onSurfaceVariant
        SourceStatus.Unsupported -> "Unsupported on this device" to MaterialTheme.colorScheme.error
        SourceStatus.NotInstalled -> "Needs install/update" to MaterialTheme.colorScheme.error
        is SourceStatus.PermissionRequired -> "Permissions required" to MaterialTheme.colorScheme.error
        SourceStatus.Ready -> "Ready" to MaterialTheme.colorScheme.primary
        is SourceStatus.Error -> "Error: ${status.message}" to MaterialTheme.colorScheme.error
    }
    AssistChip(
        onClick = {},
        label = { Text(text = label) },
        colors = AssistChipDefaults.assistChipColors(
            labelColor = tint,
        ),
    )
}

private fun WearableSource.friendlyLabel(): String = when (this) {
    WearableSource.HEALTH_CONNECT -> "Health Connect"
    WearableSource.SAMSUNG_HEALTH -> "Samsung Health Data SDK"
    WearableSource.MOCK -> "Simulated (demo)"
    WearableSource.BLE_GATT -> "Bluetooth LE GATT"
    WearableSource.WEAR_OS -> "Wear OS companion"
}
