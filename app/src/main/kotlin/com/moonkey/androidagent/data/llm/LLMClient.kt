package com.moonkey.androidagent.data.llm

import android.util.Log
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletionCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** LLMClient - Generic Wrapper for OpenAI API. */
object LLMClient {

    private const val TAG = "LLMClient"
    private var client: OpenAIClient? = null
    private var isInitialized = false

    fun initialize(apiKey: String) {
        Log.d(TAG, "Initializing LLMClient with key: ${apiKey.take(10)}...")
        client = OpenAIOkHttpClient.builder()
            .apiKey(apiKey)
            .build()
        isInitialized = true
        Log.i(TAG, "LLMClient initialized successfully")
    }

    suspend fun chat(messages: List<ChatMessage>): String {
        return withContext(Dispatchers.IO) {
            try {
                if (!isInitialized || client == null) {
                    throw IllegalStateException("LLMClient not initialized. Call initialize() first.")
                }

                Log.d(TAG, "Sending ${messages.size} messages to OpenAI...")
                messages.forEachIndexed { index, msg ->
                    Log.d(TAG, "  [$index] ${msg.role}: ${msg.content.take(100)}...")
                }

                val builder = ChatCompletionCreateParams.builder().model(ChatModel.GPT_4O)

                messages.forEach { msg ->
                    when (msg.role) {
                        Role.SYSTEM -> builder.addSystemMessage(msg.content)
                        Role.USER -> builder.addUserMessage(msg.content)
                        Role.ASSISTANT -> builder.addAssistantMessage(msg.content)
                    }
                }

                Log.d(TAG, "Making API call to OpenAI...")
                val response = client!!.chat().completions().create(builder.build())
                
                val choice = response.choices().firstOrNull()
                if (choice == null) {
                    Log.e(TAG, "No choices in response")
                    throw RuntimeException("No choices in LLM response")
                }
                
                val content = choice.message().content().orElse("")
                Log.d(TAG, "LLM Response (${content.length} chars): ${content.take(200)}...")
                content.cleanJson()
                
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "Request timeout", e)
                throw RuntimeException("Request timeout - try again", e)
            } catch (e: java.net.UnknownHostException) {
                Log.e(TAG, "Network error - no internet: ${e.message}", e)
                throw RuntimeException("No internet connection", e)
            } catch (e: java.io.IOException) {
                Log.e(TAG, "Network/IO error: ${e.message}", e)
                throw RuntimeException("Network error: ${e.message}", e)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "State error: ${e.message}", e)
                throw e
            } catch (e: Exception) {
                // Log the full exception details
                Log.e(TAG, "LLM call failed: ${e.javaClass.name}: ${e.message}")
                Log.e(TAG, "Exception cause: ${e.cause?.message}")
                e.printStackTrace()
                throw RuntimeException("LLM error: ${e.javaClass.simpleName} - ${e.message}", e)
            }
        }
    }

    private fun String.cleanJson(): String {
        return this.trim().replace("```json", "").replace("```", "").trim()
    }
}
