# Per-file changes

> Code sketches, not final implementations. Every change names the file and
> the symbol so an implementer can pick it up cold.

## New files

### `ui/theme/Ornaments.kt` (new)

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

/** Centered Fraunces fleuron, the one shared "section divider" ornament. */
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

/** Running-head row used by ChatHeader, NavigationDrawer header, Settings home. */
@Composable
fun PageMasthead(
    title: String,
    rightSlot: String? = null,             // "No 04 · Apr 22" or null
    leadingPaw: Boolean = true,
    onLeadingClick: (() -> Unit)? = null,  // menu-icon caller passes its handler
)
// Body: Row(SpaceBetween) of [paw + title in Fraunces italic Reg] | [rightSlot in monoSmall]
// Followed by a 1dp Hairline rule across full width.
```

### `ui/theme/PaperGrain.kt` (new)

```kotlin
fun Modifier.paperGrain(strength: Float = 0.015f): Modifier = composed {
    val isDark = LocalClosePawTokens.current === darkClosePawTokens()  // or pass via theme
    if (isDark) return@composed this
    val bitmap = remember(strength) { bakeNoiseTile(size = 256, alpha = strength) }
    drawWithContent {
        drawContent()
        drawImageRect(image = bitmap, /* tiled fill via repeat */)
    }
}
```

`bakeNoiseTile` is a private helper that fills a 256×256 ImageBitmap with
deterministic blue-noise (seed = 42) at `Ink` color, alpha = `strength`.
Bake once per session.

For dark theme, expose `Modifier.lanternVignette()` with a radial brush warm
gradient (no bitmap).

## File-by-file edits

### `ui/theme/Type.kt`

**Problem:** `displaySmall` is `FontWeight.SemiBold` and is being used with
`fontFamily = Fraunces` at three call sites. Fraunces SemiBold isn't shipped.

**Option A (preferred):** ship `fraunces_medium.ttf` (or semibold) and add it
to the `FontFamily`:

```kotlin
val Fraunces: FontFamily = FontFamily(
    Font(R.font.fraunces_regular, FontWeight.Normal),
    Font(R.font.fraunces_medium, FontWeight.Medium),       // new
    Font(R.font.fraunces_italic, FontWeight.Normal, FontStyle.Italic),
)
```

**Option B (no asset add):** retire upright Fraunces from identity surfaces;
standardize on Fraunces *italic Regular* everywhere a serif appears. Already
the spec for thought items and EmptyState subtitle — extend it to titles too.
Option B requires changing the three call sites listed in `critique.md` but
adds zero binary weight.

Pick A if shipping a TTF is acceptable; B otherwise. `direction.md` assumes A.

### `ui/chat/components/ChatHeader.kt`

Replace the body (`ChatHeader.kt:43-99`) with:

```kotlin
Surface(
    modifier = modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars),
    color = MaterialTheme.colorScheme.background,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenuClick) { /* unchanged Menu icon */ }
        Spacer(Modifier.width(4.dp))
        PageMasthead(
            title = "ClosePaw",
            rightSlot = todayLabel(),       // "Apr 22"
            leadingPaw = true,
            modifier = Modifier.weight(1f),
        )
        if (showNewChatButton && onNewChatClick != null) {
            IconButton(onClick = onNewChatClick) { /* unchanged Add icon */ }
        }
    }
}
```

`todayLabel()` is a thin wrapper around `DateFormat.getMediumDateFormat`
returning the day-month string in the user's locale.

### `ui/chat/components/EmptyState.kt`

Full rewrite to asymmetric layout (~80 LOC). Pseudocode:

```kotlin
Box(modifier = modifier.fillMaxSize().paperGrain()) {
    // Top-right paw, bleeds (clipped by Box bounds).
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

`StaggeredReveal` wraps content in `AnimatedVisibility` with delay = `index * 80ms`,
animation = `fadeIn(tween(ClosePawMotion.TraceEnter)) + slideInVertically(8dp → 0)`.
Skip animation under reduced-motion; render visible immediately.

`MarginaliaSuggestion`:
```
Row {
  Text("→", bodyLarge, inkFaint)               // ASCII arrow, not mono — D1 reserves mono for machine text
  Spacer(sm)
  Column {
    Text(verb, MaterialTheme.typography.bodyLarge, onSurface)
    Text(gloss, MaterialTheme.closePaw.bodyItalic, inkMuted)
  }
}
```
Tap target: full row width, padding `vertical = sm`.

### `ui/chat/components/MessageBubble.kt`

#### `UserBubble` (lines 65-101)

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
            // 1dp Hairline left rule, full text height.
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

#### `FinalSeparator` (line 262)

Replace entire composable body with a call to `Fleuron()`. Delete the
`outlineVariant` rule.

#### `CollapsedHeader` (line 166) and `OutcomeFooter` (line 198)

Swap glyphs:

```kotlin
// CollapsedHeader leading:
Text("[+]", style = MaterialTheme.closePaw.monoBody, color = MaterialTheme.closePaw.inkFaint)
// CollapsedHeader trailing (remove the standalone "▸"; fold into summary):
// summary already includes "headline · N actions · 4.2s" — append nothing.

// OutcomeFooter:
Text("[−] $text", style = MaterialTheme.closePaw.monoSmall, color = MaterialTheme.closePaw.inkFaint)
```

(The `[+] / [−]` brackets are also clearer for screen readers — no
`stateDescription` change needed; the existing one already says "collapsed"/
"expanded".)

### `ui/chat/components/ThinkingIndicator.kt`

Replace the `Surface(...) { Box(...) { PawToeSequence(...) } }` chain
(`ThinkingIndicator.kt:38-52`) with:

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

The `Surface` wrapper goes away. Existing test `ThinkingIndicatorTintTest`
should still pass (`THINKING_INDICATOR_TINT.resolve()` unchanged); confirm
during implementation.

### `ui/capsule/surface/CapsuleControlBar.kt`

#### Stop button (lines 140-167)

Add explicit Rust border:

```kotlin
OutlinedButton(
    onClick = { ... },
    enabled = btn.enabled,
    shape = MaterialTheme.shapes.large,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),   // ← new
    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
) { /* unchanged content */ }
```

### `ui/capsule/surface/SmartCapsuleSurface.kt`

#### `CapsuleStatusLine` (lines 222-266)

Add ledger counter for `Running` mode. Take a `mode` parameter (or compute
elapsed at call site and pass `String?`):

```kotlin
@Composable
private fun CapsuleStatusLine(
    spec: CapsuleRenderSpec,
    elapsedLabel: String?,           // "[t+12s]" or null
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

The ticker lives in `SmartCapsuleSurface`:

```kotlin
val elapsedLabel = produceElapsedLabel(mode)  // ticks 1Hz when Running, null otherwise
```

`produceElapsedLabel` is a small composable that uses `LaunchedEffect(mode)`
+ `delay(1.seconds)` + `remember` of the start timestamp from `mode`. Reduced
motion has no bearing — it's a status, not motion.

#### `StartupErrorBanner` (lines 184-220)

Add Rust left rule and `⚠` glyph:

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

#### Send button breath (lines 150-161)

Wrap the click with a one-shot scale animation:

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
) { /* unchanged Icon */ }
```

### `ui/onboarding/OnboardingShell.kt`

#### `FivePawProgress` (lines 119-137)

Bump unfilled tint from `outlineVariant` (8% Ink, invisible) to `inkFaint`:

```kotlin
tint = if (i < filled) MaterialTheme.colorScheme.primary
       else MaterialTheme.closePaw.inkFaint
```

#### Title (lines 102-107)

If pursuing Type Option B (no new TTF):

```kotlin
Text(
    text = title,
    style = MaterialTheme.closePaw.serifItalic.copy(fontSize = 32.sp, lineHeight = 40.sp),
    color = MaterialTheme.colorScheme.onBackground,
)
```

If Option A (ship Fraunces medium), keep current upright but add explicit
`FontWeight.Medium` and ensure font is loaded.

### `ui/navigation/NavigationDrawer.kt`

#### `DrawerHeader` (lines 162-188)

Replace the `Sessions` Text with `PageMasthead`:

```kotlin
Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
    PageMasthead(
        title = "Sessions",
        rightSlot = todayLabel(),
        leadingPaw = true,
        modifier = Modifier.weight(1f),
    )
    IconButton(onClick = onClose) { /* unchanged Close icon */ }
}
```

### `ui/settings/SettingsHomePage.kt`

Wrap the three navigation rows in section blocks:

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

#### "live" pill (lines 293-333)

Reshape to mono pill with explicit border:

```kotlin
AnimatedVisibility(visible = !followMode && messages.isNotEmpty(), ...) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.clickable { /* unchanged scroll */ },
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

#### Scaffold background

Add `paperGrain()` modifier on the `Scaffold`'s container — done by passing
`containerColor = Color.Transparent` and applying `Modifier.background(...).paperGrain()`
to the outer surface, OR by applying the modifier inside `ChatScreen`'s top
`Box`. Simplest path: at the `ModalNavigationDrawer` root, wrap with a `Box`
that has `paperGrain()`.
