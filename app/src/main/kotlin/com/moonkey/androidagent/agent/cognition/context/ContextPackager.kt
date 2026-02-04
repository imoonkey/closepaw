package com.moonkey.androidagent.agent.cognition.context

import com.moonkey.androidagent.agent.AgentPromptBuilder
import com.moonkey.androidagent.agent.cognition.profile.CognitionProfile
import com.moonkey.androidagent.model.ScreenSnapshot

internal data class RawTurnData(
    val snapshot: ScreenSnapshot
)

internal data class PackagedTurnInput(
    val userContext: AgentPromptBuilder.UserContext
)

internal interface ContextPackager {
    fun buildTurnInput(
        profile: CognitionProfile,
        raw: RawTurnData
    ): PackagedTurnInput
}

internal class DefaultContextPackager(
    private val promptBuilder: AgentPromptBuilder
) : ContextPackager {
    override fun buildTurnInput(profile: CognitionProfile, raw: RawTurnData): PackagedTurnInput {
        // Current policy is STANDARD, so we delegate to existing user context builder.
        return PackagedTurnInput(userContext = promptBuilder.buildUserContext(raw.snapshot))
    }
}
