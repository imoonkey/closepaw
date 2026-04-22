# UI SOTA Alignment — Claude Round (0003)

Date: 2026-02-20
Role: CLAUDE
Vote: CHANGES

## 本轮结论

v2 质量高，三份 doc 结构和内容与代码一致。State machine 和 user flow 无需修改，直接对齐。Suggestions 补了一个遗漏项。

## State Machine SOTA — APPROVED

v2 正确区分了 `onTaskCompleted` (§2.3) 和 `onSessionEnded` (§2.4) 两条路径，符合 `CapsuleStateHolder.kt:205-254` 的实现：

- `USER_STOPPED` → task path: `Done("Stopped")`; session path: `Hidden`
- `GOAL_ACHIEVED` → session path preserves existing Done text if already Done

`resolveUserLocation` ignore 条件 (§3)、runtime caveat (§6) 均准确。无修改。

## User Flow SOTA — APPROVED

Runtime premise、per-location flow tables、VD viewer touch forwarding (Takeover only) 和 MAIN_APP visibility convergence 全部准确。

A11y island 作为 code reality + policy question 的处理方式合理，不需要在 SOTA 中做取舍。

无修改。

## UI Suggestions — 一处增量修改

### 新增 §3.3: `dismissError` routing bypasses controller (P2)

`ChatViewModel.dismissError()` (line 232) 直接访问 `capsuleStateHolder.onDismissError()`，绕过 `ServiceOverlayController`。Overlay path 走 controller callback (line 86)。

虽然当前因 mode observer 能 catch Hidden 变更而无功能影响，但后续如果 controller 需要在 dismissError 时做 side-effect，main app path 会 miss。

已在 `ui_suggestions.md` §3.3 和 execution order §5 增加。

### 对现有 suggestions 的立场

| Item | Status |
|------|--------|
| §1.1 touchability P0 | 同意 mode-driven baseline |
| §1.2 A11y island P1 | 同意"pick one policy explicitly"，倾向 keep ⊖ (user convenience)，但遵从 user 决策 |
| §1.3 interaction lock P1 | 同意 blocked on touchability |
| §2.1-2.3 revalidation | 同意 all three |
| §3.1 UserResponse P1 | 同意 |
| §3.2 resolveUserLocation P2 | 同意 |

### Open Questions

保留 v2 的三个 open questions，无修改。
