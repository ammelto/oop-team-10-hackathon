package com.example.oop.wearables

import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState

data class WearablesUiState(
    val registrationState: RegistrationState = RegistrationState.Unavailable(),
    val hasActiveDevice: Boolean = false,
    val cameraPermission: PermissionStatus? = null,
    val canRegister: Boolean = false,
    val canShowChat: Boolean = true,
    val recentError: String? = null,
) {
    val isRegistered: Boolean =
        registrationState is RegistrationState.Registered ||
            registrationState is RegistrationState.Unregistering

    val isRegistering: Boolean = registrationState is RegistrationState.Registering

    val canStartRegistration: Boolean = canRegister && !isRegistering

    val isReadyToStream: Boolean =
        isRegistered && hasActiveDevice && cameraPermission == PermissionStatus.Granted && canShowChat
}
