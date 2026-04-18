package com.example.oop.chat.model

import android.net.Uri

enum class Role {
    USER,
    ASSISTANT,
}

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val imageUri: Uri? = null,
)
