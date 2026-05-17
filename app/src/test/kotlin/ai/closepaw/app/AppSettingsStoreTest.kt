package ai.closepaw.app

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
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
