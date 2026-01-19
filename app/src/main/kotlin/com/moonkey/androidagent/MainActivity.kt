package com.moonkey.androidagent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.moonkey.androidagent.ui.screen.AgentScreen
import com.moonkey.androidagent.ui.screen.AgentUiState
import com.moonkey.androidagent.ui.theme.AgentTheme
import com.moonkey.androidagent.util.StatusUtils
import java.io.File

/**
 * MainActivity - Compose-based UI for the Android Agent.
 * 
 * Features:
 * - Modern Material 3 design with elegant light aesthetic
 * - Edge-to-edge display
 * - Reactive UI state management
 */
class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_GOAL = "goal"
        const val EXTRA_AUTO_START = "auto_start"
    }
    
    // UI State
    private var apiKey by mutableStateOf("")
    private var goal by mutableStateOf("")
    private var statusLines by mutableStateOf(listOf<String>())
    private var isServiceEnabled by mutableStateOf(false)
    private var isRunning by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        
        // Set up status callback - also detect completion
        AgentService.statusCallback = { status ->
            runOnUiThread {
                statusLines = statusLines + status
                
                // Detect completion states to reset isRunning using shared utility
                if (StatusUtils.isTerminalStatus(status)) {
                    isRunning = false
                }
            }
        }
        
        // Load initial data
        loadApiKeyFromFile()
        handleIntent(intent)
        
        setContent {
            AgentTheme {
                AgentScreen(
                    state = AgentUiState(
                        apiKey = apiKey,
                        goal = goal,
                        statusLines = statusLines,
                        isServiceEnabled = isServiceEnabled,
                        isRunning = isRunning
                    ),
                    onApiKeyChange = { apiKey = it },
                    onGoalChange = { goal = it },
                    onStartClick = { startAgent() },
                    onAccessibilityClick = { openAccessibilitySettings() }
                )
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent called")
        setIntent(intent)
        handleIntent(intent)
    }
    
    override fun onResume() {
        super.onResume()
        // Update service status
        val serviceAvailable = AgentService.instance != null
        isServiceEnabled = serviceAvailable
        
        if (serviceAvailable && statusLines.isEmpty()) {
            statusLines = listOf("✓ Accessibility Service enabled. Ready to run.")
        } else if (!serviceAvailable && statusLines.isEmpty()) {
            statusLines = listOf("Enable Accessibility Service to get started.")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        AgentService.statusCallback = null
    }
    
    private fun handleIntent(intent: Intent) {
        intent.getStringExtra(EXTRA_API_KEY)?.let { key ->
            if (key.isNotBlank()) {
                apiKey = key
                Log.d(TAG, "API key set from intent")
            }
        }
        
        intent.getStringExtra(EXTRA_GOAL)?.let { goalText ->
            if (goalText.isNotBlank()) {
                goal = goalText
                Log.d(TAG, "Goal set from intent: $goalText")
            }
        }
        
        // Auto-start if requested
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            Log.d(TAG, "Auto-start requested")
            // Delay to allow UI to initialize
            window.decorView.postDelayed({ startAgent() }, 500)
        }
    }
    
    private fun loadApiKeyFromFile() {
        try {
            val file = File(Environment.getExternalStorageDirectory(), "api_key.txt")
            if (file.exists()) {
                val key = file.readText().trim()
                if (key.isNotBlank() && key.startsWith("sk-")) {
                    apiKey = key
                    Log.d(TAG, "API key loaded from file")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load API key from file: ${e.message}")
        }
    }
    
    private fun startAgent() {
        if (apiKey.isBlank()) {
            statusLines = statusLines + "Please enter your OpenAI API key"
            return
        }
        
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant Overlay permission", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }
        
        if (goal.isBlank()) {
            statusLines = statusLines + "Please enter a goal"
            return
        }
        
        val service = AgentService.instance
        if (service == null) {
            statusLines = statusLines + "Accessibility Service not enabled. Please enable it in Settings."
            return
        }
        
        // Clear previous status and start
        statusLines = emptyList()  // Let AgentService emit the first status
        isRunning = true
        service.runAgent(goal, apiKey)
    }
    
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }
}
