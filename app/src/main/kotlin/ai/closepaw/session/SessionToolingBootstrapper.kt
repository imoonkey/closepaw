package ai.closepaw.session

import android.util.Log
import ai.closepaw.agent.cognition.skills.AgentSkillManager
import ai.closepaw.protocol.ApprovalMode
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.PolicyEngine
import ai.closepaw.tool.ToolRegistry
import ai.closepaw.tool.ToolRouter
import ai.closepaw.tool.impl.ActivateSkillTool
import ai.closepaw.tool.impl.CompleteTaskTool
import ai.closepaw.tool.impl.MobileActionTool
import ai.closepaw.tool.impl.OpenAppTool
import ai.closepaw.tool.impl.ScratchpadTool
import ai.closepaw.tool.impl.ShellTool
import ai.closepaw.tool.impl.SystemButtonTool
import ai.closepaw.tool.impl.WaitTool
import ai.closepaw.tool.impl.WriteTodosTool

internal data class SessionToolingBootstrap(
        val policyEngine: PolicyEngine,
        val sessionState: AgentSessionState,
        val toolRegistry: ToolRegistry,
        val toolRouter: ToolRouter
)

/** Creates policy + session state + built-in tools + router for a session. */
internal object SessionToolingBootstrapper {
    private const val TAG = "SessionToolingBootstrap"

    fun create(
        approvalMode: ApprovalMode,
        appClassifier: AppClassifier,
        initialPersistentAllowList: Set<String> = emptySet(),
        onPersistentAllowListChanged: ((Set<String>) -> Unit)? = null,
        agentSkillManager: AgentSkillManager? = null
    ): SessionToolingBootstrap {
        val policyEngine = PolicyEngine(
            initialApprovalMode = approvalMode,
            appClassifier = appClassifier,
            initialPersistentAllowList = initialPersistentAllowList,
            onPersistentAllowListChanged = onPersistentAllowListChanged
        )
        val sessionState = AgentSessionState()
        val toolRegistry = ToolRegistry().apply { registerBuiltInTools(sessionState) }

        if (agentSkillManager != null && agentSkillManager.catalogPrompt() != null) {
            toolRegistry.register(ActivateSkillTool(agentSkillManager))
            Log.d(TAG, "Registered ActivateSkillTool (catalog non-empty)")
        }

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
