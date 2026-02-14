package com.moonkey.androidagent.app

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.protocol.PlatformMode
import com.moonkey.androidagent.ui.overlay.model.CapsuleContext
import com.moonkey.androidagent.ui.overlay.model.CapsuleMode
import org.junit.Test

class OverlayLocationPolicyTest {

    @Test
    fun `resolve user location detects vd viewer`() {
        val location = resolveUserLocation(
            appPackage = "com.moonkey.androidagent",
            packageName = "com.moonkey.androidagent",
            className = "com.moonkey.androidagent.ui.viewer.VirtualDisplayViewerActivity",
        )
        assertThat(location).isEqualTo(OverlayUserLocation.VD_VIEWER)
    }

    @Test
    fun `resolve user location detects main app activity`() {
        val location = resolveUserLocation(
            appPackage = "com.moonkey.androidagent",
            packageName = "com.moonkey.androidagent",
            className = "com.moonkey.androidagent.app.MainActivity",
        )
        assertThat(location).isEqualTo(OverlayUserLocation.MAIN_APP)
    }

    @Test
    fun `resolve user location detects other app`() {
        val location = resolveUserLocation(
            appPackage = "com.moonkey.androidagent",
            packageName = "com.google.android.youtube",
            className = "com.google.android.apps.youtube.app.WatchWhileActivity",
        )
        assertThat(location).isEqualTo(OverlayUserLocation.OTHER_APP)
    }

    @Test
    fun `resolve user location ignores non-activity windows`() {
        val location = resolveUserLocation(
            appPackage = "com.moonkey.androidagent",
            packageName = "com.google.android.youtube",
            className = "android.widget.FrameLayout",
        )
        assertThat(location).isNull()
    }

    @Test
    fun `resolve capsule context maps vd viewer to screen viewing`() {
        val ctx = resolveCapsuleContext(PlatformMode.VIRTUAL_DISPLAY, OverlayUserLocation.VD_VIEWER)
        assertThat(ctx).isEqualTo(CapsuleContext.SCREEN_VIEWING)
    }

    @Test
    fun `resolve capsule context maps vd other app to background`() {
        val ctx = resolveCapsuleContext(PlatformMode.VIRTUAL_DISPLAY, OverlayUserLocation.OTHER_APP)
        assertThat(ctx).isEqualTo(CapsuleContext.BACKGROUND)
    }

    @Test
    fun `island tap opens app when no active task and non-terminal mode`() {
        val result = shouldOpenAppWhenIslandTapped(
            hasActiveTask = false,
            mode = CapsuleMode.Hidden,
        )
        assertThat(result).isTrue()
    }

    @Test
    fun `island tap does not open app when done terminal mode`() {
        val result = shouldOpenAppWhenIslandTapped(
            hasActiveTask = false,
            mode = CapsuleMode.Done("Done"),
        )
        assertThat(result).isFalse()
    }

    @Test
    fun `derive visibility enforces mutual exclusion`() {
        val cases = listOf(
            deriveOverlayVisibility(
                platformMode = PlatformMode.VIRTUAL_DISPLAY,
                location = OverlayUserLocation.OTHER_APP,
                mode = CapsuleMode.Running("thinking"),
                hasActiveTask = true,
                showPreference = ShowPreference.CAPSULE,
            ),
            deriveOverlayVisibility(
                platformMode = PlatformMode.VIRTUAL_DISPLAY,
                location = OverlayUserLocation.OTHER_APP,
                mode = CapsuleMode.Running("thinking"),
                hasActiveTask = true,
                showPreference = ShowPreference.ISLAND,
            ),
            deriveOverlayVisibility(
                platformMode = PlatformMode.ACCESSIBILITY,
                location = OverlayUserLocation.OTHER_APP,
                mode = CapsuleMode.Running("thinking"),
                hasActiveTask = true,
                showPreference = ShowPreference.ISLAND,
            ),
        )

        cases.forEach { decision ->
            assertThat(decision.showCapsule && decision.showIsland).isFalse()
        }
    }

    @Test
    fun `derive visibility hides all overlays in main app`() {
        val a11y = deriveOverlayVisibility(
            platformMode = PlatformMode.ACCESSIBILITY,
            location = OverlayUserLocation.MAIN_APP,
            mode = CapsuleMode.Running("thinking"),
            hasActiveTask = true,
            showPreference = ShowPreference.CAPSULE,
        )
        val vd = deriveOverlayVisibility(
            platformMode = PlatformMode.VIRTUAL_DISPLAY,
            location = OverlayUserLocation.MAIN_APP,
            mode = CapsuleMode.Running("thinking"),
            hasActiveTask = true,
            showPreference = ShowPreference.CAPSULE,
        )

        assertThat(a11y.showCapsule).isFalse()
        assertThat(a11y.showIsland).isFalse()
        assertThat(a11y.showGlow).isFalse()
        assertThat(vd.showCapsule).isFalse()
        assertThat(vd.showIsland).isFalse()
        assertThat(vd.showGlow).isFalse()
    }

    @Test
    fun `derive visibility allows island in accessibility mode when preference is island`() {
        val decision = deriveOverlayVisibility(
            platformMode = PlatformMode.ACCESSIBILITY,
            location = OverlayUserLocation.OTHER_APP,
            mode = CapsuleMode.Running("thinking"),
            hasActiveTask = true,
            showPreference = ShowPreference.ISLAND,
        )

        assertThat(decision.showIsland).isTrue()
        assertThat(decision.showCapsule).isFalse()
    }

    @Test
    fun `derive visibility forces capsule in interactive accessibility modes even when preference is island`() {
        val modes = listOf(
            CapsuleMode.WaitingForInput(question = "q", callId = "1"),
            CapsuleMode.WaitingForAction(instruction = "do", callId = "1"),
            CapsuleMode.Error("error"),
        )
        modes.forEach { mode ->
            val decision = deriveOverlayVisibility(
                platformMode = PlatformMode.ACCESSIBILITY,
                location = OverlayUserLocation.OTHER_APP,
                mode = mode,
                hasActiveTask = mode !is CapsuleMode.Error,
                showPreference = ShowPreference.ISLAND,
            )
            assertThat(decision.normalizedShowPreference).isEqualTo(ShowPreference.CAPSULE)
            assertThat(decision.showCapsule).isTrue()
            assertThat(decision.showIsland).isFalse()
        }
    }

    @Test
    fun `derive visibility forces capsule in interactive vd modes even when preference is island`() {
        val modes = listOf(
            CapsuleMode.WaitingForInput(question = "q", callId = "1"),
            CapsuleMode.WaitingForAction(instruction = "do", callId = "1"),
            CapsuleMode.Error("error"),
        )
        modes.forEach { mode ->
            val decision = deriveOverlayVisibility(
                platformMode = PlatformMode.VIRTUAL_DISPLAY,
                location = OverlayUserLocation.OTHER_APP,
                mode = mode,
                hasActiveTask = mode !is CapsuleMode.Error,
                showPreference = ShowPreference.ISLAND,
            )
            assertThat(decision.normalizedShowPreference).isEqualTo(ShowPreference.CAPSULE)
            assertThat(decision.showCapsule).isTrue()
            assertThat(decision.showIsland).isFalse()
        }
    }

    @Test
    fun `derive visibility shows glow in vd when active and off main app`() {
        val decision = deriveOverlayVisibility(
            platformMode = PlatformMode.VIRTUAL_DISPLAY,
            location = OverlayUserLocation.OTHER_APP,
            mode = CapsuleMode.Running("thinking"),
            hasActiveTask = true,
            showPreference = ShowPreference.ISLAND,
        )

        assertThat(decision.showGlow).isTrue()
    }

    @Test
    fun `derive visibility hides glow in vd when task is inactive`() {
        val decision = deriveOverlayVisibility(
            platformMode = PlatformMode.VIRTUAL_DISPLAY,
            location = OverlayUserLocation.OTHER_APP,
            mode = CapsuleMode.Done("done"),
            hasActiveTask = false,
            showPreference = ShowPreference.ISLAND,
        )

        assertThat(decision.showGlow).isFalse()
    }

    @Test
    fun `derive visibility keeps glow for terminal a11y modes`() {
        val doneDecision = deriveOverlayVisibility(
            platformMode = PlatformMode.ACCESSIBILITY,
            location = OverlayUserLocation.OTHER_APP,
            mode = CapsuleMode.Done("done"),
            hasActiveTask = false,
            showPreference = ShowPreference.CAPSULE,
        )
        val errorDecision = deriveOverlayVisibility(
            platformMode = PlatformMode.ACCESSIBILITY,
            location = OverlayUserLocation.OTHER_APP,
            mode = CapsuleMode.Error("error"),
            hasActiveTask = false,
            showPreference = ShowPreference.CAPSULE,
        )

        assertThat(doneDecision.showGlow).isTrue()
        assertThat(errorDecision.showGlow).isTrue()
    }
}
