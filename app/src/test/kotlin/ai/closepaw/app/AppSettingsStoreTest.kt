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
