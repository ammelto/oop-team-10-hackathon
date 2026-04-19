package com.example.oop.llm.tools

import com.example.oop.ontology.TriggerCategory
import com.example.oop.ontology.TriggerIndex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class TriggerToolExecutor(private val index: TriggerIndex) : ToolExecutor {
    override val name: String = "trigger_lookup"

    override suspend fun invoke(invocation: ToolInvocation): ToolOutcome {
        val query = invocation.arguments["query"]?.jsonPrimitive?.contentOrNull
            ?: return ToolOutcome.Error("bad_arguments", "missing 'query'")
        val limit = invocation.arguments["limit"]?.jsonPrimitive?.intOrNull ?: 8
        val matches =
            index.match(query)
                .take(limit)
                .map { match ->
                    TriggerMatchPayload(
                        id = match.entry.id,
                        category = match.entry.category,
                        phrase = match.matchedPhrase,
                        pointOfInterest = match.pointOfInterest,
                        requiresCorroboration = match.entry.requiresCorroboration,
                        negated = match.negated,
                        cooldownMs = match.entry.cooldownMs,
                    )
                }

        return ToolOutcome.Success(
            json = ToolJson.encodeToString(TriggerToolPayload(source = "triggers", matches = matches)),
            designation = null,
        )
    }
}

@Serializable
private data class TriggerToolPayload(
    val source: String,
    val matches: List<TriggerMatchPayload>,
)

@Serializable
private data class TriggerMatchPayload(
    val id: String,
    val category: TriggerCategory,
    val phrase: String,
    val pointOfInterest: String,
    val requiresCorroboration: Boolean,
    val negated: Boolean,
    val cooldownMs: Long,
)
