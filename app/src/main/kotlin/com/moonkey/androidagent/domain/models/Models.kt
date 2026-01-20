package com.moonkey.androidagent.domain.models

// --- Action Models ---

sealed class AgentAction {
    abstract val reason: String?

    data class AtomicAction(
            val type: String, // "click", "type", "scroll", "system", "answer", "wait"
            val elementId: Int? = null, // Index in the list
            val text: String? = null, // For type/answer
            val direction: String? = null, // For scroll
            val button: String? = null, // For system (Back/Home)
            override val reason: String? = null
    ) : AgentAction()

    data class InvalidAction(override val reason: String? = "Invalid format") : AgentAction()

    data class FinishAction(override val reason: String? = "Task Finished") : AgentAction()
}

// --- Validation Models ---

sealed class ValidationOutcome {
    abstract val description: String

    data class Success(override val description: String = "Effective change detected") :
            ValidationOutcome() // Type A
    data class FailedBacktrack(override val description: String) :
            ValidationOutcome() // Type B: Wrong page
    data class FailedNoChange(override val description: String) :
            ValidationOutcome() // Type C: No change
}

// --- Perception Models ---

/**
 * ScreenSnapshot - Captured state of the screen.
 * 
 * No longer stores AccessibilityNodeInfo references to avoid memory leaks.
 * All necessary data for action execution is stored in PerceptionElement.
 * 
 * Note: rootOriginal and rawMap have been removed. Actions now use:
 * - Gesture-based clicks using stored bounds/center coordinates
 * - Re-querying accessibility tree for text input when needed
 */
data class ScreenSnapshot(
        val timestamp: Long,
        val elements: List<PerceptionElement> // For LLM and action execution
)

data class PerceptionElement(
        val index: Int,
        val text: String,
        val resourceId: String,
        val className: String,
        val description: String,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isScrollable: Boolean,
        val bounds: IntArray,
        val center: IntArray
)

data class ManagerResult(val thought: String, val plan: String, val completedSubgoal: String)
