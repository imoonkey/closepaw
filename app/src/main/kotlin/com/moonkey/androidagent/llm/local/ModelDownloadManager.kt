package com.moonkey.androidagent.llm.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * ModelDownloadManager - Handles downloading and managing local LLM model files.
 * 
 * Downloads GGUF model files from HuggingFace and manages storage.
 * 
 * ## Usage
 * 
 * ```kotlin
 * val manager = ModelDownloadManager(context)
 * 
 * // Check if model is downloaded
 * if (!manager.isModelDownloaded()) {
 *     // Download with progress callback
 *     manager.downloadModel(ModelQuantization.Q4_K_M) { progress ->
 *         updateProgressUI(progress)
 *     }
 * }
 * 
 * // Cancel download if needed
 * manager.cancelDownload()
 * 
 * // Get model path for inference
 * val modelPath = manager.getModelPath()
 * ```
 * 
 * ## Storage
 * 
 * Models are stored in the app's internal storage:
 * `/data/data/com.moonkey.androidagent/files/models/`
 * 
 * This location:
 * - Doesn't require storage permissions
 * - Is private to the app
 * - Is automatically cleaned up on app uninstall
 * 
 * ## Cancellation
 * 
 * Downloads can be cancelled via cancelDownload(). The download
 * coroutine checks for cancellation periodically and cleans up
 * partial downloads when cancelled.
 */
class ModelDownloadManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ModelDownloadManager"
        private const val MODELS_DIR = "models"
        private const val BUFFER_SIZE = 8192
        
        // Download URLs for LFM2.5-1.2B-Thinking-GGUF
        val MODEL_URLS = mapOf(
            ModelQuantization.Q4_0 to 
                "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Thinking-GGUF/resolve/main/LFM2.5-1.2B-Thinking-Q4_0.gguf",
            ModelQuantization.Q4_K_M to 
                "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Thinking-GGUF/resolve/main/LFM2.5-1.2B-Thinking-Q4_K_M.gguf",
            ModelQuantization.Q5_K_M to 
                "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Thinking-GGUF/resolve/main/LFM2.5-1.2B-Thinking-Q5_K_M.gguf",
            ModelQuantization.Q6_K to 
                "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Thinking-GGUF/resolve/main/LFM2.5-1.2B-Thinking-Q6_K.gguf",
            ModelQuantization.Q8_0 to 
                "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Thinking-GGUF/resolve/main/LFM2.5-1.2B-Thinking-Q8_0.gguf"
        )
    }
    
    private val modelsDir: File by lazy {
        File(context.filesDir, MODELS_DIR).also { it.mkdirs() }
    }
    
    // Track current download job for cancellation
    private val currentDownloadJob = AtomicReference<Job?>(null)
    
    // Track download state
    @Volatile
    private var downloadState: ModelDownloadState = ModelDownloadState.NotStarted
    
    /**
     * Get current download state.
     */
    fun getDownloadState(): ModelDownloadState = downloadState
    
    /**
     * Cancel any in-progress download.
     * 
     * The download coroutine will be cancelled and any partial
     * files will be cleaned up.
     */
    fun cancelDownload() {
        val job = currentDownloadJob.getAndSet(null)
        if (job != null && job.isActive) {
            Log.i(TAG, "Cancelling download...")
            job.cancel()
            downloadState = ModelDownloadState.Cancelled
        }
    }
    
    /**
     * Check if a download is in progress.
     */
    fun isDownloading(): Boolean = currentDownloadJob.get()?.isActive == true
    
    /**
     * Download a model with the specified quantization.
     * 
     * @param quantization Model quantization level
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return Result containing the downloaded file or error
     */
    suspend fun downloadModel(
        quantization: ModelQuantization = ModelQuantization.Q4_K_M,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        // Store current job for cancellation support
        currentDownloadJob.set(coroutineContext[Job])
        
        val url = MODEL_URLS[quantization]
            ?: return@withContext Result.failure(
                IllegalArgumentException("Unknown quantization: $quantization")
            )
        
        val modelName = quantization.toFileName()
        val expectedSize = quantization.sizeBytes
        val tempFile = File(modelsDir, "$modelName.tmp")
        
        try {
            Log.i(TAG, "Downloading model: $modelName")
            downloadState = ModelDownloadState.Downloading(0f)
            onProgress(0f)
            
            val targetFile = File(modelsDir, modelName)
            
            // Check if already downloaded
            if (targetFile.exists() && targetFile.length() >= expectedSize * 0.95) {
                Log.i(TAG, "Model already downloaded: $modelName")
                downloadState = ModelDownloadState.Completed(targetFile)
                onProgress(1f)
                return@withContext Result.success(targetFile)
            }
            
            // Clean up any partial download
            tempFile.delete()
            
            // Check for cancellation before network call
            coroutineContext.ensureActive()
            
            // Download
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.connect()
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                downloadState = ModelDownloadState.Failed("HTTP $responseCode")
                return@withContext Result.failure(
                    RuntimeException("Download failed: HTTP $responseCode")
                )
            }
            
            val contentLength = connection.contentLengthLong.takeIf { it > 0 } 
                ?: expectedSize
            
            Log.d(TAG, "Content length: ${contentLength / 1024 / 1024}MB")
            
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var totalBytes = 0L
                    var lastProgress = 0f
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        // Check for cancellation periodically
                        coroutineContext.ensureActive()
                        
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                        
                        val progress = (totalBytes.toFloat() / contentLength).coerceIn(0f, 1f)
                        
                        // Report progress every 1%
                        if (progress - lastProgress >= 0.01f) {
                            downloadState = ModelDownloadState.Downloading(progress)
                            onProgress(progress)
                            lastProgress = progress
                            Log.v(TAG, "Download progress: ${(progress * 100).toInt()}%")
                        }
                    }
                }
            }
            
            // Verify download size
            if (tempFile.length() < expectedSize * 0.95) {
                tempFile.delete()
                downloadState = ModelDownloadState.Failed("Download incomplete")
                return@withContext Result.failure(
                    RuntimeException("Downloaded file is too small: ${tempFile.length()} bytes")
                )
            }
            
            // Rename temp to final
            if (!tempFile.renameTo(targetFile)) {
                // Try copy if rename fails (cross-filesystem)
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            
            downloadState = ModelDownloadState.Completed(targetFile)
            onProgress(1f)
            Log.i(TAG, "Download complete: $modelName (${targetFile.length() / 1024 / 1024}MB)")
            Result.success(targetFile)
            
        } catch (e: CancellationException) {
            Log.i(TAG, "Download cancelled")
            // Clean up partial download
            tempFile.delete()
            downloadState = ModelDownloadState.Cancelled
            throw e // Re-throw to propagate cancellation
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            // Clean up partial download on error
            tempFile.delete()
            downloadState = ModelDownloadState.Failed(e.message ?: "Unknown error")
            Result.failure(e)
        } finally {
            currentDownloadJob.set(null)
        }
    }
    
    /**
     * Check if a model is downloaded.
     */
    fun isModelDownloaded(quantization: ModelQuantization = ModelQuantization.Q4_K_M): Boolean {
        val modelFile = File(modelsDir, quantization.toFileName())
        val expectedSize = quantization.sizeBytes
        return modelFile.exists() && modelFile.length() >= expectedSize * 0.95
    }
    
    /**
     * Get the path to a downloaded model.
     */
    fun getModelPath(quantization: ModelQuantization = ModelQuantization.Q4_K_M): File {
        return File(modelsDir, quantization.toFileName())
    }
    
    /**
     * Delete a downloaded model.
     */
    fun deleteModel(quantization: ModelQuantization = ModelQuantization.Q4_K_M): Boolean {
        val modelFile = File(modelsDir, quantization.toFileName())
        val deleted = modelFile.delete()
        if (deleted) {
            Log.i(TAG, "Deleted model: ${quantization.toFileName()}")
        }
        return deleted
    }
    
    /**
     * Get available storage space in bytes.
     */
    fun getAvailableSpace(): Long {
        return modelsDir.freeSpace
    }
    
    /**
     * Get total size of downloaded models.
     */
    fun getTotalDownloadedSize(): Long {
        return modelsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
    
    /**
     * List all downloaded models.
     */
    fun listDownloadedModels(): List<ModelQuantization> {
        return ModelQuantization.values().filter { isModelDownloaded(it) }
    }
    
    /**
     * Clean up temporary files.
     */
    fun cleanupTempFiles() {
        modelsDir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { 
            it.delete()
            Log.d(TAG, "Cleaned up temp file: ${it.name}")
        }
    }
    
    /**
     * Get download progress if a download is in progress.
     * 
     * Returns null if no download is in progress.
     */
    fun getDownloadProgress(quantization: ModelQuantization = ModelQuantization.Q4_K_M): Float? {
        val tempFile = File(modelsDir, "${quantization.toFileName()}.tmp")
        if (!tempFile.exists()) return null
        
        val expectedSize = quantization.sizeBytes
        return (tempFile.length().toFloat() / expectedSize).coerceIn(0f, 1f)
    }
}

/**
 * State for model download operations.
 */
sealed interface ModelDownloadState {
    data object NotStarted : ModelDownloadState
    data class Downloading(val progress: Float) : ModelDownloadState
    data class Completed(val file: File) : ModelDownloadState
    data class Failed(val error: String) : ModelDownloadState
    data object Cancelled : ModelDownloadState
}
