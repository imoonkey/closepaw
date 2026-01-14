package com.moonkey.androidagent.data.llm

enum class Role {
    SYSTEM,
    USER,
    ASSISTANT
}

data class ChatMessage(val role: Role, val content: String)
