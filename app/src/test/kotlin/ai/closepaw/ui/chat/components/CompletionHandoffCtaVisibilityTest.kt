package ai.closepaw.ui.chat.components

import ai.closepaw.protocol.CompletionHandoff
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers the four visibility guards for [CompletionHandoffCtaRow] via the pure-Kotlin
 * helper [completionHandoffCtaVisibility]. Compose-level rendering reuses the same
 * guard with a real `PackageManager` lookup as the resolver.
 */
class CompletionHandoffCtaVisibilityTest {

    private val resolveAlways: (String) -> Boolean = { true }
    private val resolveNever: (String) -> Boolean = { false }

    @Test
    fun `null handoff hides both CTAs`() {
        val v = completionHandoffCtaVisibility(handoff = null, canResolveLauncher = resolveAlways)
        assertThat(v.showOpenApp).isFalse()
        assertThat(v.showOpenViewer).isFalse()
        assertThat(v.any).isFalse()
    }

    @Test
    fun `valid handoff with viewer alive shows both CTAs`() {
        val handoff = CompletionHandoff(
            appPackage = "com.google.android.youtube",
            appLabel = "YouTube",
            virtualDisplayAvailable = true,
        )
        val v = completionHandoffCtaVisibility(handoff, resolveAlways)
        assertThat(v.showOpenApp).isTrue()
        assertThat(v.showOpenViewer).isTrue()
    }

    @Test
    fun `virtualDisplayAvailable false hides viewer button`() {
        val handoff = CompletionHandoff(
            appPackage = "com.google.android.youtube",
            appLabel = "YouTube",
            virtualDisplayAvailable = false,
        )
        val v = completionHandoffCtaVisibility(handoff, resolveAlways)
        assertThat(v.showOpenApp).isTrue()
        assertThat(v.showOpenViewer).isFalse()
    }

    @Test
    fun `live viewer unavailable hides viewer button even when handoff snapshot was available`() {
        val handoff = CompletionHandoff(
            appPackage = "com.google.android.youtube",
            appLabel = "YouTube",
            virtualDisplayAvailable = true,
        )
        val v = completionHandoffCtaVisibility(
            handoff = handoff,
            canResolveLauncher = resolveAlways,
            isVirtualDisplayViewerAvailable = false,
        )
        assertThat(v.showOpenApp).isTrue()
        assertThat(v.showOpenViewer).isFalse()
    }

    @Test
    fun `null appPackage hides Open button`() {
        val handoff = CompletionHandoff(
            appPackage = null,
            appLabel = null,
            virtualDisplayAvailable = true,
        )
        val v = completionHandoffCtaVisibility(handoff, resolveAlways)
        assertThat(v.showOpenApp).isFalse()
        assertThat(v.showOpenViewer).isTrue()
    }

    @Test
    fun `unresolvable launcher intent hides Open button`() {
        val handoff = CompletionHandoff(
            appPackage = "com.example.missing",
            appLabel = "Missing",
            virtualDisplayAvailable = true,
        )
        val v = completionHandoffCtaVisibility(handoff, resolveNever)
        assertThat(v.showOpenApp).isFalse()
        assertThat(v.showOpenViewer).isTrue()
    }

    @Test
    fun `neither guard passes hides both CTAs`() {
        val handoff = CompletionHandoff(
            appPackage = null,
            appLabel = null,
            virtualDisplayAvailable = false,
        )
        val v = completionHandoffCtaVisibility(handoff, resolveAlways)
        assertThat(v.any).isFalse()
    }
}
