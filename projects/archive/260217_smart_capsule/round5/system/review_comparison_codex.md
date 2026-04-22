# Review Comparison (ClaudeCode vs Codex)

Date: 2026-02-14  
Compared files:
- `doc/todo/0.02_smart_capsule/round5/system/review_design_claudecode.md`
- `doc/todo/0.02_smart_capsule/round5/system/review_design_codex.md`

## 1. Consensus

1. `Status Island` 与 `Smart Capsule` 的可见性策略是当前最大根因之一，必须做互斥和单点决策。
2. VD 模式下不应在 Main App 前台显示 island（Main App 已有 Compose capsule）。
3. `performHandoff()`（任务完成后把 VD app 拉到主屏）应删除或变成 no-op。
4. 需要补“点击后过渡反馈”，否则 Stop/Takeover 体验上像“没点上”。
5. 需要修复 nav 语义与行为链路一致性（按钮是否该显示、显示后是否可用）。
6. `Running` 态输入焦点冲突需要治理（至少在 A11y overlay 做输入策略约束）。
7. history/chat 链路有缺口（`complete_task` summary、`supplement` 展示一致性）需要补齐。

## 2. Conflict

1. 状态机评估结论不同。
- ClaudeCode: `CapsuleStateHolder` 的 8 态主状态机“基本正确”，问题主要在窗口可见性与 wiring。
- Codex: 现有状态机在 `ask_user` 响应闭环上不完整（缺 ack 事件），应重定义 ground truth（task/surface/panel/pending 分层）。

```
我的建议是：**采用“Claude 的增量改造路径 + Codex 的 ack 完整性约束”**，不要二选一。
    1. **先不推翻 8 态 `CapsuleMode`**  
    保留它作为渲染主状态（风险最低，改动最小）。

    2. **把 `ask_user` 闭环当成必须修的 correctness bug（优先级最高）**  
    至少补这两个事件并接入状态迁移：  
    - `UserResponseAccepted(callId)`  
    - `UserResponseRejected(callId, reason)`  
    否则 `WaitingFor*` 永远依赖“偶然事件”退出，确实不完整。

    3. **窗口可见性单独收敛为一个纯策略函数**  
    先做 Claude 的 `applyVisibility` 单点决策，解决 island/capsule 互斥和 VD main app 问题。  
    这部分不需要先做完整 state split。

    4. **`TakeoverPending` 建议用“pending UI”而不是乐观主状态迁移**  
    点击后给反馈可以做，但不要污染任务真相；等 ack 再推进主状态更稳。

    5. **等第一轮稳定后，再评估是否上完整分层（task/surface/panel/pending）**  
    如果后续还频繁出“状态真相 vs 可见性”问题，再升级到 Codex 那套完整 reducer。

    一句话：**短期按 Claude 降风险落地，长期按 Codex 保证状态机可证明正确。**
```

2. TakeoverPending 的触发策略不同。
- ClaudeCode: 倾向点击即进入 `TakeoverPending`（乐观过渡）作为即时反馈。
- Codex: 倾向点击只发 intent，状态只由 ack 事件推进；反馈放在 `PendingCommand` 这类旁路 UI 状态。

3. A11y 下“返回 app”能力处理不同。
- ClaudeCode: 建议禁用 Row1 tap，保留 phone icon（显式操作仍可回 app）。
- Codex: 建议 A11y overlay 隐藏 `showApp`（避免扰动 agent on-screen workflow）。

4. 改造范围不同。
- ClaudeCode: 偏增量重构（`applyVisibility + ShowPreference`，尽量保留现有结构）。
- Codex: 偏结构性重构（统一 reducer + presentation policy + 新 ack 事件）。

## 3. ClaudeCode Covers But Codex Does Not Emphasize

1. 对现有结构“哪些该保留”有系统性审查（如 `CapsuleRenderSpec`、`SmartCapsuleRenderer`、`UserResponseChannel`）。
2. 明确提出并列出 dead code 清理清单：`InputDock.kt`、`InputState`、controller no-op `onMessageDelta()`。
3. 给了更细的“可见性真值表”落地形式（含 `ShowPreference` 切换规则）。
4. 给了逐条 bug disposition（覆盖 `qi_bug_note` 的每项归属与 phase）。

## 4. Codex Covers But ClaudeCode Does Not Emphasize

1. 把 `ask_user` 响应链断点定义为 Critical：`Op.UserResponse -> deliver` 后没有 UI ack event，`WaitingFor*` 缺严谨闭环。
2. 明确提出新增 ack 事件模型：
- `SessionTakeoverPending`
- `UserResponseAccepted(callId)` / `UserResponseRejected(callId, reason)`
3. 明确指出 VD context 维度缺失（当前模型无法表达 VD 下 `MAIN_APP` surface）。
4. 提出更严格的状态分层：`TaskUiState + UserSurface + PanelMode + PendingCommand`，把任务真相与窗口可见性彻底解耦。
5. 明确给出 invariants（A-E）和对应测试形态（Reducer/Presentation/Ack flow 测试）。

## 5. Practical Merge Strategy (Recommended)

1. 先采纳双方共识项：
- 删 `performHandoff()`
- 做 island/capsule 互斥
- 修 VD Main App 不显示 island
- 修 nav 显示与行为一致性
2. 对冲突项采用“低风险先行”：
- 先上 ClaudeCode 的 `applyVisibility` 单点可见性控制
- 同步引入 Codex 的 `ack event` 补全（至少 user response ack）
3. 再决定 A11y 的 `showApp` 策略（保留 phone icon vs 完全隐藏），用一次真实 UX 测试拍板。


Qi Note:A11y下“返回app”能力处理：就不该存在。不该有show app或者row1 tap回app的能力。

## 6. Conflict-Resolution Recommendation Diff (ClaudeCode vs Codex)

1. `ask_user` 闭环策略不同。
- `review_comparison_claudecode.md` 倾向先用“乐观迁移”快速修复（UI submit 时直接调用 `onUserResponseSent()`），把 session-level ack event 作为后续增强。
- `review_comparison_codex.md` 倾向至少把 `UserResponse` ack 补齐到事件层（`Accepted/Rejected`），避免等待态靠偶然事件退出。

2. Takeover 反馈策略不同。
- `review_comparison_claudecode.md` 倾向点击即进 `TakeoverPending`（低改动、即时反馈）。
- `review_comparison_codex.md` 倾向“intent + pending UI + ack 驱动主状态”，避免主状态被乐观点击污染。

3. 结构改造节奏不同。
- `review_comparison_claudecode.md` 明确“增量优先”：先 `applyVisibility + ShowPreference`，较少引入新类型。
- `review_comparison_codex.md` 在 recommendation 里要求更早引入 correctness 约束（至少 user-response ack），并为后续 reducer 分层预留路径。

4. A11y 的“返回 app”决策不同。
- `review_comparison_claudecode.md` 总体上偏保留显式入口（至少讨论过保留 phone icon 的可能）。
- `review_comparison_codex.md`（结合 Qi note）更偏向彻底禁用 A11y overlay 下的 `showApp`/row1 回 app 能力。

## 7. Final Recommendation (Codex)

1. 采用“双阶段”方案，不二选一：
- Phase A（本轮必须）：走 Claude 的增量路径，先统一 `applyVisibility`，修复 island/capsule 互斥、VD main app 显示错误、handoff 删除、nav 显示逻辑。
- Phase B（同一迭代内完成，不要拖到后续）：落 Codex 的 `ask_user` correctness，至少实现 `UserResponseAccepted/Rejected`（或等价 ack 机制），让 `WaitingFor*` 退出有确定触发。

2. 对 Takeover 采用折中：
- 可以保留即时反馈，但放在 `pending UI` 层（按钮 disabled/loading），主状态迁移仍以 ack 事件为准。

3. A11y “返回 app”按 Qi note 拍板：
- 禁用 `showApp` 与 row1 回 app（A11y overlay 下都不允许），减少对 agent 前台操作的扰动。

4. 结构分层（`TaskUiState/UserSurface/PanelMode/PendingCommand`）暂不一次性重构：
- 先把 correctness 与 visibility 收敛；若第二轮仍出现状态真相/可见性串扰，再升级到完整 reducer 分层。
