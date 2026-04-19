package com.example.oop.ontology

sealed interface InstallState {
    data class Copying(
        val file: String,
        val bytes: Long,
        val total: Long,
    ) : InstallState

    data object Done : InstallState

    data class Failed(val message: String) : InstallState
}
