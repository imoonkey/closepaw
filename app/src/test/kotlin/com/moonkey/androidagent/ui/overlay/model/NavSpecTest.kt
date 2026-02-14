package com.moonkey.androidagent.ui.overlay.model

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.protocol.PlatformMode
import org.junit.Test

class NavSpecTest {

    @Test
    fun `vd main app keeps watch entry in hidden mode`() {
        val spec = NavSpec.from(
            context = CapsuleContext.MAIN_APP,
            platformMode = PlatformMode.VIRTUAL_DISPLAY,
            hasIsland = true,
            mode = CapsuleMode.Hidden,
        )

        assertThat(spec.showWatch).isTrue()
        assertThat(spec.showApp).isFalse()
        assertThat(spec.showMinimize).isFalse()
    }

    @Test
    fun `vd screen viewing hides minimize in waiting for input`() {
        val spec = NavSpec.from(
            context = CapsuleContext.SCREEN_VIEWING,
            platformMode = PlatformMode.VIRTUAL_DISPLAY,
            hasIsland = true,
            mode = CapsuleMode.WaitingForInput(question = "q", callId = "c1"),
        )

        assertThat(spec.showMinimize).isFalse()
        assertThat(spec.showApp).isTrue()
        assertThat(spec.showWatch).isFalse()
    }

    @Test
    fun `vd screen viewing hides minimize in waiting for action`() {
        val spec = NavSpec.from(
            context = CapsuleContext.SCREEN_VIEWING,
            platformMode = PlatformMode.VIRTUAL_DISPLAY,
            hasIsland = true,
            mode = CapsuleMode.WaitingForAction(instruction = "do it", callId = "c1"),
        )

        assertThat(spec.showMinimize).isFalse()
    }

    @Test
    fun `vd background hides minimize in error`() {
        val spec = NavSpec.from(
            context = CapsuleContext.BACKGROUND,
            platformMode = PlatformMode.VIRTUAL_DISPLAY,
            hasIsland = true,
            mode = CapsuleMode.Error("error"),
        )

        assertThat(spec.showMinimize).isFalse()
        assertThat(spec.showApp).isTrue()
        assertThat(spec.showWatch).isTrue()
    }

    @Test
    fun `a11y never shows nav buttons`() {
        val spec = NavSpec.from(
            context = CapsuleContext.SCREEN_VIEWING,
            platformMode = PlatformMode.ACCESSIBILITY,
            hasIsland = true,
            mode = CapsuleMode.Running("thinking"),
        )

        assertThat(spec.showMinimize).isFalse()
        assertThat(spec.showApp).isFalse()
        assertThat(spec.showWatch).isFalse()
    }
}
