package com.moonkey.androidagent.platform.virtualdisplay

import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.SystemButtonType
import com.moonkey.androidagent.platform.UIAction
import kotlinx.coroutines.delay

/**
 * Input injector for a virtual display via Shizuku.
 *
 * Handles tap, long-press, swipe, and system button injection.
 * Encapsulates MotionEvent/KeyEvent construction and display targeting.
 */
class VirtualDisplayInputInjector(
    private val shizuku: ShizukuClient,
    private val displayIdProvider: () -> Int
) {
    companion object {
        private const val TAG = "VirtualDisplayInputInjector"
    }

    fun injectTap(x: Int, y: Int): ActionResult {
        val displayId = displayIdProvider()
        if (displayId == Display.INVALID_DISPLAY) {
            return ActionResult.Failure("Virtual display not started")
        }

        val downTime = SystemClock.uptimeMillis()
        val down = motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat())
        val up = motionEvent(
            downTime,
            downTime + 50,
            MotionEvent.ACTION_UP,
            x.toFloat(),
            y.toFloat()
        )

        val ok = shizuku.injectInputEvent(down) && shizuku.injectInputEvent(up)
        down.recycle()
        up.recycle()

        return if (ok) ActionResult.Success("Tap at ($x,$y)")
        else ActionResult.Failure("Tap inject failed at ($x,$y)")
    }

    suspend fun injectLongPress(x: Int, y: Int, durationMs: Long): ActionResult {
        val displayId = displayIdProvider()
        if (displayId == Display.INVALID_DISPLAY) {
            return ActionResult.Failure("Virtual display not started")
        }

        val downTime = SystemClock.uptimeMillis()
        val down = motionEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat())

        if (!shizuku.injectInputEvent(down)) {
            down.recycle()
            return ActionResult.Failure("Long press DOWN inject failed at ($x,$y)")
        }
        down.recycle()

        delay(durationMs)

        val up = motionEvent(
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            x.toFloat(),
            y.toFloat()
        )
        val ok = shizuku.injectInputEvent(up)
        up.recycle()

        return if (ok) {
            ActionResult.Success("Long press at ($x,$y) for ${durationMs}ms")
        } else {
            ActionResult.Failure("Long press UP inject failed at ($x,$y)")
        }
    }

    suspend fun injectSwipe(action: UIAction.Swipe): ActionResult {
        val displayId = displayIdProvider()
        if (displayId == Display.INVALID_DISPLAY) {
            return ActionResult.Failure("Virtual display not started")
        }

        val downTime = SystemClock.uptimeMillis()
        val steps = 20
        val stepMs = (action.durationMs / steps).coerceAtLeast(1)

        val down = motionEvent(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            action.startX.toFloat(),
            action.startY.toFloat()
        )
        if (!shizuku.injectInputEvent(down)) {
            down.recycle()
            return ActionResult.Failure("Swipe DOWN inject failed")
        }
        down.recycle()

        for (i in 1..steps) {
            delay(stepMs)
            val t = i.toFloat() / steps
            val x = action.startX + (action.endX - action.startX) * t
            val y = action.startY + (action.endY - action.startY) * t
            val move = motionEvent(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y)
            shizuku.injectInputEvent(move)
            move.recycle()
        }

        val up = motionEvent(
            downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            action.endX.toFloat(),
            action.endY.toFloat()
        )
        val ok = shizuku.injectInputEvent(up)
        up.recycle()

        return if (ok) {
            ActionResult.Success(
                "Swipe (${action.startX},${action.startY}) → (${action.endX},${action.endY})"
            )
        } else {
            ActionResult.Failure("Swipe UP inject failed")
        }
    }

    fun injectSystemButton(button: SystemButtonType): ActionResult {
        val displayId = displayIdProvider()
        if (displayId == Display.INVALID_DISPLAY) {
            return ActionResult.Failure("Virtual display not started")
        }

        val keyCode = when (button) {
            SystemButtonType.BACK -> KeyEvent.KEYCODE_BACK
            SystemButtonType.HOME -> KeyEvent.KEYCODE_HOME
            SystemButtonType.RECENTS -> KeyEvent.KEYCODE_APP_SWITCH
            SystemButtonType.ENTER -> KeyEvent.KEYCODE_ENTER
        }

        val now = SystemClock.uptimeMillis()
        val down = keyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode)
        val up = keyEvent(now, now + 10, KeyEvent.ACTION_UP, keyCode)

        val ok = shizuku.injectInputEvent(down) && shizuku.injectInputEvent(up)

        return if (ok) ActionResult.Success("System button: $button")
        else ActionResult.Failure("System button inject failed: $button")
    }

    private fun motionEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float
    ): MotionEvent {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        setDisplayId(event, displayIdProvider())
        return event
    }

    private fun keyEvent(downTime: Long, eventTime: Long, action: Int, keyCode: Int): KeyEvent {
        val event = KeyEvent(downTime, eventTime, action, keyCode, 0)
        setDisplayId(event, displayIdProvider())
        return event
    }

    /**
     * Set displayId on an InputEvent via reflection. InputEvent.setDisplayId(int) is @hide in AOSP.
     * HiddenApiBypass exemptions must be active (called in VirtualDisplayPlatform.start()).
     */
    private fun setDisplayId(event: android.view.InputEvent, id: Int) {
        try {
            val method = android.view.InputEvent::class.java.getMethod(
                "setDisplayId",
                Int::class.javaPrimitiveType
            )
            method.invoke(event, id)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set displayId=$id on ${event.javaClass.simpleName}", e)
        }
    }
}
