package com.example.oop.whisper

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.StatFs
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

sealed interface WhisperDownloadState {
    data class InProgress(val bytesDownloaded: Long, val totalBytes: Long) : WhisperDownloadState

    data object Success : WhisperDownloadState

    data class Failed(val reason: Reason, val message: String) : WhisperDownloadState

    enum class Reason {
        NETWORK,
        STORAGE,
        CANCELLED,
        UNKNOWN,
    }
}

class WhisperModelDownloader(private val context: Context) {
    private val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    @Volatile
    private var currentId: Long = NO_DOWNLOAD

    @Volatile
    private var cancelRequested = false

    fun download(): Flow<WhisperDownloadState> = flow {
        val preflight = preflightRequest()
        if (preflight != null) {
            emit(preflight)
            return@flow
        }

        val finalFile = WhisperPaths.downloadedFile(context)
        val requiredBytes = maxOf(MIN_REQUIRED_BYTES, finalFile.length())
        if (!hasEnoughStorage(requiredBytes)) {
            emit(
                WhisperDownloadState.Failed(
                    WhisperDownloadState.Reason.STORAGE,
                    "Not enough storage for ${WhisperPaths.MODEL_FILE_NAME}",
                ),
            )
            return@flow
        }

        val partFile = WhisperPaths.partFile(context).also {
            it.parentFile?.mkdirs()
            it.delete()
        }

        val request = DownloadManager.Request(Uri.parse(HF_URL))
            .addRequestHeader("User-Agent", "oop-android/1.0")
            .setTitle("Whisper tiny.en model")
            .setDescription("Downloading ${WhisperPaths.MODEL_FILE_NAME}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(partFile))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        cancelRequested = false
        currentId = downloadManager.enqueue(request)

        try {
            while (currentCoroutineContext().isActive) {
                val snapshot = querySnapshot(currentId)
                if (snapshot == null) {
                    emit(
                        WhisperDownloadState.Failed(
                            if (cancelRequested) {
                                WhisperDownloadState.Reason.CANCELLED
                            } else {
                                WhisperDownloadState.Reason.UNKNOWN
                            },
                            if (cancelRequested) {
                                "Whisper model download cancelled"
                            } else {
                                "Whisper model download no longer available"
                            },
                        ),
                    )
                    return@flow
                }

                when (snapshot.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val finalPath = finalFile.also { it.parentFile?.mkdirs() }
                        if (finalPath.exists()) {
                            finalPath.delete()
                        }
                        if (!partFile.renameTo(finalPath)) {
                            emit(
                                WhisperDownloadState.Failed(
                                    WhisperDownloadState.Reason.STORAGE,
                                    "Failed to finalize Whisper model download",
                                ),
                            )
                            return@flow
                        }
                        emit(WhisperDownloadState.Success)
                        return@flow
                    }

                    DownloadManager.STATUS_FAILED -> {
                        emit(
                            WhisperDownloadState.Failed(
                                mapReason(snapshot.reason),
                                snapshot.reasonText,
                            ),
                        )
                        return@flow
                    }

                    else -> emit(
                        WhisperDownloadState.InProgress(
                            bytesDownloaded = snapshot.bytesDownloaded,
                            totalBytes = snapshot.totalBytes,
                        ),
                    )
                }

                delay(POLL_INTERVAL_MS)
            }
        } finally {
            currentId = NO_DOWNLOAD
            cancelRequested = false
        }
    }.flowOn(Dispatchers.IO)

    fun cancel() {
        if (currentId != NO_DOWNLOAD) {
            cancelRequested = true
            downloadManager.remove(currentId)
        }
    }

    fun clearPartial() {
        WhisperPaths.partFile(context).delete()
    }

    private fun preflightRequest(): WhisperDownloadState.Failed? =
        try {
            val connection = (URL(HF_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("User-Agent", "oop-android/1.0")
            }

            try {
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK,
                    HttpURLConnection.HTTP_PARTIAL,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    HTTP_TEMP_REDIRECT,
                    HTTP_PERM_REDIRECT,
                    -> null

                    else -> WhisperDownloadState.Failed(
                        WhisperDownloadState.Reason.NETWORK,
                        "Whisper model preflight failed (${connection.responseCode})",
                    )
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            WhisperDownloadState.Failed(
                WhisperDownloadState.Reason.NETWORK,
                "Unable to reach the Whisper model host",
            )
        }

    private fun hasEnoughStorage(requiredBytes: Long): Boolean {
        val statFs = StatFs(WhisperPaths.installDir(context).absolutePath)
        return statFs.availableBytes > requiredBytes
    }

    private fun querySnapshot(downloadId: Long): DownloadSnapshot? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query) ?: return null
        cursor.use {
            if (!it.moveToFirst()) {
                return null
            }

            return DownloadSnapshot(
                status = it.getIntSafely(DownloadManager.COLUMN_STATUS),
                reason = it.getIntSafely(DownloadManager.COLUMN_REASON),
                bytesDownloaded = it.getLongSafely(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                totalBytes = it.getLongSafely(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                reasonText = reasonMessage(it.getIntSafely(DownloadManager.COLUMN_REASON)),
            )
        }
    }

    private fun mapReason(reason: Int): WhisperDownloadState.Reason =
        when (reason) {
            DownloadManager.ERROR_INSUFFICIENT_SPACE,
            DownloadManager.ERROR_DEVICE_NOT_FOUND,
            -> WhisperDownloadState.Reason.STORAGE

            DownloadManager.ERROR_CANNOT_RESUME,
            DownloadManager.ERROR_HTTP_DATA_ERROR,
            DownloadManager.ERROR_TOO_MANY_REDIRECTS,
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
            DownloadManager.ERROR_UNKNOWN,
            -> WhisperDownloadState.Reason.NETWORK

            else -> if (cancelRequested) {
                WhisperDownloadState.Reason.CANCELLED
            } else {
                WhisperDownloadState.Reason.UNKNOWN
            }
        }

    private fun reasonMessage(reason: Int): String =
        when (reason) {
            DownloadManager.ERROR_CANNOT_RESUME -> "Whisper download could not resume"
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Whisper download storage location not found"
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "Whisper model file already exists"
            DownloadManager.ERROR_FILE_ERROR -> "Unable to write the Whisper model file"
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP error while downloading the Whisper model"
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage for the Whisper model"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Too many Whisper download redirects"
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Unhandled HTTP response while downloading Whisper"
            DownloadManager.ERROR_UNKNOWN ->
                if (cancelRequested) "Whisper download cancelled" else "Unknown Whisper download failure"
            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for network"
            DownloadManager.PAUSED_WAITING_TO_RETRY -> "Waiting to retry the Whisper download"
            else -> if (cancelRequested) "Whisper download cancelled" else "Whisper download failed"
        }

    private fun Cursor.getIntSafely(columnName: String): Int = getInt(getColumnIndexOrThrow(columnName))

    private fun Cursor.getLongSafely(columnName: String): Long = getLong(getColumnIndexOrThrow(columnName))

    private data class DownloadSnapshot(
        val status: Int,
        val reason: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val reasonText: String,
    )

    private companion object {
        const val HF_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_1.bin?download=true"
        const val HTTP_TEMP_REDIRECT = 307
        const val HTTP_PERM_REDIRECT = 308
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 15_000
        const val POLL_INTERVAL_MS = 500L
        const val NO_DOWNLOAD = -1L
        const val MIN_REQUIRED_BYTES = 45_000_000L
    }
}
