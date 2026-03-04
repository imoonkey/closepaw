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
        every { node.refresh() } returns true
        every { node.text } returns "hello"

        val performer = NodeActionPerformer(rootProvider = { root })
        val result = performer.performSetTextOnNodeAt(1, 2, "hello", clear = true)

        assertThat(result).isEqualTo(ActionResult.Success("Text entered: hello"))
        verify(exactly = 2) { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any()) }
        verify(exactly = 0) { node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS) }
        verify(exactly = 1) { node.recycle() }
        verify(exactly = 1) { root.recycle() }
    }

    @Test
    fun `performSetTextOnNodeAt returns failure when value did not change`() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)

        every { AccessibilityNodeFinder.findNodeAtLocation(root, 5, 10) } returns node
        every { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any()) } returns true
        every { node.refresh() } returns true
        // Simulate NumberPicker: setText reports success but value doesn't change
        every { node.text } returns "22"

        val performer = NodeActionPerformer(rootProvider = { root })
        val result = performer.performSetTextOnNodeAt(5, 10, "27", clear = false)

        assertThat(result).isInstanceOf(ActionResult.Failure::class.java)
        assertThat((result as ActionResult.Failure).reason).contains("value did not change")
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

    @Test
    fun `matchesIntended returns false when resourceId is only substring match`() {
        val hint = SemanticTargetHint(
            resourceId = "show_roots", text = "", description = "",
            className = "", bounds = baseBounds
        )
        val result = NodeActionPerformer.matchesIntended(
            foundId = "com.android.documentsui:id/show_roots_container",
            foundText = "", foundDesc = "", foundClass = "", hint = hint
        )
        assertThat(result).isFalse()
    }

    @Test
    fun `matchesIntended returns true when found node has no labels (container node)`() {
        // Container nodes (CardView, LinearLayout) have no text/desc — their text
        // comes from child TextViews aggregated by the Perceptor.
        val hint = SemanticTargetHint(
            resourceId = "", text = "task.html | 2.23 kB | 18:54", description = "",
            className = "CardView", bounds = baseBounds
        )
        val result = NodeActionPerformer.matchesIntended(
            foundId = "", foundText = "", foundDesc = "",
            foundClass = "androidx.cardview.widget.CardView", hint = hint
        )
        assertThat(result).isTrue()
    }

    @Test
    fun `matchesIntended returns false when found node has different text`() {
        val hint = SemanticTargetHint(
            resourceId = "", text = "Show roots", description = "",
            className = "", bounds = baseBounds
        )
        val result = NodeActionPerformer.matchesIntended(
            foundId = "", foundText = "Downloads", foundDesc = "",
            foundClass = "", hint = hint
        )
        assertThat(result).isFalse()
    }

    @Test
    fun `matchesIntended returns false when className does not match`() {
        val hint = SemanticTargetHint(
            resourceId = "", text = "Show roots", description = "",
            className = "ImageButton", bounds = baseBounds
        )
        val result = NodeActionPerformer.matchesIntended(
            foundId = "", foundText = "Show roots", foundDesc = "",
            foundClass = "android.widget.LinearLayout", hint = hint
        )
        assertThat(result).isFalse()
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
    fun `performNodeClickAt proceeds when bounds match despite label mismatch`() = runTest {
        // Container nodes (CardView, LinearLayout) have no text — Perceptor aggregates
        // child text. Bounds match confirms same node, so click should proceed.
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { AccessibilityNodeFinder.findClickableNodeAtLocation(root, 296, 978) } returns node
        every { node.viewIdResourceName } returns null
        every { node.text } returns null
        every { node.contentDescription } returns null
        every { node.className } returns "androidx.cardview.widget.CardView"
        every { node.getBoundsInScreen(any()) } answers {
            firstArg<Rect>().set(64, 678, 529, 1279)
        }
        every { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true

        val hint = SemanticTargetHint(
            resourceId = "", text = "task.html | 2.23 kB | 18:54", description = "",
            className = "CardView", bounds = Bounds(64, 678, 529, 1279)
        )
        val performer = NodeActionPerformer(rootProvider = { root })
        val result = performer.performNodeClickAt(296, 978, hint)

        assertThat(result).isEqualTo(ActionResult.Success("ACTION_CLICK at (296,978)"))
        verify(exactly = 1) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    // ===== Hint text contamination tests (P1) =====

    @Test
    fun `performSetTextOnNodeAt clear=false with hint text treats existing as empty`() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)

        every { AccessibilityNodeFinder.findNodeAtLocation(root, 100, 200) } returns node
        // Field is showing hint text "Playlist name" — should be treated as empty
        every { node.isShowingHintText } returns true
        every { node.text } returnsMany listOf(
            "Playlist name",         // cursor positioning read (existing skipped due to hint)
            "Ultimate Fails Series"  // after refresh: post-action verification
        )
        every { node.hintText } returns "Playlist name"
        every { node.textSelectionStart } returns -1
        every { node.textSelectionEnd } returns -1
        every { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any()) } returns true
        every { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, any()) } returns true
        every { node.refresh() } returns true

        val performer = NodeActionPerformer(rootProvider = { root })
        val result = performer.performSetTextOnNodeAt(100, 200, "Ultimate Fails Series", clear = false)

        // With the fix, existing="" so combined="Ultimate Fails Series" — matches post-verification
        assertThat(result).isEqualTo(ActionResult.Success("Text entered: Ultimate Fails Series"))
    }

    @Test
    fun `performSetTextOnNodeAt clear=false with real text preserves existing`() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)

        every { AccessibilityNodeFinder.findNodeAtLocation(root, 100, 200) } returns node
        every { node.isShowingHintText } returns false
        every { node.text } returnsMany listOf(
            "Hello ",          // first read: existing text
            "Hello World",     // cursor positioning read
            "Hello World"      // after refresh: post-action verification
        )
        every { node.textSelectionStart } returns 6  // cursor at end of "Hello "
        every { node.textSelectionEnd } returns 6
        every { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any()) } returns true
        every { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, any()) } returns true
        every { node.refresh() } returns true

        val performer = NodeActionPerformer(rootProvider = { root })
        val result = performer.performSetTextOnNodeAt(100, 200, "World", clear = false)

        assertThat(result).isEqualTo(ActionResult.Success("Text entered: World"))
    }

    @Test
    fun `performSetTextOnNodeAt clear=true with hint text works normally`() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)

        every { AccessibilityNodeFinder.findNodeAtLocation(root, 100, 200) } returns node
        every { node.isShowingHintText } returns true
        // clear=true: node.text is only read at post-verification (after refresh)
        every { node.text } returns "My Playlist"
        every { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any()) } returns true
        every { node.refresh() } returns true

        val performer = NodeActionPerformer(rootProvider = { root })
        val result = performer.performSetTextOnNodeAt(100, 200, "My Playlist", clear = true)

        assertThat(result).isEqualTo(ActionResult.Success("Text entered: My Playlist"))
    }

    @Test
    fun `performSetTextOnNodeAt clear=false empty field no hint`() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)

        every { AccessibilityNodeFinder.findNodeAtLocation(root, 100, 200) } returns node
        every { node.isShowingHintText } returns false
        every { node.text } returnsMany listOf(
            "",              // first read: empty field
            "typed text",    // cursor positioning read
            "typed text"     // after refresh: verification
        )
        every { node.textSelectionStart } returns -1
        every { node.textSelectionEnd } returns -1
        every { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any()) } returns true
        every { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, any()) } returns true
        every { node.refresh() } returns true

        val performer = NodeActionPerformer(rootProvider = { root })
        val result = performer.performSetTextOnNodeAt(100, 200, "typed text", clear = false)

        assertThat(result).isEqualTo(ActionResult.Success("Text entered: typed text"))
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
