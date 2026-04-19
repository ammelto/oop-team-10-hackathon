package com.example.oop.wearables

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.oop.wearables.data.WearableRepository
import com.example.oop.wearables.model.VitalSample
import com.example.oop.wearables.model.VitalType
import com.example.oop.wearables.mvi.WearablesEffect
import com.example.oop.wearables.mvi.WearablesIntent
import com.example.oop.wearables.mvi.WearablesUiState
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WearablesViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val repository: WearableRepository =
        WearablesServiceLocator.repository(appContext)

    private val _state = MutableStateFlow(WearablesUiState())
    private val _effects = Channel<WearablesEffect>(Channel.BUFFERED)

    val state = _state.asStateFlow()
    val effects: Flow<WearablesEffect> = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.sources.collect { sourcesMap ->
                _state.update { current -> current.copy(sources = sourcesMap) }
            }
        }
        viewModelScope.launch {
            repository.enabledSources.collect { enabled ->
                _state.update { current -> current.copy(enabledSources = enabled) }
            }
        }
        viewModelScope.launch {
            repository.isStreaming.collect { streaming ->
                _state.update { current -> current.copy(streaming = streaming) }
            }
        }
        viewModelScope.launch {
            repository.vitals.collect { sample ->
                updateLatestWith(sample)
            }
        }
        viewModelScope.launch {
            _state.update { current ->
                current.copy(latest = repository.latestSnapshot())
            }
        }
        viewModelScope.launch {
            refreshPermissions()
            refreshSources()
        }
        viewModelScope.launch {
            while (true) {
                delay(1_000L)
                _state.update { current -> current.copy(clockTick = Instant.now()) }
            }
        }
    }

    fun onIntent(intent: WearablesIntent) {
        when (intent) {
            WearablesIntent.Refresh -> handleRefresh()
            WearablesIntent.RequestHealthConnectPermissions -> requestPermissions()
            is WearablesIntent.SourceToggled -> toggleSource(intent.source, intent.enabled)
            WearablesIntent.StartStreaming -> startStreaming()
            WearablesIntent.StopStreaming -> stopStreaming()
            WearablesIntent.DismissError -> dismissError()
            is WearablesIntent.PermissionsGranted -> onPermissionsResult(intent.granted)
        }
    }

    private fun handleRefresh() {
        viewModelScope.launch {
            refreshSources()
            refreshPermissions()
        }
    }

    private fun requestPermissions() {
        viewModelScope.launch {
            val missing = runCatching { repository.missingHealthConnectPermissions() }
                .getOrDefault(emptySet())
            _state.update { it.copy(missingPermissions = missing) }
            if (missing.isNotEmpty()) {
                _effects.trySend(WearablesEffect.LaunchHealthConnectPermissions(missing))
            }
        }
    }

    private fun toggleSource(source: com.example.oop.wearables.model.WearableSource, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setSourceEnabled(source, enabled) }
                .onFailure { throwable ->
                    Log.w(TAG, "setSourceEnabled failed", throwable)
                    emitError(throwable.message ?: "Could not toggle source")
                }
        }
    }

    private fun startStreaming() {
        viewModelScope.launch {
            runCatching { repository.startStreaming() }
                .onFailure { throwable ->
                    Log.w(TAG, "startStreaming failed", throwable)
                    emitError(throwable.message ?: "Could not start streaming")
                }
        }
    }

    private fun stopStreaming() {
        viewModelScope.launch {
            runCatching { repository.stopStreaming() }
                .onFailure { throwable ->
                    Log.w(TAG, "stopStreaming failed", throwable)
                    emitError(throwable.message ?: "Could not stop streaming")
                }
        }
    }

    private fun dismissError() {
        _state.update { current -> current.copy(error = null) }
    }

    private fun onPermissionsResult(granted: Set<String>) {
        viewModelScope.launch {
            val missing = runCatching { repository.missingHealthConnectPermissions() }
                .getOrDefault(emptySet())
            _state.update { current -> current.copy(missingPermissions = missing) }
            runCatching { repository.reinitializeSources() }
        }
    }

    private suspend fun refreshSources() {
        runCatching { repository.reinitializeSources() }
    }

    private suspend fun refreshPermissions() {
        val missing = runCatching { repository.missingHealthConnectPermissions() }
            .getOrDefault(emptySet())
        _state.update { current -> current.copy(missingPermissions = missing) }
    }

    private fun updateLatestWith(sample: VitalSample) {
        _state.update { current ->
            current.copy(latest = current.latest + (sample.type to sample))
        }
    }

    private fun emitError(message: String) {
        _state.update { current -> current.copy(error = message) }
        _effects.trySend(WearablesEffect.ShowError(message))
    }

    private companion object {
        const val TAG = "WearablesViewModel"
    }
}
