package com.moonkey.androidagent.platform

/**
 * ActionResult - Result of executing a UIAction.
 */
sealed interface ActionResult {
    
    /**
     * Action executed successfully.
     */
    data class Success(
        val message: String = "Action completed"
    ) : ActionResult
    
    /**
     * Action failed to execute.
     */
    data class Failure(
        val reason: String,
        val exception: Throwable? = null
    ) : ActionResult
    
    /**
     * Element not found (for actions targeting specific elements).
     */
    data class ElementNotFound(
        val elementIndex: Int
    ) : ActionResult
    
    /**
     * Action was cancelled before completion.
     */
    data class Cancelled(
        val reason: String = "Action cancelled"
    ) : ActionResult
    
    /**
     * Check if the result indicates success.
     */
    fun isSuccess(): Boolean = this is Success
}

