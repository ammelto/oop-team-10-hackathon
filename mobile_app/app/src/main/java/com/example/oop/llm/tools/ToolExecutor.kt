package com.example.oop.llm.tools

import com.example.oop.ontology.Designation
import com.example.oop.ontology.OntologyMatch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

data class ToolInvocation(
    val name: String,
    val arguments: JsonObject,
    val rawTag: String,
)

sealed interface ToolOutcome {
    data class Success(
        val json: String,
        val designation: Designation?,
    ) : ToolOutcome

    data class Error(
        val code: String,
        val message: String,
    ) : ToolOutcome
}

interface ToolExecutor {
    val name: String

    suspend fun invoke(invocation: ToolInvocation): ToolOutcome
}

internal val ToolJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
private data class ToolPayload(
    val source: String,
    val matches: List<ToolMatchPayload>,
    val designation: Designation? = null,
)

@Serializable
private data class ToolMatchPayload(
    val primaryId: String,
    val preferredTerm: String,
    val matchedField: String,
    val score: Int,
    val fullySpecifiedName: String? = null,
    val section: String? = null,
    val bodyRegion: String? = null,
    val severityDigit: Char? = null,
    val severityLabel: String? = null,
)

@Serializable
private data class ToolErrorPayload(
    val error: String,
    val message: String,
)

internal fun encodeMatchesJson(
    source: String,
    matches: List<OntologyMatch>,
    designation: Designation?,
): String = ToolJson.encodeToString(
    ToolPayload(
        source = source,
        matches = matches.map { match ->
            ToolMatchPayload(
                primaryId = match.record.primaryId,
                preferredTerm = match.record.preferredTerm,
                matchedField = match.matchedField,
                score = match.score,
                fullySpecifiedName = match.record.fullySpecifiedName,
                section = match.record.section,
                bodyRegion = match.record.bodyRegion,
                severityDigit = match.record.severity?.digit,
                severityLabel = match.record.severity?.label,
            )
        },
        designation = designation,
    ),
)

internal fun encodeToolResultJson(outcome: ToolOutcome): String = when (outcome) {
    is ToolOutcome.Success -> outcome.json
    is ToolOutcome.Error -> ToolJson.encodeToString(
        ToolErrorPayload(
            error = outcome.code,
            message = outcome.message,
        ),
    )
}
