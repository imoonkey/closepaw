# Design Tokens —— "Tactile Intelligence"

可以直接替换 `ui/theme/Color.kt`、`Type.kt`、`Shape.kt` 的值。
每个数值都是被**选**出来的 —— 没有一个是默认值。

> 英文原版：[`../design-tokens.md`](../design-tokens.md)

---

## 1. Color —— "Ink on bone, claw on paper"

### 浅色模式（默认 —— "Paper"）

```kotlin
// Canvas
val Paper         = Color(0xFFF5F1EA)  // 温暖骨白；替代纯白
val PaperInset    = Color(0xFFEDE7DC)  // user bubble、inset card
val PaperDeep     = Color(0xFFE5DED1)  // pressed / hover
val Hairline      = Color(0x1F14110F)  // 12% ink —— border、divider

// Ink（文字 + 主色）
val Ink           = Color(0xFF14110F)  // 深暖黑（不是 #000）
val InkMuted      = Color(0xFF5C554C)  // secondary text、subtitle
val InkFaint      = Color(0xFF8B8278)  // placeholder、tertiary
val InkGhost      = Color(0x1414110F)  // 8% —— 水印爪、micro-divider

// Claw（**唯一的 accent** —— 用得克制：Running、primary CTA、presence dot）
val Claw          = Color(0xFFC44528)  // 焦赭石
val ClawDeep      = Color(0xFFA83820)  // pressed
val ClawSoft      = Color(0x1FC44528)  // 12% —— running 状态背景晕

// 语义色（替换 blue / teal / amber / red）
val Moss          = Color(0xFF4A5D3A)  // success（替代 teal）
val MossSoft      = Color(0x1F4A5D3A)
val Amber         = Color(0xFFE8A33D)  // pause / takeover（更暖）
val AmberSoft     = Color(0x1FE8A33D)
val Rust          = Color(0xFF8B2E1F)  // error（深沉，不是消防车红）
val RustSoft      = Color(0x1F8B2E1F)

// Shadow —— 暖色、有方向、有触感
val ShadowInk     = Color(0x1414110F)  // 8% 暖棕，不是中性灰
val ShadowUnder   = Color(0x0F14110F)  // 折纸抬升的 "under-shadow"
```

### 深色模式（独立 palette —— "Lantern" 提灯，**不是反色**）

```kotlin
val PaperDark      = Color(0xFF0F0D0B)  // 深胡桃，不是 #000
val PaperInsetDark = Color(0xFF1A1612)
val PaperDeepDark  = Color(0xFF221C17)
val HairlineDark   = Color(0x1FF0EAE0)  // 12% ink-in-dark

val InkDark        = Color(0xFFF0EAE0)  // 暖骨白（在暗色里读作浅 ink）
val InkMutedDark   = Color(0xFFB9B0A3)
val InkFaintDark   = Color(0xFF7A7268)

val ClawDark       = Color(0xFFE56B4A)  // 暗色里烧得更暖
val ClawDeepDark   = Color(0xFFC44528)
val ClawSoftDark   = Color(0x24E56B4A)

val MossDark       = Color(0xFF7A9466)
val AmberDark      = Color(0xFFF2B960)
val RustDark       = Color(0xFFD55A42)
```

### 语义 → mode 映射（Capsule）

| Capsule mode | 颜色 | Token |
|---|---|---|
| Running | Claw（呼吸 paw） | `Claw` |
| Takeover / Paused | Amber（冻结 paw） | `Amber` |
| WaitingForInput | Ink（无 paw，仅文字） | `Ink` |
| WaitingForApproval | Amber + Rust border | `Amber` / `Rust` |
| Done | Moss（眨一次 paw） | `Moss` |
| Error | Rust（抖动 paw） | `Rust` |

规则：**Claw 同时在屏幕上的元素只能是以下之一：Running 状态的 capsule、
primary CTA 按钮、爪印水印、new-entry 按钮。任何时候屏幕上不超过两个
claw 颜色的元素。**

---

## 2. Typography —— 三种声音

### 要加到 `app/src/main/res/font/` 的字体

1. **Fraunces** —— variable serif（OFL，免费）。从 Google Fonts 下，导出带 `wght, opsz, SOFT, WONK` 轴的 `fraunces_variable.ttf`。
2. **Geist** —— variable sans（OFL，免费）。Vercel 的字体 —— `geist_variable.ttf`。
   - 备选：**Inter Tight**，如果觉得 Geist 太"新"。
3. **JetBrains Mono** —— monospace（OFL，免费）。`jetbrains_mono_variable.ttf`。

### Compose 设置（`Type.kt`）

```kotlin
val Fraunces = FontFamily(Font(R.font.fraunces_variable, variationSettings = …))
val Geist    = FontFamily(Font(R.font.geist_variable, …))
val Mono     = FontFamily(Font(R.font.jetbrains_mono_variable, …))

val AgentTypography = Typography(
    // Display —— Fraunces，optical size large，soft 轴中等
    displayLarge  = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Bold,
                              fontSize = 48.sp, lineHeight = 52.sp,
                              letterSpacing = (-0.8).sp),
    displayMedium = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold,
                              fontSize = 34.sp, lineHeight = 40.sp,
                              letterSpacing = (-0.4).sp),
    displaySmall  = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold,
                              fontSize = 26.sp, lineHeight = 32.sp),

    // Headlines —— 身份表面用 Fraunces（empty state、onboarding、section head）
    headlineLarge  = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold,
                               fontSize = 22.sp, lineHeight = 28.sp),
    headlineMedium = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium,
                               fontSize = 18.sp, lineHeight = 24.sp),
    headlineSmall  = TextStyle(fontFamily = Geist,    fontWeight = FontWeight.Medium,
                               fontSize = 16.sp, lineHeight = 22.sp),

    // Titles —— Geist，UI chrome
    titleLarge  = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp, lineHeight = 22.sp),
    titleMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium,
                            fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall  = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium,
                            fontSize = 13.sp, lineHeight = 18.sp,
                            letterSpacing = 0.2.sp),

    // Body —— 用户面向的 prose 用 Geist
    bodyLarge  = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal,
                           fontSize = 16.sp, lineHeight = 24.sp,
                           letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal,
                           fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall  = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal,
                           fontSize = 12.sp, lineHeight = 16.sp),

    // Labels —— Geist medium，电报式 tracked-out caps
    labelLarge  = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium,
                            fontSize = 13.sp, letterSpacing = 0.3.sp),
    labelMedium = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium,
                            fontSize = 11.sp, letterSpacing = 0.8.sp),
    labelSmall  = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium,
                            fontSize = 10.sp, letterSpacing = 1.2.sp),  // ALL CAPS 用
)

// 不在 Material3 体系内的额外样式，通过 LocalAgentTypography 暴露：
data class AgentExtraTypography(
    val monoBody:       TextStyle,  // JetBrains Mono 13sp —— ActionCard、shell output
    val monoSmall:      TextStyle,  // JetBrains Mono 11sp —— ledger 日期、tool name
    val serifItalic:    TextStyle,  // Fraunces Italic —— empty state question
    val tgmTelegram:    TextStyle,  // Geist 11sp、ALL CAPS、1.2sp tracking —— repair card
    val romanNumeral:   TextStyle,  // Fraunces 120sp —— onboarding 章节水印
)
```

### Voice zoning —— 谁在哪里说话

| 在哪里 | 声音 |
|---|---|
| App title、empty state、onboarding 章节、section head | **Fraunces**（serif）—— 产品身份 |
| 所有 UI chrome、chat body、按钮、settings row | **Geist**（sans）—— 操作清晰 |
| Agent 执行中的 thought、ActionCard tool name、shell output、ledger 日期、API key field | **JetBrains Mono** —— "机器在说话" |

---

## 3. Shape

```kotlin
val AgentShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),   // chips
    small      = RoundedCornerShape(8.dp),   // 按钮、text field
    medium     = RoundedCornerShape(10.dp),  // card、capsule pill
    large      = RoundedCornerShape(14.dp),  // sheet
    extraLarge = RoundedCornerShape(18.dp),  // drawer、modal
)

// 特殊 shape
val BubbleUser  = RoundedCornerShape(10.dp)       // 对称，没有 "tail"
val CapsulePill = RoundedCornerShape(16.dp)       // 比当前 20dp 更扁
val PawStamp    = RoundedCornerShape(50)          // 圆形 —— toe-pad 用
```

注意：当前那个 asymmetric `BubbleShapeUser`（6dp/20dp 的"小尾巴"）是
ChatGPT 的 signature。把它去掉 —— 对称 corner 读起来是**写下**，
而不是**发消息**。

---

## 4. 爪印 glyph

手绘成 vector drawable，放在 `res/drawable/ic_paw.xml`。几何：

```
  ·   ·   ·      ← 三个 toe-pad：r=2.2，圆心 (-4,-3), (0,-4.2), (4,-3)
      ▃          ← 主 pad：圆角梯形，宽 7、高 5、上 radius 3、下 radius 2
```

使用三种尺寸：
- **10dp** —— Capsule presence dot（呼吸用）
- **20dp** —— Action Visualizer 在 swipe 终点的 stamp
- **160dp** —— Empty state 水印（InkGhost，即 8% 不透明度）

永远 single-color（用 `Color.Claw` / `Moss` / `Amber` / `Rust` /
`InkGhost` 上 tint）。永远不要 gradient 填充。永远只 fill 不 stroke ——
toe-pad 和主 pad 是分开的 path，让 agent 可以独立 animate（比如三趾依次
填色做 thinking indicator）。

---

## 5. Elevation —— "折纸"，不是悬浮卡片

把 Material elevation 换成两层触感 shadow：

```kotlin
Modifier
    .drawBehind {
        // Under-shadow —— 暖色、有方向、下方 2dp
        drawRect(ShadowUnder, topLeft = Offset(0f, 2.dp.toPx()),
                 size = size.copy(height = size.height))
        // 顶部一根 hairline —— 暗示纸的厚度
        drawLine(Hairline, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f)
    }
```

不要用 Material 的 `shadow(4.dp)`。整个 app 里唯一悬浮的元素是 Capsule
和 ModalDrawer —— 其他一切都平铺在纸上。

---

## 6. Spacing —— 编辑式韵律

用 **4pt baseline grid**，大空间用黄金比例：

```kotlin
object Spacing {
    val xs  = 4.dp
    val sm  = 8.dp
    val md  = 12.dp
    val lg  = 20.dp      // 12 × φ
    val xl  = 32.dp      // 20 × φ
    val xxl = 52.dp      // 32 × φ —— 章节跨页、empty state
}
```

页面水平 padding：`Spacing.lg`（20dp）所有地方一致。一致性就是奢侈。
