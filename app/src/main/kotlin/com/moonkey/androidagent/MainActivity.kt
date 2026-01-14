package com.moonkey.androidagent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import java.io.File

/** MainActivity - Simple UI for entering goal and starting agent. */
class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_GOAL = "goal"
        const val EXTRA_AUTO_START = "auto_start"
    }

    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var goalInput: TextInputEditText
    private lateinit var startButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        apiKeyInput = findViewById(R.id.apiKeyInput)
        goalInput = findViewById(R.id.goalInput)
        startButton = findViewById(R.id.startButton)
        accessibilityButton = findViewById(R.id.accessibilityButton)
        statusText = findViewById(R.id.statusText)

        // Set up status callback
        AgentService.statusCallback = { status -> runOnUiThread { statusText.append("\n$status") } }
        
        // Try to load API key from file or intent
        loadApiKeyFromFile()
        handleIntent(intent)

        startButton.setOnClickListener {
            startAgent()
        }

        accessibilityButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent called")
        intent?.let { 
            setIntent(it)  // Update the activity's intent
            handleIntent(it) 
        }
    }
    
    private fun handleIntent(intent: Intent) {
        // Support launching with API key and goal via intent
        intent.getStringExtra(EXTRA_API_KEY)?.let { key ->
            if (key.isNotBlank()) {
                apiKeyInput.setText(key)
                Log.d(TAG, "API key set from intent")
            }
        }
        
        intent.getStringExtra(EXTRA_GOAL)?.let { goal ->
            if (goal.isNotBlank()) {
                goalInput.setText(goal)
                Log.d(TAG, "Goal set from intent: $goal")
            }
        }
        
        // Auto-start if requested
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            Log.d(TAG, "Auto-start requested")
            // Delay to allow UI to initialize
            startButton.postDelayed({ startAgent() }, 500)
        }
    }
    
    private fun loadApiKeyFromFile() {
        try {
            // Try to load from /sdcard/api_key.txt
            val file = File(Environment.getExternalStorageDirectory(), "api_key.txt")
            if (file.exists()) {
                val key = file.readText().trim()
                if (key.isNotBlank() && key.startsWith("sk-")) {
                    apiKeyInput.setText(key)
                    Log.d(TAG, "API key loaded from file")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load API key from file: ${e.message}")
        }
    }
    
    private fun startAgent() {
        val apiKey = apiKeyInput.text?.toString() ?: ""
        val goal = goalInput.text?.toString() ?: ""

        if (apiKey.isBlank()) {
            statusText.text = "❌ Please enter your OpenAI API key"
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant Overlay permission", Toast.LENGTH_LONG).show()
            val intent =
                    Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                    )
            startActivity(intent)
            return
        }

        if (goal.isBlank()) {
            statusText.text = "❌ Please enter a goal"
            return
        }

        val service = AgentService.instance
        if (service == null) {
            statusText.text =
                    "❌ Accessibility Service not enabled!\nPlease enable it in Settings."
            return
        }

        statusText.text = ""
        service.runAgent(goal, apiKey)
    }

    override fun onResume() {
        super.onResume()
        // Update status based on service state
        if (AgentService.instance != null) {
            statusText.text = "✅ Accessibility Service is enabled. Ready to run."
        } else {
            statusText.text = "⚠️ Please enable Accessibility Service first."
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AgentService.statusCallback = null
    }
}
