# Roadmap —— 分阶段交付

五个阶段。每个都可以独立 ship。总时长：约 3 周专注前端工作 + 1 周 polish。

> 英文原版：[`../roadmap.md`](../roadmap.md)

---

## Phase 1 —— Identity 基础（3–4 天）

**目标：** 第一天 app 就**感觉**不一样，哪怕 component 一个没改。

- [ ] 加字体文件：`fraunces_variable.ttf`、`geist_variable.ttf`、`jetbrains_mono_variable.ttf` → `app/src/main/res/font/`
- [ ] 重写 `Color.kt`，用 Paper / Ink / Claw / Moss / Amber / Rust palette（[`design-tokens.md §1`](./design-tokens.md)）
- [ ] 重写 `Type.kt`，引用三个 font family，通过 `CompositionLocal` 加 `AgentExtraTypography`
- [ ] 画 `ic_paw.xml` vector（3 个 toe-pad + 主 pad，分开 path）
- [ ] 更新 `Theme.kt`，把 Material 3 color role 映射到新 token，保留 light + dark（Lantern）两套 palette
- [ ] App icon 前景换成 paper 上的 claw 色爪印
- [ ] **Verify：** build + install + 截 chat、capsule running、settings home。应该已经无可争议是 *ClosePaw* 的样子。

Commit gate：`feat(ui): adopt Tactile Intelligence palette + paw-print glyph`

---

## Phase 2 —— Smart Capsule（4–5 天）

**目标：** signature overlay 成为 app 里最 refined 的表面。

- [ ] `SmartCapsuleSurface` —— 在 mode render 外面套 `AnimatedContent`，用 [`motion-spec.md §2`](./motion-spec.md) 里的 transition spec
- [ ] `StatusIslandCompose` —— 把 `8.dp` `Box` dot 换成 `ic_paw`，用 `CapsuleColors` 上 tint
- [ ] 仅在 Running mode 给爪加 breath 动画
- [ ] Mode 专属 override：Takeover 冻结、Error 抖动、Done 眨一次
- [ ] 把 `shadowElevation(4.dp)` pill 换成折纸 elevation（顶部 hairline + 暖色 under-shadow），见 [`design-tokens §5`](./design-tokens.md)
- [ ] `EdgeGlowCompose` —— linear gradient 换成 radial 衰减、锚点跟随 capsule 位置、8s drift、alpha 上限 0.12
- [ ] 把 `CapsuleRenderSpec` 里所有 emoji 去掉（✋、✓、⚠、💬、🛡）—— 用 paw-state + 语义色替代
- [ ] **Verify：** 录一段 live eval —— Running → Takeover → Done 应该是流动的，不是跳变的。

Commit gate：`feat(capsule): breathing paw + folded-paper pill + mode animations`

---

## Phase 3 —— Chat 屏幕（4–5 天）

**目标：** 主阅读表面读起来是编辑式，不是 chat。

- [ ] `MessageBubble` —— **完全去掉 agent bubble**；agent 文字渲染成 paper 上的 prose，加 3px claw 左 margin tick
- [ ] User bubble → `PaperInset`、对称 10dp corner、无尾巴
- [ ] `ActionCard` —— 重新设计为排版式收据：顶部 hairline、mono tool name、右对齐 paw 状态、底部 hairline、可展开 output。无背景填充。
- [ ] `ThinkingIndicator` —— 三点 → 三趾依次填色（900ms 周期，[`motion-spec §4`](./motion-spec.md)）
- [ ] `StreamingText` —— block cursor → Fraunces 的 serif I-beam `|`，保持闪烁节奏
- [ ] `EmptyState` —— 160dp 爪水印、Fraunces italic 问句 "What should we look into?"、suggestion chip 改成 paper card 里的下划线 serif 链接
- [ ] `ChatHeader` —— title 用 Geist Medium，title 左侧加爪印 glyph（claw，静态）
- [ ] **Verify：** 读一段长的 agent 回应（含 3+ action card）。应该感觉像在读 report，不是在刷 feed。

Commit gate：`feat(chat): editorial prose treatment + typeset action receipts`

---

## Phase 4 —— Settings / Onboarding / Drawer（3–4 天）

**目标：** First run 和配置表面承载身份。

- [ ] `SettingsHomePage` —— Fraunces section head 带 hanging numerals（"01 — Permissions"）、hairline row divider，arrow 替换为 mono `→` glyph
- [ ] `ApiKeyFields` —— JetBrains Mono、1px ink border、focus 时 claw、show/hide 用 ink `eye` icon
- [ ] `OnboardingShell` —— progress bar 换成五个爪印一排；章节跨页用 120sp Fraunces 罗马数字水印
- [ ] `PermissionRepairCard` —— 电报样式：tracked-out caps header + mono body
- [ ] `NavigationDrawer` —— 账本式：mono 日期、Geist title、faint preview；new-session 顶部 claw 红 "New entry" serif 链接；底部 settings 入口为 `// preferences` mono 链接
- [ ] **Verify：** 截每个表面的 light / dark 双版本。每张图都应在没有 logo 时被认出是 ClosePaw。

Commit gate：`feat(ui): field-journal treatment for settings, onboarding, drawer`

---

## Phase 5 —— Motion + polish（持续，约 1 周）

**目标：** 把"很好"和"世界级"区分开的细节。

- [ ] Action visualizer —— ink-drop tap + satellite ring、long-press hold 带脉动填充、perlin-wobble swipe、终点爪印 stamp（[`motion-spec §3`](./motion-spec.md)）
- [ ] Orchestrated 冷启动 entrance（[`motion-spec §5`](./motion-spec.md)）
- [ ] 全局移除 Material ripple —— 通过 `InteractionSource` 用 pressed-color 状态替代
- [ ] 移除任何 list enter 动画；audit 所有 `animateDpAsState`，duration 必须在 `{120, 240, 480, 900}` 集合内
- [ ] Haptics pass：Capsule mode 切换时单次 fine-tick（`HapticFeedbackConstants.CONFIRM`），其他都不要
- [ ] 截图回归集 —— 加一个 "frontend golden" eval，capture capsule 全部 8 个 mode、chat 三种消息类型、settings home、onboarding step 1。PR 里做视觉 diff。
- [ ] Dark mode pass —— 验证 Lantern palette 在每个表面正确；这一步在 phase 1 容易被忽略，演示时回报极大。

Commit gate：每个 polish 项一个小 commit。不要搞一个 "polish" 大 commit。

---

## 成功标准

Phase 4 之后，下面三件事都应该成立：

1. **截图测试。** 一个 designer 只看 capsule、chat、或 onboarding 的局部截图，没有 logo 就能认出这是 ClosePaw。
2. **冷静测试。** 录 30 秒 agent 执行。屏幕上没有任何元素的变化快于 120ms 或慢于 900ms，唯一例外是那一次 orchestrated entrance。
3. **奶奶测试。** 一个非技术 user 第一次跑完 onboarding 不困惑，*并且* 24 小时后还记得那个爪印。

---

## 本次 revamp 的 non-goals

明确出 scope —— 不要 scope-creep：

- **重新设计 agent 的思考行为或 tool UX。** 这次只动 frontend。
- **加新 feature。** 今天不在屏幕上的，明天也不在屏幕上。
- **Marketing 站 / brand book。** Ship app 设计；brand book 自己会从中长出来。
- **iOS / Web 对齐。** Android-first，Android-only。在这个平台上，**爪就是产品**。

---

## 风险记录

| 风险 | 缓解 |
|---|---|
| Fraunces + custom font 给 APK 增加约 300KB | 用 variable font；通过 `fontFamily` variation setting subset 到 Latin + 标点。目标 <180KB 总。 |
| 去掉 agent bubble 破坏用户的 mental model | 先在 debug flag 后做 A/B，内部 dogfood 一周，再默认开 |
| Breath 动画在长 Running 状态会耗电 | activity 不在前台时暂停 infinite transition；`lifecycle.state < RESUMED` 时爪退回静态 |
| 移除 emoji 会丢 screenreader 语义 | 每个被移除的 emoji 在爪 glyph 上替换成 `contentDescription`（如 "running"、"paused"） |
| Claw-red accent 在 Paper 上不过 accessibility 对比度 | 实测：`C44528` on `F5F1EA` = 5.4:1（AA large/UI 通过）。Body text 用 Ink，绝不用 Claw。 |

---

## 排序说明

Phase 1 → 4 可以作为独立 commit 按序合并。Phase 5 项随机会落地。**不要**
把多个 phase 拼成一个巨型 PR —— 这个 roadmap 的意义就在于每个 phase
单独都能交付一次 identity 上的 step-change。
