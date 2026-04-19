package com.example.oop

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.oop.chat.ChatViewModel
import com.example.oop.chat.mvi.ChatIntent
import com.example.oop.chat.ui.ChatScreen
import com.example.oop.ui.theme.AppTheme
import com.example.oop.wearables.WearablesViewModel
import com.example.oop.wearables.ui.WearablesScreen

class MainActivity : ComponentActivity() {
    private val chatViewModel: ChatViewModel by viewModels()
    private val wearablesViewModel: WearablesViewModel by viewModels()
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            chatViewModel.onIntent(ChatIntent.LoadModel)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setContent {
            AppTheme {
                var destination by rememberSaveable { mutableStateOf(Destination.Chat) }
                when (destination) {
                    Destination.Chat -> ChatScreen(
                        viewModel = chatViewModel,
                        onOpenWearables = { destination = Destination.Wearables },
                    )

                    Destination.Wearables -> WearablesScreen(
                        viewModel = wearablesViewModel,
                        onBack = { destination = Destination.Chat },
                    )
                }
            }
        }
    }

    private enum class Destination { Chat, Wearables }
}
