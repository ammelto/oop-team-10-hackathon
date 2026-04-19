package com.example.oop.wearables.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.oop.ui.theme.AppTheme
import com.example.oop.wearables.data.IdMinting
import com.example.oop.wearables.model.VitalSample
import com.example.oop.wearables.model.VitalType
import com.example.oop.wearables.model.WearableSource
import java.time.Duration
import java.time.Instant

@Composable
fun VitalTile(
    label: String,
    type: VitalType,
    sample: VitalSample?,
    clockTick: Instant,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = sample?.let { formatValue(type, it.value) } ?: "—",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = type.unit,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = metadataLine(sample, clockTick),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun BloodPressureTile(
    systolic: VitalSample?,
    diastolic: VitalSample?,
    clockTick: Instant,
    modifier: Modifier = Modifier,
) {
    val mostRecent = listOfNotNull(systolic, diastolic)
        .maxByOrNull { it.timestamp.toEpochMilli() }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = "Blood pressure",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val valueText = if (systolic != null && diastolic != null) {
                    "${systolic.value.toInt()}/${diastolic.value.toInt()}"
                } else {
                    "—"
                }
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "mmHg",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = metadataLine(mostRecent, clockTick),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatValue(type: VitalType, value: Double): String {
    val isInteger = when (type) {
        VitalType.HeartRate,
        VitalType.HrvRmssd,
        VitalType.BloodPressureSystolic,
        VitalType.BloodPressureDiastolic,
        VitalType.RespiratoryRate,
        -> true
        else -> false
    }
    return if (isInteger) value.toInt().toString() else "%.1f".format(value)
}

private fun metadataLine(sample: VitalSample?, clockTick: Instant): String {
    if (sample == null) return "Waiting…"
    val now = if (clockTick == Instant.EPOCH) Instant.now() else clockTick
    val age = Duration.between(sample.timestamp, now).seconds.coerceAtLeast(0L)
    val ageText = when {
        age < 60 -> "${age}s ago"
        age < 3_600 -> "${age / 60}m ago"
        age < 86_400 -> "${age / 3_600}h ago"
        else -> "${age / 86_400}d ago"
    }
    val deviceText = sample.device?.takeIf { it.isNotBlank() } ?: sample.source.name
    return "$ageText · $deviceText"
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun PreviewVitalTile() {
    val now = Instant.now()
    val sample = VitalSample(
        id = IdMinting.mint(VitalType.HeartRate, now.toEpochMilli(), WearableSource.MOCK, "Galaxy Watch7", 72.0),
        timestamp = now.minusSeconds(3),
        type = VitalType.HeartRate,
        value = 72.0,
        source = WearableSource.MOCK,
        device = "Galaxy Watch7",
    )
    AppTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VitalTile("Heart rate", VitalType.HeartRate, sample, now)
            VitalTile("Heart rate", VitalType.HeartRate, null, now)
        }
    }
}
