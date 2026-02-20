package com.moonkey.androidagent.platform

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.google.common.truth.Truth.assertThat
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NodeActionPerformerTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(AccessibilityNodeFinder)
    }

    @After
    fun tearDown() {
        unmockkObject(AccessibilityNodeFinder)
        clearAllMocks()
        Dispatchers.resetMain()
    }

    @Test
    fun `performNodeClickAt recycles root and target node`() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { AccessibilityNodeFinder.findClickableNodeAtLocation(root, 10, 20) } returns node
        every { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true

        val performer = NodeActionPerformer(rootProvider = { root })
        val result = performer.performNodeClickAt(10, 20)

        assertThat(result).isEqualTo(ActionResult.Success("ACTION_CLICK at (10,20)"))
        verify(exactly = 1) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 1) { node.recycle() }
        verify(exactly = 1) { root.recycle() }
    }

    @Test
    fun `performSetTextOnNodeAt clears then sets text and preserves focus`() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)

        every { AccessibilityNodeFinder.findNodeAtLocation(root, 1, 2) } returns node
        every { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any()) } returnsMany
                listOf(true, true)

        val performer = NodeActionPerformer(rootProvider = { root })
        val result = performer.performSetTextOnNodeAt(1, 2, "hello", clear = true)

        assertThat(result).isEqualTo(ActionResult.Success("Text entered: hello"))
        verify(exactly = 2) { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any()) }
        verify(exactly = 0) { node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS) }
        verify(exactly = 1) { node.recycle() }
        verify(exactly = 1) { root.recycle() }
    }

    @Test
    fun `performEnterKey falls back to click when IME action is unavailable`() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        val focusedNode = mockk<AccessibilityNodeInfo>(relaxed = true)

        every { AccessibilityNodeFinder.findFocusedEditableNode(root) } returns focusedNode
        every { focusedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true

        val performer =
                NodeActionPerformer(
                        rootProvider = { root },
                        sdkIntProvider = { Build.VERSION_CODES.R }
                )
        val result = performer.performEnterKey()

        assertThat(result).isEqualTo(ActionResult.Success("Enter key pressed (click fallback)"))
        verify(exactly = 1) { focusedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 1) { focusedNode.recycle() }
        verify(exactly = 1) { root.recycle() }
    }

    @Test
    fun `performNodeLongClickAt returns failure when root is unavailable`() = runTest {
        val performer = NodeActionPerformer(rootProvider = { null })

        val result = performer.performNodeLongClickAt(5, 6)

        assertThat(result).isEqualTo(ActionResult.Failure("No a11y root available"))
    }

    @Test
    fun `performNodeLongClickAt uses long-clickable target when available`() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        val longClickable = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { AccessibilityNodeFinder.findLongClickableNodeAtLocation(root, 5, 6) } returns longClickable
        every { longClickable.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) } returns true

        val performer = NodeActionPerformer(rootProvider = { root })
        val result = performer.performNodeLongClickAt(5, 6)

        assertThat(result).isEqualTo(ActionResult.Success("ACTION_LONG_CLICK at (5,6)"))
        verify(exactly = 1) { longClickable.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }
        verify(exactly = 0) { AccessibilityNodeFinder.findClickableNodeAtLocation(any(), any(), any()) }
        verify(exactly = 1) { longClickable.recycle() }
        verify(exactly = 1) { root.recycle() }
    }

    @Test
    fun `performNodeLongClickAt falls back to clickable target`() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        val clickable = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { AccessibilityNodeFinder.findLongClickableNodeAtLocation(root, 9, 10) } returns null
        every { AccessibilityNodeFinder.findClickableNodeAtLocation(root, 9, 10) } returns clickable
        every { clickable.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) } returns true

        val performer = NodeActionPerformer(rootProvider = { root })
        val result = performer.performNodeLongClickAt(9, 10)

        assertThat(result).isEqualTo(
                ActionResult.Success("ACTION_LONG_CLICK at (9,10) via clickable fallback")
        )
        verify(exactly = 1) { clickable.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }
        verify(exactly = 1) { clickable.recycle() }
        verify(exactly = 1) { root.recycle() }
    }
}
