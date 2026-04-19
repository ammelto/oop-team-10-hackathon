package com.example.oop.ontology

import kotlinx.serialization.Serializable

data class OntologyRecord(
    val primaryId: String,
    val preferredTerm: String,
    val fullySpecifiedName: String? = null,
    val section: String? = null,
    val bodyRegion: String? = null,
    val severity: AisSeverity? = null,
)

data class OntologyMatch(
    val record: OntologyRecord,
    val matchedField: String,
    val score: Int,
)

@Serializable
data class Designation(
    val source: String,
    val primaryId: String,
    val preferredTerm: String,
    val detail: String? = null,
)

fun OntologyMatch.toDesignation(source: String): Designation = Designation(
    source = source,
    primaryId = record.primaryId,
    preferredTerm = record.preferredTerm,
    detail = when (source) {
        "snomed" -> record.fullySpecifiedName
        "ais1985" -> record.severity?.label
        else -> null
    },
)
