package com.example.oop.chat.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.oop.R
import com.example.oop.chat.mvi.TranscriptCaptureThumbnail
import com.example.oop.transcription.TranscriptUtterance
import com.example.oop.transcription.TranscriptionStatus

@Composable
fun TranscriptPane(
    utterances: List<TranscriptUtterance>,
    livePartial: String,
    status: TranscriptionStatus,
    isClassifying: Boolean,
    onToggleClassification: (Boolean) -> Unit,
    onClear: () -> Unit,
    thumbnails: Map<Long, TranscriptCaptureThumbnail>,
    onThumbnailClick: (TranscriptCaptureThumbnail) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val itemCount = utterances.size + if (livePartial.isNotBlank()) 1 else 0

    LaunchedEffect(itemCount) {
        if (itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    Surface(
        modifier = modifier,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.transcript_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    AssistChip(
                        onClick = {},
                        label = { Text(text = status.label()) },
                    )
                    if (status is TranscriptionStatus.DownloadingModel) {
                        if (status.totalBytes > 0L) {
                            val progress =
                                (status.bytesDownloaded.toFloat() / status.totalBytes.toFloat()).coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = progress,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onToggleClassification(!isClassifying) }) {
                        Icon(
                            imageVector = if (isClassifying) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = stringResource(
                                if (isClassifying) {
                                    R.string.action_stop_classifying
                                } else {
                                    R.string.action_start_classifying
                                },
                            ),
                        )
                    }
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.action_clear_transcript),
                        )
                    }
                }
            }

            if (utterances.isEmpty() && livePartial.isBlank()) {
                Text(
                    text = stringResource(R.string.empty_state_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(
                        items = utterances,
                        key = { utterance -> utterance.id },
                    ) { utterance ->
                        val thumbnail = thumbnails[utterance.id]
                        TranscriptRow(
                            text = utterance.text,
                            highlightedPhrases = utterance.highlightedPhrases,
                            timeRange = remember(utterance.startMs, utterance.endMs) {
                                formatTimeRange(utterance.startMs, utterance.endMs)
                            },
                            thumbnail = thumbnail,
                            onThumbnailClick = thumbnail?.let { { onThumbnailClick(it) } },
                        )
                    }
                    if (livePartial.isNotBlank()) {
                        item(key = "live_partial") {
                            TranscriptRow(
                                text = livePartial,
                                timeRange = stringResource(R.string.transcript_live_partial),
                                isPartial = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptRow(
    text: String,
    timeRange: String,
    highlightedPhrases: List<String> = emptyList(),
    isPartial: Boolean = false,
    thumbnail: TranscriptCaptureThumbnail? = null,
    onThumbnailClick: (() -> Unit)? = null,
) {
    val highlightBackground = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
    val annotatedText =
        remember(text, highlightedPhrases, highlightBackground) {
            buildHighlightedTranscript(
                text = text,
                highlightedPhrases = highlightedPhrases,
                highlightBackground = highlightBackground,
            )
        }
    val thumbnailBitmap: ImageBitmap? =
        thumbnail?.let {
            remember(it.bytes) {
                BitmapFactory.decodeByteArray(it.bytes, 0, it.bytes.size)?.asImageBitmap()
            }
        }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = timeRange,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = annotatedText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontStyle = if (isPartial) FontStyle.Italic else FontStyle.Normal,
            )
        }
        if (thumbnailBitmap != null && onThumbnailClick != null) {
            Image(
                bitmap = thumbnailBitmap,
                contentDescription = "Captured image of interest",
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onThumbnailClick),
            )
        }
    }
}

@Composable
private fun TranscriptionStatus.label(): String =
    when (this) {
        TranscriptionStatus.Idle -> stringResource(R.string.transcription_status_idle)
        TranscriptionStatus.Routing -> stringResource(R.string.transcription_status_routing)
        TranscriptionStatus.Listening -> stringResource(R.string.transcription_status_listening)
        TranscriptionStatus.Decoding -> stringResource(R.string.transcription_status_decoding)
        TranscriptionStatus.UsingPhoneMic -> stringResource(R.string.transcription_status_phone_mic)
        TranscriptionStatus.Error -> stringResource(R.string.transcription_status_error)
        is TranscriptionStatus.DownloadingModel -> {
            if (totalBytes > 0L) {
                val progress = ((bytesDownloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                stringResource(R.string.transcription_status_downloading_progress, progress)
            } else {
                stringResource(R.string.transcription_status_downloading)
            }
        }
    }

private fun formatTimeRange(startMs: Long, endMs: Long): String =
    "${formatTimestamp(startMs)} - ${formatTimestamp(endMs)}"

private fun formatTimestamp(timestampMs: Long): String {
    val totalSeconds = (timestampMs / 1_000L).toInt().coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun buildHighlightedTranscript(
    text: String,
    highlightedPhrases: List<String>,
    highlightBackground: Color,
): AnnotatedString {
    if (highlightedPhrases.isEmpty()) {
        return AnnotatedString(text)
    }

    val lowercaseText = text.lowercase()
    val ranges = mutableListOf<IntRange>()
    highlightedPhrases
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .sortedByDescending(String::length)
        .forEach { phrase ->
            val lowerPhrase = phrase.lowercase()
            var start = lowercaseText.indexOf(lowerPhrase)
            while (start >= 0) {
                val range = start until (start + phrase.length)
                if (ranges.none { existing -> existing.first <= range.last && range.first <= existing.last }) {
                    ranges += range
                }
                start = lowercaseText.indexOf(lowerPhrase, startIndex = start + phrase.length)
            }
        }

    if (ranges.isEmpty()) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        append(text)
        ranges.sortedBy { it.first }.forEach { range ->
            addStyle(
                style = SpanStyle(
                    color = Color.Unspecified,
                    fontWeight = FontWeight.SemiBold,
                    background = highlightBackground,
                ),
                start = range.first,
                end = range.last + 1,
            )
        }
    }
}
