package com.example.oop.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.max

class AudioCapture {
    private var audioRecord: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun startFrames(audioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION): Flow<ShortArray> =
        flow {
            val minBufferSize =
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            Log.i(TAG, "startFrames: src=$audioSource minBufferSize=$minBufferSize")
            check(minBufferSize > 0) { "Unable to create audio buffer" }

            val record =
                AudioRecord.Builder()
                    .setAudioSource(audioSource)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE_HZ)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(
                        max(
                            minBufferSize,
                            FRAME_SIZE_SAMPLES * Short.SIZE_BYTES * BUFFERED_FRAMES,
                        ),
                    )
                    .build()

            check(record.state == AudioRecord.STATE_INITIALIZED) {
                "Failed to initialize audio capture"
            }
            audioRecord = record

            val buffer = ShortArray(FRAME_SIZE_SAMPLES)
            record.startRecording()
            Log.i(
                TAG,
                "startFrames: recording started sr=${SAMPLE_RATE_HZ}Hz frame=${FRAME_SIZE_SAMPLES}samples" +
                    " routedSource=${record.audioSource}",
            )

            var frameCount = 0L
            try {
                while (currentCoroutineContext().isActive) {
                    var offset = 0
                    while (offset < buffer.size && currentCoroutineContext().isActive) {
                        val read =
                            record.read(
                                buffer,
                                offset,
                                buffer.size - offset,
                                AudioRecord.READ_BLOCKING,
                            )
                        if (read <= 0) {
                            Log.e(TAG, "startFrames: AudioRecord.read returned $read")
                            throw IllegalStateException("Audio capture failed with code $read")
                        }
                        offset += read
                    }
                    frameCount++
                    if (frameCount % FRAME_LOG_INTERVAL == 0L) {
                        val avg = averageAbs(buffer)
                        val peak = peakAbs(buffer)
                        Log.d(
                            TAG,
                            "frame#$frameCount avgAbs=${"%.1f".format(avg)} peak=$peak",
                        )
                    }
                    emit(buffer.copyOf())
                }
            } catch (t: Throwable) {
                Log.e(TAG, "startFrames: loop ended with error", t)
                throw t
            } finally {
                Log.i(TAG, "startFrames: stopping after $frameCount frames")
                stop()
            }
        }.flowOn(Dispatchers.IO)

    fun stop() {
        val record = audioRecord ?: return
        audioRecord = null
        runCatching {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        }
        record.release()
        Log.i(TAG, "stop: AudioRecord released")
    }

    private fun averageAbs(frame: ShortArray): Double {
        if (frame.isEmpty()) return 0.0
        var total = 0.0
        for (s in frame) total += abs(s.toInt())
        return total / frame.size
    }

    private fun peakAbs(frame: ShortArray): Int {
        var peak = 0
        for (s in frame) {
            val v = abs(s.toInt())
            if (v > peak) peak = v
        }
        return peak
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_DURATION_MS = 20
        const val FRAME_SIZE_SAMPLES = SAMPLE_RATE_HZ * FRAME_DURATION_MS / 1_000
        private const val BUFFERED_FRAMES = 8
        private const val TAG = "AudioPipe/Capture"
        // ~every 1s at 20ms frames
        private const val FRAME_LOG_INTERVAL = 50L
    }
}
