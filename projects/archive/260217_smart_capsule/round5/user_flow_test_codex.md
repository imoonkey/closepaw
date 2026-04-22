# Smart Capsule v2 User Flow Test Plan (Codex)

Date: 2026-02-13  
Scope: `smart capsule v2` current implementation (Round4 codebase)

## 1. Goal

把你当前手测流程系统化成可回归的 user flow 测试矩阵，覆盖：

- Main App (`SmartCapsuleCompose`)
- Accessibility overlay (`SmartCapsuleManager` + glow)
- Virtual Display (`StatusIsland` + viewer + overlay capsule)
- State machine 关键迁移（`CapsuleMode`）

核心目标不是“跑几次 demo”，而是确认每个关键 flow 都有可重复的通过标准。

## 2. Current State Machine (as-implemented)

Source of truth: `app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt`.

### 2.1 Modes

- `Hidden`
- `Running`
- `TakeoverPending` (代码里有，但当前 runtime 路径几乎不可达)
- `Takeover`
- `WaitingForInput`
- `WaitingForAction`
- `Done`
- `Error`

### 2.2 Runtime-reachable transitions to validate

- `Hidden -> Running` on `TaskStarted`
- `Running -> Takeover` on `SessionTakeover`
- `Takeover -> Running` on `SessionResumed`
- `* -> WaitingForInput/WaitingForAction` on `AskUser`
- `Active -> Done/Error` on `TaskCompleted`
- `Done -> Hidden` after auto-hide (3s)
- `Error -> Hidden` on dismiss
- `SessionCompleted(USER_STOPPED/INTERRUPTED) -> Hidden`

### 2.3 Known risk to test explicitly

- `onUserResponseSent()` is currently not wired from runtime event path, so `WaitingForInput/WaitingForAction` may not immediately return to `Running` after user submit.  
  This must be a dedicated test item (detect stuck waiting state).

## 3. Test Environment Baseline

## 3.1 Commands

Accessibility baseline:

```bash
./scripts/setup.sh && ./scripts/debug-run.sh --basic "play a <singer> song on youtube"
```

Virtual Display baseline:

```bash
./scripts/setup.sh && ./scripts/debug-run.sh --basic --vd "play a <singer> song on youtube"
```

Singer rotation pool (for your randomization requirement):

- Adele
- Ed Sheeran
- Bruno Mars
- Taylor Swift
- The Weeknd
- Billie Eilish

## 3.2 Run discipline

- A11y 与 VD 分开统计
- 每次失败都记录：step、截图、logcat 时间点、当前 mode 文本

## 4. User Flow Matrix

Legend:

- Priority: `P0` blocker, `P1` core, `P2` polish
- Surface: `Main`, `A11yOverlay`, `VD`

| ID | Priority | Surface | Flow | Expected Result |
|---|---|---|---|---|
| UF-01 | P0 | Main | Idle send task | `Hidden -> Running`，Row3 发送后进入任务态 |
| UF-02 | P0 | Main | Running supplement | 补充文本可提交，出现“received/继续执行”反馈，不中断任务 |
| UF-03 | P0 | Main/A11yOverlay | Takeover + Resume | `Running -> Takeover -> Running` |
| UF-04 | P0 | Main/A11yOverlay | AskUser QUESTION | 进入 `WaitingForInput`，输入后可继续任务（不应永久卡住） |
| UF-05 | P0 | Main/A11yOverlay | AskUser ACTION | 进入 `WaitingForAction`，点 `Done` 后继续任务 |
| UF-06 | P0 | Main/A11yOverlay/VD | Goal complete lifecycle | `Done` 显示 summary，约 3s 后 `Hidden` |
| UF-07 | P1 | Main/A11yOverlay | Error + dismiss | `Error` 可关闭回 `Hidden` |
| UF-08 | P1 | A11yOverlay | Foreground switch | App 前后台切换时 overlay/glow 显隐正确 |
| UF-09 | P1 | A11yOverlay | Row1 open app | 点击 Row1 可回主 app |
| UF-10 | P0 | VD | Island appears on task start | 任务开始显示 island |
| UF-11 | P0 | VD | Island tap expand capsule | 点 island 后 capsule 展开，island 隐藏 |
| UF-12 | P0 | VD | Capsule minimize | 点 `⊖` 后 capsule 隐藏、island 返回 |
| UF-13 | P1 | VD | Open viewer from 👁 | viewer 打开后 context/导航符合预期 |
| UF-14 | P1 | VD | Viewer close | viewer 关闭后 capsule 隐藏、island 显示 |
| UF-15 | P1 | VD | Island tap with no active task | 不应黑洞；应打开 main app 或保持可恢复路径 |
| UF-16 | P1 | VD | AskUser in VD | ask_user 时自动展示 capsule 供输入 |

## 5. Detailed Test Procedure by Flow Group

## 5.1 Core Task Loop (`UF-01`~`UF-07`)

1. Start run with `--basic` (A11y), singer A.
2. Verify `Running` state visuals:
   - Row1 thought visible
   - Row2 has `Takeover` + `Stop`
   - Row3 hint `Got ideas? Add a note...`
3. Send supplement and verify task continues.
4. Trigger takeover then resume.
5. Wait for ask_user and validate both QUESTION/ACTION branches.
6. On completion, verify summary text and auto-hide.
7. Inject/observe an error and verify dismiss path.

Pass condition:

- 7/7 flows pass for at least 2 consecutive runs.

## 5.2 Accessibility Overlay Behavior (`UF-08`, `UF-09`)

1. Run A11y mode task.
2. Put app background, verify capsule+glow appear.
3. Bring app foreground, verify capsule+glow hide.
4. In overlay, tap Row1 (`Open main app`), verify app foreground navigation.

Pass condition:

- No stale overlay left on wrong surface.

## 5.3 VD Island/Viewer Navigation (`UF-10`~`UF-16`)

1. Run `--basic --vd` task.
2. Verify island appears.
3. Tap island -> capsule appears -> island hides.
4. Tap `⊖` minimize -> capsule hides -> island returns.
5. Open viewer (`👁`) and close viewer.
6. Validate no-active-task island tap behavior.
7. Validate ask_user branch in VD always gives actionable capsule UI.

Pass condition:

- Navigation never enters "both hidden" dead state.

## 6. Exit Criteria

可以认为 "flows 都 work" 的最低标准：

- `P0` 全绿（0 fail）
- `P1` fail <= 1 且有明确 workaround
- 同一 flow 在 A11y/VD 各至少通过 2 次
- 所有失败都有可复现证据（截图 + step + log）

## 7. Test Report Template (per run)

```text
Run ID:
Mode: A11y | VD
Goal text:
Singer:

Flow Results:
- UF-01: PASS/FAIL
- UF-02: PASS/FAIL
...

Failures:
- Flow ID:
- Step:
- Expected:
- Actual:
- Evidence:
- Suspected layer (state holder / overlay manager / session / tool):
```

## 8. Recommended Execution Order

1. First stabilize `UF-01`~`UF-07` in A11y.
2. Then run VD navigation set (`UF-10`~`UF-16`).
3. Finally do mixed stress run (rapid island/capsule/viewer toggles during active task).

