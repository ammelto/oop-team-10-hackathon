package com.example.oop.stream.ui

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.oop.R
import com.example.oop.stream.StreamViewModel
import com.meta.wearable.dat.camera.types.StreamSessionState

private const val CAMERA_PANE_TAG = "DAT:STREAM:CameraPane"

@Composable
fun CameraPane(
    streamViewModel: StreamViewModel,
    isCaptureEnabled: Boolean,
    onCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by streamViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.streamSessionState) {
        Log.d(
            CAMERA_PANE_TAG,
            "streamSessionState -> ${state.streamSessionState} (videoFrame=${state.videoFrame != null}, " +
                "videoFrameCount=${state.videoFrameCount})",
        )
    }

    val showLoading =
        state.videoFrame == null || state.streamSessionState == StreamSessionState.STARTING
    LaunchedEffect(showLoading) {
        Log.d(
            CAMERA_PANE_TAG,
            "showLoading=$showLoading (videoFrame=${state.videoFrame != null}, " +
                "streamSessionState=${state.streamSessionState})",
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black),
    ) {
        state.videoFrame?.let { bitmap ->
            key(state.videoFrameCount) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Ray-Ban live feed",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        if (showLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            Text(
                text = stringResource(R.string.wearables_camera_waiting),
                modifier = Modifier.align(Alignment.BottomCenter),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        FilledIconButton(
            onClick = onCapture,
            enabled = isCaptureEnabled,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = stringResource(R.string.action_capture),
            )
        }
    }
}
