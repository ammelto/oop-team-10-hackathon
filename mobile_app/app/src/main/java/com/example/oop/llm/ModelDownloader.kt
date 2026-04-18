package com.example.oop.llm

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface DownloadState {
    data class InProgress(val bytesDownloaded: Long, val totalBytes: Long) : DownloadState

    data object Success : DownloadState

    data class Failed(val reason: Reason, val message: String) : DownloadState

    enum class Reason {
        UNAUTHORIZED,
        NETWORK,
        STORAGE,
        CANCELLED,
        UNKNOWN,
    }
}

class ModelDownloader(
    private val context: Context,
    private val tokenStore: TokenStore,
) {
    private val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    @Volatile
    private var currentId: Long = NO_DOWNLOAD

    @Volatile
    private var cancelRequested = false

    fun download(): Flow<DownloadState> = flow {
        val token = tokenStore.get()
            ?: run {
                emit(DownloadState.Failed(DownloadState.Reason.UNAUTHORIZED, "Missing HF token"))
                return@flow
            }

        val preflight = preflightRequest(token)
        if (preflight != null) {
            if (preflight.reason == DownloadState.Reason.UNAUTHORIZED) {
                tokenStore.clear()
            }
            emit(preflight)
            return@flow
        }

        val finalFile = ModelPaths.downloadedFile(context)
        val requiredBytes = maxOf(MIN_REQUIRED_BYTES, finalFile.length())
        if (!hasEnoughStorage(requiredBytes)) {
            emit(
                DownloadState.Failed(
                    DownloadState.Reason.STORAGE,
                    "Not enough storage for ${ModelPaths.FILE_NAME}",
                ),
            )
            return@flow
        }

        val partFile = ModelPaths.partFile(context).also {
            it.parentFile?.mkdirs()
            it.delete()
        }

        val request = DownloadManager.Request(Uri.parse(HF_URL))
            .addRequestHeader("Authorization", "Bearer $token")
            .addRequestHeader("User-Agent", "oop-android/1.0")
            .setTitle("Gemma 4 E4B model")
            .setDescription("Downloading ${ModelPaths.FILE_NAME}")
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
                    val reason = if (cancelRequested) {
                        DownloadState.Reason.CANCELLED
                    } else {
                        DownloadState.Reason.UNKNOWN
                    }
                    val message = if (cancelRequested) {
                        "Download cancelled"
                    } else {
                        "Download no longer available"
                    }
                    emit(DownloadState.Failed(reason, message))
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
                                DownloadState.Failed(
                                    DownloadState.Reason.STORAGE,
                                    "Failed to finalize model download",
                                ),
                            )
                            return@flow
                        }
                        emit(DownloadState.Success)
                        return@flow
                    }

                    DownloadManager.STATUS_FAILED -> {
                        val mappedReason = mapReason(snapshot.reason)
                        if (mappedReason == DownloadState.Reason.UNAUTHORIZED) {
                            tokenStore.clear()
                        }
                        emit(DownloadState.Failed(mappedReason, snapshot.reasonText))
                        return@flow
                    }

                    else -> {
                        emit(
                            DownloadState.InProgress(
                                bytesDownloaded = snapshot.bytesDownloaded,
                                totalBytes = snapshot.totalBytes,
                            ),
                        )
                    }
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
        ModelPaths.partFile(context).delete()
    }

    private fun preflightRequest(token: String): DownloadState.Failed? {
        return try {
            val connection = (URL(HF_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $token")
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

                    HttpURLConnection.HTTP_UNAUTHORIZED,
                    HttpURLConnection.HTTP_FORBIDDEN,
                    -> DownloadState.Failed(
                        DownloadState.Reason.UNAUTHORIZED,
                        "Your Hugging Face token is missing access to this model. Accept the Gemma license and try again.",
                    )

                    else -> DownloadState.Failed(
                        DownloadState.Reason.NETWORK,
                        "Model download preflight failed (${connection.responseCode})",
                    )
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            DownloadState.Failed(
                DownloadState.Reason.NETWORK,
                "Unable to reach Hugging Face",
            )
        }
    }

    private fun hasEnoughStorage(requiredBytes: Long): Boolean {
        val statFs = StatFs(ModelPaths.downloadDir(context).absolutePath)
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

    private fun mapReason(reason: Int): DownloadState.Reason =
        when (reason) {
            DownloadManager.ERROR_INSUFFICIENT_SPACE,
            DownloadManager.ERROR_DEVICE_NOT_FOUND,
            -> DownloadState.Reason.STORAGE

            DownloadManager.ERROR_CANNOT_RESUME,
            DownloadManager.ERROR_HTTP_DATA_ERROR,
            DownloadManager.ERROR_TOO_MANY_REDIRECTS,
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
            DownloadManager.ERROR_UNKNOWN,
            -> DownloadState.Reason.NETWORK

            else -> if (cancelRequested) {
                DownloadState.Reason.CANCELLED
            } else {
                DownloadState.Reason.UNKNOWN
            }
        }

    private fun reasonMessage(reason: Int): String =
        when (reason) {
            DownloadManager.ERROR_CANNOT_RESUME -> "Download could not resume"
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Download storage location not found"
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "Model file already exists"
            DownloadManager.ERROR_FILE_ERROR -> "Unable to write the model file"
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP error while downloading the model"
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage for the model"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Too many download redirects"
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Unhandled HTTP response while downloading"
            DownloadManager.ERROR_UNKNOWN -> if (cancelRequested) "Download cancelled" else "Unknown download failure"
            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for network"
            DownloadManager.PAUSED_WAITING_TO_RETRY -> "Waiting to retry the download"
            else -> if (cancelRequested) "Download cancelled" else "Model download failed"
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
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true"
        const val HTTP_TEMP_REDIRECT = 307
        const val HTTP_PERM_REDIRECT = 308
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 15_000
        const val POLL_INTERVAL_MS = 500L
        const val NO_DOWNLOAD = -1L
        const val MIN_REQUIRED_BYTES = 4_000_000_000L
    }
}
