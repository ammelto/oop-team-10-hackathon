package com.example.oop.whisper

import android.content.Context
import android.util.Log
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class WhisperNotFoundException(path: String) :
    IllegalStateException("Whisper model not found at $path")

class WhisperEngine(private val context: Context) {
    @Volatile
    private var whisperContext: WhisperContext? = null
    private val scratchLock = Mutex()
    private var scratch = FloatArray(0)

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (whisperContext != null) {
            Log.d(TAG, "initialize: already initialized, skipping")
            return@withContext
        }

        WhisperPaths.cleanupLegacyFiles(context)
        val modelFile = WhisperPaths.resolveExisting(context)
            ?: run {
                Log.e(TAG, "initialize: whisper assets missing at ${WhisperPaths.installDir(context).absolutePath}")
                throw WhisperNotFoundException(WhisperPaths.installDir(context).absolutePath)
            }
        Log.i(TAG, "initialize: model=${modelFile.absolutePath} (${modelFile.length()} bytes)")

        val loadStart = System.currentTimeMillis()
        whisperContext = WhisperContext.createContextFromFile(modelFile.absolutePath)
        Log.i(
            TAG,
            "initialize: done in ${System.currentTimeMillis() - loadStart}ms",
        )
    }

    suspend fun transcribe(
        pcm: ShortArray,
        sampleRate: Int = SAMPLE_RATE_HZ,
        partial: Boolean,
    ): String = withContext(Dispatchers.Default) {
        require(sampleRate == SAMPLE_RATE_HZ) { "WhisperEngine requires 16 kHz PCM input" }
        transcribeInternal(
            pcm = pcm,
            sampleRate = sampleRate,
            partial = partial,
            audioCtx = FINAL_AUDIO_CTX,
            singleSegment = false,
            noTimestamps = false,
            maxTokens = 0,
        )
    }

    suspend fun transcribeStreaming(
        pcm: ShortArray,
        windowMs: Long,
        sampleRate: Int = SAMPLE_RATE_HZ,
    ): String = withContext(Dispatchers.Default) {
        require(sampleRate == SAMPLE_RATE_HZ) { "WhisperEngine requires 16 kHz PCM input" }
        val audioCtx =
            when {
                windowMs <= 4_000L -> 512
                windowMs <= 6_000L -> 768
                else -> 1_024
            }
        transcribeInternal(
            pcm = pcm,
            sampleRate = sampleRate,
            partial = true,
            audioCtx = audioCtx,
            singleSegment = true,
            noTimestamps = true,
            maxTokens = 48,
        )
    }

    suspend fun close() {
        Log.i(TAG, "close()")
        whisperContext?.release()
        whisperContext = null
    }

    private suspend fun transcribeInternal(
        pcm: ShortArray,
        sampleRate: Int,
        partial: Boolean,
        audioCtx: Int,
        singleSegment: Boolean,
        noTimestamps: Boolean,
        maxTokens: Int,
    ): String {
        require(sampleRate == SAMPLE_RATE_HZ) { "WhisperEngine requires 16 kHz PCM input" }
        val activeWhisper = whisperContext ?: error("WhisperEngine not initialized")
        val started = System.currentTimeMillis()
        val decoded =
            scratchLock.withLock {
                val audioData = pcmToFloat(pcm)
                if (singleSegment || noTimestamps || maxTokens > 0) {
                    activeWhisper.transcribeStreaming(
                        data = audioData,
                        audioCtx = audioCtx,
                        singleSegment = singleSegment,
                        noTimestamps = noTimestamps,
                        maxTokens = maxTokens,
                    )
                } else {
                    activeWhisper.transcribeData(audioData)
                }
            }.trim()
        Log.i(
            TAG,
            "transcribe path=${if (partial) "streaming" else "final"}" +
                " audioCtx=$audioCtx pcmSamples=${pcm.size}" +
                " infer=${System.currentTimeMillis() - started}ms text=\"${decoded.take(80)}\"",
        )
        return decoded
    }

    private fun pcmToFloat(pcm: ShortArray): FloatArray {
        if (scratch.size != pcm.size) {
            scratch = FloatArray(pcm.size)
        }
        for (index in pcm.indices) {
            scratch[index] = pcm[index] / PCM_SCALE
        }
        return scratch
    }

    private companion object {
        const val TAG = "AudioPipe/Whisper"
        const val SAMPLE_RATE_HZ = 16_000
        const val PCM_SCALE = 32_768f
        const val FINAL_AUDIO_CTX = 768
    }
}
