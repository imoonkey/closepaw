package ai.closepaw.trace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal object TraceJson {
    val instance: Json =
        Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }
}

@Serializable
data class TraceArtifactRef(
    val kind: String,
    /** Path relative to the trace run folder. */
    val path: String,
    val mimeType: String? = null,
    val description: String? = null
)

@Serializable
data class TraceEventRecord(
    /** Trace schema version. */
    val v: Int = 1,
    val runId: String,
    val seq: Long,
    val tsMs: Long,
    val sessionId: String,
    val turnId: String? = null,
    val turnNumber: Int? = null,
    val type: String,
    val data: JsonElement? = null,
    val artifacts: List<TraceArtifactRef> = emptyList()
)

@Serializable
data class TraceRunMeta(
    val v: Int = 1,
    val runId: String,
    val createdAtMs: Long,
    val sessionId: String,
    val appId: String,
    val appVersionName: String,
    val appVersionCode: Int,
    val deviceModel: String,
    val deviceManufacturer: String,
    val deviceSdkInt: Int,
    val config: TraceRunConfig
)

@Serializable
data class TraceRunConfig(
    @SerialName("llm_backend") val llmBackend: String,
    val model: String,
    @SerialName("main_model") val mainModel: String = model,
    @SerialName("subagent_model") val subagentModel: String? = null,
    @SerialName("debug_mode") val debugMode: Boolean,
    @SerialName("screenshot_input") val screenshotInput: Boolean,
    @SerialName("max_turns") val maxTurns: Int
)
