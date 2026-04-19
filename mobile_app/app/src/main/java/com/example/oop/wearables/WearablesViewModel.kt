package com.example.oop.wearables

import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WearablesViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        private const val TAG = "DAT:STREAM:Wearables"
    }

    private val _uiState = MutableStateFlow(WearablesUiState())

    val uiState = _uiState.asStateFlow()
    val deviceSelector: DeviceSelector by lazy { AutoDeviceSelector() }

    private var monitoringStarted = false
    private var deviceSelectorJob: Job? = null

    fun onAndroidPermissionsResult(
        permissionsResult: Map<String, Boolean>,
        onAllGranted: () -> Unit,
    ) {
        val granted = permissionsResult.values.all { it }
        _uiState.update { current ->
            current.copy(
                canRegister = granted,
                recentError = if (granted) current.recentError else "Allow Bluetooth, camera, and internet permissions.",
            )
        }
        if (granted) {
            onAllGranted()
            startMonitoring()
        }
    }

    fun startRegistration(activity: Activity) {
        Wearables.startRegistration(activity)
    }

    fun startUnregistration(activity: Activity) {
        Wearables.startUnregistration(activity)
    }

    fun ensureCameraPermission(onRequest: suspend (Permission) -> PermissionStatus) {
        viewModelScope.launch {
            val currentStatus = refreshCameraPermission() ?: return@launch
            if (currentStatus == PermissionStatus.Granted) {
                enableChat()
                return@launch
            }

            val requestedStatus = onRequest(Permission.CAMERA)
            _uiState.update { current ->
                current.copy(
                    cameraPermission = requestedStatus,
                    canShowChat = requestedStatus == PermissionStatus.Granted,
                )
            }
            if (requestedStatus == PermissionStatus.Denied) {
                setRecentError("Camera permission denied in the Meta AI flow.")
            }
        }
    }

    fun clearRecentError() {
        _uiState.update { current -> current.copy(recentError = null) }
    }

    fun setRecentError(error: String) {
        _uiState.update { current -> current.copy(recentError = error) }
    }

    fun enableChat() {
        _uiState.update { current -> current.copy(canShowChat = true) }
    }

    fun disableChat(error: String? = null) {
        _uiState.update { current ->
            current.copy(
                canShowChat = false,
                recentError = error ?: current.recentError,
            )
        }
    }

    private fun startMonitoring() {
        if (monitoringStarted) {
            Log.d(TAG, "startMonitoring(): already running")
            return
        }
        monitoringStarted = true
        Log.d(TAG, "startMonitoring(): subscribing to registrationState, devices, and activeDeviceFlow")

        viewModelScope.launch {
            Wearables.registrationState.collect { registrationState ->
                Log.d(TAG, "registrationState -> $registrationState")
                _uiState.update { current ->
                    current.copy(
                        registrationState = registrationState,
                        canShowChat = if (registrationState is RegistrationState.Registered ||
                            registrationState is RegistrationState.Unregistering
                        ) {
                            current.canShowChat
                        } else {
                            true
                        },
                    )
                }
                refreshCameraPermission()
            }
        }

        viewModelScope.launch {
            Wearables.devices.collect { devices ->
                Log.d(TAG, "Wearables.devices -> size=${devices.size}")
                refreshCameraPermission()
            }
        }

        deviceSelectorJob =
            viewModelScope.launch {
                deviceSelector.activeDeviceFlow().collect { device ->
                    Log.d(TAG, "activeDevice -> ${device != null}")
                    _uiState.update { current -> current.copy(hasActiveDevice = device != null) }
                    refreshCameraPermission()
                }
            }
    }

    private suspend fun refreshCameraPermission(): PermissionStatus? {
        val currentState = _uiState.value
        if (!currentState.isRegistered || !currentState.hasActiveDevice) {
            Log.d(
                TAG,
                "refreshCameraPermission(): skipping (isRegistered=${currentState.isRegistered}, " +
                    "hasActiveDevice=${currentState.hasActiveDevice})",
            )
            _uiState.update { current ->
                current.copy(
                    cameraPermission = null,
                    canShowChat = true,
                )
            }
            return null
        }

        val result = Wearables.checkPermissionStatus(Permission.CAMERA)
        result.onFailure { error, _ ->
            Log.e(TAG, "checkPermissionStatus(CAMERA) failed: ${error.description}")
            setRecentError("Permission check error: ${error.description}")
        }
        val permissionStatus = result.getOrNull()
        Log.d(TAG, "refreshCameraPermission(): cameraPermission=$permissionStatus")
        _uiState.update { current ->
            current.copy(
                cameraPermission = permissionStatus,
                canShowChat = if (permissionStatus == PermissionStatus.Granted) current.canShowChat else true,
            )
        }
        return permissionStatus
    }

    override fun onCleared() {
        super.onCleared()
        deviceSelectorJob?.cancel()
    }
}
