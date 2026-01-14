package com.moonkey.androidagent.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.moonkey.androidagent.domain.agents.Executor
import com.moonkey.androidagent.domain.agents.Manager
import com.moonkey.androidagent.domain.agents.Reflector
import com.moonkey.androidagent.domain.models.AgentAction
import com.moonkey.androidagent.domain.models.ScreenSnapshot
import com.moonkey.androidagent.domain.state.InfoPool
import com.moonkey.androidagent.platform.AccessibilityPlatform
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.ScrollDirection
import com.moonkey.androidagent.platform.SystemButtonType
import com.moonkey.androidagent.platform.UIAction
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * AgentOrchestrator - Manages the main agent execution loop.
 * 
 * **Phase 3**: Now uses AndroidPlatform interface for screen capture and action execution.
 * This allows the orchestration logic to be tested without an actual Android device.
 */
class AgentOrchestrator(
    private val service: AccessibilityService,
    private val scope: CoroutineScope,
    private val statusListener: (String) -> Unit,
    // Phase 3: Platform abstraction - defaults to real implementation
    private val platform: AndroidPlatform = AccessibilityPlatform(service)
) {
    private val TAG = "AgentOrchestrator"

    private val manager = Manager()
    private val executor = Executor()
    private val reflector = Reflector()

    private var job: Job? = null
    private var isPaused = AtomicBoolean(false)

    fun start(goal: String) {
        stop()
        job =
                scope.launch(Dispatchers.Default) {
                    try {
                        runLoop(goal)
                    } catch (e: Exception) {
                        Log.e(TAG, "Agent Loop Crashed", e)
                    }
                }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun pause() {
        isPaused.set(true)
        statusListener("⏸️ Paused")
    }

    fun resume() {
        isPaused.set(false)
        statusListener("▶️ Resuming...")
    }

    private suspend fun runLoop(goal: String) {
        val infoPool = InfoPool(instruction = goal)
        var previousSnapshot: ScreenSnapshot? = null
        var lastAction: AgentAction? = null

        Log.i(TAG, "Starting Agent Loop for goal: $goal")
        statusListener("Starting Agent for: $goal")

        while (scope.isActive) {
            if (isPaused.get()) {
                delay(500)
                continue
            }

            // 1. Perception - Now uses platform abstraction
            statusListener("👀 Scanning Screen...")
            val currentSnapshot = platform.captureScreen()

            // 2. Reflection
            if (previousSnapshot != null &&
                            lastAction != null &&
                            lastAction !is AgentAction.FinishAction
            ) {
                statusListener("🤔 Verifying last action...")
                val outcome = reflector.validate(previousSnapshot, currentSnapshot, lastAction)
                infoPool.recordOutcome(outcome)
                Log.d(TAG, "Reflection Outcome: $outcome")
            }

            // 3. Planning
            val shouldPlan =
                    infoPool.plan.isEmpty() ||
                            infoPool.errorFlagPlan ||
                            (lastAction is AgentAction.FinishAction)

            if ("Finished" in infoPool.plan && lastAction is AgentAction.FinishAction) {
                Log.i(TAG, "Task Goal Reached!")
                break
            }

            if (shouldPlan) {
                Log.d(TAG, "Planning...")
                statusListener("🧠 Planning...")
                val result = manager.think(infoPool, currentSnapshot)
                infoPool.plan = result.plan
                infoPool.currentSubgoal = result.completedSubgoal

                Log.d(TAG, "New Plan: ${infoPool.plan}")

                if (result.plan == "Finished") {
                    Log.i(TAG, "Manager decided task is finished.")
                    break
                }
            }

            // 4. Execution - Now uses platform abstraction
            Log.d(TAG, "Executing...")
            statusListener("💡 Executing...")
            val action = executor.think(infoPool, currentSnapshot)
            Log.d(TAG, "Action Decided: $action")

            if (action is AgentAction.FinishAction) {
                // Executor believes subgoal is done or task is done.
            } else if (action is AgentAction.InvalidAction) {
                infoPool.errorDescriptions.add(action.reason ?: "Invalid Action")
            } else {
                // Execute via platform abstraction
                val uiAction = convertToUIAction(action)
                if (uiAction != null) {
                    val result = platform.performAction(uiAction, currentSnapshot)
                    Log.d(TAG, "Action result: $result")
                }
            }

            // 5. Update State
            lastAction = action
            previousSnapshot = currentSnapshot
            infoPool.actionHistory.add(action)

            delay(2000) // Wait for UI to settle
        }
    }
    
    /**
     * Convert legacy AgentAction to platform UIAction.
     */
    private fun convertToUIAction(action: AgentAction): UIAction? {
        if (action !is AgentAction.AtomicAction) return null
        
        return when (action.type) {
            "click" -> {
                action.elementId?.let { UIAction.Click(it) }
            }
            "type" -> {
                val elementId = action.elementId ?: return null
                val text = action.text ?: return null
                UIAction.Type(elementId, text)
            }
            "scroll" -> {
                val direction = when (action.direction?.lowercase()) {
                    "up" -> ScrollDirection.UP
                    "down" -> ScrollDirection.DOWN
                    "left" -> ScrollDirection.LEFT
                    "right" -> ScrollDirection.RIGHT
                    else -> ScrollDirection.DOWN
                }
                UIAction.Scroll(direction)
            }
            "system" -> {
                val button = when (action.button?.lowercase()) {
                    "back" -> SystemButtonType.BACK
                    "home" -> SystemButtonType.HOME
                    "recents" -> SystemButtonType.RECENTS
                    else -> return null
                }
                UIAction.SystemButton(button)
            }
            "wait" -> UIAction.Wait(1000)
            else -> null
        }
    }
}
