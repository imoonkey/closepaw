# 不回归项

> 在写完 `direction.md` 与 `changes.md` 之后，与
> `projects/archive/20260420_frontend-ui-revamp/aligned/design_aligned.md` 交叉核对。
> 列出本轮被约束的先验 invariant，加上两处本轮**自觉扩张**先验范围的位置
> （附理由）。

## 守住的 invariant —— 本轮不动

| 先验 invariant（D1 / aligned design） | 本轮 |
|---|---|
| **One chat model only** —— Track A：每轮一个 row、时序 trace、可选 Final、单 row-level disclosure axis | 守。仅 re-ornament；collapse policy 与 `RowState` machine 不动。 |
| **One identity system** —— 调色板 + typography + paw glyph + 安静 motion；不要一长串一次性 token | 守。新 ornament（Fleuron、PageMasthead、paperGrain）全部伸手现有 token；无新色、无新字、无新时长、无新 radii。 |
| **No color-only meaning** —— paw + 语义色总是配文字/标签 | 守。Capsule ledger counter 与 thought text 配对；stop button 保留 label；status dot 仍带现有 `stateDescription`。 |
| **Two divider levels only** —— `Hairline`（12% Ink）在组件之间，`InkGhost`（8% Ink）在组合 surface 内部 | 守。UserBubble 左 rule 复用 `Hairline`。`FinalSeparator` 这条规则被 fleuron *替换* —— 没有引入新 divider 级别。 |
| **Mono is reserved for machine-like strings**，不是"executing mode"通用字体替换 | 守。新增 mono call site：capsule `[t+12s]` ledger 计数器（机器计时）、`[+]/[−]` 折叠括号（交互状态 ledger）、`live` pill（状态 ledger）。EmptyState 的 suggestion 动词**保持 `bodyLarge` Geist** —— 明确*不*用 mono ——因为它们是用户语气的 prose，不是机器输出。 |
| **Capsule breath in Running only** | 守。Send-button breath 是 submit 时的 one-shot，不是常驻 loop。 |
| **Track A row voice locked**：Thought=Geist regular（uxfb-4 后）、Action=mono、Final=Geist regular | 守。`AgentTrace.kt` 中 ThoughtHeader / Action / Text 样式不变；本轮在 chat 区只动 `UserBubble`、`ThinkingIndicator`、`AgentTrace` 左 rule 颜色（N2）、`CollapsePill` summary register（N3）。 |
| **Reduced-motion 契约**：每个 call site 自己读一次，无全局 wrapper | 守。EmptyState stagger 与 send-button breath 各自调用 `ClosePawMotion.reducedMotion()` 并就地跳过；capsule counter 无条件 tick（status signal，与 streaming cursor 同类）。 |
| **Horizontal page padding 是 `lg` (20dp)，硬 invariant** | 守。`smartCapsuleHostPadding`（16dp）与各屏幕的 padding 数值不变。 |
| **三个 Material radii** —— small (8) / medium (10) / large (16) | 守。无新 shape 常量。 |
| **Status glyph 在 accessibility tree 中配文字** | 守。`qa-thinking-indicator`、`qa-user-bubble`、`qa-agent-bubble` 的 test tag 与 `stateDescription` 标签保留。 |
| **`foldedPaper` chrome 稀缺** —— 仅 capsule + modal sheet/drawer | 守。不把 `foldedPaper` 扩到 chat surface。PageMasthead 是平的 row + Hairline rule，不是 folded card。 |

## 自觉扩张先验范围

两处本轮越过 `design_aligned.md` 字面要求。两处都是有意为之，且符合先前所定
"identity surface" 的精神。

### 1. Fraunces italic Regular 从"empty-state question only"扩到 PageMasthead 与 section subhead

**先验原文：**`serifItalic — Fraunces italic, used only on the empty-state question.`

**本轮：**Fraunces italic Regular 也出现在：
- `PageMasthead` 标题（ChatHeader、NavigationDrawer header、Settings home）
- Settings home 的 `SectionHeader`（小，三个 call site）

**理由：**先验那一轮自己就已经把 Fraunces（upright，合成 SemiBold）用在了
`OnboardingShell` 标题和 `NavigationDrawer` header —— 所以"empty-state
question only"的措辞本就是理想，未被强制。本轮把这些 identity surface 统一收
进一个原语（`PageMasthead`），并把字重统一到*已加载*的 italic Regular，而不是
合成的 SemiBold（这是相对当前状态的*保真度提升*）。本轮之后 Fraunces 的总体足
迹是缩小，不是放大，因为我们丢掉了合成字重。

如果 review 时这一扩张有争议，回退选项是 `changes.md` §Type 中的
**Type 方案 B** —— 把 upright Fraunces 完全从 identity surface 撤出，所有
identity call site（包括 title slot）都只用 italic Regular。两条路都保住
"Fraunces is identity"的纪律。

### 2. JetBrains Mono 扩到 `CollapsePill` 的 summary 文本

**先验原文：**`Mono is allowed only for explicitly machine-like strings,
not as a blanket "executing mode" font swap.`

**本轮：**`CollapsePill.kt:72` 的 summary 文本（渲染形如 `✓ N actions · 12s`
的字串）从 `labelLarge`（Geist medium）换到 `monoSmall`（即 `direction.md`
里的 6b）。

**理由：**summary 内容本身就是 ledger 形（status glyph + 数量 + 时长），旁
边的 chevron 已经是 `monoBody`。这一换让 32dp 的小 surface 读作一个 machine-
ledger 对象。它与既有的 mono action subtitle、mono drawer ledger date、新加
的 capsule ledger counter 同 register、同角色。它*不是*在 row body 内容上做
字体替换；ThoughtHeader、action description、FinalAnswer body 都保持 Geist。

> 早先版本里讨论的是给 legacy `CollapsedHeader` 加 `[+]/[−]` mono *括号*。
> `uxfb-3` 发布了真正的 `CollapsePill`（带可旋转 chevron）后，那条 move 已
> 撤回 —— 见 `align-with-uxfb-0422.md`（W2）。上面这条 mono summary 替换
> 才是本轮真正引入的 mono 扩张。

## 先验中本提案不重新审视的事项

archive 列出了若干已结案的关切。一律不重开：

- Streaming cursor 用 serif Fraunces（archive §Open-questions 的"Phase 3 verify"）—— 不动。
- Track B（overlay）工作 —— 范围外；本轮只动主 app + capsule。
- Track D（D1 → D2 抛光）—— 已完成；PROGRESS 已确认。我们从 post-D2 接手。
- QA 报告（`qa_report.md`、`r2`、`r3`）与 codex review 已发的修复 —— 不重审。

## 验证清单（合并前）

实施完成后确认：

- [ ] Fraunces 仅出现在：PageMasthead 标题、SettingsHomePage section header、
      EmptyState 问句、Fleuron ornament。（统一 italic Regular。）
- [ ] Mono 仅出现在：ledger date（drawer、settings footer）、action subtitle
      （现有）、capsule ledger counter（新，位于 marquee 之外）、`CollapsePill`
      summary（新 —— 6b）、`live` pill（新）、`CollapsePill` chevron（现有，
      uxfb-3）。
- [ ] Geist body voice 覆盖所有 prose，包括 suggestion 动词。
- [ ] `Color.kt` 无新 color slot。`Shape.kt` 无新 shape。`Motion.kt` 无新
      duration。
- [ ] Reduced-motion 模式下 EmptyState 与 send button 无动画渲染；capsule
      counter 仍 tick；capsule breath 仍暂停（按现有契约）。
- [ ] 所有现有 QA test tag 与 `stateDescription` 标签完好。
