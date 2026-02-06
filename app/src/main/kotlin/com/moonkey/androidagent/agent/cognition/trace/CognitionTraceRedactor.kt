package com.moonkey.androidagent.agent.cognition.trace

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object CognitionTraceRedactor {
    private val emailPattern = Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")
    private val bearerPattern = Regex("(?i)\\b(bearer\\s+)[a-z0-9._~+/=-]{8,}")
    private val longTokenPattern = Regex("(?<![A-Za-z0-9])[A-Za-z0-9_\\-]{24,}(?![A-Za-z0-9])")
    private val jwtPattern = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")

    private val sensitiveKeyHints =
        setOf(
            "password",
            "passwd",
            "token",
            "secret",
            "authorization",
            "cookie",
            "session",
            "api_key",
            "apikey",
            "access_key"
        )

    fun redactText(text: String): String {
        return text
            .let { emailPattern.replace(it, "[REDACTED_EMAIL]") }
            .let { bearerPattern.replace(it, "$1[REDACTED_TOKEN]") }
            .let { jwtPattern.replace(it, "[REDACTED_JWT]") }
            .let { longTokenPattern.replace(it) { match ->
                val value = match.value
                if (value.any(Char::isDigit) && value.any(Char::isLetter)) {
                    "[REDACTED_TOKEN]"
                } else {
                    value
                }
            } }
    }

    fun redactJson(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> redactObject(element)
            is JsonArray -> JsonArray(element.map(::redactJson))
            is JsonPrimitive -> redactPrimitive(element)
            JsonNull -> JsonNull
        }
    }

    private fun redactObject(obj: JsonObject): JsonObject {
        return JsonObject(
            obj.mapValues { (key, value) ->
                if (isSensitiveKey(key)) {
                    if (value is JsonNull) JsonNull else JsonPrimitive("[REDACTED]")
                } else {
                    redactJson(value)
                }
            }
        )
    }

    private fun redactPrimitive(primitive: JsonPrimitive): JsonPrimitive {
        if (primitive.isString) {
            return JsonPrimitive(redactText(primitive.content))
        }
        return primitive
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase()
        return sensitiveKeyHints.any { hint -> hint in normalized }
    }
}
