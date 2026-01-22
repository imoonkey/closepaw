package com.moonkey.androidagent.util

import android.util.Log
import org.json.JSONObject

/**
 * PerfLogger - Structured performance logging for profiling.
 * 
 * Emits logs in a parseable format for post-hoc analysis:
 * PERF|<session_id>|<turn_number>|<span_name>|<duration_ms>|<metadata_json>
 * 
 * Usage:
 *   PerfLogger.setSession("abc123")
 *   PerfLogger.setTurn(1)
 *   PerfLogger.log("capture", 85, mapOf("elements" to 42))
 * 
 * Output:
 *   PERF|abc123|1|capture|85|{"elements":42}
 * 
 * Nested Spans:
 *   Some spans contain other spans, creating a hierarchy for analysis:
 *   - `llm_total` contains `history_build`, `llm_network`, and `response_parse`
 *   - `turn` contains `capture`, `json`, `llm_total`, `tool_*`, and `settle_inter_turn`
 *   When analyzing, be aware that parent span durations include their children.
 */
object PerfLogger {
    private const val TAG = "PERF"
    
    @Volatile
    private var sessionId: String = "unknown"
    
    @Volatile
    private var turnNumber: Int = 0
    
    /**
     * Set the current session ID for all subsequent logs.
     */
    fun setSession(id: String) {
        sessionId = id
    }
    
    /**
     * Set the current turn number for all subsequent logs.
     */
    fun setTurn(turn: Int) {
        turnNumber = turn
    }
    
    /**
     * Log a performance measurement.
     * 
     * @param span Name of the measured operation (e.g., "capture", "llm_network")
     * @param durationMs Duration in milliseconds
     * @param metadata Additional context as key-value pairs
     */
    fun log(span: String, durationMs: Long, metadata: Map<String, Any> = emptyMap()) {
        val metaJson = if (metadata.isEmpty()) {
            "{}"
        } else {
            try {
                JSONObject(metadata).toString()
            } catch (e: Exception) {
                "{}"
            }
        }
        Log.i(TAG, "PERF|$sessionId|$turnNumber|$span|$durationMs|$metaJson")
    }
    
    /**
     * Utility for timing a block of code.
     * 
     * Usage:
     *   val result = PerfLogger.timed("capture") {
     *       platform.captureScreen()
     *   }
     */
    inline fun <T> timed(span: String, metadata: Map<String, Any> = emptyMap(), block: () -> T): T {
        val start = System.currentTimeMillis()
        val result = block()
        val duration = System.currentTimeMillis() - start
        log(span, duration, metadata)
        return result
    }
    
    /**
     * Utility for timing a suspending block of code.
     * 
     * Usage:
     *   val result = PerfLogger.timedSuspend("capture") {
     *       platform.captureScreen()
     *   }
     */
    suspend inline fun <T> timedSuspend(span: String, metadata: Map<String, Any> = emptyMap(), block: () -> T): T {
        val start = System.currentTimeMillis()
        val result = block()
        val duration = System.currentTimeMillis() - start
        log(span, duration, metadata)
        return result
    }
    
    /**
     * Create a timer that can be stopped later.
     * Useful when you can't use inline functions or need to measure across boundaries.
     * 
     * Usage:
     *   val timer = PerfLogger.startTimer("llm_network")
     *   // ... do work ...
     *   timer.stop(mapOf("model" to "gpt-4o"))
     */
    fun startTimer(span: String): Timer {
        return Timer(span, System.currentTimeMillis())
    }
    
    /**
     * A started timer that can be stopped to emit a perf log.
     */
    class Timer(private val span: String, private val startTime: Long) {
        /**
         * Stop the timer and emit a perf log.
         * 
         * @param metadata Additional context to include in the log
         * @return Duration in milliseconds
         */
        fun stop(metadata: Map<String, Any> = emptyMap()): Long {
            val duration = System.currentTimeMillis() - startTime
            log(span, duration, metadata)
            return duration
        }
    }
}
