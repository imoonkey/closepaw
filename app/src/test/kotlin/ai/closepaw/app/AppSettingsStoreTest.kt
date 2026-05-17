package ai.closepaw.app

import android.content.Context
import android.content.SharedPreferences
import ai.closepaw.protocol.AppTier
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test

class AppSettingsStoreTest {
    private lateinit var backing: MutableMap<String, Any?>
    private lateinit var context: Context

    @Before
    fun setUp() {
        backing = mutableMapOf()
        val prefs = fakePrefs(backing)
        context = mockk(relaxed = true) {
            every { getSharedPreferences("agent_prefs", any()) } returns prefs
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `browser_script setting defaults disabled`() {
        assertThat(AppSettingsStore(context).load().browserScriptEnabled).isFalse()
    }

    @Test
    fun `browser_script setting round-trips through store`() {
        val store = AppSettingsStore(context)

        store.saveBrowserScriptEnabled(true)

        assertThat(AppSettingsStore(context).load().browserScriptEnabled).isTrue()
    }

    @Test
    fun `browser_script setting round-trips through state`() {
        val state = AppSettingsState(AppSettingsStore(context))

        state.load()
        state.updateBrowserScriptEnabled(true)

        val reloaded = AppSettingsStore(context).load()
        assertThat(state.browserScriptEnabled).isTrue()
        assertThat(reloaded.browserScriptEnabled).isTrue()
    }

    @Test
    fun `otherBaseUrl + otherModelId default to empty`() {
        val settings = AppSettingsStore(context).load()
        assertThat(settings.otherBaseUrl).isEmpty()
        assertThat(settings.otherModelId).isEmpty()
    }

    @Test
    fun `otherBaseUrl persists + restores`() {
        val store = AppSettingsStore(context)
        store.saveOtherBaseUrl("https://api.example.com/v1")

        assertThat(AppSettingsStore(context).load().otherBaseUrl).isEqualTo("https://api.example.com/v1")
    }

    @Test
    fun `otherModelId persists + restores`() {
        val store = AppSettingsStore(context)
        store.saveOtherModelId("vendor/model-x")

        assertThat(AppSettingsStore(context).load().otherModelId).isEqualTo("vendor/model-x")
    }

    @Test
    fun `blank otherBaseUrl write clears stored value`() {
        val store = AppSettingsStore(context)
        store.saveOtherBaseUrl("https://api.example.com/v1")
        store.saveOtherBaseUrl("")

        assertThat(AppSettingsStore(context).load().otherBaseUrl).isEmpty()
    }

    @Test
    fun `other settings round-trip through state`() {
        var invalidated = 0
        val state = AppSettingsState(
            store = AppSettingsStore(context),
            onOtherSettingsChanged = { invalidated++ },
        )
        state.load()
        state.updateOtherBaseUrl("https://api.example.com/v1")
        state.updateOtherModelId("vendor/model-x")

        val reloaded = AppSettingsStore(context).load()
        assertThat(state.otherBaseUrl).isEqualTo("https://api.example.com/v1")
        assertThat(state.otherModelId).isEqualTo("vendor/model-x")
        assertThat(reloaded.otherBaseUrl).isEqualTo("https://api.example.com/v1")
        assertThat(reloaded.otherModelId).isEqualTo("vendor/model-x")
        // updateOtherBaseUrl + updateOtherModelId must each notify the catalog.
        assertThat(invalidated).isEqualTo(2)
    }

    @Test
    fun `user app overrides default to empty`() {
        assertThat(AppSettingsStore(context).loadUserAppOverrides()).isEmpty()
    }

    @Test
    fun `user app overrides round-trip through store`() = runBlocking {
        val store = AppSettingsStore(context)
        val overrides = mapOf(
            "com.spotify.music" to AppTier.NORMAL,
            "com.evil.app" to AppTier.BLOCKED,
            "com.unknown.app" to AppTier.CAUTIOUS,
        )

        store.saveUserAppOverrides(overrides)

        assertThat(AppSettingsStore(context).loadUserAppOverrides()).isEqualTo(overrides)
    }

    @Test
    fun `saving empty overrides clears the stored value`() = runBlocking {
        val store = AppSettingsStore(context)
        store.saveUserAppOverrides(mapOf("com.spotify.music" to AppTier.NORMAL))
        store.saveUserAppOverrides(emptyMap())

        assertThat(AppSettingsStore(context).loadUserAppOverrides()).isEmpty()
    }

    @Test
    fun `unknown tier strings are dropped silently`() {
        val raw = JSONObject().apply {
            put("com.good.app", "NORMAL")
            put("com.weird.app", "PURPLE")
            put("com.blocked.app", "BLOCKED")
        }.toString()
        backing["user_app_overrides"] = raw

        val loaded = AppSettingsStore(context).loadUserAppOverrides()

        assertThat(loaded).containsExactly(
            "com.good.app", AppTier.NORMAL,
            "com.blocked.app", AppTier.BLOCKED,
        )
    }

    @Test
    fun `malformed JSON falls back to empty map`() {
        backing["user_app_overrides"] = "not a json object"

        assertThat(AppSettingsStore(context).loadUserAppOverrides()).isEmpty()
    }

    // ===== Disabled agent skills =====

    @Test
    fun `disabled agent skills default to empty`() {
        val store = AppSettingsStore(context)
        assertThat(store.disabledAgentSkills.value).isEmpty()
        assertThat(store.loadDisabledAgentSkills()).isEmpty()
    }

    @Test
    fun `setSkillDisabled true persists and updates flow`() = runBlocking<Unit> {
        val store = AppSettingsStore(context)
        store.setSkillDisabled("calendar-date-math", true)

        assertThat(store.disabledAgentSkills.value).containsExactly("calendar-date-math")
        assertThat(AppSettingsStore(context).loadDisabledAgentSkills())
            .containsExactly("calendar-date-math")
    }

    @Test
    fun `setSkillDisabled false removes from set`() = runBlocking<Unit> {
        val store = AppSettingsStore(context)
        store.setSkillDisabled("alpha", true)
        store.setSkillDisabled("beta", true)
        store.setSkillDisabled("alpha", false)

        assertThat(store.disabledAgentSkills.value).containsExactly("beta")
        assertThat(AppSettingsStore(context).loadDisabledAgentSkills()).containsExactly("beta")
    }

    @Test
    fun `setSkillDisabled clears storage when last entry removed`() = runBlocking {
        val store = AppSettingsStore(context)
        store.setSkillDisabled("alpha", true)
        store.setSkillDisabled("alpha", false)

        assertThat(store.disabledAgentSkills.value).isEmpty()
        // Backing key cleared, not left as empty JSON array.
        assertThat(backing["disabled_agent_skills"]).isNull()
    }

    @Test
    fun `setSkillDisabled is a no-op when state already matches`() = runBlocking {
        val store = AppSettingsStore(context)
        store.setSkillDisabled("alpha", true)
        val before = store.disabledAgentSkills.value
        store.setSkillDisabled("alpha", true)
        assertThat(store.disabledAgentSkills.value).isSameInstanceAs(before)
    }

    @Test
    fun `malformed disabled skills JSON falls back to empty set`() {
        backing["disabled_agent_skills"] = "not-a-json-array"
        assertThat(AppSettingsStore(context).loadDisabledAgentSkills()).isEmpty()
    }

    @Test
    fun `concurrent setSkillDisabled calls do not lose entries`() = runBlocking<Unit> {
        val store = AppSettingsStore(context)
        val names = (1..20).map { "skill-$it" }

        // Fan out 20 disables in parallel. Without serialization the
        // read-modify-write on _disabledAgentSkills.value would drop entries.
        names.map { name -> async { store.setSkillDisabled(name, true) } }.awaitAll()

        assertThat(store.disabledAgentSkills.value).containsExactlyElementsIn(names)
        assertThat(AppSettingsStore(context).loadDisabledAgentSkills())
            .containsExactlyElementsIn(names)
    }

    private fun fakePrefs(backing: MutableMap<String, Any?>): SharedPreferences {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } answers {
            backing[firstArg()] = secondArg<String?>()
            editor
        }
        every { editor.putBoolean(any(), any()) } answers {
            backing[firstArg()] = secondArg<Boolean>()
            editor
        }
        every { editor.putInt(any(), any()) } answers {
            backing[firstArg()] = secondArg<Int>()
            editor
        }
        every { editor.putStringSet(any(), any()) } answers {
            backing[firstArg()] = secondArg<Set<String>?>()
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
        every { prefs.getBoolean(any(), any()) } answers {
            backing[firstArg()] as? Boolean ?: secondArg()
        }
        every { prefs.getInt(any(), any()) } answers {
            backing[firstArg()] as? Int ?: secondArg()
        }
        every { prefs.getStringSet(any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (backing[firstArg()] as? Set<String>) ?: secondArg()
        }
        return prefs
    }
}
