package com.moonkey.androidagent.ui.overlay.model

/**
 * GlowState - Visual states for the edge glow effect.
 * 
 * Each state corresponds to a different agent execution phase,
 * providing visual feedback about what the agent is doing.
 */
enum class GlowState(val colorHex: Int) {
    /**
     * Agent is actively thinking/processing.
     * Color: Primary Blue (#2563EB)
     */
    Active(0xFF2563EB.toInt()),
    
    /**
     * Agent is currently executing an action (click, swipe, etc.).
     * Color: Lighter Blue (#3B82F6)
     */
    Executing(0xFF3B82F6.toInt()),
    
    /**
     * Task completed successfully.
     * Color: Teal (#0D9488)
     */
    Success(0xFF0D9488.toInt()),
    
    /**
     * Something went wrong.
     * Color: Red (#DC2626)
     */
    Error(0xFFDC2626.toInt()),
    
    /**
     * Agent paused by user.
     * Color: Amber (#F59E0B)
     */
    Paused(0xFFF59E0B.toInt())
}
