package ai.closepaw.ui.chat.components

import ai.closepaw.protocol.CompletionHandoff
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers the visibility guards for [CompletionHandoffCtaRow] via the pure-Kotlin
 * helper [completionHandoffCtaVisibility]. Compose-level rendering reuses the same
 * guard with a real `PackageManager` lookup as the resolver.
 */
class CompletionHandoffCtaVisibilityTest {

    private val resolveAlways: (String) -> Boolean = { true }
    private val resolveNever: (String) -> Boolean = { false }

    @Test
    fun `null handoff hides Open CTA`() {
        val v = completionHandoffCtaVisibility(handoff = null, canResolveLauncher = resolveAlways)
        assertThat(v.showOpenApp).isFalse()
        assertThat(v.any).isFalse()
    }

    @Test
    fun `valid handoff with resolvable launcher shows Open CTA`() {
        val handoff = CompletionHandoff(
            appPackage = "com.google.android.youtube",
            appLabel = "YouTube",
            virtualDisplayAvailable = true,
        )
        val v = completionHandoffCtaVisibility(handoff, resolveAlways)
        assertThat(v.showOpenApp).isTrue()
    }

    @Test
    fun `null appPackage hides Open CTA`() {
        val handoff = CompletionHandoff(
            appPackage = null,
            appLabel = null,
            virtualDisplayAvailable = true,
        )
        val v = completionHandoffCtaVisibility(handoff, resolveAlways)
        assertThat(v.showOpenApp).isFalse()
        assertThat(v.any).isFalse()
    }

    @Test
    fun `unresolvable launcher intent hides Open CTA`() {
        val handoff = CompletionHandoff(
            appPackage = "com.example.missing",
            appLabel = "Missing",
            virtualDisplayAvailable = true,
        )
        val v = completionHandoffCtaVisibility(handoff, resolveNever)
        assertThat(v.showOpenApp).isFalse()
        assertThat(v.any).isFalse()
    }
}
