package com.moonkey.androidagent.agent.cognition.policy

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.agent.cognition.context.LoopWarningSeverity
import com.moonkey.androidagent.agent.cognition.context.NavigationState
import com.moonkey.androidagent.model.Bounds
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenSnapshot
import org.junit.Test

class LoopDetectionPolicyTest {

    @Test
    fun `detect flags repeated screen signatures`() {
        val policy = LoopDetectionPolicy()
        var state = NavigationState()

        repeat(3) {
            state = state.advance(snapshot(label = "Inbox"), previousAction = null)
        }

        val result = policy.detect(state)

        assertThat(result.warning).isNotNull()
        assertThat(result.warning?.severity).isEqualTo(LoopWarningSeverity.CRITICAL)
        assertThat(result.escalation).isEqualTo(EscalationLevel.ADVISORY)
    }

    @Test
    fun `detect flags excessive scrolling`() {
        val policy = LoopDetectionPolicy()
        var state = NavigationState()

        repeat(5) { index ->
            state = state.advance(snapshot(label = "Inbox-$index"), previousAction = "scroll:up")
        }

        val result = policy.detect(state)

        assertThat(result.warning).isNotNull()
        assertThat(result.warning?.message).contains("consecutive scroll")
    }

    @Test
    fun `detect flags repeated identical action`() {
        val policy =
            LoopDetectionPolicy(
                LoopDetectionConfig(
                    repeatedScreenWindow = 4
                )
            )
        var state = NavigationState()

        repeat(3) { index ->
            state = state.advance(snapshot(label = "Screen-$index"), previousAction = "mobile_action:click")
        }

        val result = policy.detect(state)

        assertThat(result.warning).isNotNull()
        assertThat(result.warning?.severity).isEqualTo(LoopWarningSeverity.WARNING)
        assertThat(result.warning?.message).contains("Same action repeated")
    }

    @Test
    fun `detect treats near-identical screens as repeated`() {
        val policy =
            LoopDetectionPolicy(
                LoopDetectionConfig(
                    similarityThreshold = 0.80
                )
            )
        var state = NavigationState()

        state = state.advance(snapshot(label = "Inbox"), previousAction = null)
        state = state.advance(snapshot(label = "Inbox "), previousAction = null)
        state = state.advance(snapshot(label = "Inbox  "), previousAction = null)

        val result = policy.detect(state)

        assertThat(result.warning).isNotNull()
        assertThat(result.warning?.severity).isEqualTo(LoopWarningSeverity.CRITICAL)
    }

    @Test
    fun `escalation reaches BLOCK after consecutiveLoopTurns threshold`() {
        val policy = LoopDetectionPolicy(
            LoopDetectionConfig(blockEscalationThreshold = 2)
        )
        var state = NavigationState()

        // Build up 3 identical screens to trigger CRITICAL warning
        repeat(3) {
            state = state.advance(snapshot(label = "Stuck"), previousAction = null)
        }
        // Simulate that 1 consecutive CRITICAL loop turn has already occurred.
        // Current turn should be the second and trigger BLOCK.
        state = state.copy(consecutiveLoopTurns = 1)

        val result = policy.detect(state)

        assertThat(result.warning).isNotNull()
        assertThat(result.escalation).isEqualTo(EscalationLevel.BLOCK)
    }

    @Test
    fun `escalation reaches FORCE_COMPLETE after high consecutiveLoopTurns`() {
        val policy = LoopDetectionPolicy(
            LoopDetectionConfig(forceCompleteEscalationThreshold = 5)
        )
        var state = NavigationState()

        repeat(3) {
            state = state.advance(snapshot(label = "Stuck"), previousAction = null)
        }
        // Current turn is included in escalation computation.
        state = state.copy(consecutiveLoopTurns = 4)

        val result = policy.detect(state)

        assertThat(result.warning).isNotNull()
        assertThat(result.escalation).isEqualTo(EscalationLevel.FORCE_COMPLETE)
    }

    @Test
    fun `no escalation when no warning detected`() {
        val policy = LoopDetectionPolicy()
        val state = NavigationState()

        val result = policy.detect(state)

        assertThat(result.warning).isNull()
        assertThat(result.escalation).isEqualTo(EscalationLevel.NONE)
    }

    @Test
    fun `first critical turn remains advisory before block threshold`() {
        val policy = LoopDetectionPolicy(
            LoopDetectionConfig(blockEscalationThreshold = 2)
        )
        var state = NavigationState()
        repeat(3) {
            state = state.advance(snapshot(label = "Stuck"), previousAction = null)
        }
        state = state.copy(consecutiveLoopTurns = 0)

        val result = policy.detect(state)

        assertThat(result.warning).isNotNull()
        assertThat(result.warning?.severity).isEqualTo(LoopWarningSeverity.CRITICAL)
        assertThat(result.escalation).isEqualTo(EscalationLevel.ADVISORY)
    }

    @Test
    fun `warning severity does not escalate even with high prior loop turns`() {
        val policy = LoopDetectionPolicy(
            LoopDetectionConfig(
                cycleMinOccurrences = 99,
                repeatedScreenWindow = 99,
                repeatedActionWindow = 3
            )
        )
        var state = NavigationState()
        repeat(3) { index ->
            state = state.advance(
                snapshot = snapshot(label = "Screen-$index"),
                previousAction = "mobile_action:click"
            )
        }
        state = state.copy(consecutiveLoopTurns = 10)

        val result = policy.detect(state)

        assertThat(result.warning).isNotNull()
        assertThat(result.warning?.severity).isEqualTo(LoopWarningSeverity.WARNING)
        assertThat(result.escalation).isEqualTo(EscalationLevel.ADVISORY)
    }

    @Test
    fun `cycle detection downgrades to WARNING when content changes between visits`() {
        // cycleMinOccurrences=3 needs 3 screens matching at Jaccard >= 0.75.
        // With N shared tokens + 1 varying: Jaccard = N/(N+2). Need N>=6 for >= 0.75.
        val policy = LoopDetectionPolicy(LoopDetectionConfig(toolRepetitionThreshold = 99))
        var state = NavigationState()

        // 3 screens with 7 shared elements + 1 varying → Jaccard = 7/9 ≈ 0.78 ≥ 0.75
        state = state.advance(snapshotWithProgress(7, "Item A"), previousAction = null)
        state = state.advance(snapshotWithProgress(7, "Item B"), previousAction = "mobile_action:click")
        state = state.advance(snapshotWithProgress(7, "Item C"), previousAction = "mobile_action:click")

        val result = policy.detect(state)

        // Cycle fires (3 matches at >= 0.75) but progress gate detects content change → WARNING
        assertThat(result.warning).isNotNull()
        assertThat(result.warning?.severity).isEqualTo(LoopWarningSeverity.WARNING)
        assertThat(result.escalation).isEqualTo(EscalationLevel.ADVISORY)
    }

    @Test
    fun `cycle detection stays CRITICAL when same screen repeats with no content change`() {
        val policy = LoopDetectionPolicy()
        var state = NavigationState()

        repeat(4) {
            state = state.advance(snapshot(label = "Stuck"), previousAction = null)
        }

        val result = policy.detect(state)

        assertThat(result.warning).isNotNull()
        assertThat(result.warning?.severity).isEqualTo(LoopWarningSeverity.CRITICAL)
    }

    @Test
    fun `cycle detection downgrades with progress even at high consecutive loop turns`() {
        val policy = LoopDetectionPolicy(LoopDetectionConfig(
            cycleMinOccurrences = 3,
            toolRepetitionThreshold = 99
        ))
        var state = NavigationState()

        // 3 screens with same layout but different content
        state = state.advance(snapshotWithProgress(7, "Item A"), previousAction = null)
        state = state.advance(snapshotWithProgress(7, "Item B"), previousAction = "mobile_action:click")
        state = state.advance(snapshotWithProgress(7, "Item C"), previousAction = "mobile_action:click")
        state = state.copy(consecutiveLoopTurns = 10)

        val result = policy.detect(state)

        assertThat(result.warning).isNotNull()
        assertThat(result.warning?.severity).isEqualTo(LoopWarningSeverity.WARNING)
        // Even with high consecutiveLoopTurns, WARNING stays ADVISORY
        assertThat(result.escalation).isEqualTo(EscalationLevel.ADVISORY)
    }

    @Test
    fun `stable screen downgrades to WARNING when content changes`() {
        // isStable needs pairwise Jaccard >= 0.85 across window.
        // With N shared tokens + 1 varying: Jaccard = N/(N+2). Need N>=12 for >= 0.85.
        val policy = LoopDetectionPolicy(LoopDetectionConfig(
            repeatedScreenWindow = 3,
            cycleMinOccurrences = 99,  // suppress cycle check
            toolRepetitionThreshold = 99
        ))
        var state = NavigationState()

        state = state.advance(snapshotWithProgress(13, "Detail A"), previousAction = null)
        state = state.advance(snapshotWithProgress(13, "Detail B"), previousAction = "mobile_action:click")
        state = state.advance(snapshotWithProgress(13, "Detail C"), previousAction = "mobile_action:click")

        val result = policy.detect(state)

        // isStable fires (pairwise sim >= 0.85) but progress gate detects content change → WARNING
        assertThat(result.warning).isNotNull()
        assertThat(result.warning?.severity).isEqualTo(LoopWarningSeverity.WARNING)
        assertThat(result.escalation).isEqualTo(EscalationLevel.ADVISORY)
    }

    private fun snapshot(label: String): ScreenSnapshot {
        return ScreenSnapshot(
            timestamp = System.currentTimeMillis(),
            elements =
                listOf(
                    PerceptionElement(
                        index = 0,
                        text = label,
                        resourceId = "com.test:id/title",
                        className = "TextView",
                        description = label,
                        isClickable = true,
                        isEditable = false,
                        isScrollable = false,
                        isEnabled = true,
                        isFocused = false,
                        isLongClickable = false,
                        bounds = Bounds(left = 0, top = 0, right = 100, bottom = 40),
                        center = Point(x = 50, y = 20)
                    )
                )
        )
    }

    /**
     * Snapshot with [sharedCount] stable elements + 1 varying element.
     * Jaccard between two snapshots = sharedCount / (sharedCount + 2).
     * Need sharedCount >= 6 for cycle (>= 0.75), sharedCount >= 12 for isStable (>= 0.85).
     */
    private fun snapshotWithProgress(sharedCount: Int, varyingLabel: String): ScreenSnapshot {
        val sharedElements = (0 until sharedCount).map { i ->
            PerceptionElement(
                index = i,
                text = "Shared $i",
                resourceId = "com.test:id/shared_$i",
                className = "TextView",
                description = "Shared $i",
                isClickable = i % 2 == 0,
                isEditable = false,
                isScrollable = false,
                isEnabled = true,
                isFocused = false,
                isLongClickable = false,
                bounds = Bounds(left = 0, top = i * 40, right = 100, bottom = (i + 1) * 40),
                center = Point(x = 50, y = i * 40 + 20)
            )
        }
        val varyingElement = PerceptionElement(
            index = sharedCount,
            text = varyingLabel,
            resourceId = "com.test:id/item",
            className = "TextView",
            description = varyingLabel,
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isEnabled = true,
            isFocused = false,
            isLongClickable = false,
            bounds = Bounds(left = 0, top = sharedCount * 40, right = 100, bottom = (sharedCount + 1) * 40),
            center = Point(x = 50, y = sharedCount * 40 + 20)
        )
        return ScreenSnapshot(
            timestamp = System.currentTimeMillis(),
            elements = sharedElements + varyingElement
        )
    }
}
