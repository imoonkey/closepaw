# 方向 — "Bound Edition"

> 这是这一轮的代号，不是新 theme。美学仍是 D1：暖色 Paper、深色 Ink、稀缺 Claw、
> paw-glyph identity、Geist body、Fraunces italic identity、JetBrains Mono ledger。
> 改的是**register** ——从"称职的 editorial layout"走向"你愿意从书架上拿起来的物件"。
>
> 反映的代码状态截至 `08ae6378` —— 即 `uxfb-1 → uxfb-followup` 落地了 ThoughtGroup、
> CollapsePill、FinalText、capsule marquee 之后。§6 已被改写，用于调音这些新组件
> 而不是与它们对抗。差异历史见 `align-with-uxfb-0422.md`。

## 心理模型

想象 ClosePaw 以一本小册子的形态出版，perfect-bound，纸张 90gsm 暖白。每个屏幕
是一个 spread：顶端有 running head，章节之间有 fleuron，系统在用户旁边说话的地方
有 marginalia，colophon 附近有 mono 的 ledger date。Smart Capsule 是 dust jacket
——折起来，下面有真实的影子。Agent 的运行 trace 是一列分组的 thought，每组以一条
细垂直 rule 标记，像章本的 margin rule。用户的轮次是一封手写信，紧贴同样的 rule、
右对齐。

凡是落到屏幕上的 ornament，都有一个 print-shop 的理由要存在。没有装饰为装饰。
下面每一个动作都对应一个真实的 trust signal、归属问题或节奏拍子——这些目前都被
Material 3 默认值留作匿名状态。

## 八个 ornament moves

### 1. Page Masthead（新原语）

一条 32dp 的 running head，opt-in 的 composable。构成：

```
[paw 18dp]   ClosePaw                    No 04 · Apr 22
             ────────                    ──────────────
             ↑ Fraunces italic Reg 18sp  ↑ monoSmall, inkFaint
```

下方：横跨整宽的 1dp Hairline rule。

使用方：ChatHeader（替换当前 Geist title）、NavigationDrawer header（替换
"Sessions"）、Settings home（page 顶部）。

"No 04" 是 build/version 前缀；"Apr 22" 是本地化的 `Today`。两者读作 colophon，
不是 data ——用户不该去*解析*它们，只该去*认出*它们。

### 2. Fleuron 分隔符

居中的 Fraunces glyph `❦` (U+2767)，颜色 `inkFaint`，16sp，上下各 12dp 呼吸空间。
用于：

- SettingsHomePage 中版本 footer 上方的空白
-（可选）drawer session 列表与 Settings entry 之间的空白

一个 glyph、两个 call site、一个 composable，落在 `ui/theme/Ornaments.kt`。

> **撤回的 call site：**原方案有第三个用法 —— 在 `AgentRow` 里 Trace 与 Final
> 之间。`uxfb-3` 把这条边界做成结构性的（`AgentRow.kt`：`[trace] [CollapsePill]
> [FinalAnswer]`），所以在 Complete 行上 pill 本身*就是*边界。fleuron 在 chat
> 行里没有剩余职责。见 `align-with-uxfb-0422.md`（W1）。

### 3. 非对称 EmptyState（新构图）

布局（portrait）：

```
                                       ┌──────────────┐
                                       │              │
                                       │   ╱╲         │ ← Paw, 240dp, 80% Ink,
                                       │  (◯◯)        │   bleeds top-right
                                       │   \◯\        │   (overflow: clipped to 0)
                                       └──────────────┘

  What can I                                          ← Fraunces italic 32sp
  help you with?

  ────────                                            ← Hairline 24dp wide

  → Check unread emails                               ← bodyLarge, onSurface
    a quick triage                                    ← Fraunces italic, inkMuted

  → Turn on Do Not Disturb                            ← (同上)
    for the next hour

  → Find nearby restaurants
    open right now
```

完全去掉 suggestion chip card。每条 suggestion 是堆叠的两行：动词在 body
（用户的声音），serif italic 的注释（系统的解读）。点击目标：整行宽，垂直 padding 慷慨。

揭示 choreography：4 段 stagger × `TraceEnter` (240ms)，每段间隔 80ms。总计
480ms。Reduced-motion → 立即显示。

### 4. User bubble 当作"letter to the editor"

抛掉外层 `Surface`。文字右对齐。在左侧加一条 1dp Hairline margin rule，延伸
完整文本高度。Timestamp 紧贴同一列右下角，`monoSmall`，上方不留间隔。

```
                                                          │  Hey, can you check
                                                          │  my calendar for
                                                          │  tomorrow?
                                                          │            14:32
```

那条 rule 让"右对齐"读作有意为之的 column，而不是没装饰的 wrap。成本：零新
token；复用 `Hairline` 与 `monoSmall`。

### 5. ThinkingIndicator，裸露

```
[paw-toe 28dp]  Thinking…
                ↑ closePaw.bodyItalic, inkMuted
```

去掉 `Surface(shape = large, color = surfaceVariant)`。在 `AgentRow` 的 `Column`
里 inline 渲染。宽度自适应。paw-toe 动画本身就是被设计过的 ornament，不需要
chip 包它。

### 6. AgentTrace 左 rule + CollapsePill summary，统一 register

针对 `uxfb-3/4` 刚发的代码做两个一行改动。两个动作都收紧新 chat 形态已经
暗示的 ledger voice。

**6a. ThoughtGroup 左 rule 重量。**`AgentTrace.kt:109` 把 2dp 左 rule 涂成
`outlineVariant`（8% Ink）。在 Paper 上 2dp × 8% 的视觉权重大约等于 1dp
Hairline —— 勉强可接受，长时间阅读偏弱。鉴于这条 rule 现在是说"一个 turn
从这里开始"的*唯一*marker（`✱` glyph 已退役），它应当具备一根真正结构 divider
的分量。

```kotlin
// AgentTrace.kt:109
.background(MaterialTheme.colorScheme.outline),  // 原为 outlineVariant
```

**6b. CollapsePill summary register。**`CollapsePill.kt:72` 把 summary
（`✓ N actions · 12s`）渲染成 `labelLarge`（Geist medium 14sp）。旁边的
chevron 是 `monoBody`（JetBrains Mono）。summary 内容本身就是 ledger 形 ——
统一 register，让整个 pill 读作一个 machine-ledger 对象。

```kotlin
// CollapsePill.kt:72
style = MaterialTheme.closePaw.monoSmall,        // 原为 labelLarge
```

> 早先关于 `CollapsedHeader` 用 `[+]/[−]` mono 括号的 move 已撤回 ——
> `uxfb-3` 发布的真正 `CollapsePill` 带可旋转 chevron，已取代它。
> 见 `align-with-uxfb-0422.md`（W2）。

### 7. Capsule ledger 计数器

当 `mode is CapsuleMode.Running`，给 `CapsuleStatusLine` 的 thought text 前面
加一个 mono 计数器：

```
[●]  [t+12s]  reading inbox — three threads landed, the dentist…→ marqueeing
 ↑    ↑       ↑ titleMedium, basicMarquee（uxfb-2），reduced-motion → compactThought()
 paw  monoSmall, inkFaint —— 固定
```

通过 `LaunchedEffect(mode)` 的 ticker 每 1s 更新。Done/Error 时停。Reduced-motion：
继续 tick（这是 status signal，不是装饰——与 streaming cursor blink 同类，
对应 `Motion.kt:42-43`）。

**布局注意：**ledger 计数器是**独立的 `Text`**，放在 thought `Text` 之前，
**不要**包进 marquee 的 row。paw 与 ledger 保持固定，只有 thought 滚动。
Reduced-motion 下 ledger 仍 tick；thought 退到 `compactThought()`（沿用
uxfb-2 既有契约）—— 两者独立。

这是这一轮里产品功能性最强的 ornament ——用户以前就要过 elapsed time；editorial
register 让加上它显得免费。

### 8. Send-button breath

`CapsuleInputBar.kt::FilledIconButton`（当前 CircleShape，40dp，primary Claw）。
submit 点击时：240ms 内 scale 1.0 → 1.04 → 1.0（`SurfaceSwap` duration、
`EaseOutCubic`）。Claw 颜色靠"在用户提交那一刻*做点什么*"换来稀缺的合理性。

Reduced-motion → 不缩放。

## 两个新的 D1-residue 原语

都落在 `ui/theme/`。无新 color slot、无新字体、无新 motion duration。

### `Modifier.paperGrain(strength: Float = 0.015f)`

通过 `drawWithCache` 把一个确定性的 256×256 noise texture 烘焙成 `ImageBitmap`，
然后用 `drawImageRect` 平铺到 modifier 内容下面。Dark theme 下跳过（no-op） ——
Lantern 改用 gradient brush 涂一层淡淡的暖色 radial vignette。

应用在：ChatScreen Scaffold 背景、NavigationDrawer 容器、OnboardingShell 背景、
SettingsSheet 的根 `Surface`。

### `@Composable Fleuron(modifier: Modifier = Modifier)`

居中 Fraunces `❦`，`inkFaint`，16sp，上下 12dp padding。三个 call site 共用一个
composable（Trace→Final、Settings footer、Drawer）。

## Light vs. dark

Bound Edition 在两套调色板下读起来一致；Lantern (dark) 享有同样的 ornament，
两处例外：

- `paperGrain` → `lanternVignette`（暖色 radial，约 6% Ink-on-PaperDark，烘焙一次）。
- Fleuron 自动通过 theme 用 `inkFaintDark`。

## 这一轮**不是**什么

- 不是新 theme。`Theme.kt` 里的 D1 调色板与 role mapping 不变。
- 不是新字体。Fraunces 与 JetBrains Mono 已经发；只是不再合成字重。
- 不是新 motion 库。所有动画复用 `ClosePawMotion` 的 duration 与 easing。
- 不是给 Smart Capsule 重新换皮。Capsule 已经做对了；我们加一个 ledger 计数器
  让它呼吸。
- 不是 Material-3 替换工程。我们停留在 Material slot 内；D1-residue 只增长两个
  ornament。
