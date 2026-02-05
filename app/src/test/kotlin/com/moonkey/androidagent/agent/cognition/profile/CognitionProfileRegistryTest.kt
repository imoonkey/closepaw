package com.moonkey.androidagent.agent.cognition.profile

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CognitionProfileRegistryTest {

    private val registry = DefaultCognitionProfileRegistry()

    @Test
    fun `resolve returns baseline when id is null`() {
        val profile = registry.resolve(null)
        assertThat(profile.id).isEqualTo(BuiltinCognitionProfiles.BASELINE_ID)
    }

    @Test
    fun `resolve falls back to baseline when id is not registered`() {
        val profile = registry.resolve("concise")
        assertThat(profile.id).isEqualTo(BuiltinCognitionProfiles.BASELINE_ID)
    }

    @Test
    fun `resolve falls back to baseline when id unknown`() {
        val profile = registry.resolve("unknown-profile")
        assertThat(profile.id).isEqualTo(BuiltinCognitionProfiles.BASELINE_ID)
    }
}
