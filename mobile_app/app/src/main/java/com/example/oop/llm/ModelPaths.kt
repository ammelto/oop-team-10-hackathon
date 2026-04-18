package com.example.oop.llm

import android.content.Context
import java.io.File

object ModelPaths {
    const val FILE_NAME = "gemma-4-E4B-it.litertlm"
    const val LEGACY_ADB_PATH = "/data/local/tmp/litertlm/$FILE_NAME"

    fun downloadDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "litertlm").apply { mkdirs() }

    fun downloadedFile(context: Context): File = File(downloadDir(context), FILE_NAME)

    fun partFile(context: Context): File = File(downloadDir(context), "$FILE_NAME.part")

    fun resolveExisting(context: Context): File? {
        val legacy = File(LEGACY_ADB_PATH)
        if (legacy.exists()) {
            return legacy
        }

        val local = downloadedFile(context)
        return local.takeIf(File::exists)
    }
}
