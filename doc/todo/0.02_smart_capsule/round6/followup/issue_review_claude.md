# Round7 QI Issue Review

Date: 2026-02-14
Source: `qi_note.md` (8 issues: 3 A11y, 5 VD)

## Classification Legend

| Category | Meaning |
|----------|---------|
| **Design** | State machine design spec has a gap or is wrong |
| **Implementation** | Design is correct, code doesn't match it |
| **Other** | Not state machine related (Activity lifecycle, perception, etc.) |

---

## Accessibility Mode

### A1: Takeover → add note 后对话框变灰（[不用fix] P2）

**Category: Implementation**

**Design says:** A2.T — Row3 ENABLED, "Got ideas? Add a note..." + [Add note]. Focusable. Section 13: supplement 不改变 mode/visibility.

**Code bug:** `SmartCapsuleManager.handleRow3Submit()` (line 325) unconditionally调用 `setOverlayFocusable(false)`. Takeover 的 `setupInteractivity` (line 285-288) 只设置了 EditText 属性 (`isFocusable=true`), 但没有调用 `setOverlayFocusable(true)`.

**Flow:**
1. Takeover mode → user touches input → `handleInputFocused` → `setOverlayFocusable(true)` → works
2. User submits supplement → `handleRow3Submit` → `setOverlayFocusable(false)` → window becomes FLAG_NOT_FOCUSABLE
3. Supplement doesn't change mode (`Section 13`) → `setupInteractivity` not re-called → focusability never restored
4. User tries to touch input again → window is non-focusable → touches pass through → stuck

**Fix (if needed):** In `handleRow3Submit`, only call `setOverlayFocusable(false)` when mode is NOT Takeover. Or call `setOverlayFocusable(true)` in Takeover's `setupInteractivity` block and keep it set after submit.

---

### A2: Perception 看到 Smart Capsule 内容（[不用fix] P2）

**Category: Other (Perception layer)**

Not a state machine issue. The accessibility tree exposes Smart Capsule View nodes. The LLM's perception pipeline reads the full a11y tree, which includes overlay window content.

**Fix (if needed):** Filter capsule package nodes in perception preprocessing.

---

### A3: A11y 任务执行时没有 collapse 到 status island 的按钮（P1）

**Category: Design (by intent, not bug)**

**Design says:**
- Section 1 constraint: "A11y 永远不显示 island"
- user_flow.md P3 prohibition: "A11y mode showing Status Island (ever)"
- user_flow.md P4 prohibition: "A11y overlay showing navigation buttons (📱, 👁, ⊖)"
- NavSpec: `showMinimize` requires `platformMode == VIRTUAL_DISPLAY`

A11y 模式下 island 概念不存在, 所以没有 ⊖ 按钮. 这是设计上的 intentional constraint, 不是 bug.

**Recommendation:** 如果确实需要 A11y 下最小化/隐藏 capsule 的能力, 需要 design change — 比如增加一个 "collapse" 按钮让 capsule 缩小到只显示 dot, 或者增加 swipe-to-dismiss. 但这会改变整个 A11y capsule 的 UX model, 需要设计讨论.

---

## Virtual Display Mode

### V1: Done 状态下显示 👁 按钮（P1）

**Category: Implementation — ALREADY FIXED**

**Design says:** B1.D/B2c.D/B3c.D 所有 Row2 = hidden. Section 4: "若当前 mode 隐藏 Row2（如 Done），则不渲染 Row2-R 按钮".

**Status:** Commit `a93f2d0` 已修复:
- `NavSpec.from()`: 添加 `row2Hidden = mode is CapsuleMode.Done`, 所有三个 nav 条件前置 `!row2Hidden`
- `SmartCapsuleCompose`: Row2 + dividers 包裹在 `if (mode !is CapsuleMode.Done)` 中
- Test: `NavSpecTest.done mode hides all nav buttons` 覆盖所有三个 CapsuleContext

---

### V2: VD Viewer 上点 📱 无法回到 chat 界面（P1）

**Category: Other (Activity navigation)**

**State machine/overlay 路径是正确的:**
- `SmartCapsuleManager.onNavApp` (line 117) → `onOpenApp?.invoke()`
- `ServiceOverlayController` wires `capsuleManager.onOpenApp = this.onOpenApp` (line 74)
- `AgentService.onOpenApp` (line 149-156) launches `MainActivity` with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP`

**Likely root cause:** Activity 导航问题, 不是状态机问题.
- `FLAG_ACTIVITY_SINGLE_TOP`: 仅当 MainActivity 位于 task 栈顶时触发 onNewIntent. 当用户在 VD Viewer 时, VirtualDisplayViewerActivity 在栈顶, 所以可能创建新 MainActivity 实例或无法正确 bring-to-front.
- VirtualDisplayViewerActivity 不会自动 finish, 可能阻挡 MainActivity 的显示.
- Android 10+ 限制从 background 启动 Activity (AccessibilityService 豁免, 但可能有 OEM 差异).

**Recommendation:**
1. 检查 `VirtualDisplayViewerActivity` 的 `launchMode` 和 `taskAffinity` 设置
2. 在 `onOpenApp` intent 中考虑添加 `FLAG_ACTIVITY_CLEAR_TOP` 替代 `SINGLE_TOP`
3. 或在 📱 点击时先 finish VirtualDisplayViewerActivity, 再 start MainActivity
4. 加 logging 确认 intent 实际是否执行到了

---

### V3: 从 VD Viewer 上滑到 Home 再回到 app 显示新 session（P1）

**Category: Other (Activity/ViewModel lifecycle)**

不是状态机问题. 状态机和 overlay 系统在 Service 层, Activity 重建不影响它们.

**Likely root cause:** Activity 重建导致 ViewModel 丢失.
- 用户从 VD Viewer swipe 到 Home → 系统可能 destroy VirtualDisplayViewerActivity
- 用户点 app icon → 系统可能创建新 MainActivity (取决于 launchMode + taskAffinity)
- 新 MainActivity → 新 ChatViewModel → 空的 message list

**Recommendation:**
1. 检查 AndroidManifest 中 MainActivity 和 VirtualDisplayViewerActivity 的 `launchMode`/`taskAffinity`
2. 确保 re-open app 时 resume existing task 而不是创建新 task
3. 考虑 ChatViewModel 从持久化存储恢复 session history (目前 `ChatSessionHistoryController` 是否持久化到 `sessionHistoryManager`?)

**Note:** V3 和 V4 可能是因果关系 — V3 (新 session) 自然导致 V4 (看不到 completion message).

---

### V4: 任务结束后 chat history 没有 complete_task message（P1）

**Category: Needs Investigation (可能被 V3 导致, 也可能独立存在)**

**Code 本身是正确的:**
- `ChatViewModel.handleTaskCompleted()` (line 271-283): 调用 `completionSummary(event.result)` → 追加 `ContentBlock.Text(completionText)` 到最后一条 Agent message
- `completionSummary()` (line 29-30): `result?.takeIf { it.isNotBlank() } ?: "Task completed"` — 空值有 fallback
- Test: `ChatCompletionSummaryTest` 覆盖 null/blank/non-empty 三个 case

**可能的原因:**
1. **被 V3 导致:** 如果返回 app 时创建了新 ViewModel, 之前的 completion 文本自然丢失
2. **Event 未到达 ViewModel:** `AgentSession.events` flow 可能在任务完成时已结束, completion event 被 dropped
3. **updateLastAgentMessage 找不到 Agent message:** 如果 chat 中没有 Agent 消息 (例如 `handleTurnStarted` 未被调用), `indexOfLast` 返回 -1, 静默跳过

**Recommendation:**
1. 先修 V3. 然后测试 V4 是否还存在
2. 如果 V4 独立存在, 加 logging 到 `handleTaskCompleted` 确认 event 是否到达
3. 加 defensive guard: 如果 `updateLastAgentMessage` 找不到 agent message, 创建一条新的

---

### V5: VD 模式下没有 edge glow（P1）

**Category: Design (intentional omission, 可能需要 design update)**

**Design says:**
- `design.md` Section 3: "A11y OTHER_APP && isActive: OverlayCapsule + **Glow**"
- VD Section 完全没提到 glow
- `user_flow.md`: A2 table 有 Glow 列 (blue/amber/teal/red). B2c/B2i/B3c/B3i 表格没有 Glow 列

**Code matches design:**
- `deriveOverlayVisibility()`: A11y → `showGlow = showCapsule`. VD → `showGlow = false`
- `ServiceOverlayController`: edge glow update 只在 `platformMode == ACCESSIBILITY` 时触发

**Analysis:** 当时的设计思路是 A11y 用 glow 提供 edge feedback (因为 capsule 和真实屏幕在同一个物理屏上). VD 模式下 agent 在虚拟屏幕操作, 用户看到的是 capsule/island overlay, 不需要 edge hint.

**Recommendation:** 如果用户希望 VD 也有 glow, 需要 design update:
1. `design.md` Section 3 VD 规则增加 glow
2. `user_flow.md` B2c/B3c 表格增加 Glow 列
3. `deriveOverlayVisibility()` VD 分支增加 `showGlow` 逻辑
4. 考虑 VD Viewer 上 glow 是否有干扰 (glow 覆盖在 viewer 顶部)

---

## Summary

| # | Issue | Category | Priority | Status | Action |
|---|-------|----------|----------|--------|--------|
| A1 | Takeover 加 note 后变灰 | Implementation | P2 (不用fix) | Won't fix | `handleRow3Submit` 应保持 Takeover 的 focusable |
| A2 | Perception 看到 capsule | Other (Perception) | P2 (不用fix) | Won't fix | 过滤 a11y tree 中的 capsule 节点 |
| A3 | A11y 没有 collapse 按钮 | Design (intentional) | P1 | Design decision | 需要讨论是否要 A11y 模式的 minimization |
| V1 | Done 显示 👁 | Implementation | P1 | **FIXED** (a93f2d0) | NavSpec Done gating + Row2 隐藏 |
| V2 | 📱 无法回到 chat | Other (Activity lifecycle) | P1 | Open | 检查 intent flags + viewer finish 逻辑 |
| V3 | 回到 app 显示新 session | Other (Activity lifecycle) | P1 | Open | 检查 launchMode + session 恢复 |
| V4 | 没有 completion message | Needs investigation | P1 | Open | 先修 V3, 再独立验证 |
| V5 | VD 没有 edge glow | Design (omission) | P1 | Open | 需要 design update 如果需要 VD glow |

## Recommendation

**Immediate fixes (code changes only, no design change):**
- V2, V3: 都是 Android Activity lifecycle 问题. 需要调查 manifest 配置和 intent flags, 修复 navigation 逻辑.
- V4: 先修 V3, 然后测试是否消失. 如果独立存在, 加 logging 定位到 event pipeline.

**Design decisions needed:**
- A3: 是否要给 A11y 模式提供 capsule 最小化能力? (当前设计 intentionally 没有)
- V5: 是否要给 VD 模式增加 edge glow? (当前设计 intentionally 没有)

**Won't fix (user marked):**
- A1, A2: 用户已标记 [不用fix]
