package com.example.oop.audio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ScoRouteResult(
    val usingBluetooth: Boolean,
    val reason: String? = null,
    val deviceName: String? = null,
)

/**
 * Routes microphone capture through a connected Bluetooth HFP/HSP headset (e.g. Ray-Ban Meta glasses).
 *
 * Flow on API 31+:
 *   1. Wait for the [BluetoothHeadset] profile proxy to report a connected device.
 *   2. Request transient voice-communication audio focus.
 *   3. Put the audio manager into [AudioManager.MODE_IN_COMMUNICATION].
 *   4. Ask the platform to route to the SCO device via [AudioManager.setCommunicationDevice].
 *
 * On older devices we fall back to [AudioManager.startBluetoothSco] and listen for
 * [AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED] to know exactly when SCO is live.
 */
class BluetoothScoRouter(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothAdapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    @Volatile private var bluetoothHeadset: BluetoothHeadset? = null
    @Volatile private var headsetReady: Boolean = false
    @Volatile private var focusRequest: AudioFocusRequest? = null
    @Volatile private var usingLegacySco: Boolean = false
    @Volatile private var modeChanged: Boolean = false

    private val profileListener =
        object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    bluetoothHeadset = proxy as BluetoothHeadset
                    headsetReady = true
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HEADSET) {
                    bluetoothHeadset = null
                    headsetReady = false
                }
            }
        }

    init {
        val requested =
            bluetoothAdapter?.getProfileProxy(
                appContext,
                profileListener,
                BluetoothProfile.HEADSET,
            ) ?: false
        Log.i(TAG, "init: adapter=${bluetoothAdapter != null} proxyRequested=$requested")
    }

    suspend fun route(): ScoRouteResult = withContext(Dispatchers.Default) {
        Log.i(TAG, "route: start")
        if (!hasBluetoothConnectPermission()) {
            Log.w(TAG, "route: BLUETOOTH_CONNECT not granted, falling back to phone mic")
            return@withContext fallback("Bluetooth permission not granted; using phone mic.")
        }

        // The profile proxy binds asynchronously, so give it a brief window on first call.
        if (!headsetReady) {
            Log.d(TAG, "route: waiting up to ${HEADSET_PROXY_TIMEOUT_MS}ms for HEADSET proxy")
            withTimeoutOrNull(HEADSET_PROXY_TIMEOUT_MS) {
                while (!headsetReady) {
                    delay(50L)
                }
            }
            Log.d(TAG, "route: headsetReady=$headsetReady after wait")
        }

        val headsetDevice =
            connectedHeadsetDevice() ?: run {
                Log.w(TAG, "route: no BT headset connected, falling back to phone mic")
                return@withContext fallback("No Bluetooth headset connected; using phone mic.")
            }
        Log.i(TAG, "route: headset device=${deviceDisplayName(headsetDevice) ?: "<unknown>"}")

        requestAudioFocus()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        modeChanged = true
        Log.d(TAG, "route: audio mode set to IN_COMMUNICATION")

        val routed = tryRouteToSco()
        if (!routed) {
            Log.w(TAG, "route: tryRouteToSco() failed, rolling back")
            releaseInternal()
            return@withContext fallback("Couldn't establish Bluetooth SCO; using phone mic.")
        }

        val result =
            ScoRouteResult(
                usingBluetooth = true,
                deviceName = deviceDisplayName(headsetDevice),
            )
        Log.i(TAG, "route: SUCCESS via=${if (usingLegacySco) "legacy-SCO" else "setCommunicationDevice"} device=${result.deviceName}")
        result
    }

    /** Tear down the current routing session. Safe to call multiple times. */
    fun release() {
        Log.i(TAG, "release()")
        releaseInternal()
    }

    /** Close the profile proxy. Call when the owning component is permanently shutting down. */
    fun destroy() {
        Log.i(TAG, "destroy()")
        releaseInternal()
        bluetoothHeadset?.let { headset ->
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, headset)
        }
        bluetoothHeadset = null
        headsetReady = false
    }

    private fun releaseInternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
        if (usingLegacySco) {
            @Suppress("DEPRECATION")
            runCatching { audioManager.stopBluetoothSco() }
            @Suppress("DEPRECATION")
            runCatching { audioManager.isBluetoothScoOn = false }
            usingLegacySco = false
        }
        if (modeChanged) {
            runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
            modeChanged = false
        }
        abandonAudioFocus()
    }

    @SuppressLint("MissingPermission")
    private fun connectedHeadsetDevice(): BluetoothDevice? {
        val headset = bluetoothHeadset ?: return null
        if (!hasBluetoothConnectPermission()) return null
        return runCatching { headset.connectedDevices.firstOrNull() }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun deviceDisplayName(device: BluetoothDevice): String? {
        if (!hasBluetoothConnectPermission()) return null
        return runCatching { device.name }.getOrNull()
    }

    private suspend fun tryRouteToSco(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val available = audioManager.availableCommunicationDevices
            Log.d(
                TAG,
                "tryRouteToSco: availableCommDevices=${available.joinToString { "${it.productName}(type=${it.type})" }}",
            )
            val scoDevice =
                available.firstOrNull { device -> device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (scoDevice != null) {
                val ok = audioManager.setCommunicationDevice(scoDevice)
                Log.i(TAG, "tryRouteToSco: setCommunicationDevice(${scoDevice.productName}) -> $ok")
                if (ok) return true
            } else {
                Log.w(TAG, "tryRouteToSco: no BLUETOOTH_SCO in availableCommunicationDevices; trying legacy")
            }
        }
        return startLegacyScoAndWait()
    }

    private suspend fun startLegacyScoAndWait(): Boolean {
        @Suppress("DEPRECATION")
        val offCall = audioManager.isBluetoothScoAvailableOffCall
        Log.d(TAG, "startLegacyScoAndWait: isBluetoothScoAvailableOffCall=$offCall")
        if (!offCall) {
            return false
        }
        val connected =
            withTimeoutOrNull(SCO_CONNECT_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val receiver =
                        object : BroadcastReceiver() {
                            override fun onReceive(context: Context?, intent: Intent?) {
                                val state =
                                    intent?.getIntExtra(
                                        AudioManager.EXTRA_SCO_AUDIO_STATE,
                                        AudioManager.SCO_AUDIO_STATE_ERROR,
                                    ) ?: AudioManager.SCO_AUDIO_STATE_ERROR
                                Log.d(TAG, "SCO broadcast: state=${scoStateName(state)}")
                                when (state) {
                                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                                        runCatching { appContext.unregisterReceiver(this) }
                                        if (continuation.isActive) continuation.resume(true)
                                    }

                                    AudioManager.SCO_AUDIO_STATE_ERROR,
                                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                                    -> {
                                        runCatching { appContext.unregisterReceiver(this) }
                                        if (continuation.isActive) continuation.resume(false)
                                    }
                                }
                            }
                        }

                    ContextCompat.registerReceiver(
                        appContext,
                        receiver,
                        IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                    continuation.invokeOnCancellation {
                        runCatching { appContext.unregisterReceiver(receiver) }
                    }

                    @Suppress("DEPRECATION")
                    runCatching { audioManager.startBluetoothSco() }
                    @Suppress("DEPRECATION")
                    runCatching { audioManager.isBluetoothScoOn = true }
                    usingLegacySco = true
                    Log.d(TAG, "startLegacyScoAndWait: startBluetoothSco() issued, awaiting broadcast")
                }
            }
        Log.i(TAG, "startLegacyScoAndWait: connected=${connected == true}")
        return connected == true
    }

    private fun scoStateName(state: Int): String = when (state) {
        AudioManager.SCO_AUDIO_STATE_CONNECTED -> "CONNECTED"
        AudioManager.SCO_AUDIO_STATE_CONNECTING -> "CONNECTING"
        AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> "DISCONNECTED"
        AudioManager.SCO_AUDIO_STATE_ERROR -> "ERROR"
        else -> "UNKNOWN($state)"
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes =
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            // Required because we set willPauseWhenDucked below; the platform needs
            // a listener so it can notify us when focus is transiently lost/regained.
            val listener = AudioManager.OnAudioFocusChangeListener { change ->
                Log.d(TAG, "audio focus change=$change")
            }
            val request =
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(listener)
                    .build()
            val result = runCatching { audioManager.requestAudioFocus(request) }.getOrNull()
            Log.d(TAG, "requestAudioFocus result=$result")
            focusRequest = request
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { request ->
                runCatching { audioManager.abandonAudioFocusRequest(request) }
            }
        }
        focusRequest = null
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun fallback(reason: String): ScoRouteResult =
        ScoRouteResult(usingBluetooth = false, reason = reason)

    private companion object {
        const val TAG = "AudioPipe/ScoRouter"
        const val HEADSET_PROXY_TIMEOUT_MS = 1_500L
        const val SCO_CONNECT_TIMEOUT_MS = 3_000L
    }
}
