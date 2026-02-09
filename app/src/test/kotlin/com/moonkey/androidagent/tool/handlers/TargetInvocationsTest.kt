package com.moonkey.androidagent.tool.handlers

import com.google.common.truth.Truth.assertThat
import com.moonkey.androidagent.model.Bounds
import com.moonkey.androidagent.model.PerceptionElement
import com.moonkey.androidagent.model.Point
import com.moonkey.androidagent.model.ScreenSnapshot
import com.moonkey.androidagent.platform.ActionResult
import com.moonkey.androidagent.platform.AndroidPlatform
import com.moonkey.androidagent.platform.AppInfo
import com.moonkey.androidagent.platform.DisplayInfo
import com.moonkey.androidagent.platform.UIAction
import com.moonkey.androidagent.tool.ToolExecutionContext
import com.moonkey.androidagent.tool.ToolExecutionResult
import com.moonkey.androidagent.tool.ValidationResult
import com.moonkey.androidagent.tool.impl.mobileaction.SwipeActionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TargetInvocationsTest {

    @Test
    fun `click supports coordinates even when snapshot is null`() = runTest {
        val platform = RecordingAndroidPlatform(results = listOf(ActionResult.Success("ok")))
        val context = TestToolExecutionContext(platform = platform, snapshot = null)
        val params = JSONObject().apply {
            put("x", 12)
            put("y", 34)
        }

        val result = ClickTargetInvocation(params = params, description = "click").execute(context)

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        assertThat(platform.performedActions).containsExactly(UIAction.ClickNodeAt(12, 34))
    }

    @Test
    fun `click with text-only selector resolves and executes click plan`() = runTest {
        val snapshot = ScreenSnapshot(
            timestamp = 0L,
            elements = listOf(
                element(
                    index = 7,
                    resourceId = "com.app:id/confirm",
                    text = "Confirm",
                    bounds = Bounds(left = 80, top = 120, right = 160, bottom = 200),
                    center = Point(x = 120, y = 160)
                )
            )
        )
        val changedSnapshot = snapshot.copy(
            timestamp = 1L,
            elements = listOf(
                element(
                    index = 7,
                    resourceId = "com.app:id/confirm",
                    text = "Confirmed",
                    bounds = Bounds(left = 80, top = 120, right = 160, bottom = 200),
                    center = Point(x = 120, y = 160)
                )
            )
        )

        val platform = RecordingAndroidPlatform(
            results = listOf(ActionResult.Success("action_click ok")),
            capturedSnapshots = listOf(changedSnapshot)
        )
        val context = TestToolExecutionContext(platform = platform, snapshot = snapshot)
        val params = JSONObject().apply {
            put("text", "Confirm")
            put("text_index", 0)
        }

        val result = ClickTargetInvocation(params = params, description = "click").execute(context)

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        assertThat(platform.performedActions).containsExactly(UIAction.ClickNodeAt(120, 160))
    }

    @Test
    fun `click element index uses element index lookup not list position`() = runTest {
        val snapshot = ScreenSnapshot(
            timestamp = 0L,
            elements = listOf(
                element(index = 0, resourceId = "com.app:id/zero"),
                element(index = 3, resourceId = "com.app:id/three"),
                element(index = 7, resourceId = "com.app:id/seven")
            )
        )

        val platform = RecordingAndroidPlatform(results = emptyList())
        val context = TestToolExecutionContext(platform = platform, snapshot = snapshot)
        val params = JSONObject().apply {
            put("element_index", 1)
        }

        val result = ClickTargetInvocation(params = params, description = "click").execute(context)

        assertThat(result).isInstanceOf(ToolExecutionResult.Failure::class.java)
        assertThat(platform.performedActions).isEmpty()
    }

    @Test
    fun `click attempts selectors in fallback order`() = runTest {
        val snapshot = ScreenSnapshot(
            timestamp = 0L,
            elements = listOf(
                element(
                    index = 0,
                    resourceId = "com.app:id/one",
                    text = "First",
                    bounds = Bounds(left = 0, top = 0, right = 40, bottom = 40),
                    center = Point(x = 20, y = 20)
                ),
                element(
                    index = 1,
                    resourceId = "com.app:id/two",
                    text = "Second",
                    bounds = Bounds(left = 50, top = 50, right = 90, bottom = 90),
                    center = Point(x = 70, y = 70)
                ),
                element(
                    index = 2,
                    resourceId = "com.app:id/three",
                    text = "Third",
                    bounds = Bounds(left = 100, top = 100, right = 160, bottom = 160),
                    center = Point(x = 130, y = 130)
                )
            )
        )

        val platform = RecordingAndroidPlatform(
            results = listOf(
                ActionResult.Failure("fail 1"),
                ActionResult.Failure("fail 2"),
                ActionResult.Failure("fail 3"),
                ActionResult.Failure("fail 4"),
                ActionResult.Failure("fail 5"),
                ActionResult.Failure("fail 6")
            )
        )
        val context = TestToolExecutionContext(platform = platform, snapshot = snapshot)

        val params = JSONObject().apply {
            put("x", 5)
            put("y", 6)
            put("text", "Third")
            put("text_index", 0)
            put("element_index", 0)
        }

        ClickTargetInvocation(params = params, description = "click").execute(context)

        assertThat(platform.performedActions).containsExactly(
            UIAction.ClickNodeAt(20, 20),
            UIAction.TapAt(20, 20),
            UIAction.ClickNodeAt(130, 130),
            UIAction.TapAt(130, 130),
            UIAction.ClickNodeAt(5, 6),
            UIAction.TapAt(5, 6)
        ).inOrder()
    }

    @Test
    fun `click retries after unchanged screen and succeeds after later change`() = runTest {
        val preSnapshot = ScreenSnapshot(
            timestamp = 0L,
            elements = listOf(
                element(
                    index = 0,
                    resourceId = "com.app:id/submit",
                    text = "Submit",
                    bounds = Bounds(left = 30, top = 30, right = 60, bottom = 60),
                    center = Point(x = 45, y = 45)
                )
            )
        )
        val changedSnapshot = preSnapshot.copy(
            timestamp = 1L,
            elements = listOf(
                element(
                    index = 0,
                    resourceId = "com.app:id/submit",
                    text = "Submitting...",
                    bounds = Bounds(left = 30, top = 30, right = 60, bottom = 60),
                    center = Point(x = 45, y = 45)
                )
            )
        )

        val platform = RecordingAndroidPlatform(
            results = listOf(
                ActionResult.Success("action_click ok"),
                ActionResult.Success("tap ok")
            ),
            capturedSnapshots = listOf(preSnapshot, changedSnapshot)
        )
        val context = TestToolExecutionContext(platform = platform, snapshot = preSnapshot)
        val params = JSONObject().apply {
            put("element_index", 0)
        }

        val result = ClickTargetInvocation(params = params, description = "click").execute(context)

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        assertThat(platform.performedActions).containsExactly(
            UIAction.ClickNodeAt(45, 45),
            UIAction.TapAt(45, 45)
        ).inOrder()
    }

    @Test
    fun `click fails when all successful dispatches produce unchanged screen`() = runTest {
        val preSnapshot = ScreenSnapshot(
            timestamp = 0L,
            elements = listOf(
                element(
                    index = 0,
                    resourceId = "com.app:id/submit",
                    text = "Submit",
                    bounds = Bounds(left = 30, top = 30, right = 60, bottom = 60),
                    center = Point(x = 45, y = 45)
                )
            )
        )

        val platform = RecordingAndroidPlatform(
            results = listOf(
                ActionResult.Success("action_click ok"),
                ActionResult.Success("tap ok")
            ),
            capturedSnapshots = listOf(preSnapshot, preSnapshot)
        )
        val context = TestToolExecutionContext(platform = platform, snapshot = preSnapshot)
        val params = JSONObject().apply {
            put("x", 45)
            put("y", 45)
        }

        val result = ClickTargetInvocation(params = params, description = "click").execute(context)

        assertThat(result).isInstanceOf(ToolExecutionResult.Failure::class.java)
        assertThat(platform.performedActions).containsExactly(
            UIAction.ClickNodeAt(45, 45),
            UIAction.TapAt(45, 45)
        ).inOrder()
    }

    @Test
    fun `type uses text selector`() = runTest {
        val snapshot = ScreenSnapshot(
            timestamp = 0L,
            elements = listOf(
                element(index = 0, resourceId = "com.app:id/input", text = "Email"),
                element(index = 1, resourceId = "com.app:id/search", text = "Search")
            )
        )

        val platform = RecordingAndroidPlatform(results = listOf(ActionResult.Success("typed")))
        val context = TestToolExecutionContext(platform = platform, snapshot = snapshot)

        val params = JSONObject().apply {
            put("input_text", "hello")
            put("text", "Search")
            put("text_index", 0)
        }

        val result = TypeTargetInvocation(params = params, description = "type").execute(context)

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        assertThat(platform.performedActions).containsExactly(
            UIAction.Type(text = "hello", elementIndex = 1, clear = false)
        )
    }

    @Test
    fun `long press requires ui change when snapshot is available`() = runTest {
        val snapshot = ScreenSnapshot(
            timestamp = 0L,
            elements = listOf(
                element(
                    index = 0,
                    resourceId = "com.app:id/item",
                    text = "Item",
                    bounds = Bounds(left = 10, top = 10, right = 50, bottom = 50),
                    center = Point(x = 30, y = 30)
                )
            )
        )

        val platform = RecordingAndroidPlatform(
            results = listOf(ActionResult.Success("long click ok")),
            capturedSnapshots = listOf(snapshot)
        )
        val context = TestToolExecutionContext(platform = platform, snapshot = snapshot)
        val params = JSONObject().apply {
            put("element_index", 0)
        }

        val result = LongPressTargetInvocation(params = params, description = "long press").execute(context)

        assertThat(result).isInstanceOf(ToolExecutionResult.Failure::class.java)
    }

    @Test
    fun `swipe direction computes vertical motion`() = runTest {
        val platform = RecordingAndroidPlatform(results = listOf(ActionResult.Success("ok")))
        val context = TestToolExecutionContext(platform = platform, snapshot = null)
        val params = JSONObject().apply {
            put("direction", "down")
            put("distance", "medium")
        }

        val result = SwipeTargetInvocation(params = params, description = "swipe").execute(context)

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        val action = platform.performedActions.single() as UIAction.Swipe
        // "swipe down" = finger moves down = start high (smaller Y), end low (larger Y)
        assertThat(action.startY).isLessThan(action.endY)
    }

    @Test
    fun `swipe validation rejects direction with start end`() {
        val handler = SwipeActionHandler()
        val params = JSONObject().apply {
            put("direction", "down")
            put("start", JSONArray(listOf(0, 0)))
            put("end", JSONArray(listOf(0, 100)))
        }

        val result = handler.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    private class TestToolExecutionContext(
        override val platform: AndroidPlatform,
        private val snapshot: ScreenSnapshot?
    ) : ToolExecutionContext {
        override val currentSnapshot: ScreenSnapshot? = snapshot
        override fun isCancelled(): Boolean = false
    }

    private class RecordingAndroidPlatform(
        results: List<ActionResult>,
        capturedSnapshots: List<ScreenSnapshot> = emptyList()
    ) : AndroidPlatform {
        private val remainingResults = ArrayDeque(results)
        private val remainingSnapshots = ArrayDeque(capturedSnapshots)

        val performedActions = mutableListOf<UIAction>()

        override suspend fun captureScreen(): ScreenSnapshot {
            return if (remainingSnapshots.isNotEmpty()) {
                remainingSnapshots.removeFirst()
            } else {
                ScreenSnapshot(timestamp = 0L, elements = emptyList())
            }
        }

        override suspend fun performAction(action: UIAction, snapshot: ScreenSnapshot?): ActionResult {
            performedActions.add(action)
            return if (remainingResults.isEmpty()) {
                ActionResult.Success("ok")
            } else {
                remainingResults.removeFirst()
            }
        }

        override fun hasRequiredPermissions(): Boolean = true

        override fun getCurrentPackageName(): String? = "com.example"

        override fun getDisplayInfo(): DisplayInfo = DisplayInfo(1080, 1920, 2f)

        override suspend fun getInstalledApps(): List<AppInfo> = emptyList()

        override suspend fun launchApp(packageName: String): ActionResult = ActionResult.Success()
    }

    private fun element(
        index: Int,
        resourceId: String,
        text: String = "",
        description: String = "",
        isClickable: Boolean = true,
        isEditable: Boolean = true,
        bounds: Bounds = Bounds(left = 0, top = 0, right = 10, bottom = 10),
        center: Point = Point(x = 5, y = 5)
    ): PerceptionElement {
        return PerceptionElement(
            index = index,
            text = text,
            resourceId = resourceId,
            className = "View",
            description = description,
            isClickable = isClickable,
            isEditable = isEditable,
            isScrollable = false,
            isEnabled = true,
            isFocused = false,
            isLongClickable = false,
            bounds = bounds,
            center = center
        )
    }
}
