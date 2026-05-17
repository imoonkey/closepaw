package ai.closepaw.tool

import com.google.common.truth.Truth.assertThat
import ai.closepaw.protocol.AppTier
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test

class AppClassifierOverrideTest {

    // ===== Layered resolution =====

    @Test
    fun `bundled NORMAL with no override classifies as NORMAL`() {
        val classifier = AppClassifier(mapOf("com.foo" to AppTier.NORMAL))
        assertThat(classifier.classify("com.foo")).isEqualTo(AppTier.NORMAL)
    }

    @Test
    fun `override wins over bundled CAUTIOUS`() = runTest {
        val classifier = AppClassifier(emptyMap())
        classifier.setOverride("com.unknown", AppTier.NORMAL)
        assertThat(classifier.classify("com.unknown")).isEqualTo(AppTier.NORMAL)
    }

    @Test
    fun `bundled BLOCKED is the floor regardless of override`() = runTest {
        val classifier = AppClassifier(mapOf("com.bank" to AppTier.BLOCKED))
        // Write is refused so classify stays BLOCKED.
        classifier.setOverride("com.bank", AppTier.NORMAL)
        assertThat(classifier.classify("com.bank")).isEqualTo(AppTier.BLOCKED)
    }

    // ===== Write-time refusal =====

    @Test
    fun `setOverride refuses non-BLOCKED on bundled BLOCKED`() = runTest {
        val persisted = AtomicReference<Map<String, AppTier>?>(null)
        val classifier = AppClassifier(
            appTiers = mapOf("com.bank" to AppTier.BLOCKED),
            onUserOverridesChanged = { persisted.set(it) }
        )

        val result = classifier.setOverride("com.bank", AppTier.NORMAL)

        assertThat(result).isEqualTo(SetOverrideResult.RefusedBlocked)
        assertThat(classifier.userOverrides.value).isEmpty()
        // Refusal must not invoke the persistence callback.
        assertThat(persisted.get()).isNull()
    }

    @Test
    fun `setOverride BLOCKED on bundled BLOCKED removes - already at default`() = runTest {
        // bundled = BLOCKED, override = BLOCKED equals the default → entry removed.
        val classifier = AppClassifier(mapOf("com.bank" to AppTier.BLOCKED))
        val result = classifier.setOverride("com.bank", AppTier.BLOCKED)
        assertThat(result).isEqualTo(SetOverrideResult.Removed)
        assertThat(classifier.userOverrides.value).doesNotContainKey("com.bank")
    }

    // ===== Remove-on-match =====

    @Test
    fun `setOverride to bundled default removes entry`() = runTest {
        val classifier = AppClassifier(
            appTiers = mapOf("com.foo" to AppTier.NORMAL),
            initialUserOverrides = mapOf("com.foo" to AppTier.CAUTIOUS)
        )
        assertThat(classifier.userOverrides.value).containsKey("com.foo")

        val result = classifier.setOverride("com.foo", AppTier.NORMAL)

        assertThat(result).isEqualTo(SetOverrideResult.Removed)
        assertThat(classifier.userOverrides.value).doesNotContainKey("com.foo")
    }

    @Test
    fun `setOverride CAUTIOUS for unknown package removes entry`() = runTest {
        // Unknown package's implicit default is CAUTIOUS, so a CAUTIOUS write is a no-op.
        val classifier = AppClassifier(emptyMap())
        val result = classifier.setOverride("com.unknown", AppTier.CAUTIOUS)
        assertThat(result).isEqualTo(SetOverrideResult.Removed)
        assertThat(classifier.userOverrides.value).isEmpty()
    }

    // ===== Persistence callback contract =====

    @Test
    fun `persistence callback receives final emitted snapshot`() = runTest {
        val snapshots = mutableListOf<Map<String, AppTier>>()
        val classifier = AppClassifier(
            appTiers = emptyMap(),
            onUserOverridesChanged = { snap -> snapshots.add(snap.toMap()) }
        )

        classifier.setOverride("com.a", AppTier.NORMAL)
        classifier.setOverride("com.b", AppTier.BLOCKED)

        assertThat(snapshots.last()).containsExactlyEntriesIn(classifier.userOverrides.value)
        assertThat(snapshots.last()).containsExactly(
            "com.a", AppTier.NORMAL,
            "com.b", AppTier.BLOCKED
        )
    }

    // ===== Concurrency =====

    @Test
    fun `concurrent setOverride for different packages preserves final persisted snapshot`() = runTest {
        val persisted = AtomicReference<Map<String, AppTier>>(emptyMap())
        val classifier = AppClassifier(
            appTiers = emptyMap(),
            onUserOverridesChanged = { persisted.set(it.toMap()) }
        )

        coroutineScope {
            (0 until 50).map { idx ->
                async {
                    classifier.setOverride("com.pkg.$idx", AppTier.NORMAL)
                }
            }.awaitAll()
        }

        assertThat(classifier.userOverrides.value).hasSize(50)
        // Persisted snapshot must match in-memory map exactly.
        assertThat(persisted.get()).isEqualTo(classifier.userOverrides.value)
    }

    @Test
    fun `concurrent setOverride for same package - persisted matches final StateFlow`() = runTest {
        val persisted = AtomicReference<Map<String, AppTier>>(emptyMap())
        val classifier = AppClassifier(
            appTiers = emptyMap(),
            onUserOverridesChanged = { persisted.set(it.toMap()) }
        )

        coroutineScope {
            val a = async { classifier.setOverride("com.same", AppTier.NORMAL) }
            val b = async { classifier.setOverride("com.same", AppTier.BLOCKED) }
            a.await()
            b.await()
        }

        // Either tier may be the final one — what matters is disk == memory after both settle.
        val finalTier = classifier.userOverrides.value["com.same"]
        assertThat(finalTier).isAnyOf(AppTier.NORMAL, AppTier.BLOCKED)
        assertThat(persisted.get()).isEqualTo(classifier.userOverrides.value)
    }
}
