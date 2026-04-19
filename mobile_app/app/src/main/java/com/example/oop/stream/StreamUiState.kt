package com.example.oop.stream

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.types.StreamSessionState

data class StreamUiState(
    val streamSessionState: StreamSessionState = StreamSessionState.STOPPED,
    val videoFrame: Bitmap? = null,
    val videoFrameCount: Int = 0,
)
