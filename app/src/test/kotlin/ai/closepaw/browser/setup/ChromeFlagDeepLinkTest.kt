package ai.closepaw.browser.setup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChromeFlagDeepLinkTest {

    @Test
    fun `decideStrategy picks ActionView when external intent launched`() {
        val outcome = ChromeFlagDeepLink.decideStrategy(
            actionViewLaunched = true,
            shizukuAmStartSucceeded = false,
        )

        assertThat(outcome).isEqualTo(ChromeFlagDeepLink.Strategy.ActionView)
    }

    @Test
    fun `decideStrategy falls back to ShizukuAmStart when ActionView did not launch`() {
        val outcome = ChromeFlagDeepLink.decideStrategy(
            actionViewLaunched = false,
            shizukuAmStartSucceeded = true,
        )

        assertThat(outcome).isEqualTo(ChromeFlagDeepLink.Strategy.ShizukuAmStart)
    }

    @Test
    fun `decideStrategy falls back to Clipboard when both intent paths fail`() {
        val outcome = ChromeFlagDeepLink.decideStrategy(
            actionViewLaunched = false,
            shizukuAmStartSucceeded = false,
        )

        assertThat(outcome).isEqualTo(ChromeFlagDeepLink.Strategy.Clipboard)
    }

    @Test
    fun `decideStrategy reports ActionView even when am start would also work`() {
        // ActionView is now ADDITIVE — when it launches we ALSO copy the URL to the clipboard
        // with a hint toast (lossless), so we don't need to ALSO run am start. The reported
        // strategy is still ActionView because that's the user-visible launch attempt.
        val outcome = ChromeFlagDeepLink.decideStrategy(
            actionViewLaunched = true,
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
