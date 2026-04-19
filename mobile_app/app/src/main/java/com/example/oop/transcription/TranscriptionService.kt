package com.example.oop.transcription

import android.util.Log
import com.example.oop.audio.AudioCapture
import com.example.oop.audio.BluetoothScoRouter
import com.example.oop.audio.ScoRouteResult
import com.example.oop.audio.UtteranceChunk
import com.example.oop.audio.UtteranceChunkEvent
import com.example.oop.audio.UtteranceChunker
import com.example.oop.whisper.WhisperEngine
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

sealed interface TranscriptionEvent {
    data class Partial(val text: String) : TranscriptionEvent

    data class Final(val utterance: TranscriptUtterance) : TranscriptionEvent

    data class Error(val message: String) : TranscriptionEvent

    data class State(val status: TranscriptionStatus) : TranscriptionEvent
}

class TranscriptionService(
    private val router: BluetoothScoRouter,
    private val capture: AudioCapture,
    private val chunker: UtteranceChunker,
    private val engine: WhisperEngine,
    private val partialDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
    private val finalDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
) : Closeable {
    fun run(): Flow<TranscriptionEvent> = callbackFlow {
        Log.i(TAG, "run: starting transcription session")
        trySend(TranscriptionEvent.State(TranscriptionStatus.Routing))
        val route =
            runCatching { router.route() }.getOrElse { throwable ->
                Log.e(TAG, "run: router.route() threw", throwable)
                trySend(TranscriptionEvent.State(TranscriptionStatus.Error))
                trySend(TranscriptionEvent.Error(throwable.message ?: "Couldn't route audio"))
                close(throwable)
                return@callbackFlow
            }
        Log.i(
            TAG,
            "run: route usingBluetooth=${route.usingBluetooth} device=${route.deviceName} reason=${route.reason}",
        )

        emitReadyState(route)
        val partialChannel = Channel<UtteranceChunk>(Channel.CONFLATED)
        val finalChannel = Channel<UtteranceChunk>(Channel.UNLIMITED)
        val lastFinalUtteranceId = AtomicLong(0L)

        val dispatchJob =
            launch {
                chunker.process(capture.startFrames()).collect { event ->
                    when (event) {
                        is UtteranceChunkEvent.Partial -> {
                            Log.d(TAG, "recv PARTIAL chunk=${event.chunk.pcm.size}samples")
                            partialChannel.trySend(event.chunk)
                        }

                        is UtteranceChunkEvent.Final -> {
                            Log.d(TAG, "recv FINAL chunk=${event.chunk.pcm.size}samples")
                            finalChannel.trySend(event.chunk)
                        }
                    }
                }
            }

        val partialJob =
            launch(partialDispatcher) {
                for (chunk in partialChannel) {
                    if (chunk.utteranceId <= lastFinalUtteranceId.get()) {
                        Log.d(TAG, "skip stale PARTIAL utteranceId=${chunk.utteranceId}")
                        continue
                    }
                    val partialStarted = System.currentTimeMillis()
                    trySend(TranscriptionEvent.State(TranscriptionStatus.Decoding))
                    val decodeStart = System.currentTimeMillis()
                    val textResult =
                        runCatching {
                            engine
                                .transcribeStreaming(
                                    pcm = chunk.pcm,
                                    windowMs = chunk.endMs - chunk.startMs,
                                ).cleanTranscript()
                        }
                    val text =
                        textResult.getOrElse { throwable ->
                            Log.w(TAG, "PARTIAL decode failed utteranceId=${chunk.utteranceId}", throwable)
                            emitReadyState(route)
                            null
                        } ?: continue
                    Log.d(
                        TAG,
                        "PARTIAL timing utteranceId=${chunk.utteranceId}" +
                            " queuedMs=${decodeStart - partialStarted}" +
                            " decodeMs=${System.currentTimeMillis() - decodeStart}" +
                            " totalMs=${System.currentTimeMillis() - partialStarted}",
                    )
                    emitReadyState(route)
                    if (text.isNotBlank() && chunk.utteranceId > lastFinalUtteranceId.get()) {
                        Log.d(TAG, "emit PARTIAL text=\"${text.take(80)}\"")
                        trySend(TranscriptionEvent.Partial(text))
                    } else {
                        Log.d(TAG, "PARTIAL decode blank or stale, skipping emit")
                    }
                }
            }

        val finalJob =
            launch(finalDispatcher) {
                for (chunk in finalChannel) {
                    trySend(TranscriptionEvent.State(TranscriptionStatus.Decoding))
                    val textResult =
                        runCatching {
                            engine.transcribe(chunk.pcm, partial = false).cleanTranscript()
                        }
                    val text =
                        textResult.getOrElse { throwable ->
                            Log.e(TAG, "FINAL decode failed utteranceId=${chunk.utteranceId}", throwable)
                            emitReadyState(route)
                            trySend(
                                TranscriptionEvent.Error(
                                    throwable.message ?: "Final transcription failed",
                                ),
                            )
                            null
                        } ?: continue
                    lastFinalUtteranceId.set(chunk.utteranceId)
                    emitReadyState(route)
                    if (text.isNotBlank()) {
                        Log.i(
                            TAG,
                            "emit FINAL ${chunk.startMs}..${chunk.endMs}ms" +
                                " utteranceId=${chunk.utteranceId}" +
                                " text=\"${text.take(120)}\"",
                        )
                        trySend(
                            TranscriptionEvent.Final(
                                TranscriptUtterance(
                                    id = -1L,
                                    startMs = chunk.startMs,
                                    endMs = chunk.endMs,
                                    text = text,
                                ),
                            ),
                        )
                    } else {
                        Log.w(TAG, "FINAL decode blank, dropping utterance")
                    }
                }
            }

        awaitClose {
            Log.i(TAG, "run: awaitClose, tearing down session")
            partialChannel.close()
            finalChannel.close()
            dispatchJob.cancel()
            partialJob.cancel()
            finalJob.cancel()
            capture.stop()
            router.release()
        }
    }

    override fun close() {
        Log.i(TAG, "close: permanent teardown")
        capture.stop()
        router.destroy()
        partialDispatcher.close()
        finalDispatcher.close()
    }

    private fun String.cleanTranscript(): String =
        lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun kotlinx.coroutines.channels.ProducerScope<TranscriptionEvent>.emitReadyState(
        route: ScoRouteResult,
    ) {
        trySend(
            TranscriptionEvent.State(
                if (route.usingBluetooth) {
                    TranscriptionStatus.Listening
                } else {
                    TranscriptionStatus.UsingPhoneMic
                },
            ),
        )
        if (!route.usingBluetooth && !route.reason.isNullOrBlank()) {
            trySend(TranscriptionEvent.Error(route.reason))
        }
    }

    private companion object {
        const val TAG = "AudioPipe/Service"
    }
}
