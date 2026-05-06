package ai.closepaw.browser.setup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChromeFlagDeepLinkTest {

    @Test
    fun `decideStrategy picks ActionView when external intent succeeds`() {
        val outcome = ChromeFlagDeepLink.decideStrategy(
            actionViewSucceeded = true,
            shizukuAmStartSucceeded = false,
        )

        assertThat(outcome).isEqualTo(ChromeFlagDeepLink.Strategy.ActionView)
    }

    @Test
    fun `decideStrategy falls back to ShizukuAmStart when ActionView fails`() {
        val outcome = ChromeFlagDeepLink.decideStrategy(
            actionViewSucceeded = false,
            shizukuAmStartSucceeded = true,
        )

        assertThat(outcome).isEqualTo(ChromeFlagDeepLink.Strategy.ShizukuAmStart)
    }

    @Test
    fun `decideStrategy falls back to Clipboard when both intent paths fail`() {
        val outcome = ChromeFlagDeepLink.decideStrategy(
            actionViewSucceeded = false,
            shizukuAmStartSucceeded = false,
        )

        assertThat(outcome).isEqualTo(ChromeFlagDeepLink.Strategy.Clipboard)
    }

    @Test
    fun `decideStrategy short-circuits on first success even if later succeeds`() {
        // ActionView success ALWAYS wins regardless of subsequent path success — the
        // cascade is short-circuit, not "best of three".
        val outcome = ChromeFlagDeepLink.decideStrategy(
            actionViewSucceeded = true,
            shizukuAmStartSucceeded = true,
        )

        assertThat(outcome).isEqualTo(ChromeFlagDeepLink.Strategy.ActionView)
    }

    @Test
    fun `flag URL points to the chrome flag we want`() {
        // Guard against typos in the flag slug — chrome flags page won't auto-correct, the
        // user would just see an empty flags page.
        assertThat(ChromeFlagDeepLink.FLAG_URL)
            .isEqualTo("chrome://flags/#enable-command-line-on-non-rooted-devices")
    }
}
