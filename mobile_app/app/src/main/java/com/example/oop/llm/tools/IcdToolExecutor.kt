package com.example.oop.llm.tools

import com.example.oop.ontology.OntologyIndex
import com.example.oop.ontology.OntologyUnavailableException
import com.example.oop.ontology.toDesignation
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class IcdToolExecutor(private val index: OntologyIndex) : ToolExecutor {
    override val name: String = "icd_lookup"

    override suspend fun invoke(invocation: ToolInvocation): ToolOutcome {
        val query = invocation.arguments["query"]?.jsonPrimitive?.contentOrNull
            ?: return ToolOutcome.Error("bad_arguments", "missing 'query'")
        val limit = invocation.arguments["limit"]?.jsonPrimitive?.intOrNull ?: 5

        return try {
            val matches = index.icdLookup(query, limit)
            val designation = matches.firstOrNull()?.toDesignation(source = "icd10cm")
            ToolOutcome.Success(
                json = encodeMatchesJson(
                    source = "icd10cm",
                    matches = matches,
                    designation = designation,
                ),
                designation = designation,
            )
        } catch (error: OntologyUnavailableException) {
            ToolOutcome.Error("ontology_unavailable", error.message ?: "ICD-10-CM unavailable")
        }
    }
}
