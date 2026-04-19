package com.example.oop.stream

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class PresentationQueue(
    private val bufferDelayMs: Long = DEFAULT_BUFFER_DELAY_MS,
    private val maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE,
    private val onFrameReady: (PresentationFrame) -> Unit,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private companion object {
        private const val TAG = "DAT:STREAM:PresentationQueue"
        private const val PRESENTATION_THREAD = "PresentationThread"
        private const val DEFAULT_BUFFER_DELAY_MS = 100L
        private const val DEFAULT_MAX_QUEUE_SIZE = 15
        private const val MIN_PRESENT_INTERVAL_MS = 5L
        private const val MAX_DRIFT_MS = 2000L
        private const val LATE_FRAME_THRESHOLD_MS = 16L
    }

    data class PresentationFrame(
        val bitmap: Bitmap,
        val presentationTimeUs: Long,
    ) : Comparable<PresentationFrame> {
        override fun compareTo(other: PresentationFrame): Int =
            presentationTimeUs.compareTo(other.presentationTimeUs)
    }

    private val frameQueue =
        PriorityBlockingQueue<PresentationFrame>(maxQueueSize + 1)
    private val queueLock = Any()
    private val threadLock = Any()

    private var presentationThread: HandlerThread? = null
    private var presentationHandler: Handler? = null

    private val running = AtomicBoolean(false)
    private val baseWallTimeMs = AtomicLong(-1L)
    private val basePresentationTimeUs = AtomicLong(-1L)

    private val presentationRunnable =
        object : Runnable {
            override fun run() {
                if (!running.get()) {
                    return
                }

                val presented = tryPresentNextFrame()
                val delay = if (presented) MIN_PRESENT_INTERVAL_MS else 1L
                synchronized(threadLock) { presentationHandler }?.postDelayed(this, delay)
            }
        }

    fun start() {
        if (running.getAndSet(true)) {
            Log.d(TAG, "Already running")
            return
        }

        baseWallTimeMs.set(-1L)
        basePresentationTimeUs.set(-1L)

        synchronized(threadLock) {
            presentationThread =
                HandlerThread(PRESENTATION_THREAD, Process.THREAD_PRIORITY_DISPLAY).apply { start() }
            presentationHandler = presentationThread?.looper?.let(::Handler)
            presentationHandler?.post(presentationRunnable)
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) {
            Log.d(TAG, "Already stopped")
            return
        }

        synchronized(threadLock) {
            presentationHandler?.removeCallbacksAndMessages(null)
            presentationThread?.quit()
            presentationThread = null
            presentationHandler = null
        }

        synchronized(queueLock) {
            while (true) {
                val frame = frameQueue.poll() ?: break
                frame.bitmap.recycle()
            }
        }
    }

    fun enqueue(bitmap: Bitmap, presentationTimeUs: Long) {
        if (!running.get()) {
            Log.d(TAG, "Not running, dropping frame")
            return
        }

        val clonedBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
        val frame = PresentationFrame(clonedBitmap, presentationTimeUs)

        val dropped: PresentationFrame?
        synchronized(queueLock) {
            dropped = if (frameQueue.size >= maxQueueSize) frameQueue.poll() else null
            frameQueue.offer(frame)
        }

        if (dropped != null) {
            dropped.bitmap.recycle()
            Log.d(TAG, "Queue full, dropped frame ts=${dropped.presentationTimeUs}")
        }
    }

    private fun tryPresentNextFrame(): Boolean {
        val frame: PresentationFrame
        val now = clock()

        synchronized(queueLock) {
            frame = frameQueue.peek() ?: return false

            if (baseWallTimeMs.get() < 0) {
                baseWallTimeMs.set(now + bufferDelayMs)
                basePresentationTimeUs.set(frame.presentationTimeUs)
            }

            val elapsedSinceBase = now - baseWallTimeMs.get()
            val targetElapsedUs = frame.presentationTimeUs - basePresentationTimeUs.get()
            val targetElapsedMs = targetElapsedUs / 1000
            val drift = elapsedSinceBase - targetElapsedMs

            if (kotlin.math.abs(drift) > MAX_DRIFT_MS) {
                baseWallTimeMs.set(now + bufferDelayMs)
                basePresentationTimeUs.set(frame.presentationTimeUs)
                return false
            }

            if (elapsedSinceBase < targetElapsedMs) {
                return false
            }

            frameQueue.poll()

            val lateMs = elapsedSinceBase - targetElapsedMs
            if (lateMs > LATE_FRAME_THRESHOLD_MS) {
                Log.d(TAG, "Frame late by ${lateMs}ms")
            }
        }

        try {
            onFrameReady(frame)
        } catch (e: Exception) {
            Log.e(TAG, "Error presenting frame", e)
        }

        return true
    }
}
