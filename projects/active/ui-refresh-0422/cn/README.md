# UI Refresh — 2026-04-22（"Bound Edition"）

> Post-D2 抛光轮次。不是重建。D1 token 系统、Track A row model、motion 词汇表
> 与 folded-paper chrome 全部保留——这一轮要收紧的是系统**已经赚到、却还没展现出来的
> page personality**。

## 一句话方向

> **从"得体的 editorial baseline"走向"一本可装进口袋的 bound editorial print"。**

系统已经备齐材料（Paper、Ink、Claw、Fraunces italic、paw glyph、mono ledger），
缺的是**排版者留下的手痕**：marginalia、running heads、章节之间的 fleuron、
paper grain、非对称构图、运行计数。这些 ornament 才能让一张披着暖色 token 的
Material 3 surface，从屋子那头就被人一眼认出来。

## 范围

In（在内）：

- **ChatHeader** — 给 brand 一个躯壳。当前是 `[Menu] ClosePaw [+]` 56dp Material 标准件。
- **EmptyState** — 从居中网格推到非对称的 editorial first-page。
- **MessageBubble (User)** — 抛掉 chip-shape 盒子，采用"letter to the editor"处理。
- **AgentTrace 左 rule** — 把 2dp 左 rule 颜色从 `outlineVariant`（8% Ink，Paper 上 2dp 处于 JND 边缘）提到 `Hairline`（12% Ink）。这条 rule 现在是一个 turn 的*主要*结构标记（替代了已退役的 `✱` glyph），值这个分量。
- **CollapsePill summary** — 把 pill summary 文本样式从 `labelLarge` (Geist) 换成 `monoSmall`。pill 内容是 ledger 形（`✓ N actions · 12s`），旁边又是 mono chevron —— 统一 register。
- **ThinkingIndicator** — 去掉 surfaceVariant chip，让 paw-toe canvas 与
  serif-italic 的 "Thinking…" 并排裸露。
- **OnboardingShell** — 五枚 paw 进度的对比度提升（当前 `outlineVariant` 在 light
  mode 里近乎不可见）；加上 monoSmall 的 running date。
- **NavigationDrawer header** — "Sessions" 改用真正加载的 Fraunces italic Regular
  （当前的 SemiBold 是合成出来的，因为只有 Regular + Italic 字体文件）。
- **SettingsHomePage** — 加 section grouping，subhead 用 serif italic；版本 footer
  上方居中放一个 fleuron。
- **CapsuleControlBar** — Stop button 全套 Rust 处理（border、text、icon——目前是混的）。
- **CapsuleStatusLine (Running)** — 在前面加 mono `[t+12s]` ledger 计数器，位于已有 `basicMarquee`（`uxfb-2`）**之外**，让计数器与 paw 保持固定，只有 thought 滚动。
- **Page chrome 原语** — 一个 `PageMasthead` composable，任一 top-level surface 可选用。
- **Paper grain 原语** — 一个 opt-in Modifier，把 1.5%-alpha 的 noise texture 烘焙一次。
- **First-paint choreography** — EmptyState 的 staggered reveal，复用现有 `TraceEnter`。

Out（不在内）：

- 新 color slot、新 radii、新 font family、新 motion duration。
- 触动 D1 token surface (`ClosePawTokens`) 签名，超出加两个 ornament 助手
  （`Fleuron` glyph、`paperGrain` modifier）的范围。
- 重做 Track A row state machine（`RowState`、collapse policy）。
- 改 Smart Capsule 的 state-machine 契约或 overlay 渲染路径。
- 改 reduced-motion 契约——每个新 motion 都尊重 `ClosePawMotion.reducedMotion()`。

## 文件清单

| 文件 | 作用 |
|---|---|
| `README.md` | 本文件 |
| `critique.md` | 当前状态评审，附 file:line 引用与 severity 标签 |
| `direction.md` | "Bound Edition" 美学方向与 ornament moves |
| `changes.md` | 按文件给出的具体修改清单与代码草图 |
| `motion.md` | choreography 的 delta（全部复用现有 `ClosePawMotion` duration） |
| `plan.md` | 分阶段实施顺序与 verify gate |
| `non-regressions.md` | 明确**不**撤销的 D1/D2 决议 |
| `align-with-uxfb-0422.md` | changelog：`uxfb-1 → uxfb-followup` 中途落地时所对齐的内容 |
| `preview.html` | 自包含的所有 surface 交互预览（light/dark） |

## 成功判据

一位首次打开 app 的用户，应该能描述出他看见了什么——而不需要说"一个 AI app"
或"一个 chat app"：

- [ ] 第一屏读起来像**一张印好的 page**，而不是 chat 的空状态。
- [ ] 发送第一条消息**之后**，brand identity 仍在场（不仅限于 EmptyState）。
- [ ] Smart Capsule 的 running 状态把 elapsed time 当作 ledger 计数器展示。
- [ ] 没有任何 Material slot 在使用合成字重（当前 Fraunces SemiBold 就是）。
- [ ] 任何 ornament 原语（fleuron、masthead、grain）都不出现在 `ui/theme/` 之外
      ——ornament 被 token 化，不是 call-site 的一次性写法。
- [ ] 所有新 motion 尊重 `ClosePawMotion.reducedMotion()`。
- [ ] `./gradlew lint test` 干净。无新增测试失败、无破坏 QA test tag 的字符串改动。
