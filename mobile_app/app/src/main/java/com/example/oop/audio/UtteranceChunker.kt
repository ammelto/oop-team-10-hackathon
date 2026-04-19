package com.example.oop.audio

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.abs
import kotlin.math.max

data class UtteranceChunk(
    val utteranceId: Long,
    val pcm: ShortArray,
    val startMs: Long,
    val endMs: Long,
)

sealed interface UtteranceChunkEvent {
    data class Partial(val chunk: UtteranceChunk) : UtteranceChunkEvent

    data class Final(val chunk: UtteranceChunk) : UtteranceChunkEvent
}

class UtteranceChunker(
    private val energyThreshold: Double = 900.0,
    private val minSpeechMs: Long = 200L,
    private val silenceMs: Long = 250L,
    private val partialMs: Long = 300L,
    private val windowMs: Long = 4_000L,
    private val maxWindowMs: Long = 10_000L,
) {
    fun process(frames: Flow<ShortArray>): Flow<UtteranceChunkEvent> = flow {
        Log.i(
            TAG,
            "process: energyThreshold=$energyThreshold minSpeechMs=$minSpeechMs" +
                " silenceMs=$silenceMs partialMs=$partialMs windowMs=$windowMs" +
                " maxWindowMs=$maxWindowMs debugFullPartials=$DEBUG_FULL_UTTERANCE_PARTIALS",
        )
        val chunks = mutableListOf<ShortArray>()
        val ring = ShortArray(msToSamples(windowMs))
        var sampleCursor = 0L
        var startSample = 0L
        var accumulatedSamples = 0
        var speechSamples = 0
        var silenceSamples = 0
        var lastPartialAt = 0L
        var ringWriteIndex = 0
        var ringFill = 0
        var utteranceId = 0L
        var inSpeech = false
        var frameIndex = 0L
        var maxEnergySeen = 0.0

        suspend fun emitPartial() {
            if (accumulatedSamples == 0 || speechSamples < msToSamples(minSpeechMs)) {
                return
            }
            val endSample = startSample + accumulatedSamples.toLong()
            val pcm =
                if (DEBUG_FULL_UTTERANCE_PARTIALS) {
                    flatten(chunks, accumulatedSamples)
                } else {
                    copyRing(ring, ringWriteIndex, ringFill)
                }
            if (pcm.isEmpty()) {
                return
            }
            val chunkStartSample =
                if (DEBUG_FULL_UTTERANCE_PARTIALS) {
                    startSample
                } else {
                    endSample - pcm.size
                }
            val chunk =
                UtteranceChunk(
                    utteranceId = utteranceId,
                    pcm = pcm,
                    startMs = samplesToMs(chunkStartSample),
                    endMs = samplesToMs(endSample),
                )
            Log.d(TAG, "emit PARTIAL ${chunk.startMs}..${chunk.endMs}ms samples=${pcm.size} utteranceId=$utteranceId")
            emit(UtteranceChunkEvent.Partial(chunk))
        }

        suspend fun emitFinal(trimmedSamples: Int) {
            if (trimmedSamples <= 0) {
                Log.w(TAG, "VAD: speech END but trimmedSamples=$trimmedSamples, dropping")
                return
            }
            val pcm = flatten(chunks, trimmedSamples)
            val chunk =
                UtteranceChunk(
                    utteranceId = utteranceId,
                    pcm = pcm,
                    startMs = samplesToMs(startSample),
                    endMs = samplesToMs(startSample + trimmedSamples),
                )
            Log.i(TAG, "emit FINAL ${chunk.startMs}..${chunk.endMs}ms samples=${pcm.size} utteranceId=$utteranceId")
            emit(UtteranceChunkEvent.Final(chunk))
        }

        fun resetState() {
            chunks.clear()
            accumulatedSamples = 0
            speechSamples = 0
            silenceSamples = 0
            lastPartialAt = 0
            ringWriteIndex = 0
            ringFill = 0
            inSpeech = false
        }

        frames.collect { frame ->
            val energy = averageEnergy(frame)
            if (energy > maxEnergySeen) maxEnergySeen = energy
            val isSpeech = energy >= energyThreshold
            frameIndex++
            if (frameIndex % ENERGY_LOG_INTERVAL == 0L) {
                Log.d(
                    TAG,
                    "energy@frame$frameIndex avg=${"%.1f".format(energy)}" +
                        " maxSoFar=${"%.1f".format(maxEnergySeen)} threshold=$energyThreshold inSpeech=$inSpeech",
                )
            }

            if (isSpeech && !inSpeech) {
                inSpeech = true
                startSample = sampleCursor
                utteranceId++
                ringWriteIndex = 0
                ringFill = 0
                lastPartialAt = sampleCursor
                Log.i(TAG, "VAD: speech START at ${samplesToMs(startSample)}ms (energy=${"%.1f".format(energy)})")
            }

            if (inSpeech) {
                chunks += frame.copyOf()
                accumulatedSamples += frame.size
                appendToRing(
                    ring = ring,
                    frame = frame,
                    currentWriteIndex = ringWriteIndex,
                    currentFill = ringFill,
                ).also { appended ->
                    ringWriteIndex = appended.first
                    ringFill = appended.second
                }
                if (isSpeech) {
                    speechSamples += frame.size
                    silenceSamples = 0
                } else {
                    silenceSamples += frame.size
                }

                if (
                    speechSamples >= msToSamples(minSpeechMs) &&
                    sampleCursor + frame.size - lastPartialAt >= msToSamples(partialMs)
                ) {
                    emitPartial()
                    lastPartialAt = sampleCursor + frame.size
                }

                val exceededWindow = accumulatedSamples >= msToSamples(maxWindowMs)
                val reachedTrailingSilence =
                    speechSamples >= msToSamples(minSpeechMs) &&
                        silenceSamples >= msToSamples(silenceMs)
                if (exceededWindow || reachedTrailingSilence) {
                    Log.i(
                        TAG,
                        "VAD: speech END reason=${if (exceededWindow) "maxWindow" else "silence"}" +
                            " speechMs=${samplesToMs(speechSamples.toLong())}" +
                            " silenceMs=${samplesToMs(silenceSamples.toLong())}",
                    )
                    val trimmedSamples = max(accumulatedSamples - silenceSamples, 0)
                    emitFinal(trimmedSamples)
                    resetState()
                }
            }

            sampleCursor += frame.size
        }
    }

    private fun averageEnergy(frame: ShortArray): Double {
        if (frame.isEmpty()) {
            return 0.0
        }
        var total = 0.0
        for (sample in frame) {
            total += abs(sample.toInt())
        }
        return total / frame.size.toDouble()
    }

    private fun appendToRing(
        ring: ShortArray,
        frame: ShortArray,
        currentWriteIndex: Int,
        currentFill: Int,
    ): Pair<Int, Int> {
        if (ring.isEmpty()) {
            return 0 to 0
        }
        var writeIndex = 0
        var fill = 0
        for (sample in frame) {
            ring[(currentWriteIndex + writeIndex) % ring.size] = sample
            if (currentFill + fill < ring.size) {
                fill++
            }
            writeIndex++
        }
        return (currentWriteIndex + frame.size) % ring.size to minOf(currentFill + frame.size, ring.size)
    }

    private fun copyRing(
        ring: ShortArray,
        writeIndex: Int,
        fill: Int,
    ): ShortArray {
        if (fill <= 0) {
            return ShortArray(0)
        }
        val pcm = ShortArray(fill)
        val startIndex = (writeIndex - fill + ring.size) % ring.size
        val firstCopy = minOf(fill, ring.size - startIndex)
        ring.copyInto(
            destination = pcm,
            destinationOffset = 0,
            startIndex = startIndex,
            endIndex = startIndex + firstCopy,
        )
        if (firstCopy < fill) {
            ring.copyInto(
                destination = pcm,
                destinationOffset = firstCopy,
                startIndex = 0,
                endIndex = fill - firstCopy,
            )
        }
        return pcm
    }

    private fun flatten(chunks: List<ShortArray>, sampleCount: Int): ShortArray {
        val pcm = ShortArray(sampleCount)
        var offset = 0
        for (chunk in chunks) {
            if (offset >= sampleCount) {
                break
            }
            val copySize = minOf(chunk.size, sampleCount - offset)
            chunk.copyInto(
                destination = pcm,
                destinationOffset = offset,
                startIndex = 0,
                endIndex = copySize,
            )
            offset += copySize
        }
        return pcm
    }

    private fun msToSamples(durationMs: Long): Int =
        ((durationMs * AudioCapture.SAMPLE_RATE_HZ) / 1_000L).toInt()

    private fun samplesToMs(samples: Long): Long =
        (samples * 1_000L) / AudioCapture.SAMPLE_RATE_HZ

    private companion object {
        const val TAG = "AudioPipe/VAD"
        internal const val DEBUG_FULL_UTTERANCE_PARTIALS = false
        // ~every 1s at 20ms frames
        const val ENERGY_LOG_INTERVAL = 50L
    }
}
