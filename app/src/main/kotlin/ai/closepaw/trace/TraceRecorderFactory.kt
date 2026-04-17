package ai.closepaw.trace

import android.content.Context
import android.os.Build
import ai.closepaw.BuildConfig
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.protocol.SessionId

object TraceRecorderFactory {
    private const val TRACE_ROOT_DIR_NAME = "inspection-trace"

    fun create(context: Context, config: SessionConfig, sessionId: SessionId): TraceRecorder {
        if (!config.traceEnabled) return NoopTraceRecorder

        val runId = config.traceRunId?.takeIf { it.isNotBlank() } ?: sessionId.value
        val rootDir = context.getExternalFilesDir(TRACE_ROOT_DIR_NAME) ?: return NoopTraceRecorder

        val recorder =
            FileTraceRecorder(
                runId = runId,
                rootDir = rootDir
            )

        recorder.writeMeta(
            TraceRunMeta(
                runId = runId,
                createdAtMs = System.currentTimeMillis(),
                sessionId = sessionId.value,
                appId = BuildConfig.APPLICATION_ID,
                appVersionName = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE,
                deviceModel = Build.MODEL ?: "unknown",
                deviceManufacturer = Build.MANUFACTURER ?: "unknown",
                deviceSdkInt = Build.VERSION.SDK_INT,
                config =
                    TraceRunConfig(
                        llmBackend = config.llm.backendType.name,
                        model = config.mainModel,
                        mainModel = config.mainModel,
                        executorModel = config.executorModel,
                        debugMode = config.debugMode,
                        screenshotInput = config.perceptionConfig.capturesScreenshot,
                        maxTurns = config.maxTurns
                    )
            )
        )

        return recorder
    }
}
