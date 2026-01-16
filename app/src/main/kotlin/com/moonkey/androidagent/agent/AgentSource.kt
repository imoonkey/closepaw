package com.moonkey.androidagent.agent

/**
 * AgentSource - Identifies whether an agent is primary or delegated.
 * 
 * Reference: Codex's SessionSource::SubAgent pattern
 * 
 * This is a placeholder for future multi-agent support where:
 * - Primary agents are started by user input
 * - SubAgents are spawned by other agents for delegation
 */
enum class AgentSource {
    /** Agent started directly by user */
    Primary,
    
    /** Agent spawned by another agent for delegation */
    SubAgent
}

