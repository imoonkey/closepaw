package com.moonkey.androidagent.agent.cognition.profile

internal interface CognitionProfileRegistry {
    fun resolve(profileId: String?): CognitionProfile
}

internal class DefaultCognitionProfileRegistry(
    profiles: List<CognitionProfile> = BuiltinCognitionProfiles.all()
) : CognitionProfileRegistry {
    private val baseline = profiles.first { it.id == BuiltinCognitionProfiles.BASELINE_ID }
    private val byId = profiles.associateBy { it.id }

    override fun resolve(profileId: String?): CognitionProfile {
        if (profileId == null) return baseline
        return byId[profileId] ?: baseline
    }
}
