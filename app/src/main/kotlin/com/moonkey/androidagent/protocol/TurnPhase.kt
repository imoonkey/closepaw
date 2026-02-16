package com.moonkey.androidagent.protocol

/** Turn phases within a single agent step. */
enum class TurnPhase {
    /** Capturing and analyzing the screen. */
    PERCEPTION,

    /** Deciding what to do (LLM reasoning). */
    PLANNING,

    /** Executing an action (tool call). */
    EXECUTION
}
