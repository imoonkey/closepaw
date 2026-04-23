# 当前状态评审 — post-D2 + post-uxfb

> 评审日期：2026-04-22。检视文件：theme/*、chat/*、capsule/surface/*、
> onboarding/{OnboardingScreen,OnboardingShell}、settings/SettingsHomePage、
> navigation/NavigationDrawer。
>
> 反映的代码状态截至 `08ae6378` —— 即 `uxfb-1 → uxfb-followup` 落地了
> ThoughtGroup、CollapsePill、FinalText、capsule marquee 之后。先前的两条
> finding（`FinalSeparator` 重量、`✱` glyph + italic Thought 倒置层级）已被
> 该工作吸收并移除；两条新 finding（AgentTrace 左 rule 重量、CollapsePill
> summary register）顶替进来。差异历史见 `align-with-uxfb-0422.md`。
>
> Severity：**[H]** = 破坏 brand identity 或 trust signal，
> **[M]** = 称职但平庸，**[L]** = 抛光。

## 在做对的事（保留）

- **`ui/theme/` 里的 D1 token 系统**已经成熟：暖色 Paper/Ink/Claw 调色板、三个命名
  radii、三个字体家族、命名好的 motion 原语、reduced-motion 契约、`foldedPaper`
  chrome modifier。Material 3 slot 与 `ClosePawTokens` 之间的接缝干净——feature
  代码先伸手 Material，只在 Material 不模型化的地方才走 D1-residue。这个契约别动。
- **Smart Capsule surface**（`SmartCapsuleSurface.kt:93`）— `foldedPaper(shape)`
  + 暖色 under-shadow + top hairline，是整个 UI 里最强的*物理感*瞬间。把它当作
  app 其它部分应当向往的参照。
- **Track A `AgentRow`**（`MessageBubble.kt:115`）— 时序 Trace + Final 模型加上
  row-level 的 disclosure 在概念上是对的，且 `stateDescription` 语义正确。结构别
  重排；只重新装饰 ornament。
- **`StatusPawGlyph`**（`StatusPawGlyph.kt:25`）— 把 paw 当 status dot，是回报
  自己开销的 identity 动作，凡出现皆赚。

## 评审项

### [H] 发出第一条消息的瞬间，brand 就垮了
- `ChatHeader.kt:72` — 标题是 `Geist Medium` 的 `"ClosePaw"`。没有 paw glyph、
  没有 Fraunces、没有 ornament。当 EmptyState（brand 真正活着的唯一地方）滚出
  屏幕，chrome 就读起来像随便哪个 app。
- 该 header 拥有 56dp 垂直空间，identity 占用了大约 0%。
- **动作：**小号 paw glyph + Fraunces italic Regular 的 `ClosePaw`，下方用
  `monoSmall` 写 `Today · Apr 22`；或者居中 paw + 一个 mono 的 "running number"
  在各 surface（sessions list、settings）之间贯穿。详见 `direction.md` §1
  (Page Masthead)。

### [H] `Fraunces SemiBold` 在被合成
- `Type.kt:18-20` 仅装载 `fraunces_regular` 与 `fraunces_italic`。
- `EmptyState.kt:58` 用 `headlineMedium.copy(fontFamily = Fraunces)` 然后强行
  `fontWeight = FontWeight.SemiBold` → Compose 合成字重，在 display 尺寸下
  serif 字形边缘发糊。
- `NavigationDrawer.kt:175` 在 `titleLarge.copy(fontFamily = Fraunces)` 上做了
  同样的 `FontWeight.SemiBold`。
- `OnboardingShell.kt:104` 用 `displaySmall.copy(fontFamily = Fraunces)` —
  `displaySmall` 在 typography 表里就是 `FontWeight.SemiBold`。在最大号
  Fraunces 时刻同样有合成问题。
- **动作：**所有 identity-tier 字串统一到 Fraunces *italic Regular*，或者
  追加一个 Fraunces medium/semibold TTF——如果上半身字重在视觉上是承重的。
  详见 `changes.md` §Type。

### [H] Stop button 是装着 Rust 戏服的默认 outline
- `CapsuleControlBar.kt:140-167` — `OutlinedButton` 的 text + icon 是
  `MaterialTheme.colorScheme.error`，但 `border` 走 `ButtonDefaults` 默认
  → 解析成 `outline`（12% Ink）。这个破坏性动词读起来是"灰文字 + 暖图标"，
  不是"stop"。
- **动作：**显式 `BorderStroke(1.dp, MaterialTheme.colorScheme.error)`。

### [H] AgentTrace 2dp 左 rule 作为*主要*结构标记，分量不够
- `AgentTrace.kt:105-110`（`uxfb-4` 落地）用 2dp 左 rule 作为 ThoughtGroup
  marker，替代了原先的 `✱` glyph。颜色是 `outlineVariant`（8% Ink）。在 Paper 上
  2dp × 8% 的视觉权重大致等于 1dp Hairline —— 勉强可接受，长时间阅读偏弱。
  鉴于这条 rule 现在是说"一个 turn 从这里开始"的*唯一*marker，它应当具备一根
  真正结构 divider 的分量。
- **动作：**改用 `MaterialTheme.colorScheme.outline`（Hairline，12% Ink）。
  2dp × 12% 让 rule 读作一根有信心的垂直 column，又不至于变成厚重 divider。
  一行代码改动。

### [M] CollapsePill summary 文本走错了 typographic register
- `CollapsePill.kt:70-73`（`uxfb-3` 落地）把 summary（`✓ N actions · 12s`）渲染
  成 `labelLarge`（Geist medium）。旁边的 chevron 是 `monoBody`（JetBrains Mono）。
  summary 内容本身就是 ledger 形（status + 数量 + 时长）。这个 32dp 的小 surface
  上混了两种 register。
- **动作：**summary style 从 `labelLarge` → `MaterialTheme.closePaw.monoSmall`，
  让整个 pill 读作一个 machine-ledger 对象。落在 D1 mono 使用规则内（数值 +
  状态，不是叙述 prose）。一行代码改动。

### [M] EmptyState 是网格居中、可预测的
- `EmptyState.kt:32-94` — paw → title → subtitle → 三个垂直堆叠的 chip，全
  居中。功能 OK，但读起来像默认的"AI app 空状态"布局。构图上没有任何令人难忘
  的特征。
- chip 用真正的 ASCII `"…"` 把建议文本引起来（`EmptyState.kt:112`）——这对
  引号是"这是 AI 替你说的建议"的指纹，恰恰是错的归属（说话的是*用户*）。
- **动作：**非对称构图。Paw 在右上方溢出 ~240dp，80% alpha；headline 左对齐
  在下三分之一；suggestions 改成 marginalia 行（`→` 引导 + body 动词 + serif
  italic 注释）。首次绘制时分级揭示。详见 `direction.md` §3。

### [M] User bubble 是个棕褐色盒子
- `MessageBubble.kt:81-93` — `Surface(shape = medium, color = surfaceVariant)`。
  PaperInset 叠在 Paper 上。读作"被打了 tag 的内容"，不是"你说的话"。Editorial
  paper 应让用户的声音*坐在 page 上*，而不是装在 card 里。
- **动作：**抛掉 Surface；文字右对齐；左侧给一条 1dp Hairline *margin rule*，
  延伸完整文本高度。letter-to-the-editor 美学。Timestamp 紧贴同一列底部、
  右对齐，`monoSmall` 字号，没有上方间隔。

### [M] Thinking indicator 被困在 chip 里
- `ThinkingIndicator.kt:38-52` — 精心制作的 paw-toe 动画被装进
  `Surface(shape = large, color = surfaceVariant)`，28dp 高。读作"又一个
  bubble"，不是"agent 在思考"。
- **动作：**让 paw-toe canvas 裸露，左侧 28dp，右侧用 `bodyItalic`
  (`MaterialTheme.closePaw.bodyItalic`) 写 `Thinking…`，颜色 `inkMuted`。
  不要 chrome。

### [M] 五枚 paw 的 onboarding 进度在 light mode 下不可见
- `OnboardingShell.kt:128-135` — 未填充的 paw 用 `outlineVariant`（8% Ink）。
  叠在 Paper 上大约是 `#DCD7CD` ——20dp 时基本看不见。
- **动作：**未填充 = `inkFaint`（`#8B8278`，约 36% Ink）；填充 = `primary` (Claw)。

### [M] suggestion chip、ledger date、timestamp 共享一种 mono voice，但没扩散到 app 其它地方
- drawer 的相对日期（`NavigationDrawer.kt:300-304`）和 settings entry 版本
  （`NavigationDrawer.kt:386-390`）用 `JetBrains Mono`。好——"machine ledger"
  register 在工作。但它停在 drawer。
- **动作：**把 mono 扩展到 (a) capsule running counter `[t+12s]`、
  (b) settings home subtitle 里的数字片段（`60 turns`、`v0.5.x`）、(c) 每个
  top-level page 都带一个小的 running header（Page Masthead，详见 direction）。

### [M] CapsuleControlBar 主按钮用 Amber (tertiaryContainer)；send button 用 Claw (primary)
- 这其实*符合* D1（Claw 稀缺，Amber 用于"立即执行"），但两个 CTA 的视觉差异
  不一致——最显眼 surface 上最常被按的按钮，*不是* brand 颜色。
- **动作：**capsule-mode 主动作（Takeover/Resume/Done/Allow）保留 Amber，但让
  Claw send button 的 stand-out 时刻有意义：在 submit 时加一个单帧 breath
  （240ms scale 1.0 → 1.04 → 1.0），让 brand 颜色在用户提交那一刻*做点什么*。

### [L] StartupErrorBanner 是匿名的 Material errorContainer
- `SmartCapsuleSurface.kt:185-220` — 普通的有色 card 加一个 Close。没有 sigil、
  没有 Rust hairline。感觉不属于上方 capsule 那个被设计过的系统。
- **动作：**加 2dp Rust 左侧 rule + 引导的 `⚠` glyph，`monoBody`，Rust 色。

### [L] "live" pill 是个 Material rounded chip
- `ChatScreen.kt:301-333` — `Surface(shape = extraLarge)` + 箭头 + "live"。普通。
- **动作：**改成小号 mono pill `↓ live · N new`（当 N>0 时），`monoSmall`，
  `onSurfaceVariant`，加显式 `outline` border。与 Capsule running counter
  同节奏。

### [L] SettingsHomePage 是一列三行，没有分组
- `SettingsHomePage.kt:39-78` — 扁平列表。每行 subtitle 写得不错
  （`agentBehaviorSubtitle`、`permissionsSubtitle`），但 page 本身除了"行"
  之外没有任何形态。
- **动作：**包成三个 section，serif-italic subhead：*Voice* (LLM)、
  *Behavior* (Agent)、*System* (Permissions)。版本 footer 上方放居中 fleuron。

### [L] 没有 paper grain
- 整个隐喻是"Paper"。纯色 `#F5F1EA` 是*米色*，不是 paper。1.5%-alpha 的 noise
  overlay（用 `drawWithCache` 烘焙到 ImageBitmap 一次，所有 surface 复用）能
  把隐喻坐实，运行成本为零。
- Dark mode（"Lantern"）：跳过 noise；改用淡淡的暖色 radial vignette。

## 反向评审（不要"修"这些）

- `foldedPaper` modifier 当前正确地限定在 capsule + modal chrome 上
  （`Tokens.kt:71`）。不要把它套到 chat bubble 或 onboarding card 上——稀缺
  本身就是它的意义。
- `ClosePawShapes`（`Shape.kt:8`）只发三个 radii。不要加"bubble shape"或
  "input shape"。chip-vs-bubble 词汇属于 call site，不属于 theme。
- Track A row 的 collapse policy（`MessageBubble.kt:122-131`）—— tri-state、
  user-override-wins-over-default —— 微妙且正确。可自由 re-ornament；不要碰
  policy。
- Reduced-motion 的处理被设计为每个 call site 自己读一次（`Motion.kt:46-56`）。
  不要引入全局 animation wrapper。
