# 按文件的修改清单

> 是代码草图，不是最终实现。每条修改都标注文件与符号，便于实现者直接接手。

## 新文件

### `ui/theme/Ornaments.kt`（新）

```kotlin
package ai.closepaw.ui.theme

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 居中 Fraunces fleuron，唯一共享的"section divider" ornament。 */
@Composable
fun Fleuron(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "❦",
            fontFamily = Fraunces,
            fontStyle = FontStyle.Italic,
            fontSize = 16.sp,
            color = MaterialTheme.closePaw.inkFaint,
        )
    }
}

/** Running-head 行，被 ChatHeader、NavigationDrawer header、Settings home 共用。 */
@Composable
fun PageMasthead(
    title: String,
    rightSlot: String? = null,             // "No 04 · Apr 22" 或 null
    leadingPaw: Boolean = true,
    onLeadingClick: (() -> Unit)? = null,  // 调用方（如 menu icon）传它的 handler
)
// Body：Row(SpaceBetween)，左 [paw + Fraunces italic Reg 的 title] | 右 [monoSmall 的 rightSlot]
// 后接整宽 1dp Hairline rule。
```

### `ui/theme/PaperGrain.kt`（新）

```kotlin
fun Modifier.paperGrain(strength: Float = 0.015f): Modifier = composed {
    val isDark = LocalClosePawTokens.current === darkClosePawTokens()  // 或通过 theme 传入
    if (isDark) return@composed this
    val bitmap = remember(strength) { bakeNoiseTile(size = 256, alpha = strength) }
    drawWithContent {
        drawContent()
        drawImageRect(image = bitmap, /* 通过 repeat 平铺填充 */)
    }
}
```

`bakeNoiseTile` 是一个私有 helper，用确定性 blue-noise（seed = 42）以 `Ink` 颜色、
alpha = `strength` 填充 256×256 的 ImageBitmap。每个 session 烘焙一次。

dark theme 下，导出 `Modifier.lanternVignette()`，用 radial brush 暖色 gradient
（不用 bitmap）。

## 按文件编辑

### `ui/theme/Type.kt`

**问题：**`displaySmall` 在 typography 表里是 `FontWeight.SemiBold`，并被用在三处
`fontFamily = Fraunces` 调用。Fraunces SemiBold 没发布。

**方案 A（推荐）：**追加一个 `fraunces_medium.ttf`（或 semibold）并加进 `FontFamily`：

```kotlin
val Fraunces: FontFamily = FontFamily(
    Font(R.font.fraunces_regular, FontWeight.Normal),
    Font(R.font.fraunces_medium, FontWeight.Medium),       // 新
    Font(R.font.fraunces_italic, FontWeight.Normal, FontStyle.Italic),
)
```

**方案 B（不增字体）：**从 identity surface 撤掉 upright Fraunces；任何用到 serif
的地方统一用 Fraunces *italic Regular*。当前已是 thought item 与 EmptyState
subtitle 的规范——把 title 也并入。方案 B 需要改 `critique.md` 列出的三处 call
site，但不增二进制开销。

如果可以发字体就选 A；否则选 B。`direction.md` 假定 A。

### `ui/chat/components/ChatHeader.kt`

把 body（`ChatHeader.kt:43-99`）替换为：

```kotlin
Surface(
    modifier = modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars),
    color = MaterialTheme.colorScheme.background,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenuClick) { /* 不变的 Menu icon */ }
        Spacer(Modifier.width(4.dp))
        PageMasthead(
            title = "ClosePaw",
            rightSlot = todayLabel(),       // "Apr 22"
            leadingPaw = true,
            modifier = Modifier.weight(1f),
        )
        if (showNewChatButton && onNewChatClick != null) {
            IconButton(onClick = onNewChatClick) { /* 不变的 Add icon */ }
        }
    }
}
```

`todayLabel()` 是 `DateFormat.getMediumDateFormat` 的薄包装，按用户 locale 返回
day-month 字串。

### `ui/chat/components/EmptyState.kt`

整体重写为非对称布局（约 80 LOC）。伪代码：

```kotlin
Box(modifier = modifier.fillMaxSize().paperGrain()) {
    // 右上 paw，溢出（被 Box 边界裁剪）。
    Icon(
        painter = painterResource(R.drawable.ic_paw),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f),
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset(x = 56.dp, y = (-32).dp)
            .size(240.dp),
    )
    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(horizontal = spacing.lg)
            .padding(bottom = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        StaggeredReveal(index = 0) {
            Text(
                "What can I\nhelp you with?",
                style = TextStyle(
                    fontFamily = Fraunces,
                    fontStyle = FontStyle.Italic,
                    fontSize = 32.sp,
                    lineHeight = 38.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        StaggeredReveal(index = 1) { HairlineRule(width = 80.dp) }
        SUGGESTIONS.forEachIndexed { i, s ->
            StaggeredReveal(index = 2 + i) {
                MarginaliaSuggestion(verb = s.verb, gloss = s.gloss, onClick = ...)
            }
        }
    }
}
```

`StaggeredReveal` 把内容套在 `AnimatedVisibility` 里，delay = `index * 80ms`，
animation = `fadeIn(tween(ClosePawMotion.TraceEnter)) + slideInVertically(8dp → 0)`。
Reduced-motion 跳过动画；立即显示。

`MarginaliaSuggestion`：
```
Row {
  Text("→", bodyLarge, inkFaint)               // ASCII 箭头，不用 mono —— D1 把 mono 留给 machine text
  Spacer(sm)
  Column {
    Text(verb, MaterialTheme.typography.bodyLarge, onSurface)
    Text(gloss, MaterialTheme.closePaw.bodyItalic, inkMuted)
  }
}
```
点击目标：整行宽，padding `vertical = sm`。

### `ui/chat/components/MessageBubble.kt`

#### `UserBubble`（行 65-101）

```kotlin
@Composable
private fun UserBubble(message: ChatMessage.User, maxWidth: Dp, modifier: Modifier) {
    val spacing = MaterialTheme.closePaw.spacing
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .testTag("qa-user-bubble"),
        ) {
            // 1dp Hairline 左侧 rule，覆盖完整文本高度。
            VerticalDivider(
                modifier = Modifier.fillMaxHeight().align(Alignment.TopStart),
                color = MaterialTheme.colorScheme.outline,
                thickness = 1.dp,
            )
            Column(
                modifier = Modifier.padding(start = spacing.md),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = formatTime(LocalContext.current, message.timestamp),
                    style = MaterialTheme.closePaw.monoSmall,
                    color = MaterialTheme.closePaw.inkFaint,
                )
            }
        }
    }
}
```

#### `FinalSeparator`（行 262）

整个 composable body 替换为 `Fleuron()` 调用。删除 `outlineVariant` rule。

#### `CollapsedHeader`（行 166）与 `OutcomeFooter`（行 198）

替换 glyph：

```kotlin
// CollapsedHeader 引导：
Text("[+]", style = MaterialTheme.closePaw.monoBody, color = MaterialTheme.closePaw.inkFaint)
// CollapsedHeader 尾部（去掉单独的 "▸"；并入 summary）：
// summary 已包含 "headline · N actions · 4.2s" —— 不再追加。

// OutcomeFooter:
Text("[−] $text", style = MaterialTheme.closePaw.monoSmall, color = MaterialTheme.closePaw.inkFaint)
```

（`[+] / [−]` 括号对屏幕阅读器也更清晰——不需改 `stateDescription`，现有的
"collapsed"/"expanded" 标签已经覆盖。）

### `ui/chat/components/ThinkingIndicator.kt`

把 `Surface(...) { Box(...) { PawToeSequence(...) } }` 链
（`ThinkingIndicator.kt:38-52`）替换为：

```kotlin
Row(
    modifier = modifier
        .testTag("qa-thinking-indicator")
        .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "Thinking" },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.sm),
) {
    PawToeSequence(tint = THINKING_INDICATOR_TINT.resolve())
    Text(
        text = "Thinking…",
        style = MaterialTheme.closePaw.bodyItalic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
```

`Surface` wrapper 消失。现有测试 `ThinkingIndicatorTintTest` 仍应通过
（`THINKING_INDICATOR_TINT.resolve()` 不变）；实现时确认。

### `ui/capsule/surface/CapsuleControlBar.kt`

#### Stop button（行 140-167）

显式给 Rust border：

```kotlin
OutlinedButton(
    onClick = { ... },
    enabled = btn.enabled,
    shape = MaterialTheme.shapes.large,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),   // ← 新
    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
) { /* 内容不变 */ }
```

### `ui/capsule/surface/SmartCapsuleSurface.kt`

#### `CapsuleStatusLine`（行 222-266）

为 `Running` mode 加 ledger 计数器。增一个 `mode` 参数（或在 call site 计算
elapsed 后传入 `String?`）：

```kotlin
@Composable
private fun CapsuleStatusLine(
    spec: CapsuleRenderSpec,
    elapsedLabel: String?,           // "[t+12s]" 或 null
    onClick: (() -> Unit)?,
) {
    Row(...) {
        if (spec.dot != null) { StatusPawGlyph(...); Spacer(Modifier.width(8.dp)) }
        if (elapsedLabel != null) {
            Text(
                text = elapsedLabel,
                style = MaterialTheme.closePaw.monoSmall,
                color = MaterialTheme.closePaw.inkFaint,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(spec.thought.text, ...)
    }
}
```

ticker 落在 `SmartCapsuleSurface`：

```kotlin
val elapsedLabel = produceElapsedLabel(mode)  // Running 时 1Hz tick，否则 null
```

`produceElapsedLabel` 是一个小 composable，用 `LaunchedEffect(mode)` +
`delay(1.seconds)` + `remember` 记住 `mode` 的开始时间戳。Reduced-motion 与它
无关——它是 status，不是 motion。

#### `StartupErrorBanner`（行 184-220）

加 Rust 左 rule 与 `⚠` glyph：

```kotlin
Surface(modifier = ..., color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 0.dp, end = 4.dp, ...)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.error))
        Spacer(Modifier.width(12.dp))
        Text("⚠", style = MaterialTheme.closePaw.monoBody, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        Text(message, ..., modifier = Modifier.weight(1f))
        IconButton(...) { /* close */ }
    }
}
```

### `ui/capsule/surface/CapsuleInputBar.kt`

#### Send-button breath（行 150-161）

把点击包成一次性 scale 动画：

```kotlin
val scope = rememberCoroutineScope()
val scale = remember { Animatable(1f) }

FilledIconButton(
    onClick = {
        if (!ClosePawMotion.reducedMotion()) {
            scope.launch {
                scale.animateTo(1.04f, tween(ClosePawMotion.SurfaceSwap / 2, easing = ClosePawMotion.EaseOutCubic))
                scale.animateTo(1.0f, tween(ClosePawMotion.SurfaceSwap / 2, easing = ClosePawMotion.EaseOutCubic))
            }
        }
        submit()
    },
    enabled = inputEnabled && inputText.isNotBlank(),
    modifier = Modifier.size(40.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value },
    shape = CircleShape,
) { /* 不变的 Icon */ }
```

### `ui/onboarding/OnboardingShell.kt`

#### `FivePawProgress`（行 119-137）

把未填充色从 `outlineVariant`（8% Ink，不可见）提到 `inkFaint`：

```kotlin
tint = if (i < filled) MaterialTheme.colorScheme.primary
       else MaterialTheme.closePaw.inkFaint
```

#### Title（行 102-107）

如果走 Type 方案 B（不加新 TTF）：

```kotlin
Text(
    text = title,
    style = MaterialTheme.closePaw.serifItalic.copy(fontSize = 32.sp, lineHeight = 40.sp),
    color = MaterialTheme.colorScheme.onBackground,
)
```

如果走方案 A（发 Fraunces medium），保留当前 upright 但显式标 `FontWeight.Medium`，
并确认字体已加载。

### `ui/navigation/NavigationDrawer.kt`

#### `DrawerHeader`（行 162-188）

把 `Sessions` Text 替换为 `PageMasthead`：

```kotlin
Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
    PageMasthead(
        title = "Sessions",
        rightSlot = todayLabel(),
        leadingPaw = true,
        modifier = Modifier.weight(1f),
    )
    IconButton(onClick = onClose) { /* 不变的 Close icon */ }
}
```

### `ui/settings/SettingsHomePage.kt`

把三个 navigation row 包成 section block：

```kotlin
Column(modifier = Modifier.fillMaxWidth()) {
    PageMasthead(title = "Settings", rightSlot = todayLabel())
    Column(modifier = Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

        SectionHeader("Voice")
        SettingsNavigationRow(title = "LLM & Authentication", subtitle = ..., onClick = ...)

        SectionHeader("Behavior")
        SettingsNavigationRow(title = "Agent Behavior", subtitle = ..., onClick = ...)

        SectionHeader("System")
        SettingsNavigationRow(title = "Permissions & Advanced", subtitle = ..., onClick = ...)

        Fleuron()

        Text(
            text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.closePaw.monoSmall,
            color = MaterialTheme.closePaw.inkFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.closePaw.serifItalic.copy(fontSize = 18.sp),
        color = MaterialTheme.closePaw.inkFaint,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}
```

### `ui/chat/ChatScreen.kt`

#### "live" pill（行 293-333）

改成 mono pill，显式 border：

```kotlin
AnimatedVisibility(visible = !followMode && messages.isNotEmpty(), ...) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.clickable { /* 滚动逻辑不变 */ },
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), ...) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = "live",
                style = MaterialTheme.closePaw.monoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

#### Scaffold 背景

给 Scaffold 容器加 `paperGrain()` ——办法是传 `containerColor = Color.Transparent`，
然后在外层 surface 上链 `Modifier.background(...).paperGrain()`；或者在
`ChatScreen` 顶层 `Box` 内部应用。最简路径：在 `ModalNavigationDrawer` 根处包一
个带 `paperGrain()` 的 `Box`。
