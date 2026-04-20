# ClosePaw — 前端设计 Revamp

> 对当前 Android UI 的全面 review，以及把它从
> *"clean Material 3 with ChatGPT influence"* 推到**令人难忘**的方案。

**作者：** design review · 2026-04-17
**范围：** `app/src/main/kotlin/ai/closepaw/ui/**` + theme + overlays
**配套文档：**
- [`design-tokens.md`](./design-tokens.md) — 可直接落地的 color / type / shape tokens
- [`motion-spec.md`](./motion-spec.md) — state transitions 与 micro-interactions
- [`roadmap.md`](./roadmap.md) — 分阶段交付计划
- [`../preview.html`](../preview.html) — **浏览器直接打开看视觉效果**

---

## 1. 诚实的 audit

ClosePaw 现在的状态是 **engineering 做得好，design 太保守**。Smart
Capsule 那套 state machine 是真的很出色 —— 一个 sealed `CapsuleMode`
驱动一个 deterministic `CapsuleRenderSpec`，覆盖 8 种 overlay 状态，这种
有纪律的 UI 架构很多 app 一辈子都达不到。但**视觉表层**是一层淡淡上色的
Material 3 主题，看起来就是"又一个 AI 套壳"。

### 做得对的（保留）
- **State-first overlay 架构** —— `CapsuleMode` → `RenderSpec` → view 是纯粹且正确的。契约不要动，只换像素。
- **高对比文字** —— 没有被 alpha 洗白的 placeholder。一眼就能看清。
- **语义化配色** —— blue=running, amber=paused, teal=done, red=error。语义保留，调色板换掉。
- **Action Visualizer canvas overlay** —— tap 用扩散圆环，swipe 用动画线。已经接近 great。
- **Approval-scope UX**（Once / Session / Always）—— 真的很 sophisticated；大多数 agent 只发一个 "Approve" 按钮就完事。
- **Edge-glow** presence indicator —— 微妙且有辨识度。

### 拖后腿的
| # | 问题 | 证据 |
|---|---|---|
| 1 | **没有 brand voice。** 调色板就是 ChatGPT teal + soft black。没有任何东西在说"这是 ClosePaw"。 | `Accent = Color(0xFF10A37F) // ChatGPT green`（`Color.kt:23`）|
| 2 | **完全用系统默认字体** —— 没有任何 custom font 文件，全部是 Compose 默认。 | `Type.kt` 里没有任何 `Font(R.font.*)` |
| 3 | **生产 UI 里用 emoji 当 icon** —— ✋ ✓ ⚠ 💬 🛡 跟 Material icon 混在一起。tonal whiplash。 | `CapsuleRenderSpec.kt`、按钮 label |
| 4 | **State transitions 太突兀。** Capsule mode 切换是瞬切；Running → Takeover → Done 之间没有 slide / fade / morph。 | `SmartCapsuleSurface.kt` 在 mode render 之间没有 `AnimatedContent` |
| 5 | **Settings 和 Onboarding 是完全的 stock Material 3** —— 跟一个 Gradle template 没区别。零身份。 | `SettingsHomePage.kt`、`OnboardingShell.kt` |
| 6 | **没有 depth。** Shadow 是 `0x0A000000`。没有层次、没有颗粒、没有触感 —— 然而这是一个名字字面意思就是*触摸*的 app。 | `ShadowColor = Color(0x0A000000)` |
| 7 | **没用到的 icon tint token**（`ChatIconPrimary/Secondary`）和死掉的调色板项 —— 暴露 design system 在漂移。 | `Color.kt:109-112` |
| 8 | **没有 empty / error / success illustration** —— 只有 icon + 文字。错过了讲故事的表面。 | `EmptyState.kt` |

---

## 2. 美学方向：**"Tactile Intelligence"**（触感智能）

ClosePaw 不是一个 chatbot。它是一个会**伸手进你的手机帮你做事**的伙伴。
"paw"（爪子）—— 一只爪子，握住任务 —— 这个名字异常具体。设计应该
让人感觉是：

> **一本属于聪明动物的编辑式田野笔记本。**
> 温暖的骨白纸张。深墨色的思考。一笔黏土红的爪痕，留给重要时刻。
> 看起来像手工排版的 serif 标题。Agent 在做技术活时，底下流着
> monospace 的暗流。Motion 是**呼吸**，而不是 bounce。

三个锚点：

### a. Palette —— "Ink on bone, claw on paper"
退役 ChatGPT teal。承诺一套**像被做出来的、不像被生成的** palette。
完整数值在 [`design-tokens.md`](./design-tokens.md)。

- **Paper** `#F5F1EA` —— 温暖的骨白，替代纯白作为画布
- **Ink** `#14110F` —— 深暖黑，不是纯 `#000`
- **Claw** `#C44528` —— burnt sienna（焦赭石），唯一的标志性 accent。用得**克制** —— 只用在 Running 状态、primary CTA、paw-print presence dot。稀有 = 冲击。
- **Moss** `#4A5D3A` —— 沉稳的橄榄绿，做 Success（替代 teal）
- **Amber** `#E8A33D` —— 比当前 `F5A623` 更暖，做 Pause / Takeover
- **Rust** `#8B2E1F` —— 深沉的，不是消防车红，做 Error
- Dark mode **不是**反色复制 —— 是另一套独立的 "Lantern"（提灯）调色板：`#0F0D0B` paper、`#F0EAE0` ink、claw 在暗色下烧得更暖到 `#E56B4A`。

### b. Typography —— 三种声音，一个系统
- **Display / Headlines：[Fraunces](https://fonts.google.com/specimen/Fraunces)**（variable serif，OFL）。带 optical size + soft / wonky 轴，能营造手工排版的感觉。用在 `displayLarge/Medium`、empty state 标题、onboarding hero。
- **Body / UI：[Geist](https://vercel.com/font)** 或 **[Inter Tight](https://rsms.me/inter/)**（选一个 —— 想感觉新就 Geist，想感觉经典就 Inter Tight）。所有 chat 文字、按钮、label。
- **Technical：[JetBrains Mono](https://www.jetbrains.com/lp/mono/)** —— 只在 agent 真的在做技术活时露面：action card 代码、shell output、tool name、debug view 里的 thought line。表示"这是机器在说话"。

三种声音，区域分明。读者随时知道自己在读 product、agent 的思考、还是
agent 的动作。

### c. The Paw —— 一个标志性 motif
引入一个 **3 趾爪印** 作为 app 的 signature glyph（三个 toe-pad 在一个
更大的 pad 上方）。它替换：
- Status Island 里那个普通的圆形 "presence dot" → 变成一个**会随呼吸搏动的爪印**（4s 吸 / 4s 呼，不是焦虑的 600ms 闪）
- App icon 前景（现在是 launcher icon 占位符）
- Loading indicator（三个 toe-pad 依次填色，替代三个点）
- Empty state illustration（一只爪印，claw 红，画在纸上）

这是用户会**记住**的东西。其他一切都是它的支撑。

---

## 3. Component-by-component 改造

### 3.1 Status Island / Capsule —— 皇冠上的明珠

**现状：** rounded pill，小圆形 colored dot + thought 文字，4dp elevation。

**提议：**
- 把 `8.dp` 那个圆形 dot 换成 **paw-print glyph**，颜色用 mode 的语义色。Running 时 paw **呼吸**（scale 1.0 → 1.04，4s，`EaseInOutSine`）；Takeover 时**冻住**；Done 时**眨一下**然后淡出。
- Pill 改成 **"折纸卡片"** —— 1px ink border at 8% alpha + 单条 `0 2px 0` 暖棕色 "under-shadow"，带来触感的抬升，而不是普通的 material drop shadow。
- 浅色模式 paper（`#F5F1EA`）背景；深色模式 lantern（`#1A1612`）。
- Thought 文字用 Geist Medium 14sp，**Ink** 色；当 Agent 在*执行*（不是在叙述）时，切换到 JetBrains Mono Medium 13sp，传达"机器在动"。
- Mode transitions：在 Running / Takeover / WaitingForInput 之间用 `AnimatedContent`，`slideInVertically + fadeIn`（240ms，`EaseOutCubic`）。Done 状态向**上**滑出并 `scaleOut(0.95f)`（480ms）；Error 进入时横向抖一次（±3dp，120ms）。

### 3.2 Edge glow

**现状：** 4 边的 Canvas linear gradient，40dp，语义色。

**提议：**
- 把硬性 linear gradient 换成**柔性 radial 衰减**，靠 Status Island 锚点边的部分更强（让 glow 看上去是**从 paw 散发出来**，而不是从外框）。
- 把不透明度上限从"看得见"压到**几乎看不见**（最大 12% alpha）。世界级的 presence indicator 是那种你只在它消失时才注意到的。
- Running 时加一个慢速**漂移**（2px 振幅，8s 周期），让 glow 像活的光晕，而不是 stroke。

### 3.3 Action Visualizer（tap / swipe overlay）

**现状：** 蓝色扩散圆环表示 tap，蓝色线条表示 swipe。

**提议：**
- Tap → 一个 **ink-drop**：claw 红实心圆 6dp，迅速扩散为 ring 同色，再加一个延迟 80ms 的更小 satellite ring。读起来是一次有意的触摸，不是普通 ripple。
- Swipe → 模拟**手绘笔触**：path 带轻微 wobble（perlin noise 偏移，±1.5px），端点 taper。到达终点时，一个小爪印盖章并淡出。
- Long-press → ring **停在最大半径**，内部填充微妙脉动 —— 体感传达"压力"。

### 3.4 Chat 屏幕

**现状：** user bubble 浅灰右；agent 左边是裸文字块 + 内嵌 ActionCard。

**提议：**
- **User bubble：** pill → **微凹的 inset 卡片** 在 paper 上：`#EDE7DC` 在 `#F5F1EA`，无 border 无 shadow。文字 Ink。读起来像被**写下**，不是被**说出**。
- **Agent messages：** 把 bubble 完全去掉。Agent 文字直接以**编辑式的 prose**（散文）渲染在 paper 画布上，第一行左侧对齐一个 3px claw 红 left-margin tick。这是单一最大的可读性 + 身份 win。
- **ActionCard：** 不要再用 5% alpha 上色。改成排版式的**收据**：
  - 顶部一根 hairline（ink 20%）
  - Monospace 的 tool name + args
  - 状态 glyph 右对齐（小爪印，执行中 claw，完成 moss）
  - 底部 hairline + 可展开 output
  - 背景完全没有色块 —— 让 paper 透出来
- **ThinkingIndicator：** 三点 → 三趾依次填色，900ms 周期。当前 600ms 脉动读起来*紧张*；900ms 读起来*在思考*。
- **StreamingText cursor：** 把 `█` 换成 **Fraunces 的 serif I-beam**（`|`），保持同样的闪烁节奏。Block cursor 像 terminal；serif I-beam 像*在写字*。
- **EmptyState：** 把 64dp Material icon 换成**单只 claw 红爪印水印**（20% 不透明度，160dp），偏右上方。Headline 用 Fraunces Italic 32sp："*What should we look into?*"（不是 "How can I help?" —— 换语气）。Suggestion chip 改成 **paper card 里的下划线 serif 链接**，加 hairline border。

### 3.5 Settings

**现状：** 页面式导航，row 是 title / subtitle / arrow。读起来就是 stock。

**提议：**
- 把 settings 当作 **field journal 的目录页** 来对待。Section header 用 Fraunces SemiBold 22sp，**带 hanging numerals 左对齐**（"01 — Permissions"、"02 — Model"、"03 — Behavior"）。Row 之间用 hairline divider（ink 8%），不要用 surface 色块。
- Row：去掉 arrow；改成右侧一个小的 monospace key hint（`→`）。Title 用 Geist Medium；subtitle 用 Geist Regular 13sp，60% ink。光是 weight 差异就足够做 hierarchy —— 把颜色差异去掉。
- **API-key field**：monospace，paper 底 ink border 1px，focus 时变 claw 红。Show/hide toggle 是一个 ink 小 icon，不是 text button。

### 3.6 Onboarding

**现状：** progress bar + title + step 内容，一个普通的 Material layout。

**提议：**
- 每一步是一个 **chapter spread**（章节跨页）：120sp 的 Fraunces 罗马数字（I、II、III、IV、V）作 ink 8% 不透明度的水印，章节标题 28sp Fraunces，一段编辑式短文，然后 action。
- Progress bar 退役。换成底部**一排五个爪印** —— 未完成是 outline，完成是 ink 实心，当前是 claw 红。
- Permission-repair card 改成**电报**：monospace，顶部一行 11sp tracked-out caps "MISSING — ACCESSIBILITY SERVICE"。读起来紧迫且系统级，不是又一个 Material dialog。

### 3.7 Navigation drawer（session history）

**现状：** 85% 宽 modal drawer，header、new-session 按钮、列表、settings。

**提议：**
- 重新想象为一本**账本（ledger）**。每一行是一条带日期的 entry：日期 monospace（13sp），title Geist Medium，第一条消息预览 12sp 50% ink。Delete 是一个 hover/swipe 时出现的 ink `×`，没有破坏性按钮的 chrome。
- New-session 按钮改成顶部一个 **claw 红的 "New entry" 钢笔感链接** —— drawer 里唯一的 claw 元素。稀有且应得。
- 底部 settings 入口：monospace 的 `// preferences` 链接。在 journal 和 machine 的接缝处。

---

## 4. Motion 系统

完整 spec 在 [`motion-spec.md`](./motion-spec.md)。三条规则：

1. **Breath，不是 bounce。** ClosePaw 里没有任何东西应该 `spring` 或 overshoot。所有 easing curve 是 `EaseInOutSine` 或 `EaseOutCubic`。Agent 是冷静且 deliberate 的；它的 UI 也应当如此。
2. **只用四种 duration：** `120ms`（micro）、`240ms`（transition）、`480ms`（mode change）、`900ms`（breath cycle）。每个动画选一个 —— 没有 `300ms` 这种漏网。
3. **只有一次 orchestrated entrance。** App 冷启动时，Fraunces 标题落位、爪印盖章、chat area 淡入 —— 错开 120ms。就这么一次。之后所有都是小且局部的。Delight 是被结构性地保持稀有的。

---

## 5. 实际改的优先顺序

详见 [`roadmap.md`](./roadmap.md)。摘要：

**Phase 1 — Identity（1 周）。** 上 palette、字体、爪印 glyph。哪怕 component 一个没改，第一天 app 就会感觉不一样。

**Phase 2 — Capsule（1 周）。** 呼吸 paw dot、折纸 pill、mode-transition 动画。这是 signature UI —— 应该是 app 里最 refined 的表面。

**Phase 3 — Chat（1 周）。** 干掉 agent bubble、改成编辑式 prose、ActionCard 改排版式收据、serif I-beam cursor、三趾 thinking indicator。

**Phase 4 — Settings / Onboarding / Drawer（1 周）。** Field-journal 改造。用户可见频次低但 first run 和 first screenshot 上的身份回报巨大。

**Phase 5 — Motion + polish（持续）。** Edge-glow 径向衰减、action-visualizer ink-drop、long-press hold、perlin 笔触 swipe。

---

## 6. 这值多少

现在的设计是**安全**的局部最优。Smart Capsule 架构已经是世界级的
*engineering*。把世界级的*视觉*装到上面，主要就是一次调色板替换、两个
字体文件、一个手绘爪印、和有纪律的 motion 克制。**三周专注工作。**

测试：2026 年某个 reviewer 截一张 ClosePaw 的图，应该光看那只爪印和
那点 claw 红就能认出这是哪个 app —— logo 和名字都不需要看见。

今天，他们不能。这就是这次 revamp 要补上的 gap。
