package ai.closepaw.platform

/**
 * ActionResult — result of executing an atomic UIAction.
 *
 * Simplified: no ElementNotFound (platform doesn't know about elements,
 * returns Failure with descriptive reason). No exception field (log at source,
 * don't carry through layers).
 */
sealed interface ActionResult {

    /** Action executed successfully. */
    data class Success(val message: String = "Action completed") : ActionResult

    /** Action failed to execute. */
    data class Failure(val reason: String) : ActionResult

    /** Action was cancelled before completion. */
    data class Cancelled(val reason: String = "Action cancelled") : ActionResult
}
