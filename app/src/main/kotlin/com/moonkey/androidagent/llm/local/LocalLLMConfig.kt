package com.moonkey.androidagent.llm.local

/**
 * Configuration for local LLM inference.
 * 
 * Controls model loading, inference parameters, and resource management.
 * 
 * ## Model Selection
 * 
 * The recommended model is LFM2.5-1.2B-Thinking in GGUF format:
 * - Q4_K_M quantization (~731MB) offers the best balance
 * - Q4_0 (~696MB) for devices with limited storage
 * - Q8_0 (~1.25GB) for higher quality when memory allows
 * 
 * ## Memory Considerations
 * 
 * The model requires approximately:
 * - Q4_K_M: ~1GB RAM for inference
 * - Q4_0: ~0.9GB RAM
 * - Q8_0: ~1.8GB RAM
 * 
 * Ensure adequate free memory before loading. Use `release()` when
 * the app is backgrounded to free memory.
 * 
 * ## Performance Tuning
 * 
 * - `threads`: Set to number of performance cores (usually 4)
 * - `contextSize`: Larger context uses more memory but handles longer conversations
 * - `temperature`: Lower values (0.3) for tool calling, higher (0.7) for creativity
 */
data class LocalLLMConfig(
    /**
     * GGUF model filename (relative to app's files/models directory).
     * 
     * Default: LFM2.5-1.2B-Thinking-Q4_K_M.gguf
     * 
     * Available quantizations:
     * - LFM2.5-1.2B-Thinking-Q4_0.gguf (696MB)
     * - LFM2.5-1.2B-Thinking-Q4_K_M.gguf (731MB) ← Recommended
     * - LFM2.5-1.2B-Thinking-Q5_K_M.gguf (843MB)
     * - LFM2.5-1.2B-Thinking-Q6_K.gguf (963MB)
     * - LFM2.5-1.2B-Thinking-Q8_0.gguf (1.25GB)
     */
    val modelFileName: String = "LFM2.5-1.2B-Thinking-Q4_K_M.gguf",
    
    /**
     * Context window size in tokens.
     * 
     * Larger context allows longer conversations but uses more memory.
     * The model supports up to 4096 tokens; default is 2048 for mobile.
     */
    val contextSize: Int = 2048,
    
    /**
     * Number of CPU threads for inference.
     * 
     * Recommend setting to the number of performance cores.
     * On most mobile devices, 4 is optimal.
     */
    val threads: Int = 4,
    
    /**
     * Maximum tokens to generate per response.
     * 
     * Tool calls typically need 100-200 tokens.
     * Text responses may need more.
     */
    val maxTokens: Int = 512,
    
    /**
     * Sampling temperature (0.0 to 2.0).
     * 
     * Lower values make output more deterministic (good for tool calls).
     * Higher values increase creativity (good for text generation).
     * 
     * Recommended: 0.3 for agent tasks, 0.7 for conversational.
     */
    val temperature: Float = 0.3f,
    
    /**
     * Nucleus sampling threshold (0.0 to 1.0).
     * 
     * Limits sampling to tokens within this cumulative probability.
     * Lower values make output more focused.
     */
    val topP: Float = 0.9f,
    
    /**
     * Top-K sampling (0 = disabled).
     * 
     * Limits sampling to the K most likely tokens.
     * 0 disables top-K sampling, using only top-P.
     */
    val topK: Int = 40,
    
    /**
     * Repetition penalty (1.0 = no penalty).
     * 
     * Penalizes repeated tokens to reduce repetitive output.
     * Values > 1.0 discourage repetition.
     */
    val repeatPenalty: Float = 1.1f,
    
    /**
     * Whether this is a thinking model that produces reasoning content.
     * 
     * When true, the parser will extract thinking content separately
     * from the final JSON output.
     */
    val isThinkingModel: Boolean = true,
    
    /**
     * Whether to use mmap for model loading.
     * 
     * mmap can speed up loading on devices with fast storage
     * but may cause issues on some devices.
     */
    val useMmap: Boolean = true,
    
    /**
     * Batch size for prompt processing.
     * 
     * Larger batches are faster but use more memory.
     */
    val batchSize: Int = 512
) {
    
    /**
     * Estimate memory usage for this configuration.
     * 
     * @return Estimated memory in bytes
     */
    fun estimateMemoryUsage(): Long {
        // Base model size depends on quantization
        val baseSize = when {
            modelFileName.contains("Q4_0") -> 696L * 1024 * 1024
            modelFileName.contains("Q4_K") -> 731L * 1024 * 1024
            modelFileName.contains("Q5_K") -> 843L * 1024 * 1024
            modelFileName.contains("Q6_K") -> 963L * 1024 * 1024
            modelFileName.contains("Q8_0") -> 1280L * 1024 * 1024
            else -> 800L * 1024 * 1024 // Estimate
        }
        
        // Context buffer (rough estimate: 4 bytes per token per layer)
        // LFM2.5-1.2B has ~24 layers
        val contextBuffer = contextSize.toLong() * 24 * 4
        
        return baseSize + contextBuffer
    }
    
    companion object {
        /** Configuration optimized for tool calling accuracy */
        val TOOL_CALLING = LocalLLMConfig(
            temperature = 0.2f,
            topP = 0.85f,
            maxTokens = 256,
            repeatPenalty = 1.0f
        )
        
        /** Configuration optimized for conversational responses */
        val CONVERSATIONAL = LocalLLMConfig(
            temperature = 0.7f,
            topP = 0.95f,
            maxTokens = 512,
            repeatPenalty = 1.1f
        )
        
        /** Configuration for resource-constrained devices */
        val LOW_MEMORY = LocalLLMConfig(
            modelFileName = "LFM2.5-1.2B-Thinking-Q4_0.gguf",
            contextSize = 1024,
            threads = 2,
            maxTokens = 256,
            batchSize = 256
        )
    }
}

/**
 * Model quantization options.
 */
enum class ModelQuantization(
    val suffix: String,
    val sizeBytes: Long,
    val description: String
) {
    Q4_0("Q4_0", 696L * 1024 * 1024, "Fast, smallest size"),
    Q4_K_M("Q4_K_M", 731L * 1024 * 1024, "Recommended balance"),
    Q5_K_M("Q5_K_M", 843L * 1024 * 1024, "Higher quality"),
    Q6_K("Q6_K", 963L * 1024 * 1024, "Near-original quality"),
    Q8_0("Q8_0", 1280L * 1024 * 1024, "Best quality, largest");
    
    fun toFileName(): String = "LFM2.5-1.2B-Thinking-$suffix.gguf"
    
    fun downloadUrl(): String = 
        "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Thinking-GGUF/resolve/main/${toFileName()}"
}
