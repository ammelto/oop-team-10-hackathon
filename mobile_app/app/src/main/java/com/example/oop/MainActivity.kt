package com.example.oop

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.example.oop.chat.ChatViewModel
import com.example.oop.chat.mvi.ChatIntent
import com.example.oop.ui.AppScaffold
import com.example.oop.ui.theme.AppTheme
import com.example.oop.wearables.WearablesViewModel
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.Wearables
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {
    companion object {
        val ANDROID_PERMISSIONS: Array<String>
            get() = buildList {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.CAMERA)
                add(Manifest.permission.INTERNET)
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }.toTypedArray()
    }

    private val chatViewModel: ChatViewModel by viewModels()
    private val wearablesViewModel: WearablesViewModel by viewModels()
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val permissionCheckLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            wearablesViewModel.onAndroidPermissionsResult(results) {
                if (!hasInitializedWearables) {
                    Wearables.initialize(this)
                    hasInitializedWearables = true
                }
            }
        }
    private val permissionsResultLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            permissionContinuation?.resume(result.getOrDefault(PermissionStatus.Denied))
            permissionContinuation = null
        }

    private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
    private val permissionMutex = Mutex()
    private var hasInitializedWearables = false

    suspend fun requestWearablesPermission(permission: Permission): PermissionStatus =
        permissionMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                permissionContinuation = continuation
                continuation.invokeOnCancellation { permissionContinuation = null }
                permissionsResultLauncher.launch(permission)
            }
        }

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
                AppScaffold(
                    chatViewModel = chatViewModel,
                    wearablesViewModel = wearablesViewModel,
                    onRequestWearablesPermission = ::requestWearablesPermission,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        permissionCheckLauncher.launch(ANDROID_PERMISSIONS)
    }
}
