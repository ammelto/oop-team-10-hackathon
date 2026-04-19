package com.example.oop.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.oop.chat.ChatViewModel
import com.example.oop.chat.ui.ChatScreen
import com.example.oop.chat.ui.ModelLoadingScreen
import com.example.oop.stream.StreamViewModel
import com.example.oop.wearables.WearablesViewModel
import com.example.oop.wearables.ui.RegisterScreen
import com.example.oop.wearables.ui.SetupScreen
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus

@Composable
fun AppScaffold(
    chatViewModel: ChatViewModel,
    wearablesViewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    modifier: Modifier = Modifier,
) {
    val wearablesState by wearablesViewModel.uiState.collectAsStateWithLifecycle()
    val chatState by chatViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val activity = LocalContext.current as? ComponentActivity

    LaunchedEffect(wearablesState.recentError) {
        wearablesState.recentError?.let { message ->
            snackbarHostState.showSnackbar(message)
            wearablesViewModel.clearRecentError()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when {
                !wearablesState.isRegistered -> {
                    RegisterScreen(
                        wearablesViewModel = wearablesViewModel,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                !wearablesState.isReadyToStream -> {
                    SetupScreen(
                        wearablesViewModel = wearablesViewModel,
                        onRequestWearablesPermission = onRequestWearablesPermission,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                !chatState.isModelReady -> {
                    ModelLoadingScreen(
                        viewModel = chatViewModel,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                else -> {
                    if (activity != null) {
                        val streamViewModel: StreamViewModel =
                            viewModel(
                                factory =
                                    StreamViewModel.Factory(
                                        application = activity.application as Application,
                                        wearablesViewModel = wearablesViewModel,
                                    ),
                            )
                        BindStreamToLifecycle(streamViewModel = streamViewModel)
                        LaunchedEffect(streamViewModel) {
                            chatViewModel.bindFrameSource(streamViewModel)
                        }
                        ChatScreen(
                            viewModel = chatViewModel,
                            streamViewModel = streamViewModel,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BindStreamToLifecycle(streamViewModel: StreamViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(streamViewModel, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> streamViewModel.startStream()
                Lifecycle.Event.ON_STOP -> streamViewModel.stopStream()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            streamViewModel.startStream()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            streamViewModel.stopStream()
        }
    }
}
