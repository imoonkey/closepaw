package com.moonkey.androidagent.domain.agents

import com.moonkey.androidagent.domain.models.ScreenSnapshot
import com.moonkey.androidagent.domain.state.InfoPool

/** Base Agent Interface. Defines the contract for all intelligent agents in the systems. */
interface Agent<Result> {
    /**
     * Reasoning step.
     * @param scope The global state (InfoPool)
     * @param context The current perception of the world (ScreenSnapshot)
     * @return The agent's decision/output
     */
    suspend fun think(scope: InfoPool, context: ScreenSnapshot): Result
}
