package com.example.oop.wearables.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.oop.R
import com.example.oop.wearables.WearablesViewModel
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus

@Composable
fun SetupScreen(
    wearablesViewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    modifier: Modifier = Modifier,
) {
    val state by wearablesViewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity

    val bodyText =
        when {
            !state.hasActiveDevice -> stringResource(R.string.wearables_setup_waiting_for_device)
            !state.canShowChat -> stringResource(R.string.wearables_setup_resume_body)
            state.cameraPermission != PermissionStatus.Granted ->
                stringResource(R.string.wearables_setup_permission_body)

            else -> stringResource(R.string.wearables_setup_ready)
        }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.wearables_setup_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = bodyText,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 24.dp),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )

        if (!state.canShowChat && state.hasActiveDevice && state.cameraPermission == PermissionStatus.Granted) {
            Button(
                onClick = { wearablesViewModel.enableChat() },
            ) {
                Text(text = stringResource(R.string.wearables_setup_resume_action))
            }
        } else if (state.hasActiveDevice && state.cameraPermission != PermissionStatus.Granted) {
            Button(
                onClick = { wearablesViewModel.ensureCameraPermission(onRequestWearablesPermission) },
            ) {
                Text(text = stringResource(R.string.wearables_setup_permission_action))
            }
        }

        OutlinedButton(
            modifier = Modifier.padding(top = 12.dp),
            onClick = { activity?.let(wearablesViewModel::startUnregistration) },
            enabled = activity != null,
        ) {
            Text(text = stringResource(R.string.wearables_unregister_action))
        }
    }
}
