package ai.closepaw.protocol

import java.util.UUID

/**
 * SessionId - Unique identifier for an agent session.
 * 
 * Value class for type safety without runtime overhead.
 */
@JvmInline
value class SessionId(val value: String) {
    companion object {
        fun generate(): SessionId = SessionId(UUID.randomUUID().toString())
    }
    
    override fun toString(): String = value
}

