package com.moonkey.androidagent.onboarding

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionStateMonitorTest {

    private fun monitor(
        accessibility: Boolean,
        overlay: Boolean,
        battery: Boolean,
    ): PermissionStateMonitor {
        val context = mockk<Context>(relaxed = true)
        val m = spyk(PermissionStateMonitor(context))
        every { m.isAccessibilityEnabled() } returns accessibility
        every { m.isOverlayEnabled() } returns overlay
        every { m.isBatteryOptimized() } returns battery
        return m
    }

    @Test
    fun `all granted returns null`() {
        val m = monitor(accessibility = true, overlay = true, battery = true)
        assertNull(m.deriveRepairModel(batteryWasDone = true))
    }

    @Test
    fun `accessibility missing is reported`() {
        val m = monitor(accessibility = false, overlay = true, battery = true)
        val model = m.deriveRepairModel(batteryWasDone = true)!!
        assertTrue(model.accessibilityMissing)
        assertFalse(model.overlayMissing)
        assertFalse(model.batteryMissing)
        assertEquals("Accessibility service is disabled", model.primaryIssue)
    }

    @Test
    fun `multiple missing permissions all reported`() {
        val m = monitor(accessibility = false, overlay = false, battery = false)
        val model = m.deriveRepairModel(batteryWasDone = true)!!
        assertTrue(model.accessibilityMissing)
        assertTrue(model.overlayMissing)
        assertTrue(model.batteryMissing)
        assertTrue(model.hasAnyIssue)
        assertEquals("Accessibility service is disabled", model.primaryIssue)
    }

    @Test
    fun `battery ignored when not done during onboarding`() {
        val m = monitor(accessibility = true, overlay = true, battery = false)
        assertNull(m.deriveRepairModel(batteryWasDone = false))
    }

    @Test
    fun `overlay missing primary issue when accessibility ok`() {
        val m = monitor(accessibility = true, overlay = false, battery = true)
        val model = m.deriveRepairModel(batteryWasDone = true)!!
        assertFalse(model.accessibilityMissing)
        assertTrue(model.overlayMissing)
        assertEquals("Overlay permission is revoked", model.primaryIssue)
    }
}
