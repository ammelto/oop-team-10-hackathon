package com.example.oop.whisper

import android.content.Context
import java.io.File

object WhisperPaths {
    const val MODEL_FILE_NAME = "ggml-tiny.en-q5_1.bin"
    private val LEGACY_FILE_NAMES = listOf("ggml-tiny.en.bin")
    private const val ADB_DIR = "/data/local/tmp/whisper"

    fun installDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "whisper").apply { mkdirs() }

    fun downloadedFile(context: Context): File = File(installDir(context), MODEL_FILE_NAME)

    fun partFile(context: Context): File = File(installDir(context), "$MODEL_FILE_NAME.part")

    fun cleanupLegacyFiles(context: Context) {
        val dir = installDir(context)
        LEGACY_FILE_NAMES.forEach { name ->
            File(dir, name).takeIf(File::exists)?.delete()
        }
    }

    fun resolveExisting(context: Context): File? {
        val adbCandidates =
            buildList {
                add(File(ADB_DIR, MODEL_FILE_NAME))
                LEGACY_FILE_NAMES.forEach { add(File(ADB_DIR, it)) }
            }
        for (candidate in adbCandidates) {
            if (candidate.exists()) {
                return candidate
            }
        }
        return downloadedFile(context).takeIf(File::exists)
    }
}
