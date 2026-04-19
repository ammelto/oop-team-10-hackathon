package com.example.oop.ontology

import android.content.Context
import com.example.oop.R
import java.io.File

object OntologyPaths {
    const val LEGACY_ADB_DIR = "/data/local/tmp/ontology"

    data class Source(val resId: Int, val fileName: String)

    val SNOMED = Source(R.raw.snomed, "snomed.txt")
    val ICD = Source(R.raw.icd10cm_codes_2026, "icd10cm_codes_2026.txt")
    val AIS = Source(R.raw.ais_codes, "ais_codes.txt")
    val MANIFEST = Source(R.raw.ontology_manifest, "ontology_manifest.json")

    val allSources: List<Source> = listOf(MANIFEST, SNOMED, ICD, AIS)

    fun installDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "ontology").apply { mkdirs() }

    fun snomedFile(context: Context): File = resolve(context, SNOMED.fileName)

    fun icdFile(context: Context): File = resolve(context, ICD.fileName)

    fun aisFile(context: Context): File = resolve(context, AIS.fileName)

    fun manifestFile(context: Context): File = resolve(context, MANIFEST.fileName)

    fun isInstalled(context: Context): Boolean =
        listOf(
            snomedFile(context),
            icdFile(context),
            aisFile(context),
            manifestFile(context),
        ).all(File::exists)

    fun legacyFile(name: String): File = File(LEGACY_ADB_DIR, name)

    fun resolve(context: Context, fileName: String): File {
        val legacy = legacyFile(fileName)
        return if (legacy.exists()) legacy else File(installDir(context), fileName)
    }
}
