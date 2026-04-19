package com.example.oop.llm.tools

import com.example.oop.ontology.OntologyIndex
import com.example.oop.ontology.OntologyUnavailableException
import com.example.oop.ontology.toDesignation
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class AisToolExecutor(private val index: OntologyIndex) : ToolExecutor {
    override val name: String = "ais_lookup"

    override suspend fun invoke(invocation: ToolInvocation): ToolOutcome {
        val args = invocation.arguments
        val code = args["code"]?.jsonPrimitive?.contentOrNull
        val query = args["query"]?.jsonPrimitive?.contentOrNull
        val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 5

        if (code.isNullOrBlank() && query.isNullOrBlank()) {
            return ToolOutcome.Error("bad_arguments", "missing 'query' or 'code'")
        }

        return try {
            val matches = if (!code.isNullOrBlank()) {
                listOfNotNull(index.lookupAisByCode(code))
            } else {
                index.aisLookup(query.orEmpty(), limit)
            }
            val designation = matches.firstOrNull()?.toDesignation(source = "ais1985")
            ToolOutcome.Success(
                json = encodeMatchesJson(
                    source = "ais1985",
                    matches = matches,
                    designation = designation,
                ),
                designation = designation,
            )
        } catch (error: OntologyUnavailableException) {
            ToolOutcome.Error("ontology_unavailable", error.message ?: "AIS unavailable")
        }
    }
}
