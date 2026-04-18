package com.example.oop.chat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.oop.chat.model.ChatMessage
import com.example.oop.chat.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MessageBubble(
    message: ChatMessage,
    isStreamingTail: Boolean,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == Role.USER
    val context = LocalContext.current
    var bitmap by remember(message.imageUri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(message.imageUri) {
        bitmap = message.imageUri?.let { uri ->
            withContext(Dispatchers.IO) {
                decodeMessageBitmap(context, uri.toString(), MESSAGE_IMAGE_MAX_EDGE_PX)
            }
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = MaterialTheme.shapes.large,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isUser && message.imageUri != null) {
                        val preview = bitmap
                        if (preview != null) {
                            Image(
                                bitmap = preview.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .widthIn(max = MESSAGE_IMAGE_WIDTH)
                                    .clip(MaterialTheme.shapes.medium),
                                contentScale = ContentScale.FillWidth,
                            )
                        }
                    }

                    val displayText = when {
                        message.text.isNotEmpty() -> message.text
                        isStreamingTail -> "▌"
                        else -> ""
                    }
                    if (displayText.isNotEmpty()) {
                        Text(
                            text = displayText,
                            modifier = Modifier,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

private fun decodeMessageBitmap(
    context: Context,
    uriString: String,
    maxSizePx: Int,
): Bitmap? {
    val uri = android.net.Uri.parse(uriString)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longestEdge = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
            val sampleSize = (longestEdge / maxSizePx).coerceAtLeast(1)
            decoder.setTargetSampleSize(sampleSize)
        }
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    }
}

private val MESSAGE_IMAGE_WIDTH: Dp = 180.dp
private const val MESSAGE_IMAGE_MAX_EDGE_PX = 720
