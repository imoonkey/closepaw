package ai.closepaw.ui.chat.components

import ai.closepaw.protocol.CompletionHandoff
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers the visibility guard for [CompletionHandoffCtaRow] via the pure-Kotlin
 * helper [shouldShowOpenAppCta]. The composable wraps the same guard with a real
 * `PackageManager` lookup as the resolver.
 */
class CompletionHandoffCtaVisibilityTest {

    private val resolveAlways: (String) -> Boolean = { true }
    private val resolveNever: (String) -> Boolean = { false }

    @Test
    fun `null handoff hides CTA`() {
        assertThat(shouldShowOpenAppCta(handoff = null, canResolveLauncher = resolveAlways)).isFalse()
    }

    @Test
    fun `valid handoff with resolvable launcher shows CTA`() {
        val handoff = CompletionHandoff(
            appPackage = "com.google.android.youtube",
            appLabel = "YouTube",
        )
        assertThat(shouldShowOpenAppCta(handoff, resolveAlways)).isTrue()
    }

    @Test
    fun `null appPackage hides CTA`() {
        val handoff = CompletionHandoff(appPackage = null, appLabel = null)
        assertThat(shouldShowOpenAppCta(handoff, resolveAlways)).isFalse()
    }

    @Test
    fun `unresolvable launcher intent hides CTA`() {
        val handoff = CompletionHandoff(
            appPackage = "com.example.missing",
            appLabel = "Missing",
        )
        assertThat(shouldShowOpenAppCta(handoff, resolveNever)).isFalse()
    }
}
