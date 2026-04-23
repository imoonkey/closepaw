# 与 uxfb-0422 对齐 — changelog

> **本文的实质内容已被合并回各源文件。**本页现在只作为 changelog —— 记录
> `uxfb-1 → uxfb-followup` 中途落地时所对齐的内容。直接读源文件
> （`README`、`critique`、`direction`、`changes`、`plan`、`non-regressions`）
> —— 它们已是独立正确的。

## 背景

本轮文档书写期间，六个 commit 落入 `main`：

| commit | 发布内容 |
|---|---|
| `uxfb-1` | `ThoughtUpdate { full, compact }` —— thought 全文端到端保留。`sanitizeThought` → `compactThought`。 |
| `uxfb-2` | Capsule thought 通过 `Modifier.basicMarquee` 滚动；reduced motion → `compactThought()`（~80 字）。 |
| `uxfb-3` | `ContentBlock.FinalText` + `CollapsePill.kt` + `AgentRow.kt` 拆分。Row 根不再可点击。FinalAnswer 始终在 pill 下方可见。 |
| `uxfb-4` | `AgentTrace.kt` 抽出。ThoughtGroup 层级，**2dp `outlineVariant` 左 rule** 作为 marker（替代 `✱` glyph）。ThoughtHeader = `bodyLarge / onSurface`，无 italic。`MessageBubble.kt` 变成薄 dispatcher。 |
| `uxfb-followup` | `complete_task` 不再截断；`MainActivity` 用 `adjustNothing`；`ChatScreen` bottomBar 加 `imePadding`。 |

## 撤回

| Move | 原因 |
|---|---|
| **W1.** `MessageBubble::FinalSeparator → Fleuron()` | `uxfb-3` 把边界做成结构性的：`[trace] [CollapsePill] [FinalAnswer]`。Complete 行上 pill 本身就是边界；Live/Waiting/Error 行还没有 Final block 可分隔。fleuron 原语本身保留 —— Settings home 仍用它。 |
| **W2.** `CollapsedHeader / OutcomeFooter → [+]/[−]` mono 括号 | `uxfb-3` 发布了 `CollapsePill.kt`，带可旋转 `▸` chevron（已 codex-review）。不要去对抗刚发的组件。 |

## 已被 uxfb 吸收（从 `critique.md` 删）

| Critique | 由谁修复 |
|---|---|
| `[H] FinalSeparator 低于 JND 阈值` | `uxfb-3`（结构性边界） |
| `[M] AgentRow ✱ glyph + italic+onSurfaceVariant Thought = 倒置层级` | `uxfb-4`（`AgentTrace.kt:105-110` —— bodyLarge/onSurface，2dp 左 rule 替代 `✱`） |
| `[M] Track A row voice：italic Thought 与 mono Action 冲突` | 同上 —— Thought 不再 italic |

## 新增（新形态衍生的发现）

| Finding | 落入源文件的位置 |
|---|---|
| **N1.** Capsule ledger counter 必须位于新 `basicMarquee` *之外*（paw + ledger 固定，thought 滚动） | `direction.md` §7 + `changes.md` §SmartCapsuleSurface |
| **N2.** `AgentTrace.kt:109` —— 把 2dp 左 rule 颜色从 `outlineVariant`（8% Ink，Paper 上 2dp 处于 JND 边缘）提到 `outline`（Hairline，12%）。这条 rule 现在是 turn 的*主要*marker —— 值这个分量。 | `critique.md`（新 [H]）、`direction.md` §6a、`changes.md` §AgentTrace、`plan.md` Phase 3 |
| **N3.** `CollapsePill.kt:72` —— summary 文本从 `labelLarge`（Geist）换到 `monoSmall`，让 chevron + summary 读作一个 ledger 对象。 | `critique.md`（新 [M]）、`direction.md` §6b、`changes.md` §CollapsePill、`plan.md` Phase 3、`non-regressions.md` §2 |

## 保留（无变化）

13 条 move 按原样保留：PageMasthead、Fraunces SemiBold 合成修复、paperGrain
modifier、Stop button 全 Rust border、capsule ledger counter（加了 N1 布局
注释）、send-button breath、StartupErrorBanner Rust rule + ⚠、EmptyState 非
对称重建、user bubble letter-to-the-editor、ThinkingIndicator 裸露、五枚
paw 进度对比度、Settings 分组 + fleuron + mono 版本、`live` pill mono 重塑。

## 阶段计划 delta

`plan.md` Phase 3 从"5 项 chat ornament 改动，3-4h"压缩到"3 项 chat 改动 +
2 项一行 move，2h"。整轮估时从 12-16h 降到 **10-14h**。

## Bound Edition 方向不受影响

美学方向与两个新 theme 原语（`Ornaments.kt`、`PaperGrain.kt`）按原样保留。
本对齐只是把轮次裁到匹配新的 MessageBubble 形态 —— 不是撤退。
