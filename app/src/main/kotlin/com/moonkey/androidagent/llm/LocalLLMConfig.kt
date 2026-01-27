package com.moonkey.androidagent.llm

import ai.liquid.leap.GenerationOptions

/**
 * Configuration for local LLM.
 *
 * Available models from Leap Model Library (leap.liquid.ai/models):
 * - "LFM2.5-1.2B-Instruct" / "Q4_K_M" (~731MB, recommended for tool-calling)
 * - "LFM2.5-1.2B-Instruct" / "Q5_K_M" (~843MB, higher quality)
 * - "lfm2-350m" / "lfm2-350m-20250710-8da4w" (~400MB, smallest, less capable)
 */
data class LocalLLMConfig(
    /** Model slug (e.g., "LFM2.5-1.2B-Instruct") */
    val modelSlug: String = "LFM2.5-1.2B-Instruct",
    /** Quantization slug (e.g., "Q4_K_M") - must match Leap Model Library */
    val quantizationSlug: String = "Q4_K_M",
    /**
     * Optional generation options (e.g., functionCallParser, temperature).
     * When null, the SDK defaults from the model manifest are used.
     */
    val generationOptions: GenerationOptions? = null
)
