# Settings Design Unification — Bound Edition

> Pull Settings (home + every sub-page) into the same Bound Edition register
> that Chat and Capsule already inhabit. End state: flipping between Chat,
> Settings home, any settings sub-page, and the Capsule overlay reads as one
> bound book — same warm paper grain, same Fraunces italic running head, same
> folded-paper tactile chrome, same Fleuron seals.
> Last updated: 2026-05-18 (v1 — Claude initial draft, post-HTML-preview)

Visual preview: `.design-preview/option-1-bound-edition.png` + live tabbed
HTML at `.design-preview/index.html`.

> **Calibration note (2026-05-18):** the HTML preview rendered grain at
> ~50% multiply blend for legibility. The real `Modifier.paperGrain()` runs
> at 4% max alpha (`PaperGrain.kt:41` — `strength = 0.04f`), which on a
> real device is a faint paper-quality, not a magazine-thick texture.
> **This plan does NOT change grain strength.** Settings adopts the same
> 4% grain Chat already uses — outcome will look like current Chat, not
> like the punched-up HTML preview. Tuning grain to feel more tactile is
> tracked as a separate followup (see Open question 7).

## Goal

Today, Chat and Capsule live in the D1 "Bound Edition" register. Settings
drifted off it during organic growth. The audit (`/home/qiguo/ld-workspace/
android-agent-workspace/androidagent/.design-preview/` and inline analysis)
found:

- **Three competing header patterns** in Settings: `PageMasthead`,
  `SettingsHeader` (dead duplicate), `SettingsSubPageHeader` (off-system
  back+close row used by every sub-page).
- **Six hand-rolled "card/row" variants** (`SettingsRow`,
  `SettingsNavigationRow`, `ToolSettingsCard`, `OpenAiAuthCard`,
  `MemoryFileEditor`, `AppRowExpansion`) with duplicated shape/padding/color
  logic.
- **Ad-hoc `RoundedCornerShape(12.dp)`** scattered across `OpenAiAuthCard`,
  `ApiKeyFields`, `LlmAuthSettingsPage` (lines 623/645), `ToolsSection`
  buttons (334/364/402), `MemorySettingsPage` (83), `AppRowExpansion`
  (128/157). None of these match D1's three radii (8/10/16).
- **Raw `16.dp` / `12.dp` / `24.dp` paddings** instead of `spacing.*` tokens
  — Chat and Capsule consistently use the grid, Settings almost never does.
- **No `paperGrain()`** on the Settings shell. Chat shell has it.
- **`HorizontalDivider`** in `LlmAuthDiscoveryUi` where every other surface
  seals sections with `Fleuron()`.
- **Doc/code drift in the ornaments themselves**: `style.md` claims
  `PageMasthead` renders `paw · title · todayLabel · trailing`, but the
  actual `Ornaments.kt:49–86` signature is just `(title, leadingPaw,
  onLeadingClick)` — no right-slot, no ledger date anywhere in the app.
  Chat's mockup in style.md describes a header that doesn't exist.

Outcome: one consistent identity register across **all** the following
surfaces (full Settings inventory):

- `SettingsHomePage`
- `LlmAuthSettingsPage` (and its discovery sub-flow `LlmAuthDiscoveryUi`)
- `AgentBehaviorSettingsPage`
- `MemorySettingsPage` + `MemoryFileEditorPage` (a deeper nested page
  reached from Memory)
- `PermissionsAdvancedSettingsPage`
- `AppAccessSettingsPage` (and its inline `AppRow` / `AppRowExpansion`)
- `OpenSourceLicensesPage`
- `ModelPicker` (modal, reached from LLM Auth)

Plus the supporting widget files: `SettingsSheet`, `SettingsWidgets`,
`SettingsDropdown`, `ApiKeyFields`, `OpenAiAuthCard`, `ToolSettingsCard`,
`ToolsSection`, `AgentSkillToggleRows`.

Out of scope: changing the data layer (`AppSettingsStore`, `MemoryStore`,
gates, classifiers), changing the page navigation graph, changing copy or
information architecture. This is a visual+chrome unification only.

## Approach

**Pull Settings into the existing Bound Edition.** Don't invent new
patterns. Use what Chat and Capsule already validated:

- `paperGrain()` background (currently Chat-only) → also Settings shell
- `PageMasthead` with paw + Fraunces italic title + **mono ledger date**
  (currently doc'd but never rendered) → Chat *and* Settings *and* drawer
  pick up the ledger date in the same Phase 0; sub-pages get a back-chevron
  variant
- `foldedPaper(shapes.medium)` tactile chrome (currently Capsule-only) →
  also Settings navigation/action cards (rule update — documented)
- `Fleuron()` ❦ seal (currently bottom of Settings home only) → replace
  every `HorizontalDivider` in Settings; close every identity sub-page
- `MaterialTheme.shapes.{small|medium|large}` → kill all
  `RoundedCornerShape(12.dp)`, normalize button radii to `shapes.large`
  (matching Capsule)
- `MaterialTheme.closePaw.spacing.*` → kill hardcoded `.dp` paddings

The unifying gesture: **Settings stops being a Material drawer and becomes
the appendix of the same bound book**.

### Key decisions

1. **Realize the doc's ornaments contract, don't drop it.** `style.md`
   describes `PageMasthead` as `[leading] · title · todayLabel · [trailing]`
   but the code only has `[paw] · title`. Two paths: (A) implement what the
   doc claims and propagate the ledger date everywhere, (B) update the doc
   to match reality and skip the ledger.

   Going with **(A)**. Rationale: the Capsule already shows `[t+12s]` in
   monoSmall as a "ledger" pattern, and the bound-book metaphor wants a
   running head. The HTML preview the user approved (Option ①) showed the
   ledger date on every masthead. Adding it is the smallest change that
   delivers the doc's promise across Chat header, Drawer header, Settings
   home, and every Settings sub-page in one stroke.

   Trade-off: this adds a small mono-text element to the Chat header that
   isn't there today. Risk: it visually competes with the "+ NewChat" icon.
   Mitigation: ledger is `inkFaint` monoSmall (10sp); already smaller and
   quieter than the title.

2. **One masthead primitive, two convenience wrappers.** Extend
   `PageMasthead` to take slots; offer two pre-composed wrappers so call
   sites stay terse:

   ```kotlin
   @Composable
   fun PageMasthead(
       title: String,
       modifier: Modifier = Modifier,
       leadingSlot: @Composable RowScope.() -> Unit = { PawGlyph() },
       rightSlot: @Composable RowScope.() -> Unit = { TodayLabel() },
       trailingSlot: @Composable RowScope.() -> Unit = {},
   )

   // Convenience for top-level identity pages (Settings home, Chat, Drawer)
   @Composable
   fun PageMastheadIdentity(title: String, onClose: (() -> Unit)? = null, ...)

   // Convenience for drilled-down pages (every Settings sub-page)
   @Composable
   fun PageMastheadDrillDown(title: String, onBack: () -> Unit, onClose: () -> Unit, ...)
   ```

   `leadingPaw: Boolean` and `onLeadingClick` are kept as call-site
   compatibility for one release, then deleted — three call sites is small
   enough to migrate in the same PR.

3. **`todayLabel` is a Composable, not a String.** Returning a `String`
   from a top-level function forces every caller to handle the locale
   `Context`. Making it `@Composable fun TodayLabel(style = ...)` lets it
   pull `LocalContext.current` itself and render the `Text` inline with the
   masthead's typography. Cheaper and avoids leaking `Context` into call
   sites. style.md describes it as a label-returning helper; we deliver the
   Composable form instead and update the doc.

4. **Sub-page mastheads are left-aligned, not centered.** Today,
   `SettingsSubPageHeader` centers the title with `textAlign = Center,
   modifier = weight(1f)`. That's a Material drawer/dialog convention,
   foreign to the Bound Edition. The unified pattern is:

   `[ ‹ ]  title (Fraunces italic, left-aligned)  ledger date  [ × ]`

   Same axis as home masthead, parent-context implied by the back chevron.

5. **The ledger date appears on every Settings sub-page masthead.** No
   "identity vs utility" carve-out. Consistency wins; the ledger is quiet
   (`inkFaint` monoSmall) and won't compete with the title. The single
   exception is modal pickers that aren't a "page" (e.g. `ModelPicker`
   bottom sheet) — these don't use `PageMasthead` at all.

6. **Folded paper now applies to Settings navigation cards.** style.md
   currently says folded paper is "Capsule and modal sheet/drawer only;
   everything else stays flat". We're updating that rule:

   > **Folded paper applies to:** Capsule surface, modal sheet/drawer
   > chrome, **and any Settings card that represents a navigable or
   > tappable page-of-the-book** (navigation rows, tool toggle cards, auth
   > cards). It does **not** apply to inline content cards (radio groups
   > inside a parent card, inline editors, alert banners) — nested shadows
   > muddy the hierarchy.

   This is the rule change we're committing to. The HTML preview
   demonstrates the result: cards lift off the paper with the same warm
   under-shadow the Capsule already uses, giving Settings the tactile
   "bound page you can flip" feel.

7. **Page padding normalizes to `spacing.lg` (20.dp), not 24.** Settings
   home uses 24.dp horizontal; style.md says lg=20 is the convention.
   Settings is the outlier. Fix is to drop to 20 (4dp less per side, 8dp
   wider content area). This is a visible change — flagging so the user
   can override.

8. **`SettingsCard` primitive in Phase 0, migration deferred to Phase 5.**
   Introduce one canonical `SettingsCard` Composable up front so new code
   has somewhere to land. But don't refactor the 6 existing hand-rolled
   variants in the same wave — those have intricate internal state
   (especially `ToolSettingsCard`, which already shipped its own
   unification round per `projects/active/tool_settings_unify/`). Phases
   1–4 apply foldedPaper / shape normalization inline to existing cards;
   Phase 5 (optional, later) collapses them.

9. **Card shape goes to `shapes.medium` (10dp), button shape to
   `shapes.large` (16dp).** Buttons in `ToolsSection` are the most visible
   outlier today (custom 12dp). Capsule buttons use `shapes.large` (16dp).
   Settings buttons follow Capsule — one button shape across the whole app.

10. **`SettingsAlertCard` for error/warning surfaces.** Three ad-hoc error
    surfaces today (`MemorySettingsPage:83`, `AppRowExpansion:128/157`,
    `ToolsSection` outlined-button error border). Introduce one primitive
    in Phase 3:

    ```kotlin
    @Composable
    internal fun SettingsAlertCard(
        message: String,
        tone: AlertTone = AlertTone.Error,
        icon: ImageVector? = null,
        action: (@Composable () -> Unit)? = null,
    )
    ```

    Shape `shapes.small` (8dp), padding `spacing.md`, color from
    `colorScheme.errorContainer` / `tertiaryContainer` per tone.

11. **Fleuron closes identity sub-pages, not tool sub-pages.** Rule:
    - Closing Fleuron: Settings home, LLM Auth, Agent Behavior,
      Permissions, App Access, Open Source Licenses
    - No Fleuron: Memory File Editor (tool), Model Picker (modal),
      sub-flows inside expansions (inline editors)

12. **`MemoryFileEditorPage` is treated as a sub-page, not a tool.** Even
    though it's an editor, the user reaches it via "Memory → file row",
    which is page-navigation. Header becomes `PageMastheadDrillDown`;
    closing Fleuron debatable — defer to Phase 3 manual decision.

13. **Adjacent bonus (separate PR, post Phase 3): chat trace timestamps.**
    Cheap echo of the Capsule's `[t+12s]` pattern on every chat agent
    action (`AgentTrace.kt`). Right-aligned monoSmall `[+12s]`. Doesn't
    touch Settings — listed here so it isn't lost. Not in the 5-phase
    main plan.

## Components

### New

- **`app/src/main/kotlin/ai/closepaw/ui/theme/Ornaments.kt`** — extend
  `PageMasthead` to slot-based API; add `PageMastheadIdentity`,
  `PageMastheadDrillDown` convenience wrappers; add `TodayLabel()`
  Composable; add `PawGlyph()` extracted Composable (currently inline in
  PageMasthead).

- **`app/src/main/kotlin/ai/closepaw/ui/settings/SettingsCard.kt`** (new
  file) — base `SettingsCard` primitive: `Surface(color = surfaceVariant,
  shape = shapes.medium).foldedPaper(shapes.medium)` with standard padding
  + optional `onClick` (with `clip(shapes.medium)` for ripple
  containment). Sized for content slot.

- **`app/src/main/kotlin/ai/closepaw/ui/settings/SettingsAlertCard.kt`**
  (new file) — `SettingsAlertCard(message, tone, icon, action)` primitive
  for error/warning surfaces.

- **`app/src/main/kotlin/ai/closepaw/ui/theme/Tokens.kt`** — add
  `ClosePawSpacing.cardPadding = 16.dp` token (documents the existing
  16dp internal card padding instead of repeating the literal). Add
  `ClosePawSpacing.pagePadding = lg` alias for clarity. No new visual
  output; intent annotation only.

### Modified

**Theme + ornaments (Phase 0)**
- `ui/theme/Ornaments.kt` — see above
- `ui/theme/Tokens.kt` — see above
- `doc/main/ui/style.md` — update `PageMasthead` contract section, update
  folded-paper rule (Settings cards now included), document
  `cardPadding` / `pagePadding` aliases

**Masthead call sites (Phase 1)**
- `ui/chat/components/ChatHeader.kt` — `PageMasthead(...)` call gains
  `rightSlot = { TodayLabel() }` implicitly via default
- `ui/navigation/NavigationDrawer.kt` — `DrawerHeader` (line 161) gains
  ledger date via default
- `ui/settings/SettingsHomePage.kt` — replace inline `Row { PageMasthead;
  IconButton(Close) }` (lines 59–78) with
  `PageMastheadIdentity(title="Settings", onClose=onDismiss)`
- `ui/settings/SettingsWidgets.kt` — **delete** `SettingsHeader`
  (lines 46–72) and `SettingsSubPageHeader` (lines 295–330)
- All eight sub-page files — replace `SettingsSubPageHeader(title, onBack,
  onClose)` calls with `PageMastheadDrillDown(title, onBack, onClose)`:
  - `LlmAuthSettingsPage.kt`
  - `AgentBehaviorSettingsPage.kt`
  - `MemorySettingsPage.kt`
  - `MemoryFileEditorPage.kt`
  - `PermissionsAdvancedSettingsPage.kt`
  - `AppAccessSettingsPage.kt`
  - `OpenSourceLicensesPage.kt`

**Shell + seals (Phase 2)**
- `ui/settings/SettingsSheet.kt` — outer `Column` (line 96) gains
  `.background(MaterialTheme.colorScheme.background).paperGrain()`
- `ui/settings/LlmAuthDiscoveryUi.kt` — replace `HorizontalDivider()` with
  `Fleuron()`
- Identity sub-pages get closing `Fleuron()` at end of scrollable content:
  `LlmAuthSettingsPage`, `AgentBehaviorSettingsPage`,
  `PermissionsAdvancedSettingsPage`, `AppAccessSettingsPage`,
  `OpenSourceLicensesPage`. `MemorySettingsPage` already has one
  semantically (end of file list).

**Card chrome + shapes (Phase 3)**
- `ui/settings/SettingsWidgets.kt:204–253` (`SettingsRow`) — wrap card
  with `.foldedPaper(shapes.medium)`
- `ui/settings/SettingsWidgets.kt:255–293` (`SettingsNavigationRow`) —
  same
- `ui/settings/SettingsWidgets.kt:363–434` (`PerceptionModeSelector`)
  outer card — `foldedPaper`; inner radio rows stay flat (per decision 6)
- `ui/settings/OpenAiAuthCard.kt:49` — drop `RoundedCornerShape(12.dp)`,
  use `shapes.medium`; add `.foldedPaper`
- `ui/settings/ToolSettingsCard.kt` — outer card gets `.foldedPaper`
- `ui/settings/AppRow.kt` / `AppRowExpansion.kt` — outer per-app card
  `.foldedPaper`; inline expansion stays flat; replace `RoundedCornerShape
  (8.dp)` (lines 128/157) with `shapes.small`; replace error surface with
  `SettingsAlertCard`
- `ui/settings/MemorySettingsPage.kt:83` — replace error surface with
  `SettingsAlertCard`; per-file row `.foldedPaper`
- `ui/settings/MemoryFileEditor.kt` — inline editor: no folded paper
  (per decision 6 — nested in App Access expansion)
- `ui/settings/MemoryFileEditorPage.kt` — standalone editor: outer card
  `.foldedPaper`
- `ui/settings/ApiKeyFields.kt:68` — `OutlinedTextField` shape
  `RoundedCornerShape(12.dp)` → `shapes.small` (8)
- `ui/settings/LlmAuthSettingsPage.kt:623, 645` — same TextField fix
- `ui/settings/ToolsSection.kt:334, 364, 402` — Button shapes 12dp →
  `shapes.large` (16dp, matching Capsule)
- `ui/settings/SettingsWidgets.kt` (`ModelLoadingStatusIndicator`) — cards
  inherit `foldedPaper` for Downloading/Loading/Ready/Error states; Error
  state migrates to `SettingsAlertCard`

**Spacing tokens (Phase 4)**
- All Settings files — bulk replace hardcoded `.dp` paddings/arrangements
  with `MaterialTheme.closePaw.spacing.*`. See [Spacing migration
  table](#spacing-migration-table) below.

### Removed

- `SettingsHeader` (`SettingsWidgets.kt:46–72`) — dead duplicate of
  `PageMasthead`
- `SettingsSubPageHeader` (`SettingsWidgets.kt:295–330`) — replaced by
  `PageMastheadDrillDown`
- All inline `RoundedCornerShape(12.dp)` literals across Settings
- All inline `RoundedCornerShape(8.dp)` literals across Settings (replaced
  by `MaterialTheme.shapes.small`)
- `HorizontalDivider` in `LlmAuthDiscoveryUi`
- Inline error `Surface(...)` blocks in `MemorySettingsPage` and
  `AppRowExpansion` (replaced by `SettingsAlertCard`)
- `PageMasthead.leadingPaw: Boolean` and `onLeadingClick` parameters
  (replaced by `leadingSlot` Composable) — deleted in Phase 1 same PR
  that updates call sites

### Untouched

- `ui/chat/*` except `ChatHeader.kt` (ledger date addition only)
- `ui/capsule/*` (already in the register)
- `ui/theme/Color.kt`, `Shape.kt`, `Theme.kt`, `Motion.kt`, `Type.kt`,
  `PaperGrain.kt`, `WindowInsets.kt`
- All data/business logic: `AppSettingsStore`, `MemoryStore`,
  `MemoryEditGate`, `AppClassifier`, `TermuxBridgeManager`,
  `BrowserScriptToggleGate`, `RunCommandPermissionGate`, `LLMProvider`,
  `ModelCatalog`
- Page navigation graph (`SettingsPage` enum, `SettingsSheet`
  `AnimatedContent` slide transitions)
- Copy / strings / information architecture
- `MemoryFileEditorPage` internal editor logic (only the outer shell
  chrome changes)

## Interactions

### `PageMasthead` new signature

```kotlin
@Composable
fun PageMasthead(
    title: String,
    modifier: Modifier = Modifier,
    leadingSlot: @Composable RowScope.() -> Unit = { PawGlyph() },
    rightSlot: @Composable RowScope.() -> Unit = { TodayLabel() },
    trailingSlot: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)               // 32 → 48 to host icon buttons
            .padding(horizontal = MaterialTheme.closePaw.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingSlot()
        Spacer(Modifier.width(MaterialTheme.closePaw.spacing.sm))
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = TextStyle(
                fontFamily = Fraunces,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Normal,
                fontSize = 18.sp,
                lineHeight = 22.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(MaterialTheme.closePaw.spacing.sm))
        rightSlot()
        trailingSlot()
    }
}

@Composable
fun PawGlyph(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val mod = modifier.size(18.dp)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    Icon(
        painter = painterResource(R.drawable.ic_paw),
        contentDescription = null,
        modifier = mod,
        tint = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
fun TodayLabel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val text = remember(context) {
        DateFormat.getMediumDateFormat(context).format(Date())
    }
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.closePaw.monoSmall,
        color = MaterialTheme.closePaw.inkFaint,
    )
}

@Composable
fun PageMastheadIdentity(
    title: String,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) = PageMasthead(
    title = title,
    modifier = modifier,
    trailingSlot = {
        if (onClose != null) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close",
                     tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    },
)

@Composable
fun PageMastheadDrillDown(
    title: String,
    onBack: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) = PageMasthead(
    title = title,
    modifier = modifier,
    leadingSlot = {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ChevronLeft, contentDescription = "Back",
                 tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    },
    trailingSlot = {
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, contentDescription = "Close",
                 tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    },
)
```

### `SettingsCard` primitive

```kotlin
@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.medium,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = MaterialTheme.closePaw.spacing.cardPadding,
        vertical = MaterialTheme.closePaw.spacing.md,
    ),
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier.fillMaxWidth().clip(shape)
    val clickable = if (onClick != null) base.clickable(onClick = onClick) else base
    Surface(
        modifier = clickable.foldedPaper(shape),
        color = color,
        shape = shape,
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}
```

### `SettingsAlertCard` primitive

```kotlin
enum class AlertTone { Error, Warning, Info }

@Composable
internal fun SettingsAlertCard(
    message: String,
    tone: AlertTone = AlertTone.Error,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val (bg, fg) = when (tone) {
        AlertTone.Error -> colorScheme.errorContainer to colorScheme.onErrorContainer
        AlertTone.Warning -> colorScheme.tertiaryContainer to colorScheme.onTertiaryContainer
        AlertTone.Info -> colorScheme.surfaceContainerHigh to colorScheme.onSurface
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,    // 8dp — tighter than navigation cards
        color = bg,
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.closePaw.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.sm),
        ) {
            icon?.let { Icon(it, contentDescription = null, tint = fg) }
            Text(message, style = MaterialTheme.typography.bodySmall, color = fg,
                 modifier = Modifier.weight(1f))
            action?.invoke()
        }
    }
}
```

### Spacing migration table

| Hardcoded value | Replacement | Where used |
|---|---|---|
| `4.dp` | `spacing.xs` | Sub-page header horizontal padding |
| `8.dp` | `spacing.sm` | Inter-row arrangement, button gaps |
| `12.dp` | `spacing.md` | Vertical card content gaps, inline help gaps |
| `16.dp` | `spacing.cardPadding` (new alias = 16) | Card internal padding (preserve current) |
| `20.dp` | `spacing.lg` | Section vertical gaps, tab section spacing |
| `24.dp` | `spacing.lg` (=20, **−4dp visible change**) | Settings page horizontal padding — flagged |
| `32.dp` | `spacing.xl` | Section break, masthead clearance |

## Phasing

Five phases, each independently shippable, each ~½–1 day of careful work
plus tests. Phases 1–4 deliver the visible unification; Phase 5 is the
maintenance refactor (defer until 1–4 land and are QA'd).

### Phase 0 — Ornament + token foundations (½ day, 1 PR)

Goal: introduce the new primitives without changing any pixel of the app
yet. Defaults make the change opt-in.

- Extend `PageMasthead` to slot-based API; keep `leadingPaw: Boolean` and
  `onLeadingClick` as `@Deprecated` shims that delegate to `leadingSlot`
  (so existing three call sites still compile mid-PR)
- Add `PawGlyph`, `TodayLabel`, `PageMastheadIdentity`,
  `PageMastheadDrillDown` to `Ornaments.kt`
- Add `cardPadding = 16.dp` and `pagePadding = lg` to `ClosePawSpacing`
- Update `style.md` § Bound Edition Ornaments to reflect the new
  Composable API and the folded-paper rule update (decision 6)
- **Tests**: snapshot test for `PageMasthead` + `TodayLabel` rendering;
  the existing three call sites compile and behave identically (default
  `rightSlot = TodayLabel()` will start rendering the ledger date —
  validate against a screenshot)

### Phase 1 — Mastheads (1 day, 1 PR)

Goal: every page-level surface uses the unified masthead. Dead headers
deleted.

- `ChatHeader.kt` — confirm default `rightSlot = TodayLabel()` renders;
  no other change
- `NavigationDrawer.kt:DrawerHeader` — same; `[×]` trailing slot wired
- `SettingsHomePage.kt:59–78` — replace inline `Row { PageMasthead;
  IconButton }` with `PageMastheadIdentity("Settings", onClose=onDismiss)`
- All 7 Settings sub-pages — replace `SettingsSubPageHeader(...)` with
  `PageMastheadDrillDown(...)`. Files:
  `LlmAuthSettingsPage`, `AgentBehaviorSettingsPage`,
  `MemorySettingsPage`, `MemoryFileEditorPage`,
  `PermissionsAdvancedSettingsPage`, `AppAccessSettingsPage`,
  `OpenSourceLicensesPage`
- Delete `SettingsHeader` and `SettingsSubPageHeader` from
  `SettingsWidgets.kt`
- Delete deprecated `leadingPaw`/`onLeadingClick` from `PageMasthead`
- **Tests**: update `MemorySettingsPageTest.kt` and any header-asserting
  test in `app/src/androidTest/kotlin/ai/closepaw/ui/` to find
  `"Memory"` (still text-finder) and the `"Close"` content-description
  (preserved). Add a smoke test that every sub-page's masthead reads
  back the expected `title` + `"Close"`.

### Phase 2 — Shell grain + Fleuron seals (½ day, 1 PR)

Goal: Settings shell joins the paper-zine. Section breaks unified.

- `SettingsSheet.kt:96` — outer `Column` gains
  `.background(MaterialTheme.colorScheme.background).paperGrain()`. (The
  background fill is required because the sheet has no explicit color
  today.) **Uses default `strength = 0.04f`** — matches current Chat
  exactly. Do not bump strength in this PR; see Open question 7.
- `LlmAuthDiscoveryUi.kt` — `HorizontalDivider()` → `Fleuron()`
- Each identity sub-page — add closing `Fleuron()` before any trailing
  Spacer/footer. Skipped for tool sub-pages (Memory File Editor, Model
  Picker)
- **Tests**: visual diff via `/ux-visual-debug` skill — flip into each
  sub-page on a real device, capture screenshot, compare to preview.
  No unit-test impact expected (grain modifier is decorative).

### Phase 3 — Card chrome + shape normalization (1 day, 1 PR)

Goal: every Settings card lifts off the paper with the same warm
under-shadow Capsule has. All shapes route through `MaterialTheme.shapes`.

- Introduce `SettingsCard.kt` and `SettingsAlertCard.kt` primitives (do
  not yet refactor existing cards to derive from them — see Phase 5)
- For each existing hand-rolled card, append `.foldedPaper(shapes.medium)`
  to its `Surface` modifier chain. Files:
  `SettingsWidgets.kt` (SettingsRow, SettingsNavigationRow,
  PerceptionModeSelector outer, ModelLoadingStatusIndicator cards),
  `OpenAiAuthCard.kt`, `ToolSettingsCard.kt`, `AppRow.kt`,
  `MemoryFileEditorPage.kt` outer
- Normalize shapes: every `RoundedCornerShape(12.dp)` →
  `MaterialTheme.shapes.medium` (for cards) or `.large` (for buttons in
  `ToolsSection`); every `RoundedCornerShape(8.dp)` → `.small`
- Replace error/warning ad-hoc surfaces with `SettingsAlertCard`
  (`MemorySettingsPage:83`, `AppRowExpansion:128/157`,
  `ModelLoadingStatusIndicator.Error` branch)
- **Tests**: visual diff. No structural test impact unless tests assert
  on shape — none found in current `androidTest/`.

### Phase 4 — Spacing tokens (½ day, 1 PR)

Goal: all settings paddings and arrangements route through
`MaterialTheme.closePaw.spacing.*`. Bulk mechanical edit.

- Apply [Spacing migration table](#spacing-migration-table) across all
  Settings files
- **Decision point**: Settings home `24.dp` page padding →
  `spacing.lg` (20). This is a 4dp visible reduction. If user prefers
  to keep 24dp, add `spacing.pagePaddingWide = 24.dp` as a documented
  exception, or update `spacing.lg` to 24 globally (affects Chat too).
- **Tests**: same as Phase 3 (visual diff). No unit test impact.

### Phase 5 — Card variant consolidation (optional, deferred, 1–2 days)

Goal: collapse `SettingsRow`, `SettingsNavigationRow`, `ToolSettingsCard`,
`OpenAiAuthCard`, `MemoryFileEditor`, `AppRow` to derive from the
`SettingsCard` primitive. Removes 200+ lines of duplicated chrome logic.

- Refactor each card one at a time, validate per-card with existing tests
- Keep public Composable signatures stable so call sites don't change
- **Risk**: `ToolSettingsCard` already shipped its own consolidation
  round (`projects/active/tool_settings_unify/`) — coordinate so we don't
  undo or duplicate that work
- **Defer until Phases 1–4 are landed, QA'd on real device, and the
  maintenance pain motivates the refactor.**

## Test plan

### Unit / instrumentation

- **Phase 0**: snapshot tests for `PageMasthead` + `TodayLabel`,
  `PageMastheadIdentity`, `PageMastheadDrillDown` covering default and
  custom-slot cases
- **Phase 1**: update `MemorySettingsPageTest` (already touched by
  `df39b052 fix(test): pass platformMode to SettingsSheet`) to find new
  masthead structure. Smoke test per sub-page: open via deep link,
  assert title text + "Close" content description present
- **Phase 2/3/4**: existing unit tests should continue to pass — these
  phases change chrome only. If a test breaks, the test was over-asserting
  on layout — fix the test, not the chrome
- **Full check**: `./gradlew clean assembleDebug lint test` after each
  phase

### Visual QA (`/ux-visual-debug`)

After each phase, real-device QA via the project's `ux-visual-debug`
skill. Capture screenshots for:

1. Chat home (verify ledger date renders, paw + title unchanged)
2. NavigationDrawer open (ledger date present)
3. Settings home (grain visible, foldedPaper on rows, Fleuron at bottom)
4. Each sub-page (drill-down masthead, foldedPaper on rows, closing
   Fleuron where applicable)
5. Memory File Editor (deep page, drill-down masthead)
6. App Access expansion open (inline editor flat, outer row folded)
7. Dark mode (Lantern palette) — folded paper shadow doesn't crush in
   dark; lantern vignette + grain combine cleanly
8. Reduced motion — page transitions still fade-only; folded paper static

### Regression watchlist

- `MemorySettingsPage` Save-button gating (`b1304c52 fix(memory-settings):
  gate Save during post-unlock reload window`) — verify still works after
  masthead refactor
- `AppRowExpansion` Memory chip flow (`00447d31 feat(memory-settings):
  surface "+ Memory" inside skill-only app expansion`) — verify still
  renders and reachable
- Settings page slide animation (`SettingsSheet.kt:104–125`) — unchanged
  but visually verify foldedPaper shadow doesn't tear during slide
- `OpenAiAuthCard` OAuth state machine — chrome change must not affect
  state propagation

## Open questions

1. **Page padding**: keep Settings home at 24dp, drop to 20dp
   (`spacing.lg`), or alias 24dp as `spacing.pagePaddingWide`? Default in
   plan: drop to 20 (matches style.md). User decision in Phase 4.

2. **Ledger date on sub-pages**: confirmed yes per decision 5, but flag
   in QA — if the date competes with title visually on narrow phones
   (320dp width), fall back to "Settings home only".

3. **Close button on sub-pages**: today `SettingsSubPageHeader` has both
   back AND close. Some UX schools say "back implies close-on-root, no
   need for both". The plan keeps both for parity with today. If we
   wanted to simplify, the sub-page masthead would drop close and force
   the user to back-back to home, then close. User decision.

4. **Phase 5 trigger**: under what condition do we actually do the card
   consolidation refactor? Recommendation: only when a 7th card variant
   is about to be added — then the refactor is cheaper than another
   duplicate. Otherwise defer indefinitely.

5. **Adjacent bonus** (chat trace `[+12s]` timestamps): in scope here or
   spin off? Recommendation: spin off — orthogonal to Settings, and the
   user explicitly asked for Settings ↔ Chat ↔ Capsule unification, this
   bonus only touches Chat. Track as separate plan if desired.

6. **Dark mode shadow tuning**: folded paper's warm under-shadow uses a
   light-mode-tuned alpha. Need to confirm it still reads as "lift" in
   the Lantern palette without becoming "halo". May require a Phase 3
   followup tweak to `Modifier.foldedPaper`.

7. **[Followup] Grain strength / texture model.** Current
   `Modifier.paperGrain(strength = 0.04f)` is a uniform-random noise tile
   at 4% max alpha — on real devices this reads as "slight imperfection",
   not "magazine paper". User confirmed (2026-05-18) to **keep 4% in this
   plan** (Settings adopts what Chat already has) and revisit strength /
   texture model as a separate experiment after the unification lands.
   Three candidate moves for that followup, in order of investment:
   - (a) bump default `strength` to 0.08–0.10 (single-line change, needs
     light + dark validation)
   - (b) replace uniform random with multi-octave fractal noise (better
     "paper" character; ~½ day of bitmap baking + tuning)
   - (c) replace with a pre-rendered paper-fiber PNG asset bundled in
     `res/drawable/` (highest quality; needs design asset)
   Plan stays neutral until the experiment runs. Out of scope here.

## Files inventory

Full list of files touched by this plan (35 files total across 5 phases):

**Theme + ornaments (4 files, Phase 0)**
- `app/src/main/kotlin/ai/closepaw/ui/theme/Ornaments.kt`
- `app/src/main/kotlin/ai/closepaw/ui/theme/Tokens.kt`
- `doc/main/ui/style.md`
- (new) `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsCard.kt`

**Masthead call sites (10 files, Phase 1)**
- `app/src/main/kotlin/ai/closepaw/ui/chat/components/ChatHeader.kt`
- `app/src/main/kotlin/ai/closepaw/ui/navigation/NavigationDrawer.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsHomePage.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsWidgets.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/AgentBehaviorSettingsPage.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/MemorySettingsPage.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/MemoryFileEditorPage.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/PermissionsAdvancedSettingsPage.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/AppAccessSettingsPage.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/OpenSourceLicensesPage.kt`

**Shell + seals (7 files, Phase 2)**
- `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsSheet.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthDiscoveryUi.kt`
- All 5 identity sub-pages (closing Fleuron addition)

**Card chrome + shapes (10 files, Phase 3)**
- (new) `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsAlertCard.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsWidgets.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/OpenAiAuthCard.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/ToolSettingsCard.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/AppRow.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/AppRowExpansion.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/MemorySettingsPage.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/MemoryFileEditorPage.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/ApiKeyFields.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt`
- `app/src/main/kotlin/ai/closepaw/ui/settings/ToolsSection.kt`

**Spacing tokens (12+ files, Phase 4)**
- All Settings files mechanically swept

**Tests (touched across all phases)**
- `app/src/androidTest/kotlin/ai/closepaw/ui/MemorySettingsPageTest.kt`
- Add: per-sub-page smoke test for masthead

## Acceptance criteria

Plan is "done" when, on a real device with light mode + dark mode + reduced
motion + 320dp narrow phone:

1. Chat header shows `[paw] ClosePaw [today date] [+]`
2. Settings home shows `[paw] Settings [today date] [×]` with paperGrain
   visible, foldedPaper cards visibly lift, Fleuron seal at the bottom
3. Every Settings sub-page shows `[‹] Title [today date] [×]` with the
   same paperGrain shell and foldedPaper cards
4. Capsule overlay is unchanged but visually belongs to the same family
5. No `RoundedCornerShape(12.dp)` or `RoundedCornerShape(8.dp)` literals
   remain in `ui/settings/`
6. No `SettingsHeader` or `SettingsSubPageHeader` remain
7. `HorizontalDivider` in `LlmAuthDiscoveryUi` is replaced
8. `./gradlew clean assembleDebug lint test` passes
9. `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=ai.closepaw.qa` passes
10. style.md reflects the new `PageMasthead` contract and folded-paper rule
