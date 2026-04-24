package ai.closepaw.tool.impl

import ai.closepaw.memory.MemoryScope
import ai.closepaw.memory.MemorySchema
import ai.closepaw.memory.MemorySection
import ai.closepaw.memory.MemoryStore
import ai.closepaw.protocol.AppTier
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ToolInvocation
import ai.closepaw.tool.ToolSpec
import ai.closepaw.tool.ValidationResult
import ai.closepaw.tool.appendReason
import ai.closepaw.tool.textToolSuccess
import org.json.JSONArray
import org.json.JSONObject

class RememberExperienceTool(
    private val store: MemoryStore,
    private val appClassifier: AppClassifier
) : ToolSpec {
    companion object {
        private val SCOPE_VALUES = JSONArray(MemoryScope.entries.map { it.wireValue })
        private val SECTION_VALUES = JSONArray(MemorySection.entries.map { it.wireValue })
    }

    override val name: String = "remember_experience"

    override val description: String =
        """
        Save a durable learning to long-term memory. Call alongside complete_task when you learned something reusable across future tasks.

        Use `scope=user` for cross-app user facts/preferences, `scope=device` for device facts/pitfalls/verification, and `scope=app` for app-specific overrides/preferences/operational notes.
        App operational notes should be plain-language notes, not `[pitfall]` or `[verification]` prefixes.
        Don't save task-specific steps or info already covered by App Skills. Keep to 1-2 generalized sentences.
        """.trimIndent()

    override val parameterSchema: JSONObject =
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("agent_thought", JSONObject().apply {
                    put("type", "string")
                    put("description", "Brief reason for saving this experience")
                })
                put("scope", JSONObject().apply {
                    put("type", "string")
                    put("enum", SCOPE_VALUES)
                    put("description", "Memory scope: user, device, or app")
                })
                put("section", JSONObject().apply {
                    put("type", "string")
                    put("enum", SECTION_VALUES)
                    put("description", "Target section within that scope")
                })
                put("content", JSONObject().apply {
                    put("type", "string")
                    put("description", "The learning to save (1-2 generalized sentences)")
                })
                put("package_name", JSONObject().apply {
                    put("type", "string")
                    put("description", "Target app package name (required when scope = app)")
                })
            })
            put("required", JSONArray(listOf("scope", "section", "content")))
            put("additionalProperties", false)
        }

    override fun validate(params: JSONObject): ValidationResult {
        val scope = MemoryScope.fromWireValue(params.optString("scope"))
        if (scope == null) {
            return ValidationResult.Invalid("scope must be: user, device, or app")
        }
        val section = MemorySection.fromWireValue(params.optString("section"))
        if (section == null) {
            return ValidationResult.Invalid(
                "section must be one of: ${MemorySection.entries.joinToString { it.wireValue }}"
            )
        }
        if (!MemorySchema.isSectionAllowed(scope, section)) {
            val allowed = MemorySchema.sectionsFor(scope).joinToString { it.wireValue }
            return ValidationResult.Invalid("section must be one of: $allowed for scope=${scope.wireValue}")
        }
        val content = params.optString("content", "").trim()
        if (content.isEmpty()) return ValidationResult.Invalid("content must not be empty")
        if (content.length > store.maxContentLength) {
            return ValidationResult.Invalid("content too long (max ${store.maxContentLength} chars)")
        }
        val pkg = params.optString("package_name", "").trim()
        if (scope == MemoryScope.APP) {
            if (pkg.isEmpty()) return ValidationResult.Invalid("package_name required when scope = app")
            if (!pkg.matches(Regex("^[a-zA-Z0-9_.]+$"))) {
                return ValidationResult.Invalid("package_name contains invalid characters")
            }
        } else if (pkg.isNotEmpty()) {
            return ValidationResult.Invalid("package_name is only allowed when scope = app")
        }
        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val agentThought = params.optString("agent_thought", "").trim()
        val scope =
            requireNotNull(MemoryScope.fromWireValue(params.getString("scope"))) {
                "scope must be valid"
            }
        val section =
            requireNotNull(MemorySection.fromWireValue(params.getString("section"))) {
                "section must be valid"
            }
        val content = params.getString("content").trim()
        val packageName = params.optString("package_name", "").trim().ifEmpty { null }
        return RememberExperienceInvocation(
            store = store,
            appClassifier = appClassifier,
            params = params,
            scope = scope,
            section = section,
            content = content,
            packageName = packageName,
            description = appendReason("Save experience (${scope.wireValue}/${section.wireValue})", agentThought)
        )
    }
}

private class RememberExperienceInvocation(
    private val store: MemoryStore,
    private val appClassifier: AppClassifier,
    override val params: JSONObject,
    private val scope: MemoryScope,
    private val section: MemorySection,
    private val content: String,
    private val packageName: String?,
    private val description: String
) : ToolInvocation {
    override val toolName: String = "remember_experience"

    override fun getDescription(): String = description

    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (context.isCancelled()) return ToolExecutionResult.Cancelled()

        // Layer 4: Memory Gate — block writes when foreground app is BLOCKED
        val currentPkg = context.platform.getCurrentPackageName()
        if (appClassifier.classify(currentPkg) == AppTier.BLOCKED) {
            return ToolExecutionResult.Failure(
                "Cannot save memory: current app ($currentPkg) is blocked by security policy"
            )
        }

        // Layer 4b: Block app-scoped writes targeting a BLOCKED package
        if (scope == MemoryScope.APP && packageName != null &&
            appClassifier.classify(packageName) == AppTier.BLOCKED
        ) {
            return ToolExecutionResult.Failure(
                "Cannot save memory: target app is restricted by security policy"
            )
        }

        return try {
            val saved =
                store.append(
                    scope = scope,
                    section = section,
                    content = content,
                    packageName = packageName
                )
            if (!saved) {
                ToolExecutionResult.Failure("Failed to save memory.")
            } else {
                textToolSuccess(
                    output = "Saved to long-term memory (${scope.wireValue}/${section.wireValue})."
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult.Failure("Failed to save memory: ${e.message}", e)
        }
    }
}
