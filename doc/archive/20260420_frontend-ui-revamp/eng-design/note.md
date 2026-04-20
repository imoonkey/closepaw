几个UX code改动的期待：

1. [product-aspect] 现在的chat ui对action只显示mobile action点了啥，不像capsule一样显示agent thought，信息量少，需要改变。
2. [Eng-aspect] 我的capsule code命名之前有问题，有些就叫row2, row3，这是垃圾命名，需要都改成semantic的命名(input, status row, control row, or whatever is better alignend with what the rows actually are).
3. [Eng-aspect] focus on improving UI code architecture refactoring if needed, to make it easier to make any appearance-aspect changes easy. Right now I may have some spaghetti code on UI side.
4. [Eng-aspect] write test and document UI side state-machines, like doc/main/state_machines/ when it is proper.
5. [appearance-aspect] 我觉得现在的doc/todo/frontend-ui-review/不错，可以以此为基础。

---

## 三个 Design Tracks

将以上 5 点拆分为 3 个独立的 design task（item 5 作为共享视觉基线，不单列）。每个 task 走 `/double-design`（Claude + Codex 各自设计 → 互评 → 对齐）。

### Track A — Chat Row Info Architecture (UX)
**覆盖**: item 1
**问题**: 现 chat 只显示 mobile action，缺 agent thought，信息密度远低于 capsule。
**产出**: 一个 chat turn 的 UX spec —— thought + action + result 的呈现方式、折叠/展开行为、上游 agent pipeline 哪些事件被 surface 到 UI。
**依赖**: 无（与 C 可并行）。会被 B 消费（新架构需容纳更丰富的 chat row）。
**Status**: ✅ Design complete (2026-04-20). Final aligned spec: [`track-a/final/design_aligned.md`](./track-a/final/design_aligned.md). Initial drafts and cross-reviews in [`track-a/initial/`](./track-a/initial/).

### Track B — UI Architecture Refactor + Semantic Naming (Eng)
**覆盖**: items 2 + 3
**问题**: capsule 命名 row2/row3 等垃圾命名；UI 侧 spaghetti，appearance-aspect 改动困难。
**注意**: item 2（rename）是 item 3（refactor）的副产品，不要 rename 两次。
**产出**: 现有 capsule + chat composable 的 audit，目标 module 边界（如 CapsuleInputRow / CapsuleStatusRow / CapsuleControlRow），迁移计划。
**依赖**: A 的 spec（架构需容纳新 chat row）+ C 的 tests（refactor 的 safety net）。
**Status**: ✅ Design + implement complete (2026-04-20). Final aligned spec: [`track-b/final/design_aligned.md`](./track-b/final/design_aligned.md). All `Row1/Row2/Row3` positional names replaced with `CapsuleStatusLine` / `CapsuleControlBar` / `CapsuleInputBar`; spec field `row3 → input`, `Row3Spec → InputSpec`, `buttonText → submitLabel`, `clearInput → clearDraft`; `SmartCapsuleCompose.kt` pass-through wrapper deleted; input draft state hoisted out of `SmartCapsuleSurface` into `CapsuleInputBar`. All Track C state-machine tests pass.

### Track C — UI State Machine Doc + Tests (Eng)
**覆盖**: item 4
**问题**: 缺 UI state machine 文档与回归测试，refactor 无 baseline。
**产出**: `doc/main/state_machines/` 下记录 capsule states (idle/listening/thinking/acting/takeover/supplement) 与 chat states；TDD 围绕状态转换的单测。
**依赖**: 无。**B 实施前必须完成** —— 没有 behavioral spec 的 refactor 会静默回归 UX。

### 依赖图与执行顺序

```
A (UX design)  ─┐
                ├─→ B design ─→ B implement ─→ A implement
C (state-doc) ──┘
```

A、C 并行启动；B 的 design 阶段消费两者输出；B implement 完成后，A implement 落在新架构上。