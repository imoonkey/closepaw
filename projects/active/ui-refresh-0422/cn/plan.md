# 实施计划

> 五阶段，每阶段独立可发。每阶段后跑 `./gradlew lint test`。如果某阶段触发了
> 现有测试失败，只在该测试断言的是*被移除*的美学（例如断言 `outlineVariant`
> 分隔色）时才修测试。
>
> 反映的代码状态截至 `08ae6378` —— 即 `uxfb-1 → uxfb-followup` 落地了
> ThoughtGroup、CollapsePill、FinalText、capsule marquee 之后。Phase 3 已被
> 裁掉（撤回两条 move、新增两条一行 move）以匹配新的 chat 形态。差异历史见
> `align-with-uxfb-0422.md`。

## Phase 1 — Theme 原语（地基，不改 UX）

- [ ] 加 `ui/theme/Ornaments.kt`：`Fleuron()` 与 `PageMasthead()`。
- [ ] 加 `ui/theme/PaperGrain.kt`：`Modifier.paperGrain()` 与
      `Modifier.lanternVignette()`（dark 下的 no-op composable seam）。
- [ ] 决定 Fraunces 路径：发 `fraunces_medium.ttf`（方案 A）或撤 upright
      Fraunces（方案 B）。若 A，加字体 asset 并更新 `Type.kt::Fraunces`
      `FontFamily`。若 B，无 asset 改动。
- [ ] call site 暂不改。
- [ ] 验证：`./gradlew assembleDebug lint`。

**Gate：**新文件可编译，Compose preview 无回归。

## Phase 2 — 每个 top-level surface 上的 brand chrome

- [ ] `ChatHeader.kt`：把中央 title 换成 `PageMasthead("ClosePaw", todayLabel())`。
- [ ] `NavigationDrawer.kt::DrawerHeader`：把 `Sessions` Text 换成 `PageMasthead`。
- [ ] `SettingsHomePage.kt`：在最前加 `PageMasthead("Settings", todayLabel())`。
- [ ] `ChatScreen` 根 box 加 `paperGrain()`（仅 light）。
- [ ] 验证：在设备或 Compose preview 中打开每个屏幕，light/dark 各截图，目测
      masthead 读起来像 colophon（不是 data）。

**Gate：**brand 出现在每个屏幕，不仅是 EmptyState。

## Phase 3 — Chat 内容 ornament

- [ ] `MessageBubble.kt::UserBubble` → letter-to-the-editor（去掉 Surface，加
      Hairline 左侧 margin rule，mono timestamp inline）。
- [ ] `ThinkingIndicator.kt` → 裸露 Row，无 Surface chip。
- [ ] `AgentTrace.kt:109` —— 左 rule 颜色从 `outlineVariant` → `outline`
      （Hairline）。一行改动；P2，落地前知会 `uxfb-4` 原作者。
- [ ] `CollapsePill.kt:72` —— summary style 从 `labelLarge` → `monoSmall`。
      一行；P2 抛光，无测试影响。
- [ ] `ChatScreen.kt::live pill` → mono pill，显式 border。
- [ ] 验证：固定测试 `ThinkingIndicatorTintTest`、`ThinkingIndicatorCadenceTest`、
      `ChatDoneBridgeTest`、`CollapsePillTest`、`ThoughtGroupTest` 仍通过。
      QA test tag（`qa-user-bubble`、`qa-thinking-indicator`、`qa-agent-bubble`、
      `qa-collapse-pill`、`qa-final-answer`）保留。

**Gate：**chat 读作 editorial page，不是 Material card 堆。

## Phase 4 — EmptyState 重建

- [ ] 按 `changes.md` §EmptyState 整体重写。
- [ ] 新增 `StaggeredReveal` helper（`EmptyState.kt` 内私有）。
- [ ] 硬编码三条 suggestion，每条一个 verb + gloss 对（或扩 data model ——
      两行 suggestion 是一次 UX 升级）。
- [ ] 验证：冷启动 app，看一遍 staggered reveal，验证 reduced-motion 模式下
      所有元素立即可见。

**Gate：**首次启动屏读作一张印刷的首页。

## Phase 5 — Capsule 抛光 + 杂项

- [ ] `SmartCapsuleSurface.kt`：加 `produceElapsedLabel(mode)` ticker，传给
      `CapsuleStatusLine`。
- [ ] `CapsuleStatusLine`：把 `[t+12s]` mono ledger 渲染为它自己的 `Text`，
      放在带 marquee 的 thought *之前*（uxfb-2 契约）。Ledger 保持固定，
      thought 滚动。
- [ ] `CapsuleControlBar.kt`：Stop button 显式 Rust `BorderStroke`。
- [ ] `CapsuleInputBar.kt`：submit 时 send-button breath（240ms scale）。
- [ ] `SmartCapsuleSurface.kt::StartupErrorBanner`：2dp Rust 左 rule + ⚠ glyph。
- [ ] `OnboardingShell.kt::FivePawProgress`：未填充色 → `inkFaint`。
- [ ] `SettingsHomePage.kt`：加 `SectionHeader` 与 `Fleuron()` ornament。
- [ ] 验证：双主题全 app 巡检，跑 `/qa` skill。

**Gate：**capsule 在长任务期间显示 elapsed time；settings 有分组；onboarding
进度真正可见。

## 最终 gate

- [ ] `./gradlew clean assembleDebug lint test` 干净。
- [ ] 跑 `/verify` skill。
- [ ] 跑 `/ux-visual-debug`：冷启动 → EmptyState；发一条消息 → ChatHeader
      masthead 可见；触发长任务 → capsule ticker；打开 drawer → masthead 与
      ledger date；打开 settings → section + fleuron。
- [ ] 更新 `doc/main/ui/style.md`：Fleuron 原语、PageMasthead 原语、paperGrain
      modifier、ledger counter 模式。引用 `Ornaments.kt` 与 `PaperGrain.kt`。
- [ ] 更新 `doc/main/ui/user_interaction.md`：mastheaded surface 列表、
      EmptyState marginalia 布局、capsule running counter 行为。
- [ ] commit 时把本文件夹归档为 `projects/archive/20260422_ui-refresh/`。

## 回滚

每阶段独立 commit。如果 Phase N 导致回归，回滚那个 commit；前 N-1 阶段独立成立。

## 时间估算

| Phase | 复杂度 | 估时 |
|---|---|---|
| 1 | 新文件，无 UX | 1-2h |
| 2 | 机械替换 + paperGrain wiring | 2-3h |
| 3 | chat ornament 工作 + 在 uxfb-3/4 代码上加两个一行 move，回测 | 2h |
| 4 | EmptyState 重建 + stagger | 3-4h |
| 5 | ticker + 跨 5 个文件的小抛光 | 2-3h |

总计：~10-14h 专注工作。无意外的话单 session 可完成。
