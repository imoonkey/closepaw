package com.moonkey.androidagent.session

import android.content.Context
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.protocol.SessionConfig
import com.moonkey.androidagent.tool.ToolSpec
import com.moonkey.androidagent.trace.TraceRecorder
import kotlinx.coroutines.CoroutineScope

/** Extension for creating SessionServices with additional tool options. */
object SessionServicesBuilder {

    /** Create SessionServices with custom tool configuration. */
    fun createWithCustomTools(
            config: SessionConfig,
            platform: AndroidPlatform,
            apiKeys: Map<String, String> = emptyMap(),
            context: Context,
            scope: CoroutineScope,
            traceRecorder: TraceRecorder,
            additionalTools: List<ToolSpec> = emptyList(),
            excludeTools: Set<String> = emptySet()
    ): SessionServices {
        val services =
                SessionServices.create(config, platform, apiKeys, context, scope, traceRecorder)

        excludeTools.forEach { name -> services.toolRegistry.unregister(name) }
        additionalTools.forEach { tool -> services.toolRegistry.register(tool) }

        return services
    }
}
