package com.whispercpp.whisper

import android.util.Log
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

private const val LOG_TAG = "WhisperContext"

class WhisperContext private constructor(private var ptr: Long) {
    // whisper.cpp contexts are not thread-safe; serialize every native call.
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    suspend fun transcribeData(data: FloatArray): String = withContext(dispatcher) {
        require(ptr != 0L) { "WhisperContext released" }
        val numThreads = WhisperCpuConfig.preferredThreadCount
        Log.d(LOG_TAG, "Selecting $numThreads threads")
        WhisperLib.fullTranscribe(ptr, numThreads, data)
        collectSegments()
    }

    suspend fun transcribeStreaming(
        data: FloatArray,
        audioCtx: Int,
        singleSegment: Boolean,
        noTimestamps: Boolean,
        maxTokens: Int,
    ): String = withContext(dispatcher) {
        require(ptr != 0L) { "WhisperContext released" }
        val numThreads = WhisperCpuConfig.preferredThreadCount
        Log.d(LOG_TAG, "Selecting $numThreads threads")
        WhisperLib.fullTranscribeStreaming(
            ptr,
            numThreads,
            data,
            audioCtx,
            singleSegment,
            noTimestamps,
            maxTokens,
        )
        collectSegments()
    }

    private fun collectSegments(): String =
        buildString {
            val activePtr = ptr
            val textCount = WhisperLib.getTextSegmentCount(activePtr)
            for (index in 0 until textCount) {
                append(WhisperLib.getTextSegment(activePtr, index))
            }
        }

    suspend fun release() {
        withContext(dispatcher) {
            if (ptr != 0L) {
                WhisperLib.freeContext(ptr)
                ptr = 0L
            }
        }
        dispatcher.close()
    }

    companion object {
        fun createContextFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            check(ptr != 0L) { "Couldn't create context with path $filePath" }
            return WhisperContext(ptr)
        }
    }
}
