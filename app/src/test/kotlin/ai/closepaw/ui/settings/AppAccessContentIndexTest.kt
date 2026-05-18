package ai.closepaw.ui.settings

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppAccessContentIndexTest {

    @Test
    fun `load merges memory and skill packages`() = runTest {
        val index = AppAccessContentIndex(
            memoryPackages = { setOf("com.example.alpha", "com.example.beta") },
            skillPackages = { setOf("com.example.beta", "com.example.gamma") },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val result = index.load()

        assertThat(result).containsExactly(
            "com.example.alpha", AppContentSummary(hasMemory = true, hasSkill = false),
            "com.example.beta", AppContentSummary(hasMemory = true, hasSkill = true),
            "com.example.gamma", AppContentSummary(hasMemory = false, hasSkill = true),
        )
        assertThat(index.summaries.value).isEqualTo(result)
    }

    @Test
    fun `summaryFor returns NONE for unknown package after load`() = runTest {
        val index = AppAccessContentIndex(
            memoryPackages = { setOf("com.a") },
            skillPackages = { emptySet() },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        index.load()

        val unknown = index.summaryFor("com.does.not.exist")

        assertThat(unknown).isEqualTo(AppContentSummary.NONE)
        assertThat(unknown.hasMemory).isFalse()
        assertThat(unknown.hasSkill).isFalse()
    }

    @Test
    fun `summaryFor reports memory-only correctly`() = runTest {
        val index = AppAccessContentIndex(
            memoryPackages = { setOf("com.mem") },
            skillPackages = { setOf("com.skill") },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        index.load()

        assertThat(index.summaryFor("com.mem"))
            .isEqualTo(AppContentSummary(hasMemory = true, hasSkill = false))
        assertThat(index.summaryFor("com.skill"))
            .isEqualTo(AppContentSummary(hasMemory = false, hasSkill = true))
    }

    @Test
    fun `summaryFor before load returns NONE`() {
        val index = AppAccessContentIndex(
            memoryPackages = { setOf("com.a") },
            skillPackages = { setOf("com.a") },
        )

        assertThat(index.summaryFor("com.a")).isEqualTo(AppContentSummary.NONE)
        assertThat(index.summaries.value).isEmpty()
    }

    @Test
    fun `update overwrites existing entry`() = runTest {
        val index = AppAccessContentIndex(
            memoryPackages = { emptySet() },
            skillPackages = { setOf("com.x") },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        index.load()
        assertThat(index.summaryFor("com.x").hasMemory).isFalse()

        index.update("com.x", AppContentSummary(hasMemory = true, hasSkill = true))

        assertThat(index.summaryFor("com.x"))
            .isEqualTo(AppContentSummary(hasMemory = true, hasSkill = true))
    }

    @Test
    fun `update inserts new entry not seen at load`() = runTest {
        val index = AppAccessContentIndex(
            memoryPackages = { emptySet() },
            skillPackages = { emptySet() },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        index.load()

        index.update("com.new", AppContentSummary(hasMemory = true, hasSkill = false))

        assertThat(index.summaryFor("com.new"))
            .isEqualTo(AppContentSummary(hasMemory = true, hasSkill = false))
    }

    @Test
    fun `update with NONE removes entry`() = runTest {
        val index = AppAccessContentIndex(
            memoryPackages = { setOf("com.a") },
            skillPackages = { emptySet() },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        index.load()
        assertThat(index.summaries.value).containsKey("com.a")

        index.update("com.a", AppContentSummary.NONE)

        assertThat(index.summaries.value).doesNotContainKey("com.a")
        assertThat(index.summaryFor("com.a")).isEqualTo(AppContentSummary.NONE)
    }

    @Test
    fun `update with NONE on missing package is a no-op`() = runTest {
        val index = AppAccessContentIndex(
            memoryPackages = { setOf("com.kept") },
            skillPackages = { emptySet() },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        index.load()
        val before = index.summaries.value

        index.update("com.absent", AppContentSummary.NONE)

        assertThat(index.summaries.value).isEqualTo(before)
    }

    @Test
    fun `load is idempotent and reflects latest stub state`() = runTest {
        var memorySet = setOf("com.a")
        val index = AppAccessContentIndex(
            memoryPackages = { memorySet },
            skillPackages = { emptySet() },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        index.load()
        assertThat(index.summaries.value.keys).containsExactly("com.a")

        memorySet = setOf("com.b")
        index.load()

        assertThat(index.summaries.value.keys).containsExactly("com.b")
    }

    @Test
    fun `update during in-flight load is not erased by load`() = runBlocking {
        val scanStarted = CountDownLatch(1)
        val releaseScan = CountDownLatch(1)
        val index = AppAccessContentIndex(
            memoryPackages = {
                scanStarted.countDown()
                check(releaseScan.await(2, TimeUnit.SECONDS)) { "scan never released" }
                setOf("com.scanned")
            },
            skillPackages = { emptySet() },
            ioDispatcher = Dispatchers.IO,
        )

        val loadJob = launch(Dispatchers.IO) { index.load() }
        check(scanStarted.await(2, TimeUnit.SECONDS)) { "scan never started" }

        val updateJob = launch(Dispatchers.IO) {
            index.update("com.updated", AppContentSummary(hasMemory = true, hasSkill = false))
        }
        // Give update a chance to attempt the mutex while load still holds it.
        delay(50)

        releaseScan.countDown()
        loadJob.join()
        updateJob.join()

        assertThat(index.summaryFor("com.scanned"))
            .isEqualTo(AppContentSummary(hasMemory = true, hasSkill = false))
        assertThat(index.summaryFor("com.updated"))
            .isEqualTo(AppContentSummary(hasMemory = true, hasSkill = false))
    }
}
