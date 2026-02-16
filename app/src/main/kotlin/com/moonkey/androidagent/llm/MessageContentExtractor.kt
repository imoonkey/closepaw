package com.moonkey.androidagent.llm

/** Shared extractor for mixed message content payloads. */
internal fun extractMessageContent(content: Any): String {
    return when (content) {
        is String -> content
        is List<*> -> {
            content.mapNotNull { part ->
                when (part) {
                    is String -> part
                    else -> part?.toString()
                }
            }.joinToString(" ")
        }
        else -> content.toString()
    }
}
