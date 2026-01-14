package com.moonkey.androidagent.platform.mock

import com.moonkey.androidagent.domain.models.PerceptionElement
import com.moonkey.androidagent.domain.models.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.DisplayInfo
import com.moonkey.androidagent.platform.UIAction

/**
 * MockPlatform - Test implementation of AndroidPlatform.
 * 
 * Allows unit testing of orchestration logic without an Android device.
 * Provides configurable screen sequences and action results.
 */
class MockPlatform(
    private val config: MockPlatformConfig = MockPlatformConfig()
) : AndroidPlatform {
    
    private var screenIndex = 0
    private val executedActions = mutableListOf<UIAction>()
    private val capturedScreenCount = mutableListOf<Long>()
    
    override suspend fun captureScreen(): ScreenSnapshot {
        capturedScreenCount.add(System.currentTimeMillis())
        
        val screens = config.screenSequence
        return if (screens.isEmpty()) {
            createEmptySnapshot()
        } else {
            screens.getOrElse(screenIndex++) { screens.last() }
        }
    }
    
    override suspend fun performAction(action: UIAction, snapshot: ScreenSnapshot?): ActionResult {
        executedActions.add(action)
        
        // Check for specific action results
        config.actionResults[action]?.let { return it }
        
        // Check for action type results
        val typeResult = when (action) {
            is UIAction.Click -> config.defaultClickResult
            is UIAction.ClickAt -> config.defaultClickResult
            is UIAction.Type -> config.defaultTypeResult
            is UIAction.Scroll -> config.defaultScrollResult
            is UIAction.Swipe -> config.defaultSwipeResult
            is UIAction.SystemButton -> config.defaultSystemButtonResult
            is UIAction.Wait -> ActionResult.Success("Waited")
        }
        
        return typeResult ?: config.defaultResult
    }
    
    override fun hasRequiredPermissions(): Boolean = config.hasPermissions
    
    override fun getCurrentPackageName(): String? = config.currentPackageName
    
    override fun getDisplayInfo(): DisplayInfo = config.displayInfo
    
    // ===== Test Helpers =====
    
    /**
     * Get all actions that were executed.
     */
    fun getExecutedActions(): List<UIAction> = executedActions.toList()
    
    /**
     * Get the number of times captureScreen was called.
     */
    fun getCaptureCount(): Int = capturedScreenCount.size
    
    /**
     * Reset the mock state for a new test.
     */
    fun reset() {
        screenIndex = 0
        executedActions.clear()
        capturedScreenCount.clear()
    }
    
    /**
     * Check if a specific action was executed.
     */
    fun wasActionExecuted(action: UIAction): Boolean = action in executedActions
    
    /**
     * Check if any action of a specific type was executed.
     */
    fun wasActionTypeExecuted(actionClass: Class<out UIAction>): Boolean = 
        executedActions.any { actionClass.isInstance(it) }
    
    private fun createEmptySnapshot(): ScreenSnapshot {
        return ScreenSnapshot(
            timestamp = System.currentTimeMillis(),
            rootOriginal = null,
            elements = emptyList(),
            rawMap = emptyMap()
        )
    }
}

/**
 * Configuration for MockPlatform.
 */
data class MockPlatformConfig(
    /** Sequence of screens to return from captureScreen */
    val screenSequence: List<ScreenSnapshot> = emptyList(),
    
    /** Specific results for specific actions */
    val actionResults: Map<UIAction, ActionResult> = emptyMap(),
    
    /** Default result for all actions */
    val defaultResult: ActionResult = ActionResult.Success(),
    
    /** Default result for click actions */
    val defaultClickResult: ActionResult? = null,
    
    /** Default result for type actions */
    val defaultTypeResult: ActionResult? = null,
    
    /** Default result for scroll actions */
    val defaultScrollResult: ActionResult? = null,
    
    /** Default result for swipe actions */
    val defaultSwipeResult: ActionResult? = null,
    
    /** Default result for system button actions */
    val defaultSystemButtonResult: ActionResult? = null,
    
    /** Whether permissions are available */
    val hasPermissions: Boolean = true,
    
    /** Current package name */
    val currentPackageName: String? = "com.example.test",
    
    /** Display information */
    val displayInfo: DisplayInfo = DisplayInfo(
        widthPixels = 1080,
        heightPixels = 2400,
        density = 2.75f
    )
)

/**
 * Builder for creating test ScreenSnapshots.
 */
object MockScreenBuilder {
    
    /**
     * Create a simple snapshot with a list of elements.
     */
    fun createSnapshot(
        elements: List<PerceptionElement> = emptyList(),
        timestamp: Long = System.currentTimeMillis()
    ): ScreenSnapshot {
        return ScreenSnapshot(
            timestamp = timestamp,
            rootOriginal = null,
            elements = elements,
            rawMap = emptyMap() // No raw nodes in mock
        )
    }
    
    /**
     * Create a snapshot with clickable buttons.
     */
    fun createWithButtons(vararg buttonTexts: String): ScreenSnapshot {
        val elements = buttonTexts.mapIndexed { index, text ->
            PerceptionElement(
                index = index,
                text = text,
                resourceId = "button_$index",
                className = "Button",
                description = "",
                isClickable = true,
                isEditable = false,
                isScrollable = false,
                bounds = intArrayOf(0, index * 100, 200, (index + 1) * 100),
                center = intArrayOf(100, index * 100 + 50)
            )
        }
        return createSnapshot(elements)
    }
    
    /**
     * Create a snapshot with text input fields.
     */
    fun createWithInputs(vararg hints: String): ScreenSnapshot {
        val elements = hints.mapIndexed { index, hint ->
            PerceptionElement(
                index = index,
                text = "",
                resourceId = "input_$index",
                className = "EditText",
                description = hint,
                isClickable = true,
                isEditable = true,
                isScrollable = false,
                bounds = intArrayOf(0, index * 100, 400, (index + 1) * 100),
                center = intArrayOf(200, index * 100 + 50)
            )
        }
        return createSnapshot(elements)
    }
}

