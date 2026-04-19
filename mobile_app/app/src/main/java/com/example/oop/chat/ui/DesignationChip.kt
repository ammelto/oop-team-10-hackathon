package com.example.oop.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.oop.R
import com.example.oop.ontology.Designation

@Composable
fun DesignationChip(
    designation: Designation,
    modifier: Modifier = Modifier,
) {
    val label = designation.sourceLabel()
    if (label == null) {
        return
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = designation.primaryId,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            Text(
                text = designation.preferredTerm,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
            designation.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun Designation.sourceLabel(): String? = when (source) {
    "snomed" -> stringResource(R.string.caption_source_snomed)
    "icd10cm" -> stringResource(R.string.caption_source_icd)
    "ais1985" -> stringResource(R.string.caption_source_ais)
    else -> null
}
