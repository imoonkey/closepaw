package com.moonkey.androidagent.session

import android.util.Log
import com.moonkey.androidagent.protocol.ApprovalMode
import com.moonkey.androidagent.tool.PolicyEngine
import com.moonkey.androidagent.tool.ToolRegistry
import com.moonkey.androidagent.tool.ToolRouter
import com.moonkey.androidagent.tool.impl.CompleteTaskTool
import com.moonkey.androidagent.tool.impl.MobileActionTool
import com.moonkey.androidagent.tool.impl.OpenAppTool
import com.moonkey.androidagent.tool.impl.ScratchpadTool
import com.moonkey.androidagent.tool.impl.ShellTool
import com.moonkey.androidagent.tool.impl.SystemButtonTool
import com.moonkey.androidagent.tool.impl.WaitTool
import com.moonkey.androidagent.tool.impl.WriteTodosTool

internal data class SessionToolingBootstrap(
        val policyEngine: PolicyEngine,
        val sessionState: AgentSessionState,
        val toolRegistry: ToolRegistry,
        val toolRouter: ToolRouter
)

/** Creates policy + session state + built-in tools + router for a session. */
internal object SessionToolingBootstrapper {
    private const val TAG = "SessionToolingBootstrap"

    fun create(approvalMode: ApprovalMode): SessionToolingBootstrap {
        val policyEngine = PolicyEngine(approvalMode)
        val sessionState = AgentSessionState()
        val toolRegistry = ToolRegistry().apply { registerBuiltInTools(sessionState) }
        val toolRouter = ToolRouter(toolRegistry, policyEngine)

        Log.d(TAG, "Created policy/tool stack with ${toolRegistry.size()} built-in tools")

        return SessionToolingBootstrap(
                policyEngine = policyEngine,
                sessionState = sessionState,
                toolRegistry = toolRegistry,
                toolRouter = toolRouter
        )
    }

    private fun ToolRegistry.registerBuiltInTools(sessionState: AgentSessionState) {
        register(CompleteTaskTool())
        register(MobileActionTool())
        register(SystemButtonTool())
        register(WaitTool())
        register(OpenAppTool())
        register(ShellTool())
        register(WriteTodosTool(sessionState.todos))
        register(ScratchpadTool(sessionState.scratchpad))
    }
}
