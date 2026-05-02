package ai.closepaw.session

import android.content.Context
import android.content.SharedPreferences
import ai.closepaw.app.AppSettingsStore
import ai.closepaw.tool.ToolRegistry
import ai.closepaw.tool.impl.BrowserScriptTool
import ai.closepaw.trace.NoopTraceRecorder
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionBrowserIntegrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        val prefs = fakePrefs()
        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSharedPreferences("agent_prefs", any()) } returns prefs
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `registerBrowserScriptTool registers browser_script in tool registry`() {
        val registry = ToolRegistry()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

        val manager = SessionServices.registerBrowserScriptTool(
            toolRegistry = registry,
            context = context,
            scope = scope,
            traceRecorder = NoopTraceRecorder,
            settingsStore = AppSettingsStore(context),
        )

        assertThat(registry.contains("browser_script")).isTrue()
        assertThat(registry.get("browser_script")).isInstanceOf(BrowserScriptTool::class.java)
        assertThat(manager).isNotNull()
        manager.close()
        scope.cancel()
    }

    private fun fakePrefs(): SharedPreferences {
        val backing = mutableMapOf<String, Any?>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putBoolean(any(), any()) } answers {
            backing[firstArg()] = secondArg<Boolean>()
            editor
        }
        every { editor.putStringSet(any(), any()) } answers {
            backing[firstArg()] = secondArg<Set<String>?>()
            editor
        }

        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.edit() } returns editor
        every { prefs.getBoolean(any(), any()) } answers {
            backing[firstArg()] as? Boolean ?: secondArg()
        }
        every { prefs.getStringSet(any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (backing[firstArg()] as? Set<String>) ?: secondArg()
        }
        return prefs
    }
}
