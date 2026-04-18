package com.example.oop.chat

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object CameraPhoto {
    data class Capture(val file: File, val uri: Uri)

    fun newCapture(context: Context): Capture {
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "photos").apply {
            mkdirs()
        }
        val file = File(directory, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Capture(file = file, uri = uri)
    }
}
