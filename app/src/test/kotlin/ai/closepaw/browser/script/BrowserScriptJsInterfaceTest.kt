package ai.closepaw.browser.script

import android.webkit.JavascriptInterface
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserScriptJsInterfaceTest {

    @Test
    fun `bridge interface exposes only send and done as JavascriptInterface methods`() {
        val annotation = JavascriptInterface::class.java
        val exposed = BrowserScriptJsInterface::class.java.declaredMethods
            .filter { it.isAnnotationPresent(annotation) }
            .map { it.name }
            .toSet()
        assertThat(exposed).containsExactly("send", "done")
    }

    @Test
    fun `send and done both accept a single String argument`() {
        val annotation = JavascriptInterface::class.java
        BrowserScriptJsInterface::class.java.declaredMethods
            .filter { it.isAnnotationPresent(annotation) }
            .forEach { method ->
                assertThat(method.parameterTypes.toList())
                    .containsExactly(String::class.java)
            }
    }
}
