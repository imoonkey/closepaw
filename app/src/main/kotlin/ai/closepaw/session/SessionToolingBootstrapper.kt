package ai.closepaw.session

import android.content.Context
import android.util.Log
import ai.closepaw.agent.cognition.skills.AgentSkillManager
import ai.closepaw.agent.definition.AgentRoleDef
import ai.closepaw.agent.definition.ResolvedAgentRole
import ai.closepaw.agent.definition.StandaloneRoleDef
import ai.closepaw.protocol.ApprovalMode
import ai.closepaw.termux.TermuxBridgeManager
import ai.closepaw.termux.TermuxCapabilitySnapshot
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.PolicyEngine
import ai.closepaw.tool.ToolName
import ai.closepaw.tool.ToolRegistry
import ai.closepaw.tool.ToolRouter
import ai.closepaw.tool.impl.ActivateSkillTool
import ai.closepaw.tool.impl.CompleteTaskTool
import ai.closepaw.tool.impl.MobileActionTool
import ai.closepaw.tool.impl.OpenAppTool
import ai.closepaw.tool.impl.ScratchpadTool
import ai.closepaw.tool.impl.ShellTool
import ai.closepaw.tool.impl.SystemButtonTool
import ai.closepaw.tool.impl.TermuxShellTool
import ai.closepaw.tool.impl.WaitTool
import ai.closepaw.tool.impl.WriteTodosTool
import okhttp3.OkHttpClient

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
        agentSkillManager: AgentSkillManager? = null,
        agentRoleDef: AgentRoleDef = StandaloneRoleDef,
        delegatableRoleDefs: List<AgentRoleDef> = emptyList(),
        termuxSnapshot: TermuxCapabilitySnapshot = TermuxCapabilitySnapshot.Unavailable,
        excludedTools: Set<String> = emptySet(),
        context: Context? = null
    ): SessionToolingBootstrap {
        val policyEngine = PolicyEngine(
            initialApprovalMode = approvalMode,
            appClassifier = appClassifier,
            initialPersistentAllowList = initialPersistentAllowList,
            onPersistentAllowListChanged = onPersistentAllowListChanged
        )
        val sessionState = AgentSessionState()
        val allowedToolNames = resolveAllowedToolNames(
            agentRoleDef = agentRoleDef,
            delegatableRoleDefs = delegatableRoleDefs,
            termuxSnapshot = termuxSnapshot,
            excludedTools = excludedTools
        )
        val toolRegistry = ToolRegistry().apply {
            registerBuiltInTools(
                sessionState = sessionState,
                allowedToolNames = allowedToolNames,
                context = context
            )
        }

        if (
            ToolName.ActivateSkill.raw in allowedToolNames &&
                agentSkillManager != null &&
                agentSkillManager.catalogPrompt() != null
        ) {
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

    private fun resolveAllowedToolNames(
        agentRoleDef: AgentRoleDef,
        delegatableRoleDefs: List<AgentRoleDef>,
        termuxSnapshot: TermuxCapabilitySnapshot,
        excludedTools: Set<String>
    ): Set<String> {
        val excludedToolNames = excludedTools.map { ToolName.from(it) }.toSet()
        val resolvedRoles: List<ResolvedAgentRole> =
            (listOf(agentRoleDef) + delegatableRoleDefs)
                .map { it.resolve(termuxSnapshot, excludedToolNames) }

        return resolvedRoles
            .flatMap { role -> role.allowedTools.map { tool -> tool.raw } }
            .toSet()
    }

    private fun ToolRegistry.registerBuiltInTools(
        sessionState: AgentSessionState,
        allowedToolNames: Set<String>,
        context: Context?
    ) {
        if (ToolName.CompleteTask.raw in allowedToolNames) register(CompleteTaskTool())
        if (ToolName.MobileAction.raw in allowedToolNames) register(MobileActionTool())
        if (ToolName.SystemButton.raw in allowedToolNames) register(SystemButtonTool())
        if (ToolName.Wait.raw in allowedToolNames) register(WaitTool())
        if (ToolName.OpenApp.raw in allowedToolNames) register(OpenAppTool())
        if (ToolName.Shell.raw in allowedToolNames) register(ShellTool())
        if (ToolName.TermuxShell.raw in allowedToolNames) registerTermuxShellTool(context)
        if (ToolName.WriteTodos.raw in allowedToolNames) register(WriteTodosTool(sessionState.todos))
        if (ToolName.Scratchpad.raw in allowedToolNames) register(ScratchpadTool(sessionState.scratchpad))
    }

    private fun ToolRegistry.registerTermuxShellTool(context: Context?) {
        if (context != null) {
            TermuxBridgeManager.get(context)
        } else {
            Log.w(TAG, "Registering termux_shell without a Context; bridge manager not touched")
        }

        register(TermuxShellTool(httpClient = OkHttpClient()))
    }
}
