package com.example.oop

import android.Manifest
import android.os.Bundle
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.example.oop.chat.ChatViewModel
import com.example.oop.chat.mvi.ChatIntent
import com.example.oop.chat.ui.ChatScreen
import com.example.oop.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            viewModel.onIntent(ChatIntent.LoadModel)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setContent {
            AppTheme {
                ChatScreen(viewModel = viewModel)
            }
        }
    }
}
