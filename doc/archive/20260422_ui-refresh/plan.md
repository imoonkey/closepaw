# Implementation plan

> Five phases, each independently shippable. Run `./gradlew lint test` after each.
> If a phase trips an existing test, fix the test only if the test is asserting
> the *removed* aesthetic (e.g. asserting `outlineVariant` separator color).
>
> Reflects code as of `08ae6378` — i.e. after `uxfb-1 → uxfb-followup` landed
> ThoughtGroup, CollapsePill, FinalText, and capsule marquee. Phase 3 was
> trimmed (two moves withdrawn, two one-liners added) to fit the new chat
> shape. See `align-with-uxfb-0422.md` for the diff history.

## Phase 1 — Theme primitives (foundation, no UX change)

- [ ] Add `ui/theme/Ornaments.kt` with `Fleuron()` and `PageMasthead()`.
- [ ] Add `ui/theme/PaperGrain.kt` with `Modifier.paperGrain()` and
      `Modifier.lanternVignette()` (no-op composable seam for dark).
- [ ] Decide Fraunces approach: ship `fraunces_medium.ttf` (Option A) or
      retire upright Fraunces (Option B). If A, add the font asset and update
      `Type.kt::Fraunces` `FontFamily`. If B, no asset change.
- [ ] No call sites change yet.
- [ ] Verify: `./gradlew assembleDebug lint`.

**Gate:** new files compile, no Compose preview regressions.

## Phase 2 — Brand chrome on every top-level surface

- [ ] `ChatHeader.kt`: replace center title with `PageMasthead("ClosePaw", todayLabel())`.
- [ ] `NavigationDrawer.kt::DrawerHeader`: replace `Sessions` Text with `PageMasthead`.
- [ ] `SettingsHomePage.kt`: prepend `PageMasthead("Settings", todayLabel())`.
- [ ] Add `paperGrain()` to `ChatScreen` root box (light only).
- [ ] Verify: open each screen on a device or via Compose preview, screenshot
      both light and dark, eyeball that the masthead reads as colophon (not data).

**Gate:** brand visible on every screen, not only EmptyState.

## Phase 3 — Chat content ornaments

- [ ] `MessageBubble.kt::UserBubble` → letter-to-the-editor (drop Surface, add
      Hairline left margin rule, mono timestamp inline).
- [ ] `ThinkingIndicator.kt` → naked Row, no Surface chip.
- [ ] `AgentTrace.kt:109` — left-rule color from `outlineVariant` → `outline`
      (Hairline). One-line bump; flag P2 to original author of `uxfb-4`.
- [ ] `CollapsePill.kt:72` — summary style from `labelLarge` → `monoSmall`.
      One-line; P2 polish, no test impact.
- [ ] `ChatScreen.kt::live pill` → mono pill with explicit border.
- [ ] Verify: pinned tests `ThinkingIndicatorTintTest`, `ThinkingIndicatorCadenceTest`,
      `ChatDoneBridgeTest`, `CollapsePillTest`, `ThoughtGroupTest` still pass.
      QA test tags (`qa-user-bubble`, `qa-thinking-indicator`, `qa-agent-bubble`,
      `qa-collapse-pill`, `qa-final-answer`) preserved.

**Gate:** chat reads as editorial page, not as Material card stack.

## Phase 4 — EmptyState rebuild

- [ ] Full rewrite per `changes.md` §EmptyState.
- [ ] Add `StaggeredReveal` helper (private, in `EmptyState.kt`).
- [ ] Hard-code three suggestions with verb + gloss pairs (or extend the
      data model — two-line suggestions are a UX upgrade).
- [ ] Verify: launch app fresh, watch the staggered reveal once, verify
      reduced-motion mode renders all elements visible immediately.

**Gate:** first-launch screen reads as a printed first page.

## Phase 5 — Capsule polish + miscellany

- [ ] `SmartCapsuleSurface.kt`: add `produceElapsedLabel(mode)` ticker; pass
      to `CapsuleStatusLine`.
- [ ] `CapsuleStatusLine`: render `[t+12s]` mono ledger as its own `Text`
      *before* the marqueed thought (uxfb-2 contract). Ledger stays fixed
      while thought scrolls.
- [ ] `CapsuleControlBar.kt`: stop button gets explicit Rust `BorderStroke`.
- [ ] `CapsuleInputBar.kt`: send-button breath on submit (240ms scale).
- [ ] `SmartCapsuleSurface.kt::StartupErrorBanner`: 2dp Rust left rule + ⚠ glyph.
- [ ] `OnboardingShell.kt::FivePawProgress`: unfilled tint → `inkFaint`.
- [ ] `SettingsHomePage.kt`: add `SectionHeader` and `Fleuron()` ornaments.
- [ ] Verify: full app sweep, both themes, run `/qa` skill.

**Gate:** capsule shows elapsed time during a live task; settings has
sectioned grouping; onboarding progress is actually visible.

## Final gates

- [ ] `./gradlew clean assembleDebug lint test` clean.
- [ ] Run `/verify` skill.
- [ ] Run `/ux-visual-debug` for: cold launch → EmptyState; send a message →
      ChatHeader masthead visible; trigger a long task → capsule ticker;
      open drawer → masthead and ledger dates; open settings → sections + fleuron.
- [ ] Update `doc/main/ui/style.md` with: Fleuron primitive, PageMasthead
      primitive, paperGrain modifier, ledger counter pattern. Reference
      `Ornaments.kt` and `PaperGrain.kt`.
- [ ] Update `doc/main/ui/user_interaction.md` with: mastheaded surfaces list,
      EmptyState marginalia layout, capsule running counter behavior.
- [ ] On commit, archive this folder as `projects/archive/20260422_ui-refresh/`.

## Rollback plan

Each phase is a separate commit. If Phase N regresses something, revert that
commit; phases ≤ N-1 stand on their own.

## Time estimate

| Phase | Complexity | Est. |
|---|---|---|
| 1 | New files, no UX | 1-2h |
| 2 | Mechanical replace, paperGrain wiring | 2-3h |
| 3 | Chat ornament work + 2 one-liners on uxfb-3/4 code, retest | 2h |
| 4 | EmptyState rebuild + stagger | 3-4h |
| 5 | Ticker + small polishes across 5 files | 2-3h |

Total: ~10-14h focused work. Single coding session feasible if no surprises.
