# Round7 Issue Review (Codex)

Date: 2026-02-14
Input: `doc/todo/0.02_smart_capsule/round7/qi_note.md`

分类标准：
1. `state machine design` 问题（设计定义本身不满足期望）
2. `state machine implementation` 问题（设计是对的，但状态机/可见性实现有 bug）
3. `别的问题`（不属于状态机设计/实现，通常是 activity/task/session/perception 等系统层问题）

---

## Accessibility Mode

### A11y-1
问题：Takeover 后 add note 一次后输入框变灰，不允许继续加。  
分类：**2（state machine implementation）**

判断依据：
- 设计期望在 A11y `Takeover` 下 Row3 可继续输入（`design.md` Section 8）。
- 代码里 `handleRow3Submit()` 每次提交后都会 `setOverlayFocusable(false)`（`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/SmartCapsuleManager.kt:305`），存在焦点恢复/可交互状态处理不完整风险。

Recommendation：
- 维持 `Takeover` 下可重复补充的 invariant，修正提交后焦点与可编辑状态恢复逻辑（例如提交后 clear focus，再允许下一次 tap 稳定 re-focus）。
- 增加回归测试：A11y `Takeover` 连续发送 2~3 次 supplement。
- 你已标记 `[不用fix]`，建议先记录为已知行为，后续若影响大再排期。

### A11y-2
问题：Perception 能看到 smart capsule 内容。  
分类：**3（别的问题）**

判断依据：
- 这是 perception/a11y tree 过滤问题，不是 capsule 状态机。
- 与 UI 状态机解耦，应在感知管线或 overlay 可访问性属性层处理。

Recommendation：
- 在 perception pipeline 增加 overlay window/package 过滤；
- 或确保 overlay view `importantForAccessibility` 与可见性策略一致，避免被 agent 读到。
- 你已标记 `[不用fix]`，可以先不改。

### A11y-3
问题：任务执行时没有“collapse 到 status island”按钮。  
分类：**1（state machine design）**

判断依据：
- round6 设计明确 A11y 不提供 `⊖/📱/👁` 导航按钮（`design.md` Section 4）。
- 当前行为与现有设计一致，不是实现 bug。

Recommendation：
- 若产品上确实要 A11y 支持 collapse，需要先改 design（含安全性论证：不干扰 agent 控屏），再改实现与测试。
- 若不改设计，这条应从 bug 列表移除，转为“需求变更候选”。

---

## Virtual Display Mode

### VD-1
问题：Done 状态不应显示 👁 按钮。  
分类：**2（state machine implementation，若当前版本仍可复现）**

判断依据：
- 设计要求 Done 隐藏 Row2（自然不应显示 Row2-R 导航）（`design.md` Section 4/12）。
- 该问题若存在，属于渲染/可见性实现偏差，不是设计问题。

Recommendation：
- 在 Compose 与 Overlay 两侧都加断言：`mode=Done -> Row2 hidden -> no 👁`。
- 增加自动化 UI/渲染回归测试覆盖 Done 态按钮可见性。
- 先用最新 commit 再复测一次，确认是否已被近期修复覆盖。

### VD-2
问题：主界面点 👁 可进 viewer，但 viewer 点 📱 无法回 chat；上滑回 Home 后 recent 截图仍是 viewer 内容。  
分类：**3（别的问题）**

判断依据：
- 这不是状态机迁移定义问题，核心是 Activity/task 栈行为。
- Viewer 当前 `singleTask`（`AndroidManifest.xml:36`），`onOpenApp` 仅 `NEW_TASK|SINGLE_TOP`（`AgentService.kt:149`），很容易出现 task 路由异常。

Recommendation：
- 优先修 task/navigation：统一 Main/Viewer 的 launchMode 与 intent flags（建议评估 `CLEAR_TOP`/`REORDER_TO_FRONT` 等策略）。
- 明确“从 viewer 回 chat”的单一路径并做 instrumentation test（含 Home/recent 场景）。

### VD-3
问题：viewer 上滑回 Home 后，再点 app 回来进入了新 session，不是进行中的 session。  
分类：**3（别的问题）**

判断依据：
- 根因更像 session 生命周期/重绑定问题，不是 capsule 状态机。
- `MainActivity` 用本地 `currentSession` 持有会话（`MainActivity.kt:70`），Activity 重建/任务切换后容易丢绑定。

Recommendation：
- 让 session owner 下沉到 service/仓库层，MainActivity 重建时自动 rebind 正在运行的 session；
- 进入 app 时优先恢复 active session，而不是默认空白/新 session。
- 加回归测试：viewer -> Home -> launcher return，session continuity 必须保持。

### VD-4
问题：任务结束后 chat history 没有 complete_task message。  
分类：**3（别的问题，当前更像 session/UI 绑定链路问题）**

判断依据：
- 当前 `ChatViewModel.handleTaskCompleted()` 已会追加 completion 文本（`ChatViewModel.kt:271`）。
- 若仍看不到，常见是 UI 未绑定到正确 session 或返回后落在新 session（与 VD-3 同根）。

Recommendation：
- 先修 VD-3 的 session continuity；
- 再做端到端验证：同一 session 内完成任务后，completion 文本可见。
- 若 continuity 修复后仍丢消息，再单独排查事件投递链（`TaskCompleted` 是否到达当前 ViewModel collector）。

### VD-5
问题：VD 任务执行中没有 edge glow，但 A11y 有。  
分类：**1（state machine design / UI policy）**

判断依据：
- round6 设计只要求 A11y `OTHER_APP && isActive` 显示 glow（`design.md` Section 3），VD 未定义 glow。
- 所以“VD 也要 glow”是设计变更，不是现有实现 bug。

Recommendation：
- 先决策：是否要把 VD glow 纳入规范。
- 若要：更新 design/user_flow/bug_prevention，再实现 + 回归测试。
- 若不要：将该项标注为“与现设计一致”。

---

## Overall Recommendation (Prioritized)

1. **先修 `VD-2 + VD-3`（P1）**  
   这是当前最可能的主因，会连带触发 `VD-4` 观感问题。

2. **`VD-4` 在 continuity 修复后复测**  
   若仍复现，再进入事件链路专项排查。

3. **设计确认项：`A11y-3`、`VD-5`**  
   二者是“要不要改设计”的产品决策，不建议直接按 bug 修代码。

4. **`A11y-1` 作为次优先实现修复**  
   可作为 UX 健壮性改进（即使当前标注不用 fix）。
