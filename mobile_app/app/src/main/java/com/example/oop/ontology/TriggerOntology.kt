package com.example.oop.ontology

import android.content.Context
import android.util.Log
import com.example.oop.R
import kotlinx.serialization.Serializable

@Serializable
enum class TriggerCategory {
    PROCEDURE_INITIATION,
    PROCEDURE_CONFIRMATION,
    WOUND_INJURY,
    EXAM_FINDING,
    HEMORRHAGE,
    STATUS_CHANGE,
    SCENE_CONTEXT,
    EQUIPMENT,
    ;

    companion object {
        fun fromFileToken(token: String): TriggerCategory? = when (token.trim().lowercase()) {
            "procedure_initiation" -> PROCEDURE_INITIATION
            "procedure_confirmation" -> PROCEDURE_CONFIRMATION
            "wound_identification" -> WOUND_INJURY
            "exam_finding" -> EXAM_FINDING
            "hemorrhage" -> HEMORRHAGE
            "status_change" -> STATUS_CHANGE
            "scene_context" -> SCENE_CONTEXT
            "equipment_visualization" -> EQUIPMENT
            else -> null
        }
    }
}

data class TriggerEntry(
    val id: String,
    val phrase: String,
    val category: TriggerCategory,
    val pointOfInterest: String,
    val requiresCorroboration: Boolean,
    val cooldownMs: Long,
)

object TriggerOntology {
    fun load(context: Context): List<TriggerEntry> {
        val entries =
            context.resources.openRawResource(R.raw.triggers).bufferedReader().useLines { lines ->
                lines.mapIndexedNotNull { index, line -> parseLine(line, index + 1) }.toList()
            }
        check(entries.isNotEmpty()) { "No trigger entries loaded from triggers.txt" }
        return entries
    }

    private fun parseLine(
        rawLine: String,
        lineNumber: Int,
    ): TriggerEntry? {
        val line = rawLine.trim()
        if (line.isBlank() || line.startsWith("#")) {
            return null
        }

        val parts = rawLine.split('|').map(String::trim)
        if (parts.size < 3) {
            Log.w(TAG, "Skipping malformed trigger row $lineNumber: $rawLine")
            return null
        }

        val phrase = parts[0]
        val category =
            TriggerCategory.fromFileToken(parts[1]) ?: run {
                Log.w(TAG, "Skipping trigger row $lineNumber with unknown category: ${parts[1]}")
                return null
            }
        val pointOfInterest = parts[2]
        if (phrase.isBlank() || pointOfInterest.isBlank()) {
            Log.w(TAG, "Skipping trigger row $lineNumber with blank phrase or POI: $rawLine")
            return null
        }

        return TriggerEntry(
            id = buildId(category, phrase),
            phrase = phrase,
            category = category,
            pointOfInterest = pointOfInterest,
            requiresCorroboration = parseCorroboration(parts.getOrNull(3)),
            cooldownMs = defaultCooldownMs(category),
        )
    }

    private fun buildId(
        category: TriggerCategory,
        phrase: String,
    ): String {
        val slug =
            phrase
                .trim()
                .lowercase()
                .replace(SLUG_SEPARATOR_REGEX, "_")
                .replace(SLUG_TRIM_REGEX, "")
        return "${category.name.lowercase()}:$slug"
    }

    private fun parseCorroboration(rawValue: String?): Boolean = when (rawValue?.trim()?.lowercase()) {
        null,
        "",
        "0",
        "false",
        -> false

        "1",
        "true",
        -> true

        else -> false
    }

    private fun defaultCooldownMs(category: TriggerCategory): Long =
        if (category == TriggerCategory.SCENE_CONTEXT) {
            60_000L
        } else {
            20_000L
        }

    private const val TAG = "TriggerOntology"
    private val SLUG_SEPARATOR_REGEX = Regex("[^a-z0-9]+")
    private val SLUG_TRIM_REGEX = Regex("^_+|_+$")
}
