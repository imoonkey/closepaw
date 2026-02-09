在 **AccessibilityService** 里，“swipe 到底部后再 swipe 没变化”这类情况，**最可靠的判断依据不是手势回调**（`dispatchGesture` 只告诉你手势是否注入成功），而是 **滚动是否真的发生** —— 也就是你能否收到 **`TYPE_VIEW_SCROLLED`**（以及可选的内容变化事件）。

下面给你几种从“强到弱”的判定方式（实际工程里通常组合用）：

---

## 1) 优先不用 swipe：对 scrollable 节点做语义滚动（推荐）

与其注入手势，不如找到可滚动容器节点，对它执行：

* `ACTION_SCROLL_FORWARD`（向下/向前）
* `ACTION_SCROLL_BACKWARD`（向上/向后）

这样系统更容易给你稳定的滚动事件，也更不受不同 App 手势实现影响。

**关键点：** `performAction()` 的返回值只代表“是否成功派发动作”，**不代表一定滚动了**。所以仍要结合事件判断。

---

## 2) 监听 `TYPE_VIEW_SCROLLED`，用 event 的 scroll 信息判断是否到底

当真正发生滚动时，系统通常会发 `TYPE_VIEW_SCROLLED`。这个事件里有几类信息可以判断“是否到底”：

### A. 像 `ScrollView` 这种有绝对滚动量的（最直接）

看下面字段（Y 向下滑为例）：

* `event.scrollY`
* `event.maxScrollY`

**到底条件：**
[
scrollY \ge maxScrollY \quad (\text{且 } maxScrollY > 0)
]

（横向同理用 `scrollX/maxScrollX`）

### B. 像 `RecyclerView/ListView` 这种集合型列表（更常见）

看这些字段：

* `event.itemCount`：总条目数
* `event.toIndex`：当前可见范围的最后一个 index
* `event.fromIndex`：可见范围第一个 index

**到底条件（常用）：**
[
itemCount > 0 \ \wedge\ toIndex \ge itemCount - 1
]

> 注：不同控件/不同 App 对这些字段填充质量不一，但在系统控件、RecyclerView 适配得好的场景里非常好用。

### Kotlin 示例：在事件里判断到底

```kotlin
fun isAtBottomByEvent(e: AccessibilityEvent): Boolean {
    // 1) 绝对滚动量
    val maxY = e.maxScrollY
    val y = e.scrollY
    if (maxY > 0 && y >= maxY) return true

    // 2) 集合列表
    val count = e.itemCount
    val to = e.toIndex
    if (count > 0 && to >= count - 1) return true

    return false
}
```

---

## 3) 用 “有没有产生滚动事件” 作为判定：没有事件≈没有滚动

如果你发起一次向下滚动（无论是 `performAction(ACTION_SCROLL_FORWARD)` 还是 swipe 手势），然后：

* **在一个短超时窗口内没有收到 `TYPE_VIEW_SCROLLED`**
* 并且（可选）也没有收到足够的 `TYPE_WINDOW_CONTENT_CHANGED` / `TYPE_WINDOWS_CHANGED`

那基本可以认为：

* 要么已经到底（无法再滚）
* 要么目标容器没选对/当前界面不支持滚动

工程上常见做法是：**把“发起滚动”与“等待匹配的 scrolled 事件”做成一次事务**（用 windowId / source viewId / packageName 去过滤匹配）。

---

## 4) scrollable 节点会有“到没到底”的标记吗？

### 有：`isScrollable()`（但只能说明“这个控件具备滚动能力”，不能说明“现在还能不能继续往下”）

* `node.isScrollable == true` 只说明它是可滚动容器。

### 有时有：动作列表里是否包含 `ACTION_SCROLL_FORWARD`

一些控件在到底时会把 `ACTION_SCROLL_FORWARD` 从 action 列表移除（或反之），你可以尝试：

* `node.actionList` 是否包含 `ACTION_SCROLL_FORWARD`

但这 **不保证所有 App/自定义控件都正确维护**，所以我把它当“辅助信号”，不当最终判据。

---

## 5) 兜底：内容快照不变（适合自定义控件/事件不可靠时）

当 App 不发 `TYPE_VIEW_SCROLLED`，或 scroll 字段一直是 0，你可以做一个轻量“可见内容快照”：

* 滚动前：取列表里最后一个可见 item 的（文本/描述 + bounds）
* 发起滚动
* 滚动后：再取一次
* 如果完全一致，基本可判定没动（可能到底）

这招对 “无障碍树能拿到 item 节点” 的列表很有效。

---

## 一套实战组合（推荐策略）

1. **优先找到最近的可滚动祖先节点**（从当前焦点/目标元素向上找 `isScrollable==true`）。
2. 用 `performAction(ACTION_SCROLL_FORWARD)` 发起滚动（少用 swipe）。
3. 等待并匹配 `TYPE_VIEW_SCROLLED`：

   * 若收到：用 `scrollY/maxScrollY` 或 `toIndex/itemCount` 判断是否到底
   * 若没收到：判定“未滚动”（大概率到底或容器不对）
4. 事件字段不可信时，加上“内容快照不变”兜底。

---

如果你愿意贴一小段：你现在是用 **手势 swipe** 还是 **`ACTION_SCROLL_FORWARD`**？目标是 **RecyclerView / ScrollView / WebView / 自定义列表** 哪一种？我可以按具体控件把“事件字段优先级”和“匹配过滤方式（windowId/source/viewId）”给你写得更稳。
