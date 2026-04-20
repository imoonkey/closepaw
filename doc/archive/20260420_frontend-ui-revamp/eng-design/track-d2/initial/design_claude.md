# Track D2 — Visual Implementation Eng Design (Compose theme/tokens/animations)

**Author:** Claude
**Date:** 2026-04-20
**Status:** Initial draft (design-only)
**Inputs consumed:** `eng-design/note.md`, `aligned/design_aligned.md` (D1), `eng-design/track-a/final/design_aligned.md` (Track A row IA), current code under `app/src/main/kotlin/ai/closepaw/ui/theme/**`, `ui/capsule/**`, `ui/chat/**`.

---

## 1. Goal

Make D1's visual baseline (Paper/Ink/Claw palette, 5-tier spacing, 4-duration motion, three type families with extras, Track A row typography) implementable as a thin layer on top of `MaterialTheme`, with the **fewest possible** new abstractions, and migrate the existing ad-hoc styles in `ui/capsule/` and `ui/chat/components/` onto it.

Non-goals: a theming framework, a custom design-system module, multi-brand support, Material You dynamic color, runtime token swapping.

---

## 2. Verdict on the API surface

**Use `MaterialTheme` for everything it can carry, plus exactly one `CompositionLocal` (`LocalAgentTokens`) for the tokens Material has no slot for.** Do not invent per-token `CompositionLocal`s. Do not invent style objects. Animation specs are plain top-level `const`s and `val`s — no Local at all.

### Why this and not the alternatives

| Option | Files added | Indirection | Why rejected |
|---|---|---|---|
| **A. Material slots only** | 0 | none | Can't express `InkFaint`, `Hairline` vs `InkGhost` cleanly, mono/italic typography extras, motion durations, spacing tiers. We'd start sprinkling magic numbers again within a week. |
| **B. Material + one `LocalAgentTokens`** ✅ | 2 (`Tokens.kt`, `Motion.kt`) | one Local | Smallest surface that actually holds D1. Material carries 90% of the palette; Local carries the residue. Motion is constants — no Local needed. |
| C. CompositionLocal-per-domain (`LocalSpacing`, `LocalSemanticColors`, `LocalAgentTypography`, `LocalMotion`) | 4–5 | four Locals | Pure ceremony. Same data, four places to plumb. |
| D. Style-object-per-component (`CapsuleStyle`, `ChatRowStyle`) | many | one wrapper per component | Premature framework. Invents a layer between tokens and components for no win. |

### How D1 maps to Material slots

D1's palette substantially fits `ColorScheme`. Mapping (light shown; dark mirrors with `*Dark` counterparts):

| D1 token | Material slot |
|---|---|
| `Paper` | `background`, `surface` |
| `PaperInset` | `surfaceVariant`, `surfaceContainer*` family |
| `Ink` | `onBackground`, `onSurface` |
| `InkMuted` | `onSurfaceVariant` |
| `Claw` | `primary` (with `Paper` as `onPrimary` for high-contrast CTA labels — verify in §10 contrast handoff) |
| `Moss` | `secondary` |
| `Amber` | `tertiary` |
| `Rust` | `error` |
| `Hairline` (12% Ink) | `outline` |
| `InkGhost` (8% Ink) | `outlineVariant` |

**Residue that Material has no slot for** → goes into `AgentTokens`:

- `InkFaint` (third-rank text — distinct from `InkMuted`)
- Three named radii (`small=8`, `card=10`, `pill=16`) — Material's `Shapes` carries this fine, no extension needed; just rename slots.
- Five spacing tiers (`xs/sm/md/lg/xl`) — Material has no spacing system.
- Type extras: `monoBody`, `monoSmall`, `bodyItalic`, `serifItalic` — Material's `Typography` is a fixed-name set.
- Folded-paper elevation modifier (`Modifier.foldedPaper()`) — single function, not a token.

### Final API surface

```kotlin
// Usage at call sites is trivial:
val ink = MaterialTheme.colorScheme.onSurface         // D1 Ink
val claw = MaterialTheme.colorScheme.primary          // D1 Claw
val faint = MaterialTheme.tokens.colors.inkFaint      // D1 InkFaint
val mono = MaterialTheme.tokens.type.monoBody         // D1 monoBody
val md = MaterialTheme.tokens.space.md                // 12dp

Modifier
    .padding(MaterialTheme.tokens.space.lg)
    .foldedPaper()                                     // capsule elevation
```

`MaterialTheme.tokens` is a single inline composable extension property reading `LocalAgentTokens.current`. One Local, one data tree, one access pattern. That's the whole system.

---

## 3. Animation primitives

Motion lives in `theme/Motion.kt` as plain Kotlin — no `CompositionLocal`, no Local provider. Durations are `const Int`s; easings are top-level `val`s; reusable `EnterTransition`/`ExitTransition` builders are top-level functions. This is enough because motion does not change per-theme.

### 3.1 D1 motion contract realized

```kotlin
object AgentMotion {
    // Durations — D1 §5.1
    const val Fast = 120        // glyph/status flips, reduced-motion fades
    const val Standard = 240    // entry/exit, expand/collapse
    const val Slow = 480        // capsule mode transitions
    const val Long = 900        // ink-drop pulse, long-press fill

    // Easings — D1 §5.1 (no springs, no overshoot)
    val EaseStandard: Easing = EaseInOutSine
    val EaseEmphasized: Easing = EaseOutCubic

    // Reusable specs
    fun <T> standardTween(): TweenSpec<T> = tween(Standard, easing = EaseEmphasized)
    fun <T> fastTween(): TweenSpec<T> = tween(Fast, easing = EaseStandard)
}
```

### 3.2 Track A row motion (wired to AgentMotion)

Track A §8 specifies an exact 8dp slide. `slideInVertically`'s offset lambda is `(fullHeight: Int) -> Int` and we want a fixed-dp upward translation independent of element height, so the helper must be `@Composable` to read `LocalDensity`:

```kotlin
// Trace item appearance — Track A §8 (exact 8dp)
@Composable
fun traceItemEnter(): EnterTransition {
    val offset = with(LocalDensity.current) { 8.dp.roundToPx() }
    return fadeIn(AgentMotion.standardTween()) +
        slideInVertically(AgentMotion.standardTween()) { -offset }
}

// Row expand/collapse — Track A §8
fun rowExpand(): EnterTransition =
    expandVertically(AgentMotion.standardTween()) + fadeIn(AgentMotion.standardTween())
fun rowCollapse(): ExitTransition =
    shrinkVertically(AgentMotion.standardTween()) + fadeOut(AgentMotion.standardTween())

// Action status glyph cross-fade (⏳ → ✓) — 120ms
fun statusGlyphCrossfade(): ContentTransform =
    (fadeIn(AgentMotion.fastTween()) togetherWith fadeOut(AgentMotion.fastTween()))
        .using(SizeTransform(clip = false))
```

The `@Composable` annotation is acceptable here — every call site already runs inside composition. Alternative was passing density at call site, which leaks plumbing into every Track A row — strictly worse.

### 3.3 Capsule motion

- **Breath (Running only):** `infiniteTransition` with `tween(Slow, easing = EaseStandard, repeatMode = Reverse)` on a scale 1.00 → 1.02. Breath is gated by capsule mode at the call site — no global toggle.
- **Mode transition:** `AnimatedContent` keyed on `CapsuleMode` with `standardTween()` cross-fade.
- **Status glyph swap (paw toes / ⏳ → ✓):** same `statusGlyphCrossfade()` Track A uses. One primitive, two callers.

### 3.4 Streaming cursor (D1 Open Question — closed)

D1 §4.2 flags Compose's text cursor as not trivially restyle-able. The deeper inspection: the Final block is **read-only display**, not an editable `TextField`, so no platform cursor is involved at all. The "cursor" is a separately-rendered glyph anchored to the end of the streaming string.

But there's a real implementation gotcha: when streaming text wraps to multiple lines and ends mid-line, the cursor must sit immediately after the last character on the *last visual line*, not at the right edge of the `Text` bounding box and not on its own row below. Naive `Row { Text(stream); Cursor() }` fails — Cursor lands at the right edge of the Text block, vertically centered. The canonical Compose solution is `inlineContent`:

```kotlin
private const val CURSOR_TAG = "cursor"

@Composable
fun StreamingFinal(text: String, modifier: Modifier = Modifier) {
    val annotated = remember(text) {
        buildAnnotatedString {
            append(text)
            appendInlineContent(CURSOR_TAG, "|")
        }
    }
    val inline = mapOf(
        CURSOR_TAG to InlineTextContent(
            placeholder = Placeholder(
                width = 0.5.em,
                height = 1.em,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            ),
            children = { BlinkingPipe() },
        ),
    )
    Text(annotated, inlineContent = inline, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
}

@Composable
private fun BlinkingPipe() {
    val alpha by rememberInfiniteTransition(label = "cursor")
        .animateFloat(
            initialValue = 1f, targetValue = 0f,
            animationSpec = infiniteRepeatable(
                tween(AgentMotion.Slow, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "alpha",
        )
    Text("|", style = MaterialTheme.tokens.type.serifItalic, modifier = Modifier.alpha(alpha))
}
```

`inlineContent` places the cursor in the text layout itself, so it follows reflow, line-wraps with the stream, and sits on the correct visual line. **D1's Open Question is closed**: no `TextField` cursor involvement, and the multi-line anchoring problem has a one-API-call solution. Fallback to Geist sans is a one-line style swap if Fraunces font loading is delayed.

### 3.5 Reduced-motion fallback

D1 §8: "replace slide-in with instant + 120ms fade; collapse/expand becomes instant; breath pauses to a static paw at full alpha." Single helper read at call sites:

```kotlin
@Composable
fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}
```

Each motion call site picks: full motion or `EnterTransition.None + fadeIn(fastTween())` / `infiniteTransition` skipped entirely. No global wrapper that mutates all transitions — too magical, hides what each component actually does. Reading at call sites also lets us defend specific motions that should *never* reduce (e.g., the streaming cursor blink, which is a liveness signal, not decoration).

### 3.6 Folded-paper elevation primitive

D1 §4.4: "subtle warm under-shadow plus a top hairline." Implemented as one `Modifier`:

```kotlin
@Composable
fun Modifier.foldedPaper(shape: Shape = MaterialTheme.shapes.large): Modifier {
    val warm = MaterialTheme.colorScheme.scrim          // Ink-derived, warm in our palette
    val hairline = MaterialTheme.colorScheme.outline    // Hairline (12% Ink)
    val strokePx = with(LocalDensity.current) { 1.dp.toPx() }
    return this
        .shadow(elevation = 4.dp, shape = shape, ambientColor = warm, spotColor = warm)
        .drawWithContent {
            drawContent()
            drawLine(hairline, Offset(0f, 0f), Offset(size.width, 0f), strokePx)
        }
}
```

Compose 1.4+ supports `ambientColor`/`spotColor` on `shadow()`, so the warm tint is native — no custom paint. The top hairline is a `drawWithContent` add-on, drawn after content so it sits on top.

---

## 4. File structure

Total new files: **2** (`Tokens.kt`, `Motion.kt`). Modified: `Color.kt`, `Type.kt`, `Shape.kt`, `Theme.kt`. Deleted: nothing yet (legacy color symbols deleted in migration Phase D2-3).

```
app/src/main/kotlin/ai/closepaw/ui/theme/
├── Color.kt        ── D1 Paper/Ink/Claw palette (light + dark). Replaces dual legacy + Chat* sets.
├── Type.kt         ── Material Typography slots wired to Geist/Fraunces FontFamily. No extras here.
├── Shape.kt        ── 3 shapes only: small (8), medium=card (10), large=pill (16). Drops Bubble*/Card/Pill/Sheet/Input.
├── Tokens.kt       ── NEW. AgentTokens (colors.inkFaint, type.monoBody/monoSmall/bodyItalic/serifItalic, space.xs..xl).
│                      LocalAgentTokens CompositionLocal. MaterialTheme.tokens extension. Modifier.foldedPaper().
├── Motion.kt       ── NEW. AgentMotion constants + traceItemEnter/rowExpand/rowCollapse/statusGlyphCrossfade.
├── Theme.kt        ── Wires colorScheme + Typography + Shapes + provides LocalAgentTokens.
└── WindowInsets.kt ── unchanged
```

Reusable styled components live where they're used. **No `ui/components/` shared module is created up-front.** A component graduates to a shared location only when a second caller appears. Initial candidates that already have ≥2 callers post-migration:

- `StreamingCursor` (Final block + potential capsule echo) → `ui/chat/components/` for now (single caller).
- `PawGlyph` (capsule status + chat thinking indicator + empty-state watermark) → already exists conceptually as `ic_paw` drawable; render via `Icon(painterResource(R.drawable.ic_paw), ...)`. No new Kotlin component.
- `Hairline` divider → inlined as `HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)`. Material already provides this; no wrapper.

KISS check: zero new shared component files in this design. Track B already owns capsule row decomposition; Track A consumers (chat row) will land their components under `ui/chat/components/` as they're built. D2 only ships tokens + motion.

---

## 5. Migration plan

Three phases, each independently mergeable. Each phase is one PR, in order.

### Phase D2-1 — Land tokens (no behavior change)

**Touches:** `theme/Color.kt`, `theme/Type.kt`, `theme/Shape.kt`, `theme/Theme.kt`, new `theme/Tokens.kt`, new `theme/Motion.kt`, **font assets under `app/src/main/res/font/`**.

0. **Font assets (hard prerequisite).** Add Fraunces (variable, SIL OFL), Geist (variable, SIL OFL), JetBrains Mono (Apache 2.0) under `res/font/` with `font_certs.xml` / NOTICE attribution as required by license. Without these, `Type.kt` wiring crashes at first composition. This is a sub-task of D2-1, not a follow-up.
1. Rewrite `Color.kt` with D1 palette (`Paper`, `Ink`, `Claw`, `Moss`, `Amber`, `Rust`, `Hairline`, `InkGhost`, `InkFaint`, dark counterparts). **Keep legacy symbols (`Background`, `Primary`, `ChatPrimary`, etc.) as `val` re-exports pointing into the new palette** so call sites keep compiling. Delete in Phase D2-3.
2. Rewrite `Type.kt`: Material slots use Geist (sans) at the existing sizes; `FontFamily` declarations for Fraunces and JetBrainsMono (consumed by `AgentTokens.type` extras).
3. Slim `Shape.kt` to three shapes (`small=8`, `medium=10`, `large=16`); keep legacy aliases (`BubbleShapeUser`, `CapsuleShape`, etc.) re-exporting to the three. Delete aliases in Phase D2-3.
4. Add `Tokens.kt` with `AgentTokens` data class, `LocalAgentTokens`, `MaterialTheme.tokens` extension, `Modifier.foldedPaper()`.
5. Add `Motion.kt` with `AgentMotion`, primitives, `reducedMotion()`.
6. `Theme.kt`: build `colorScheme` from D1 palette via the §2 mapping; provide `LocalAgentTokens` inside `MaterialTheme`.

**Acceptance:** project builds; UI renders with D1 palette/typography (visible diff only — no layout breakage); manual capsule + chat + onboarding screenshot pass.

### Phase D2-2 — Migrate capsule call sites

**Touches:** `ui/capsule/surface/CapsuleControlBar.kt`, `CapsuleInputBar.kt`, `SmartCapsuleSurface.kt`, `SmartCapsuleHostLayout.kt`.

Mechanical replacement of magic numbers and ad-hoc colors:

- `RoundedCornerShape(14.dp)` / `(24.dp)` / `(10.dp)` → `MaterialTheme.shapes.small` / `.large` (capsule pill) / `.medium`.
- `Modifier.size(16.dp)` etc. — leave concrete sizes when they're icon sizes (Material spec); replace layout paddings with `MaterialTheme.tokens.space.*`.
- `fontSize = 14.sp` → drop, inherit from `MaterialTheme.typography.bodyMedium`.
- `shadowElevation = 8.dp` on capsule → replace with `Modifier.foldedPaper()`.
- Hardcoded color literals (none expected in capsule per audit) → tokens.
- Capsule breath / mode transitions → `AgentMotion.standardTween()` / `slowTween()`.

**Acceptance:** capsule renders identically (allowing palette shift from Phase 1); zero `Color(0x` literals, zero raw `.dp` paddings (icon sizes exempt), zero raw `.sp` font sizes in these files.

### Phase D2-3 — Migrate chat components and delete legacy aliases

**Touches:** `ui/chat/components/*.kt`, `ui/chat/ChatScreen.kt`; finally `theme/Color.kt`, `theme/Shape.kt` to delete aliases.

- `MessageBubble.kt`: bubble shape → `MaterialTheme.shapes.medium` (D1 `card=10`). User bubble background → `MaterialTheme.colorScheme.surfaceVariant` (PaperInset). Symmetric corners (D1 §6.2 — drop the 6dp asymmetric tail).
- `ActionCard.kt`: **slated for deletion** by Track A (no standalone action cards; inline trace rows). D2 just stops styling it; Track A's chat-row implementation deletes the file.
- `ChatHeader.kt`, `EmptyState.kt`, `ThinkingIndicator.kt`, `StreamingText.kt`: same magic-number sweep as Phase D2-2.
- Remove all legacy color symbols from `Color.kt` (`Background`, `Surface`, `Primary`, `ChatPrimary`, ... — the entire dual set). Remove legacy shape aliases (`BubbleShapeUser`, `BubbleShapeAgent`, `CapsuleShape`, `PillShape`, `CardShape`, `InputShape`, `SheetShape`).
- Remove `BubbleShapeAgent` usage entirely (D1: no agent bubble).

**Acceptance:** `grep -r "Color(0x" app/src/main/kotlin/ai/closepaw/ui/` returns only token definitions in `Color.kt`. `grep -rn "ChatPrimary\|BubbleShape\|CapsuleShape" app/` returns zero hits. Chat + capsule render with the full D1 visual identity.

### Sequencing with other tracks

- **Track B** (capsule refactor) is already complete (per `note.md`). D2-2 touches the renamed files post-B.
- **Track A** implementation (chat row IA) lands after D2-1; A's new components consume `MaterialTheme.tokens` and `AgentMotion` from day one — born on tokens, no migration debt. D2-3 may shrink to "delete the chat components A obsoleted (`ActionCard.kt`) + sweep the survivors." If A's PR is in flight when D2-3 lands, **fold D2-3 into A's PR** rather than serialize — they touch overlapping files.
- **Track C** (state-machine doc + tests) is independent; provides the safety net for D2-3 chat changes.

### Why phased aliases instead of one big-bang PR

A 1,700-line UI codebase migrated in one PR is unreviewable. The alias layer in D2-1 introduces a "wrong way" to write code (e.g., `ChatPrimary`) that exists for one PR cycle. Mitigation: (a) the aliases are literally `val ChatPrimary = Claw` — zero behavior change, so accidental new usage is corrected by D2-3's deletion sweep; (b) D2-2 and D2-3 land within the same week, bounding the alias half-life. The cost (one PR cycle of dual idioms) is worth the benefit (three reviewable PRs vs. one unreviewable one).

---

## 6. Components

| File | Status | What changes |
|---|---|---|
| `theme/Color.kt` | rewritten | D1 palette only; legacy symbols become aliases in D2-1, deleted in D2-3 |
| `theme/Type.kt` | rewritten | Material slots wired to Geist; FontFamily declarations for Fraunces + JetBrainsMono |
| `theme/Shape.kt` | slimmed | 3 shapes; aliases in D2-1, deleted in D2-3 |
| `theme/Theme.kt` | extended | Maps D1 palette into Material colorScheme, provides `LocalAgentTokens` |
| `theme/Tokens.kt` | **new** | `AgentTokens` (colors.inkFaint, type extras, space tiers), `LocalAgentTokens`, `MaterialTheme.tokens`, `Modifier.foldedPaper()` |
| `theme/Motion.kt` | **new** | `AgentMotion` durations/easings + entry/exit/expand/glyph primitives + `StreamingCursor` (or in chat/components) |
| `ui/capsule/surface/*.kt` | migrated | Magic numbers → tokens; ad-hoc shadow → `foldedPaper()` |
| `ui/chat/components/MessageBubble.kt` | migrated | Symmetric `medium` shape, `surfaceVariant` background |
| `ui/chat/components/ActionCard.kt` | **deleted** by Track A | D2 leaves it alone |
| `ui/chat/components/{ChatHeader,EmptyState,ThinkingIndicator,StreamingText}.kt` | migrated | Magic numbers → tokens |
| `ui/chat/ChatScreen.kt` | migrated | Spacing/colors → tokens |

---

## 7. Interactions

Token resolution at any call site:

```
CallSite → MaterialTheme.tokens (composable extension)
         → LocalAgentTokens.current (CompositionLocal)
         → AgentTokens instance (provided in Theme.kt)
         → AgentColors / AgentTypeExtras / AgentSpacing (plain data classes)
```

One Local read per access. Compose recomposes on Local change — but `AgentTokens` is constant per (light/dark) theme, so recompositions only happen when the system theme flips, which is the desired behavior.

`AgentMotion` is referenced statically — no Compose plumbing.

`MaterialTheme.colorScheme` and `MaterialTheme.typography` are used directly for everything Material covers. There is **no `MaterialTheme.tokens.colors.paper`** alias — that would be `MaterialTheme.colorScheme.background`. Two ways to spell the same color is exactly what KISS forbids; the Material slot wins because it's the existing idiom.

---

## 8. Tasks

Tasks for `/update-tasks` to populate the task graph. Each task is one mergeable PR.

| Slug | Scope | Acceptance | Depends on |
|---|---|---|---|
| `d2-1-tokens-and-motion` | `app/src/main/kotlin/ai/closepaw/ui/theme/**`, new `Tokens.kt`, new `Motion.kt`, font asset additions under `app/src/main/res/font/` | Project builds; capsule + chat + onboarding screenshots show D1 palette/typography; legacy color/shape symbols still resolve as aliases | D1 aligned spec (this doc's input) |
| `d2-2-capsule-migration` | `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/*.kt` | Zero raw `Color(0x`, zero raw padding `.dp`, zero raw `.sp` font sizes in capsule files (icon sizes exempt); capsule visually unchanged after Phase 1 | `d2-1-tokens-and-motion`, Track B (done) |
| `d2-3-chat-migration-and-cleanup` | `app/src/main/kotlin/ai/closepaw/ui/chat/components/*.kt`, `ChatScreen.kt`, `theme/Color.kt`, `theme/Shape.kt` (delete aliases) | Same magic-number invariants in chat files; `ChatPrimary*`/`BubbleShape*`/legacy color set deleted; grep clean | `d2-1`, Track A implementation (parallel/coordinated) |
| `d2-4-contrast-handoff` | `doc/todo/frontend-ui-review/aligned/contrast-matrix.md` (new) | Measured contrast pairs for `Ink/InkMuted/Claw/Moss/Amber/Rust on Paper/PaperInset` (light + dark); AA min for body, AA-large min for status. Required by D1 §4.1 | `d2-1` (palette landed) |

---

## 9. Trade-offs

**Why `MaterialTheme.tokens` extension over scattering top-level vals (`Space.md`, `MonoBody`, `inkFaint()`).** I considered killing the Local entirely: spacing has no theme dependency, type extras have no theme dependency, only `InkFaint` actually needs to switch on dark mode. So in principle `Space`, `MonoBody`, etc. could be plain top-level `val`s and `InkFaint` a `@Composable get()` reading the system theme. That ditches `Tokens.kt`. But it produces *five* different access roots (`MaterialTheme.X`, top-level `Space`, top-level `MonoBody`, function-call `inkFaint()`, top-level `AgentMotion`). One root for everything-not-Material is cheaper to learn and read than five. The Local pays for itself the moment a second theme-dependent token appears (and one always does — semantic glyph tints, brand-tinted shadows, etc.).

**Why `MaterialTheme.tokens` extension over a separate `AgentTheme.foo` accessor.** Two access patterns (`MaterialTheme.colorScheme.x` and `AgentTheme.colors.y`) double the cognitive load. Hanging `tokens` off `MaterialTheme` keeps one root, one `M`-completion in IDE.

**Why not put `InkFaint` into Material's `outlineVariant` and reuse it for both faint text and 8% dividers.** `outlineVariant` is `InkGhost` (8% Ink) per the §2 mapping. `InkFaint` is a third-rank text tone (~50% Ink) — semantically distinct. Forcing them to share would muddy contrast guarantees.

**Why no `LocalSpacing`.** Spacing values do not change per theme. They are constants. Wrapping them in a Local adds runtime cost (Local lookup) and ceremony (Provider plumbing) for zero benefit. They live in `AgentTokens.space` only because that's where `monoBody`/`monoSmall` already are — one Local for everything that's "ours."

**Why motion has no Local at all.** Same argument as spacing, plus motion specs are functions, not data — no per-theme variance.

**Why aliases during migration instead of breaking changes in one PR.** A 1700-line UI codebase migrated in one PR is unreviewable. Phase D2-1's alias layer keeps each phase's diff small and independently revertable. Phase D2-3 deletes aliases in a commit dedicated to that.

**Why Track A's chat-row components are not pre-built in D2.** D2 ships the kit; A consumes it. Pre-building components A doesn't need yet is speculation. Reusable extractions wait for the second caller.

**Why the streaming cursor is rendered text, not a `TextField` cursor.** D1 flagged this as a Phase 3 verification item. Resolved here: the Final block is read-only `Text`, so there is no `TextField` cursor to restyle. The "cursor" is just a blinking glyph after the streaming text. Compose handles this trivially — D1's open question is closed.

---

## 10. Self-review against goal

- ✅ Specifies token API surface (§2) — picked Material + one Local with rationale.
- ✅ Specifies motion primitives wiring D1 (§3) — closes D1's streaming-cursor open question.
- ✅ Specifies file structure (§4) — 2 new files, no shared component module up-front.
- ✅ Specifies migration plan (§5) — three phased PRs, each independently mergeable, with grep-able acceptance.
- ✅ KISS: 1 Local total, 0 Locals for motion, 2 new theme files, 0 new shared components.
- ✅ Aligns with codebase: extends existing `Theme.kt`/`Color.kt`/`Type.kt`/`Shape.kt`; does not introduce a parallel design-system module.
- ✅ Track A compatibility: §3.2 wires the exact motion specs Track A §8 calls for; §2 type extras (`bodyItalic`, `monoBody`, `serifItalic`) are the names Track A's row uses.

## 11. Open questions

None blocking. Items deferred:

- Font licensing/sourcing for Fraunces, Geist, JetBrainsMono (procurement, not design).
- Whether `Modifier.foldedPaper()` needs a tunable shadow color across light/dark — start with one warm shadow tinted by `colorScheme.shadow`-equivalent, revisit if dark-mode capsule reads wrong.
