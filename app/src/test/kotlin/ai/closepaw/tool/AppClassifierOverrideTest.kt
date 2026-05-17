package ai.closepaw.tool

import com.google.common.truth.Truth.assertThat
import ai.closepaw.app.AppSettingsStore
import ai.closepaw.protocol.AppTier
import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
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
    fun `override NORMAL on bundled BLOCKED is refused - bundled is the floor`() = runTest {
        // FLAG_SECURE on sensitive apps makes any "Allow" override useless theater, so the
        // classifier refuses the write at the source. No emission, no persistence.
        val persisted = AtomicReference<Map<String, AppTier>?>(null)
        val classifier = AppClassifier(
            appTiers = mapOf("com.bank" to AppTier.BLOCKED),
            onUserOverridesChanged = { persisted.set(it) }
        )

        val result = classifier.setOverride("com.bank", AppTier.NORMAL)

        assertThat(result).isEqualTo(SetOverrideResult.RefusedBlocked)
        assertThat(classifier.userOverrides.value).doesNotContainKey("com.bank")
        assertThat(classifier.classify("com.bank")).isEqualTo(AppTier.BLOCKED)
        assertThat(persisted.get()).isNull()
    }

    @Test
    fun `override CAUTIOUS on bundled BLOCKED is refused - bundled is the floor`() = runTest {
        val persisted = AtomicReference<Map<String, AppTier>?>(null)
        val classifier = AppClassifier(
            appTiers = mapOf("com.bank" to AppTier.BLOCKED),
            onUserOverridesChanged = { persisted.set(it) }
        )

        val result = classifier.setOverride("com.bank", AppTier.CAUTIOUS)

        assertThat(result).isEqualTo(SetOverrideResult.RefusedBlocked)
        assertThat(classifier.userOverrides.value).doesNotContainKey("com.bank")
        assertThat(classifier.classify("com.bank")).isEqualTo(AppTier.BLOCKED)
        assertThat(persisted.get()).isNull()
    }

    @Test
    fun `classify pins bundled BLOCKED even if a stale override exists`() = runTest {
        // Belt + suspenders: even if a NORMAL override entry survived from an older build,
        // classify() must still return BLOCKED on a bundled-BLOCKED package.
        val classifier = AppClassifier(
            appTiers = mapOf("com.bank" to AppTier.BLOCKED),
            initialUserOverrides = mapOf("com.bank" to AppTier.NORMAL)
        )
        assertThat(classifier.classify("com.bank")).isEqualTo(AppTier.BLOCKED)
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

    // ===== Real-store concurrency (end-to-end persistence) =====

    @After fun tearDown() = unmockkAll()

    @Test
    fun `concurrent setOverride persists to real AppSettingsStore - reload matches StateFlow`() = runBlocking {
        // Wire the classifier callback through a real AppSettingsStore (in-memory-backed
        // SharedPreferences) and then construct a *fresh* classifier from the persisted
        // overrides. Reloaded map must equal the original classifier's StateFlow exactly.
        val backing = mutableMapOf<String, Any?>()
        val context = mockContextWithPrefs(backing)
        val store = AppSettingsStore(context)
        val classifier = AppClassifier(
            appTiers = emptyMap(),
            initialUserOverrides = store.loadUserAppOverrides(),
            onUserOverridesChanged = { snapshot -> store.saveUserAppOverrides(snapshot) }
        )

        coroutineScope {
            (0 until 50).map { idx ->
                async {
                    classifier.setOverride("com.pkg.$idx", AppTier.NORMAL)
                }
            }.awaitAll()
        }

        val reloaded = AppSettingsStore(context).loadUserAppOverrides()
        assertThat(classifier.userOverrides.value).hasSize(50)
        assertThat(reloaded).isEqualTo(classifier.userOverrides.value)
    }

    private fun mockContextWithPrefs(backing: MutableMap<String, Any?>): Context {
        val prefs = fakePrefs(backing)
        return mockk(relaxed = true) {
            every { getSharedPreferences(any(), any()) } returns prefs
        }
    }

    private fun fakePrefs(backing: MutableMap<String, Any?>): SharedPreferences {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } answers {
            backing[firstArg()] = secondArg<String?>()
            editor
        }
        every { editor.remove(any()) } answers {
            backing.remove(firstArg<String>())
            editor
        }
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns editor
        every { prefs.getString(any(), any()) } answers {
            backing[firstArg()] as? String ?: secondArg()
        }
        return prefs
    }
}
