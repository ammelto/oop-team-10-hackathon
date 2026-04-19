package com.example.oop.wearables.model

sealed class SourceStatus {
    data object Uninitialized : SourceStatus()

    data object Unsupported : SourceStatus()

    data object NotInstalled : SourceStatus()

    data class PermissionRequired(val missing: Set<String>) : SourceStatus()

    data object Ready : SourceStatus()

    data class Error(val message: String, val cause: Throwable? = null) : SourceStatus()
}
