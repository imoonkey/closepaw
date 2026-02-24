package com.moonkey.androidagent.platform

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.model.Bounds
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
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

    // ===== matchesIntended unit tests =====

    private val baseBounds = Bounds(left = 0, top = 0, right = 100, bottom = 100)

    @Test
    fun `matchesIntended returns true when resourceId matches`() {
        val hint = SemanticTargetHint(
            resourceId = "show_roots", text = "", description = "",
            className = "ImageButton", bounds = baseBounds
        )
        val result = NodeActionPerformer.matchesIntended(
            foundId = "com.android.documentsui:id/show_roots",
            foundText = "", foundDesc = "", foundClass = "ImageButton", hint = hint
        )
        assertThat(result).isTrue()
    }

    @Test
    fun `matchesIntended returns true when text matches`() {
        val hint = SemanticTargetHint(
            resourceId = "", text = "Downloads", description = "",
            className = "TextView", bounds = baseBounds
        )
        val result = NodeActionPerformer.matchesIntended(
            foundId = "", foundText = "Downloads", foundDesc = "",
            foundClass = "TextView", hint = hint
        )
        assertThat(result).isTrue()
    }

    @Test
    fun `matchesIntended returns true when description matches`() {
        val hint = SemanticTargetHint(
            resourceId = "", text = "", description = "Show roots",
            className = "ImageButton", bounds = baseBounds
        )
        val result = NodeActionPerformer.matchesIntended(
            foundId = "", foundText = "", foundDesc = "Show roots",
            foundClass = "ImageButton", hint = hint
        )
        assertThat(result).isTrue()
    }

    @Test
    fun `matchesIntended returns false when none match`() {
        val hint = SemanticTargetHint(
            resourceId = "show_roots", text = "Show roots", description = "Show roots",
            className = "ImageButton", bounds = baseBounds
        )
        val result = NodeActionPerformer.matchesIntended(
            foundId = "com.android.documentsui:id/dir_row",
            foundText = "Downloads",
            foundDesc = "",
            foundClass = "LinearLayout",
            hint = hint
        )
        assertThat(result).isFalse()
    }

    @Test
    fun `matchesIntended returns true when hint has no identity fields`() {
        val hint = SemanticTargetHint(
            resourceId = "", text = "", description = "",
            className = "View", bounds = baseBounds
        )
        val result = NodeActionPerformer.matchesIntended(
            foundId = "anything", foundText = "anything",
            foundDesc = "anything", foundClass = "View", hint = hint
        )
        assertThat(result).isTrue()
    }

    // ===== Mismatch guard integration tests =====

    @Test
    fun `performNodeClickAt returns failure on hint mismatch`() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { AccessibilityNodeFinder.findClickableNodeAtLocation(root, 73, 191) } returns node
        every { node.viewIdResourceName } returns "com.android.documentsui:id/dir_row"
        every { node.text } returns "Downloads"
        every { node.contentDescription } returns null
        every { node.className } returns "android.widget.LinearLayout"
        every { node.getBoundsInScreen(any()) } answers {
            firstArg<Rect>().set(0, 128, 1080, 225)
        }

        val hint = SemanticTargetHint(
            resourceId = "show_roots", text = "", description = "Show roots",
            className = "ImageButton", bounds = Bounds(0, 128, 147, 254)
        )
        val performer = NodeActionPerformer(rootProvider = { root })
        val result = performer.performNodeClickAt(73, 191, hint)

        assertThat(result).isInstanceOf(ActionResult.Failure::class.java)
        assertThat((result as ActionResult.Failure).reason).contains("mismatch")
        verify(exactly = 0) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun `performNodeClickAt proceeds on hint match`() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { AccessibilityNodeFinder.findClickableNodeAtLocation(root, 73, 191) } returns node
        every { node.viewIdResourceName } returns "com.android.documentsui:id/show_roots"
        every { node.text } returns null
        every { node.contentDescription } returns "Show roots"
        every { node.className } returns "android.widget.ImageButton"
        every { node.getBoundsInScreen(any()) } answers {
            firstArg<Rect>().set(0, 128, 147, 254)
        }
        every { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true

        val hint = SemanticTargetHint(
            resourceId = "show_roots", text = "", description = "Show roots",
            className = "ImageButton", bounds = Bounds(0, 128, 147, 254)
        )
        val performer = NodeActionPerformer(rootProvider = { root })
        val result = performer.performNodeClickAt(73, 191, hint)

        assertThat(result).isEqualTo(ActionResult.Success("ACTION_CLICK at (73,191)"))
        verify(exactly = 1) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun `performNodeClickAt with null hint skips guard`() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { AccessibilityNodeFinder.findClickableNodeAtLocation(root, 10, 20) } returns node
        every { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true

        val performer = NodeActionPerformer(rootProvider = { root })
        val result = performer.performNodeClickAt(10, 20, hint = null)

        assertThat(result).isEqualTo(ActionResult.Success("ACTION_CLICK at (10,20)"))
        verify(exactly = 1) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }
}
