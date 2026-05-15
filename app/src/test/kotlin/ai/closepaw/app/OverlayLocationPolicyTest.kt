package ai.closepaw.app

import android.view.Display
import com.google.common.truth.Truth.assertThat
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.overlay.model.CapsuleContext
import ai.closepaw.ui.overlay.model.CapsuleMode
import org.junit.Test

class OverlayLocationPolicyTest {

    @Test
    fun `resolve user location detects vd viewer`() {
        val location = resolveUserLocation(
            appPackage = "ai.closepaw",
            packageName = "ai.closepaw",
            className = "ai.closepaw.ui.viewer.VirtualDisplayViewerActivity",
        )
        assertThat(location).isEqualTo(OverlayUserLocation.VD_VIEWER)
    }

    @Test
    fun `resolve user location detects main app activity`() {
        val location = resolveUserLocation(
            appPackage = "ai.closepaw",
            packageName = "ai.closepaw",
            className = "ai.closepaw.app.MainActivity",
        )
        assertThat(location).isEqualTo(OverlayUserLocation.MAIN_APP)
    }

    @Test
    fun `resolve user location detects other app`() {
        val location = resolveUserLocation(
            appPackage = "ai.closepaw",
            packageName = "com.google.android.youtube",
            className = "com.google.android.apps.youtube.app.WatchWhileActivity",
        )
        assertThat(location).isEqualTo(OverlayUserLocation.OTHER_APP)
    }

    @Test
    fun `resolve user location detects other app even without Activity suffix`() {
        val location = resolveUserLocation(
            appPackage = "ai.closepaw",
            packageName = "com.android.settings",
            className = "com.android.settings.Settings",
        )
        assertThat(location).isEqualTo(OverlayUserLocation.OTHER_APP)
    }

    @Test
    fun `resolve user location ignores non-activity windows`() {
        val location = resolveUserLocation(
            appPackage = "ai.closepaw",
            packageName = "com.google.android.youtube",
            className = "android.widget.FrameLayout",
        )
        assertThat(location).isNull()
    }

    @Test
    fun `resolve user location ignores input method windows`() {
        val location = resolveUserLocation(
            appPackage = "ai.closepaw",
            packageName = "com.google.android.inputmethod.latin",
            className = "com.google.android.inputmethod.latin.LatinIME",
        )
        assertThat(location).isNull()
    }

    @Test
    fun `resolve user location ignores androidx library windows`() {
        val location = resolveUserLocation(
            appPackage = "ai.closepaw",
            packageName = "ai.closepaw",
            className = "androidx.compose.ui.platform.ComposeView",
        )
        assertThat(location).isNull()
    }

    @Test
    fun `resolve user location ignores non-default display windows`() {
        val location = resolveUserLocation(
            appPackage = "ai.closepaw",
            packageName = "com.google.android.youtube",
            className = "com.google.android.apps.youtube.app.watchwhile.MainActivity",
            displayId = 85,
        )
        assertThat(location).isNull()
    }

    @Test
    fun `resolve user location accepts default display windows`() {
        val location = resolveUserLocation(
            appPackage = "ai.closepaw",
            packageName = "com.google.android.youtube",
            className = "com.google.android.apps.youtube.app.watchwhile.MainActivity",
            displayId = Display.DEFAULT_DISPLAY,
        )
        assertThat(location).isEqualTo(OverlayUserLocation.OTHER_APP)
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
    fun `derive visibility shows capsule in vd main app for approval and input modes`() {
        val modes = listOf(
            CapsuleMode.WaitingForApproval(
                callId = "1",
                description = "open Settings",
                appLabel = "Settings",
                packageName = "com.android.settings",
                reason = "test"
            ),
            CapsuleMode.WaitingForInput(question = "q", callId = "1"),
            CapsuleMode.WaitingForAction(instruction = "do", callId = "1"),
            CapsuleMode.Error("error"),
        )
        modes.forEach { mode ->
            val decision = deriveOverlayVisibility(
                platformMode = PlatformMode.VIRTUAL_DISPLAY,
                location = OverlayUserLocation.MAIN_APP,
                mode = mode,
                hasActiveTask = mode !is CapsuleMode.Error,
                showPreference = ShowPreference.CAPSULE,
            )
            assertThat(decision.showCapsule).isTrue()
        }
    }

    @Test
    fun `derive visibility shows capsule in vd viewer for approval mode`() {
        val decision = deriveOverlayVisibility(
            platformMode = PlatformMode.VIRTUAL_DISPLAY,
            location = OverlayUserLocation.VD_VIEWER,
            mode = CapsuleMode.WaitingForApproval(
                callId = "1",
                description = "open Settings",
                appLabel = "Settings",
                packageName = "com.android.settings",
                reason = "test"
            ),
            hasActiveTask = true,
            showPreference = ShowPreference.CAPSULE,
        )
        assertThat(decision.showCapsule).isTrue()
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
    fun `derive visibility shows glow in vd when active and viewer is visible`() {
        val decision = deriveOverlayVisibility(
            platformMode = PlatformMode.VIRTUAL_DISPLAY,
            location = OverlayUserLocation.VD_VIEWER,
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
    fun `derive visibility hides glow in vd background even when task is active`() {
        val decision = deriveOverlayVisibility(
            platformMode = PlatformMode.VIRTUAL_DISPLAY,
            location = OverlayUserLocation.OTHER_APP,
            mode = CapsuleMode.Running("thinking"),
            hasActiveTask = true,
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

    @Test
    fun `should lock interaction in a11y running other app`() {
        val lock = shouldLockUserInteraction(
            platformMode = PlatformMode.ACCESSIBILITY,
            location = OverlayUserLocation.OTHER_APP,
            mode = CapsuleMode.Running("thinking"),
        )
        assertThat(lock).isTrue()
    }

    @Test
    fun `should unlock interaction in a11y takeover`() {
        val lock = shouldLockUserInteraction(
            platformMode = PlatformMode.ACCESSIBILITY,
            location = OverlayUserLocation.OTHER_APP,
            mode = CapsuleMode.Takeover("paused"),
        )
        assertThat(lock).isFalse()
    }

    @Test
    fun `should lock interaction in vd viewer running and unlock in takeover`() {
        val locked = shouldLockUserInteraction(
            platformMode = PlatformMode.VIRTUAL_DISPLAY,
            location = OverlayUserLocation.VD_VIEWER,
            mode = CapsuleMode.Running("thinking"),
        )
        val unlocked = shouldLockUserInteraction(
            platformMode = PlatformMode.VIRTUAL_DISPLAY,
            location = OverlayUserLocation.VD_VIEWER,
            mode = CapsuleMode.Takeover("paused"),
        )
        assertThat(locked).isTrue()
        assertThat(unlocked).isFalse()
    }

    // ── Capsule overlay touchability ──

    @Test
    fun `capsule overlay is not touchable when hidden`() {
        assertThat(shouldCapsuleOverlayBeTouchable(CapsuleMode.Hidden)).isFalse()
    }

    @Test
    fun `capsule overlay is touchable in all non-hidden modes`() {
        val modes = listOf(
            CapsuleMode.Running("thinking"),
            CapsuleMode.TakeoverPending("paused"),
            CapsuleMode.Takeover("paused"),
            CapsuleMode.WaitingForInput(question = "q", callId = "1"),
            CapsuleMode.WaitingForAction(instruction = "do", callId = "1"),
            CapsuleMode.Done("done"),
            CapsuleMode.Error("error"),
        )
        modes.forEach { mode ->
            assertThat(shouldCapsuleOverlayBeTouchable(mode))
                .isTrue()
        }
    }

    @Test
    fun `onMainAppHidden flips MAIN_APP to OTHER_APP`() {
        // Catches the race where the new foreground app's window-state event arrived
        // before MainActivity.onStop fired and was dropped by the isMainAppResumed guard.
        assertThat(resolveLocationOnMainAppHidden(OverlayUserLocation.MAIN_APP))
            .isEqualTo(OverlayUserLocation.OTHER_APP)
    }

    @Test
    fun `onMainAppHidden preserves VD_VIEWER`() {
        // Regression guard: VirtualDisplayViewerActivity.onStart calls onViewerOpened()
        // BEFORE MainActivity.onStop. Clobbering VD_VIEWER → OTHER_APP would lose the
        // edge glow on the first viewer entry until a second user action re-triggered
        // onViewerOpened.
        assertThat(resolveLocationOnMainAppHidden(OverlayUserLocation.VD_VIEWER))
            .isEqualTo(OverlayUserLocation.VD_VIEWER)
    }

    @Test
    fun `onMainAppHidden leaves OTHER_APP unchanged`() {
        assertThat(resolveLocationOnMainAppHidden(OverlayUserLocation.OTHER_APP))
            .isEqualTo(OverlayUserLocation.OTHER_APP)
    }

    @Test
    fun `viewer auto-finish fires when VD viewer goes idle (Hidden, no task)`() {
        assertThat(
            shouldFinishViewerOnIdle(
                platformMode = PlatformMode.VIRTUAL_DISPLAY,
                location = OverlayUserLocation.VD_VIEWER,
                mode = CapsuleMode.Hidden,
                hasActiveTask = false,
            )
        ).isTrue()
    }

    @Test
    fun `viewer auto-finish does NOT fire on Done (capsule still showing success)`() {
        assertThat(
            shouldFinishViewerOnIdle(
                platformMode = PlatformMode.VIRTUAL_DISPLAY,
                location = OverlayUserLocation.VD_VIEWER,
                mode = CapsuleMode.Done("done"),
                hasActiveTask = false,
            )
        ).isFalse()
    }

    @Test
    fun `viewer auto-finish does NOT fire on Error (user must dismiss)`() {
        assertThat(
            shouldFinishViewerOnIdle(
                platformMode = PlatformMode.VIRTUAL_DISPLAY,
                location = OverlayUserLocation.VD_VIEWER,
                mode = CapsuleMode.Error("oops"),
                hasActiveTask = false,
            )
        ).isFalse()
    }

    @Test
    fun `viewer auto-finish does NOT fire while a task is active`() {
        // Defensive: hasActiveTask=true with mode=Hidden shouldn't normally happen, but
        // guard so a transient state doesn't yank the viewer out from under the agent.
        assertThat(
            shouldFinishViewerOnIdle(
                platformMode = PlatformMode.VIRTUAL_DISPLAY,
                location = OverlayUserLocation.VD_VIEWER,
                mode = CapsuleMode.Hidden,
                hasActiveTask = true,
            )
        ).isFalse()
    }

    @Test
    fun `viewer auto-finish does NOT fire when user is in MAIN_APP`() {
        assertThat(
            shouldFinishViewerOnIdle(
                platformMode = PlatformMode.VIRTUAL_DISPLAY,
                location = OverlayUserLocation.MAIN_APP,
                mode = CapsuleMode.Hidden,
                hasActiveTask = false,
            )
        ).isFalse()
    }

    @Test
    fun `viewer auto-finish does NOT fire in accessibility platform mode`() {
        // No VD viewer to finish in A11y mode — guard so the rule never misfires.
        assertThat(
            shouldFinishViewerOnIdle(
                platformMode = PlatformMode.ACCESSIBILITY,
                location = OverlayUserLocation.VD_VIEWER,
                mode = CapsuleMode.Hidden,
                hasActiveTask = false,
            )
        ).isFalse()
    }
}
