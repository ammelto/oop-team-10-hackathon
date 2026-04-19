package com.example.oop.chat.mvi

import com.example.oop.ontology.Designation
import kotlinx.serialization.Serializable

@Serializable
data class ClassifiedCaption(
    val statement: String,
    val designations: List<Designation> = emptyList(),
)
