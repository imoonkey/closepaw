package com.moonkey.androidagent.history.model

import com.moonkey.androidagent.protocol.ScreenStatePhase
import kotlinx.serialization.Serializable

@Serializable
data class ScreenStateRecord(
    val id: String,
    val timestamp: Long,
    val turnId: String,
    val turnNumber: Int,
    val phase: ScreenStatePhase,
    val elementCount: Int,
    val packageName: String?,
    val activityName: String?,
    val rawA11yTreePath: String?,
    val sanitizedA11yTreePath: String?,
    val screenshotPath: String?,
    val traceRunId: String?
)
