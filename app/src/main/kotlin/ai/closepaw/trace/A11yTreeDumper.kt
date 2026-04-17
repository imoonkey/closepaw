package ai.closepaw.trace

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import ai.closepaw.util.isCheckedCompat
import ai.closepaw.util.recycleCompat
import kotlinx.serialization.Serializable

@Serializable
internal data class A11yNodeDump(
    val className: String? = null,
    val packageName: String? = null,
    val viewIdResourceName: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val bounds: List<Int>? = null,
    val clickable: Boolean = false,
    val enabled: Boolean = false,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val rangeInfo: A11yRangeInfo? = null,
    val selected: Boolean = false,
    val password: Boolean = false,
    val visibleToUser: Boolean = false,
    val childCount: Int = 0,
    val children: List<A11yNodeDump> = emptyList()
)

@Serializable
internal data class A11yRangeInfo(
    val current: Float,
    val min: Float,
    val max: Float
)

internal object A11yTreeDumper {
    private const val MAX_TEXT_LEN = 2000
    private const val MAX_DESC_LEN = 2000
    private const val MAX_ID_LEN = 300
    private const val MAX_CLASS_LEN = 200

    fun dump(root: AccessibilityNodeInfo?): A11yNodeDump? {
        if (root == null) return null
        return dumpNode(root, shouldRecycle = false)
    }

    private fun dumpNode(node: AccessibilityNodeInfo, shouldRecycle: Boolean): A11yNodeDump {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val children =
            buildList {
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    add(dumpNode(child, shouldRecycle = true))
                }
            }

        val viewId = node.viewIdResourceName?.take(MAX_ID_LEN)
        val className = node.className?.toString()?.take(MAX_CLASS_LEN)
        val text = node.text?.toString()?.take(MAX_TEXT_LEN)
        val desc = node.contentDescription?.toString()?.take(MAX_DESC_LEN)
        val rangeInfo =
            node.rangeInfo?.let {
                A11yRangeInfo(current = it.current, min = it.min, max = it.max)
            }

        val dump =
            A11yNodeDump(
                className = className,
                packageName = node.packageName?.toString(),
                viewIdResourceName = viewId,
                text = text,
                contentDescription = desc,
                bounds = listOf(rect.left, rect.top, rect.right, rect.bottom),
                clickable = node.isClickable,
                enabled = node.isEnabled,
                focusable = node.isFocusable,
                focused = node.isFocused,
                scrollable = node.isScrollable,
                editable = node.isEditable,
                checkable = node.isCheckable,
                checked = node.isCheckedCompat(),
                rangeInfo = rangeInfo,
                selected = node.isSelected,
                password = node.isPassword,
                visibleToUser = node.isVisibleToUser,
                childCount = node.childCount,
                children = children
            )

        if (shouldRecycle) {
            node.recycleCompat()
        }

        return dump
    }
}
