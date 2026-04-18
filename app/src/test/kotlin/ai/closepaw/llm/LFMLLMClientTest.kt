package ai.closepaw.llm

import ai.liquid.leap.ModelRunner
import ai.liquid.leap.manifest.LeapDownloader
import ai.liquid.leap.manifest.ProgressData
import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Characterization tests for LFMLLMClient.ModelLoadingState transitions.
 *
 * Source of truth: doc/main/state_machines/local_model_loading.md
 *
 * The FSM has five states (NotLoaded, Downloading(p), Loading, Ready, Error)
 * and the following transitions:
 *   NotLoaded/Error -> Downloading(0f)         (loadModel, modelRunner == null)
 *   Downloading(p)  -> Downloading(p')         (progress callback, p < 1f)
 *   Downloading(*)  -> Loading                 (progress callback, p >= 1f)
 *   Loading         -> Ready                   (downloader.loadModel returns)
 *   any-in-progress -> Error(message)          (exception thrown)
 *   Ready           -> NotLoaded               (cleanup())
 *   guard: loadModel no-ops when modelRunner != null
 */
class LFMLLMClientTest {

    private lateinit var context: Context
    private lateinit var runner: ModelRunner

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        every { context.filesDir } returns File(System.getProperty("java.io.tmpdir"), "leap-test")
        runner = mockk(relaxed = true)
        mockkConstructor(LeapDownloader::class)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun captureProgressLambda(): io.mockk.CapturingSlot<Function1<ProgressData, Unit>> {
        val slot = slot<Function1<ProgressData, Unit>>()
        coEvery {
            anyConstructed<LeapDownloader>().loadModel(
                modelSlug = any(),
                quantizationSlug = any(),
                progress = capture(slot)
            )
        } coAnswers { runner }
        return slot
    }

    // ---------- Initial state ----------

    @Test
    fun `initial state is NotLoaded`() {
        val client = LFMLLMClient(context)
        assertThat(client.getLoadingState())
            .isEqualTo(LFMLLMClient.ModelLoadingState.NotLoaded)
        assertThat(client.isReady()).isFalse()
    }

    // ---------- NotLoaded -> Downloading(0f) -> ... -> Ready ----------

    @Test
    fun `loadModel emits Downloading(0f), progress updates, Loading, then Ready`() = runBlocking {
        val progressSlot = captureProgressLambda()
        val client = LFMLLMClient(context)

        val observed = mutableListOf<LFMLLMClient.ModelLoadingState>()
        // Drive intermediate progress events from inside the downloader stub
        coEvery {
            anyConstructed<LeapDownloader>().loadModel(
                modelSlug = any(),
                quantizationSlug = any(),
                progress = capture(progressSlot)
            )
        } coAnswers {
            progressSlot.captured.invoke(ProgressData(bytes = 25, total = 100))
            progressSlot.captured.invoke(ProgressData(bytes = 75, total = 100))
            progressSlot.captured.invoke(ProgressData(bytes = 100, total = 100))
            runner
        }

        client.loadModel { observed += it }

        // Initial Downloading(0f) emitted before the downloader runs.
        assertThat(observed.first())
            .isEqualTo(LFMLLMClient.ModelLoadingState.Downloading(0f))
        // Two intermediate Downloading updates with p < 1f.
        assertThat(observed[1])
            .isEqualTo(LFMLLMClient.ModelLoadingState.Downloading(0.25f))
        assertThat(observed[2])
            .isEqualTo(LFMLLMClient.ModelLoadingState.Downloading(0.75f))
        // p >= 1f flips to Loading.
        assertThat(observed[3]).isEqualTo(LFMLLMClient.ModelLoadingState.Loading)
        // Downloader return -> Ready.
        assertThat(observed.last()).isEqualTo(LFMLLMClient.ModelLoadingState.Ready)

        assertThat(client.getLoadingState()).isEqualTo(LFMLLMClient.ModelLoadingState.Ready)
        assertThat(client.isReady()).isTrue()
    }

    // ---------- Downloading -> Loading boundary at exactly 1f ----------

    @Test
    fun `progress exactly 1f transitions Downloading to Loading`() = runBlocking {
        val slot = slot<Function1<ProgressData, Unit>>()
        coEvery {
            anyConstructed<LeapDownloader>().loadModel(
                modelSlug = any(),
                quantizationSlug = any(),
                progress = capture(slot)
            )
        } coAnswers {
            slot.captured.invoke(ProgressData(bytes = 100, total = 100))
            runner
        }
        val client = LFMLLMClient(context)
        val observed = mutableListOf<LFMLLMClient.ModelLoadingState>()

        client.loadModel { observed += it }

        // No Downloading(1f) — exactly at 1f flips straight to Loading.
        assertThat(observed).contains(LFMLLMClient.ModelLoadingState.Loading)
        assertThat(observed.none {
            it is LFMLLMClient.ModelLoadingState.Downloading && it.progress >= 1f
        }).isTrue()
    }

    // ---------- Downloading -> Error (exception during download phase) ----------

    @Test
    fun `exception during download transitions to Error and rethrows`() = runBlocking {
        coEvery {
            anyConstructed<LeapDownloader>().loadModel(
                modelSlug = any(),
                quantizationSlug = any(),
                progress = any()
            )
        } throws RuntimeException("network down")
        val client = LFMLLMClient(context)
        val observed = mutableListOf<LFMLLMClient.ModelLoadingState>()

        val ex = assertThrows(RuntimeException::class.java) {
            runBlocking { client.loadModel { observed += it } }
        }
        assertThat(ex.message).isEqualTo("network down")
        assertThat(client.getLoadingState())
            .isEqualTo(LFMLLMClient.ModelLoadingState.Error("network down"))
        assertThat(client.isReady()).isFalse()
        // First state surfaced was Downloading(0f), final state is Error.
        assertThat(observed.first())
            .isEqualTo(LFMLLMClient.ModelLoadingState.Downloading(0f))
        assertThat(observed.last())
            .isEqualTo(LFMLLMClient.ModelLoadingState.Error("network down"))
    }

    // ---------- Loading -> Error (exception after progress hit 1f) ----------

    @Test
    fun `exception after Loading state transitions to Error`() = runBlocking {
        val slot = slot<Function1<ProgressData, Unit>>()
        coEvery {
            anyConstructed<LeapDownloader>().loadModel(
                modelSlug = any(),
                quantizationSlug = any(),
                progress = capture(slot)
            )
        } coAnswers {
            slot.captured.invoke(ProgressData(bytes = 100, total = 100))
            throw IllegalStateException("runtime init failed")
        }
        val client = LFMLLMClient(context)
        val observed = mutableListOf<LFMLLMClient.ModelLoadingState>()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { client.loadModel { observed += it } }
        }

        // Sequence must include Loading then Error.
        val loadingIdx = observed.indexOf(LFMLLMClient.ModelLoadingState.Loading)
        assertThat(loadingIdx).isAtLeast(0)
        assertThat(observed.last())
            .isEqualTo(LFMLLMClient.ModelLoadingState.Error("runtime init failed"))
        assertThat(client.getLoadingState())
            .isEqualTo(LFMLLMClient.ModelLoadingState.Error("runtime init failed"))
    }

    // ---------- Error -> Downloading on retry ----------

    @Test
    fun `loadModel after Error retries from Downloading(0f) and reaches Ready`() = runBlocking {
        var call = 0
        val slot = slot<Function1<ProgressData, Unit>>()
        coEvery {
            anyConstructed<LeapDownloader>().loadModel(
                modelSlug = any(),
                quantizationSlug = any(),
                progress = capture(slot)
            )
        } coAnswers {
            call++
            if (call == 1) {
                throw RuntimeException("first attempt failed")
            } else {
                slot.captured.invoke(ProgressData(bytes = 100, total = 100))
                runner
            }
        }
        val client = LFMLLMClient(context)

        assertThrows(RuntimeException::class.java) {
            runBlocking { client.loadModel() }
        }
        assertThat(client.getLoadingState())
            .isEqualTo(LFMLLMClient.ModelLoadingState.Error("first attempt failed"))

        val observedRetry = mutableListOf<LFMLLMClient.ModelLoadingState>()
        client.loadModel { observedRetry += it }

        assertThat(observedRetry.first())
            .isEqualTo(LFMLLMClient.ModelLoadingState.Downloading(0f))
        assertThat(observedRetry.last())
            .isEqualTo(LFMLLMClient.ModelLoadingState.Ready)
        assertThat(client.isReady()).isTrue()
    }

    // ---------- Guard: loadModel is idempotent when modelRunner != null ----------

    @Test
    fun `loadModel is no-op when model already loaded`() = runBlocking {
        captureProgressLambda()
        val client = LFMLLMClient(context)

        client.loadModel()                 // first call -> Ready
        assertThat(client.isReady()).isTrue()

        val observed = mutableListOf<LFMLLMClient.ModelLoadingState>()
        client.loadModel { observed += it } // second call -> guard fires, no callbacks

        assertThat(observed).isEmpty()
        assertThat(client.getLoadingState()).isEqualTo(LFMLLMClient.ModelLoadingState.Ready)
        // Downloader was constructed/invoked exactly once.
        coVerify(exactly = 1) {
            anyConstructed<LeapDownloader>().loadModel(
                modelSlug = any(),
                quantizationSlug = any(),
                progress = any()
            )
        }
    }

    // ---------- Ready -> NotLoaded via cleanup ----------

    @Test
    fun `cleanup from Ready unloads runner and returns to NotLoaded`() = runBlocking {
        captureProgressLambda()
        val client = LFMLLMClient(context)
        client.loadModel()
        assertThat(client.isReady()).isTrue()

        client.cleanup()

        coVerify { runner.unload() }
        assertThat(client.getLoadingState()).isEqualTo(LFMLLMClient.ModelLoadingState.NotLoaded)
        assertThat(client.isReady()).isFalse()
    }

    @Test
    fun `cleanup from NotLoaded is a safe no-op`() = runBlocking {
        val client = LFMLLMClient(context)

        client.cleanup()

        assertThat(client.getLoadingState()).isEqualTo(LFMLLMClient.ModelLoadingState.NotLoaded)
        coVerify(exactly = 0) { runner.unload() }
    }

    // ---------- After cleanup, loadModel can run again (NotLoaded -> Ready) ----------

    @Test
    fun `loadModel after cleanup re-runs full download`() = runBlocking {
        captureProgressLambda()
        val client = LFMLLMClient(context)

        client.loadModel()
        client.cleanup()
        client.loadModel()

        assertThat(client.isReady()).isTrue()
        coVerify(exactly = 2) {
            anyConstructed<LeapDownloader>().loadModel(
                modelSlug = any(),
                quantizationSlug = any(),
                progress = any()
            )
        }
    }

    // ---------- Error.message defaults when exception message is null ----------

    @Test
    fun `Error state uses Unknown error when exception message is null`() = runBlocking {
        coEvery {
            anyConstructed<LeapDownloader>().loadModel(
                modelSlug = any(),
                quantizationSlug = any(),
                progress = any()
            )
        } throws RuntimeException()
        val client = LFMLLMClient(context)

        assertThrows(RuntimeException::class.java) {
            runBlocking { client.loadModel() }
        }
        assertThat(client.getLoadingState())
            .isEqualTo(LFMLLMClient.ModelLoadingState.Error("Unknown error"))
    }

    // ---------- Final-callback ordering: Ready always last on success ----------

    @Test
    fun `onProgress receives Downloading then Loading then Ready in order`() = runBlocking {
        val slot = slot<Function1<ProgressData, Unit>>()
        coEvery {
            anyConstructed<LeapDownloader>().loadModel(
                modelSlug = any(),
                quantizationSlug = any(),
                progress = capture(slot)
            )
        } coAnswers {
            slot.captured.invoke(ProgressData(bytes = 50, total = 100))
            slot.captured.invoke(ProgressData(bytes = 100, total = 100))
            runner
        }
        val client = LFMLLMClient(context)
        val observed = mutableListOf<LFMLLMClient.ModelLoadingState>()
        client.loadModel { observed += it }

        // Strict ordering of the five emitted states.
        assertThat(observed).containsExactly(
            LFMLLMClient.ModelLoadingState.Downloading(0f),
            LFMLLMClient.ModelLoadingState.Downloading(0.5f),
            LFMLLMClient.ModelLoadingState.Loading,
            LFMLLMClient.ModelLoadingState.Ready,
        ).inOrder()
    }
}
