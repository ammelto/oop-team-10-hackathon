package com.whispercpp.whisper

import android.os.Build
import android.util.Log
import java.io.File

private const val LOG_TAG = "WhisperLib"

internal class WhisperLib {
    companion object {
        init {
            Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}")

            var loadVfpv4 = false
            var loadV8fp16 = false
            if (isArmEabiV7a()) {
                val cpuInfo = cpuInfo()
                if (cpuInfo?.contains("vfpv4") == true) {
                    Log.d(LOG_TAG, "CPU supports vfpv4")
                    loadVfpv4 = true
                }
            } else if (isArmEabiV8a()) {
                val cpuInfo = cpuInfo()
                if (cpuInfo?.contains("fphp") == true) {
                    Log.d(LOG_TAG, "CPU supports fp16 arithmetic")
                    loadV8fp16 = true
                }
            }

            when {
                loadVfpv4 -> {
                    Log.d(LOG_TAG, "Loading libwhisper_vfpv4.so")
                    System.loadLibrary("whisper_vfpv4")
                }

                loadV8fp16 -> {
                    Log.d(LOG_TAG, "Loading libwhisper_v8fp16_va.so")
                    System.loadLibrary("whisper_v8fp16_va")
                }

                else -> {
                    Log.d(LOG_TAG, "Loading libwhisper.so")
                    System.loadLibrary("whisper")
                }
            }
        }

        external fun initContext(modelPath: String): Long

        external fun freeContext(contextPtr: Long)

        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray)

        external fun fullTranscribeStreaming(
            contextPtr: Long,
            numThreads: Int,
            audioData: FloatArray,
            audioCtx: Int,
            singleSegment: Boolean,
            noTimestamps: Boolean,
            maxTokens: Int,
        )

        external fun getTextSegmentCount(contextPtr: Long): Int

        external fun getTextSegment(contextPtr: Long, index: Int): String

        external fun getSystemInfo(): String

        external fun benchMemcpy(nThreads: Int): String

        external fun benchGgmlMulMat(nThreads: Int): String
    }
}

private fun isArmEabiV7a(): Boolean = Build.SUPPORTED_ABIS.firstOrNull() == "armeabi-v7a"

private fun isArmEabiV8a(): Boolean = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"

private fun cpuInfo(): String? =
    try {
        File("/proc/cpuinfo").inputStream().bufferedReader().use { it.readText() }
    } catch (error: Exception) {
        Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", error)
        null
    }
