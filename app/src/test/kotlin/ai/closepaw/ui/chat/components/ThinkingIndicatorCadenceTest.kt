package ai.closepaw.ui.chat.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pin: paw-toe thinking indicator cadence (motion spec §4 / commit 4d0e1168).
 * Four elements light cumulatively (toe₁ → toe₂ → toe₃ → pad) with
 * unlit elements at 0.30 alpha and lit at 1.0.
 */
class ThinkingIndicatorCadenceTest {

    @Test
    fun `four phases cover toes plus pad`() {
        assertThat(PAW_TOE_ELEMENT_COUNT).isEqualTo(4)
    }

    @Test
    fun `phase 0 lights only toe-1`() {
        val alphas = (0 until PAW_TOE_ELEMENT_COUNT).map { pawToeAlpha(0f, it) }
        assertThat(alphas).containsExactly(1.0f, 0.30f, 0.30f, 0.30f).inOrder()
    }

    @Test
    fun `phase grows cumulatively without resetting prior toes`() {
        val phase2 = (0 until PAW_TOE_ELEMENT_COUNT).map { pawToeAlpha(2f, it) }
        assertThat(phase2).containsExactly(1.0f, 1.0f, 1.0f, 0.30f).inOrder()
    }

    @Test
    fun `final phase lights all four`() {
        val phase3 = (0 until PAW_TOE_ELEMENT_COUNT).map { pawToeAlpha(3f, it) }
        assertThat(phase3).containsExactly(1.0f, 1.0f, 1.0f, 1.0f).inOrder()
    }

    @Test
    fun `phase clamps so loop boundary stays in range`() {
        // Animation targetValue = ELEMENT_COUNT; at the boundary the int cast
        // would over-index without coerceIn. Pad must stay lit, not reset.
        val boundary = (0 until PAW_TOE_ELEMENT_COUNT).map { pawToeAlpha(4f, it) }
        assertThat(boundary).containsExactly(1.0f, 1.0f, 1.0f, 1.0f).inOrder()
    }

    @Test
    fun `tint role pinned to onSurface`() {
        // ThinkingIndicator() resolves its tint via THINKING_INDICATOR_TINT.
        // Pin the role so theme refactors that swap to e.g. primary or Claw
        // trip this test. Color value itself is theme-resolved at runtime.
        assertThat(THINKING_INDICATOR_TINT).isEqualTo(ThinkingTintRole.OnSurface)
    }
}
