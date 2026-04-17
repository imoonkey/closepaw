package ai.closepaw.onboarding

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PermissionStateMonitorTest {

    private fun derive(
        accessibility: Boolean,
        overlay: Boolean,
        batteryIgnoring: Boolean,
        batteryWasDone: Boolean = true,
    ) = PermissionStateMonitor.deriveRepairModel(
        accessibilityEnabled = accessibility,
        overlayEnabled = overlay,
        batteryIgnoringOptimizations = batteryIgnoring,
        batteryWasDone = batteryWasDone,
    )

    @Test
    fun `all granted returns null`() {
        assertThat(derive(true, true, true)).isNull()
    }

    @Test
    fun `accessibility missing is reported`() {
        val model = derive(accessibility = false, overlay = true, batteryIgnoring = true)!!
        assertThat(model.accessibilityMissing).isTrue()
        assertThat(model.overlayMissing).isFalse()
        assertThat(model.batteryMissing).isFalse()
        assertThat(model.primaryIssue).isEqualTo("Accessibility service is disabled")
    }

    @Test
    fun `multiple missing permissions all reported`() {
        val model = derive(accessibility = false, overlay = false, batteryIgnoring = false)!!
        assertThat(model.accessibilityMissing).isTrue()
        assertThat(model.overlayMissing).isTrue()
        assertThat(model.batteryMissing).isTrue()
        assertThat(model.hasAnyIssue).isTrue()
        assertThat(model.primaryIssue).isEqualTo("Accessibility service is disabled")
    }

    @Test
    fun `battery ignored when not done during onboarding`() {
        assertThat(derive(true, true, batteryIgnoring = false, batteryWasDone = false)).isNull()
    }

    @Test
    fun `overlay missing primary issue when accessibility ok`() {
        val model = derive(accessibility = true, overlay = false, batteryIgnoring = true)!!
        assertThat(model.accessibilityMissing).isFalse()
        assertThat(model.overlayMissing).isTrue()
        assertThat(model.primaryIssue).isEqualTo("Overlay permission is revoked")
    }
}
