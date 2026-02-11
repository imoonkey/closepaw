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
    fun `performSetTextOnNodeAt clears text then sets text and clears focus`() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)

        every { AccessibilityNodeFinder.findNodeAtLocation(root, 1, 2) } returns node
        every { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any()) } returnsMany
                listOf(true, true)
        every { node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS) } returns true

        val performer = NodeActionPerformer(rootProvider = { root })
        val result = performer.performSetTextOnNodeAt(1, 2, "hello", clear = true)

        assertThat(result).isEqualTo(ActionResult.Success("Text entered: hello"))
        verify(exactly = 2) { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any()) }
        verify(exactly = 1) { node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS) }
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
}
