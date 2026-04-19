package com.example.oop.ontology

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

class OntologyInstaller(private val context: Context) {
    fun ensureInstalled(): Flow<InstallState> = flow {
        if (shouldSkipInstall()) {
            emit(InstallState.Done)
            return@flow
        }

        val installDir = OntologyPaths.installDir(context).apply {
            deleteRecursively()
            mkdirs()
        }

        for (source in OntologyPaths.allSources) {
            if (OntologyPaths.legacyFile(source.fileName).exists()) {
                continue
            }

            val partFile = File(installDir, "${source.fileName}.part")
            val targetFile = File(installDir, source.fileName)
            emit(InstallState.Copying(source.fileName, bytes = 0L, total = estimateSize(source.resId)))

            val copyResult = runCatching { copyRawResource(source.resId, partFile) }
            if (copyResult.isFailure) {
                partFile.delete()
                emit(
                    InstallState.Failed(
                        copyResult.exceptionOrNull()?.message ?: "Failed to install ${source.fileName}",
                    ),
                )
                return@flow
            }

            if (!partFile.renameTo(targetFile)) {
                partFile.delete()
                emit(InstallState.Failed("Failed to finalize ${source.fileName}"))
                return@flow
            }
        }

        emit(InstallState.Done)
    }.flowOn(Dispatchers.IO)

    private fun shouldSkipInstall(): Boolean {
        if (!OntologyPaths.isInstalled(context)) {
            return false
        }
        val installedVersion = readManifestVersion(OntologyPaths.manifestFile(context)) ?: return false
        val bundledVersion = readBundledManifestVersion() ?: return false
        return installedVersion == bundledVersion
    }

    private fun copyRawResource(resId: Int, destination: File) {
        context.resources.openRawResource(resId).use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output, DEFAULT_BUFFER_SIZE)
            }
        }
    }

    private fun estimateSize(resId: Int): Long =
        runCatching {
            context.resources.openRawResourceFd(resId).use { it.length }
        }.getOrDefault(-1L)

    private fun readBundledManifestVersion(): Int? {
        val text = context.resources.openRawResource(OntologyPaths.MANIFEST.resId).bufferedReader().use { it.readText() }
        return parseVersion(text)
    }

    private fun readManifestVersion(file: File): Int? {
        if (!file.exists()) {
            return null
        }
        return parseVersion(file.readText())
    }

    private fun parseVersion(text: String): Int? =
        VERSION_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private companion object {
        val VERSION_REGEX = Regex("\"version\"\\s*:\\s*(\\d+)")
    }
}
