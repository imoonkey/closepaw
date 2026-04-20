# Frontend UI Revamp — Device QA Report

**Date:** 2026-04-20
**QA agent:** Claude (`/ux-visual-debug`)
**Diff under test:** `fcb9e0ac..HEAD` (D2-1..6 + Track A + 2 fixer rounds + font binaries)
**Device:** EP0110MZ0BC101266W (Android 16, 1264×2800 physical)
**Evidence dir:** `doc/todo/frontend-ui-review/eng-design/qa_evidence/`

## Verdict

**3 Issues, 3 Polish items found.** Track A row mechanics work, theme foundation is sound, all instrumented tests pass. The largest visible regression is **Material `*Container` slots leaking the default purple/lavender palette** through any component that uses `FilledTonalButton` / `FilterChip` (selected) — most prominent on the Takeover button and provider-selector chips.

Not a blocker — the architecture is correct, the issues are localized to color-slot mapping and a couple of spec-drift details. Recommend a small follow-up (1 PR) to override the `*Container` slots before archiving D2.

## Instrumented test results

| Suite | Command | Result |
|---|---|---|
| `ChatAgentRowDisclosureTest` | `:app:connectedDebugAndroidTest -P…class=ai.closepaw.qa.ChatAgentRowDisclosureTest` | **PASS** (exit 0) |
| Full QA package | `:app:connectedDebugAndroidTest -P…package=ai.closepaw.qa` | **PASS** (exit 0) |

Codex round-2 wrap-in-`ClosePawTheme` fix verified — the deferred Compose test is now green.

## Findings

### Issue I-1 — Material `secondaryContainer` lavender leak (Important)

**Surface:** Capsule Takeover button, onboarding provider chips (OpenAI/OpenRouter/Novita), Settings sub-screen segmented selector.
**Severity:** Issue (regresses D1 editorial baseline; not a blocker — no functional break).

**Repro:**
1. Launch app, walk to onboarding Step 4 → "Connect your model" → observe selected chip.
2. Send a message → observe Takeover button while capsule is Running.
3. Open Settings → LLM & Authentication → API Key tab → observe Provider segmented selector.

**Expected:** Selected chip / Takeover button uses warm Paper-derived container with Amber or Moss tint per D1 §6.1 (Pause/Takeover = Amber).

**Actual:** Background = `(230, 222, 246)` ≈ `#E6DEF6` — Material 3's default `secondaryContainer` (purple-toned) leaks through because `Theme.kt` only maps `primary/secondary/tertiary/error/surface*/outline*` and **does not override the `*Container` family** (`primaryContainer`, `secondaryContainer`, `tertiaryContainer`, `errorContainer`).

**Code pointers:**
- `app/src/main/kotlin/ai/closepaw/ui/theme/Theme.kt:18-40` — light scheme missing `*Container` slots.
- `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleControlBar.kt:96` — Takeover uses `FilledTonalButton` (defaults to `secondaryContainer`).

**Evidence:** `qa_evidence/02_onboarding_step4_chip_lavender.png`, `05_running_capsule.png`, `09_settings_provider_lavender.png`

**Suggested fix (out of scope for QA):** override the four `*Container` slots in `Theme.kt` to PaperInset (or per-role tints). Map Takeover specifically to Amber if a Tertiary-flavored tonal button is needed.

---

### Issue I-2 — Collapsed Complete row headline does not follow Track A spec §5.2 (Issue)

**Surface:** Chat row when task completes.
**Severity:** Issue (spec drift in core Track A behavior — but mechanics work).

**Repro:** Send a task; wait for completion; observe collapsed row headline.

**Expected (Track A §5.2):** `✓ <user-prompt-truncated to ~6 words> · N actions · elapsed`. Headline ladder: user prompt → first thought → first action → "(no activity)".

**Actual:** `✓ Task completed · 3.8s ▸` — generic "Task completed" string used as headline; missing actions count.

**Evidence:** `qa_evidence/06_collapsed_complete_row.png`

**Suggested fix:** wire `ChatViewModel` to derive headline from the user prompt that opened the turn (plus action count) per spec §5.2.

---

### Issue I-3 — EmptyState uses `Icons.Rounded.SmartToy` instead of paw watermark (Polish→Issue)

**Surface:** Chat empty state on first run / fresh session.
**Severity:** Polish leaning Issue (D1 §6.2 explicitly calls for "large paw watermark").

**Repro:** Fresh session → Main app empty state in portrait orientation.

**Expected (D1 §6.2):** "large paw watermark on the empty state".

**Actual:** Material `Icons.Rounded.SmartToy` (robot face, 64dp, `onSurfaceVariant` tint).

**Code pointer:** `app/src/main/kotlin/ai/closepaw/ui/chat/components/EmptyState.kt:14, 44-49`.

**Evidence:** `qa_evidence/03_empty_state_portrait.png`

---

### Polish P-1 — Final block missing for instant-completion tasks

When the agent answers without `MessageDelta` stream content (e.g., trivial Q&A like "2+2"), the expanded row shows no Final block, only the outcome footer. Spec §4.4 says `TaskCompleted.result` should populate Final if no deltas arrived. Cannot tell from screenshots whether `result` was empty server-side or whether the renderer dropped it. Worth one debug-run trace check.

**Evidence:** `qa_evidence/07_expanded_row.png`

---

### Polish P-2 — Send button stays gray (`surfaceVariant`) in disabled state instead of muted Claw

When the composer is empty, the send button is a gray circle. Spec doesn't require it to be Claw, but the current treatment looks like an unintentional Material default rather than an editorial choice. Acceptable — flagging only because the active state IS Claw, so the disabled state could ladder consistently.

---

### Polish P-3 — Empty state vertically clipped in landscape

In landscape orientation (the device used for this QA), the EmptyState column overflows the viewport and the SuggestionChips + italic question are cut off below the composer. Portrait renders correctly. Likely not worth a fix unless tablet/landscape is a target form factor.

## What passes spec ✓

- **D1 palette:** page background sampled at `(244, 241, 235)` — matches Paper `#F5F1EA` (within 1 unit). No dynamic-color leak from Material You — confirmed `dynamicColor` not used.
- **Geist sans rendering:** clean Geist on titles/body/headers — not system Roboto fallback.
- **Fraunces serif italic** on EmptyState subtitle — clearly slanted serif, not Geist italic.
- **JetBrains Mono** wired for `monoBody`/`monoSmall` (not visually verified live — would require an action trace; trusted via theme code + passing instrumented tests).
- **Send button (active)** = filled Claw circle with up-arrow ✓.
- **SettingsRow Required label** uses `onSurface` (Ink) not Amber on PaperInset — round-1 critical fix verified in code (`SettingsWidgets.kt:228-242`) with explanatory comment referencing the contrast matrix.
- **Setup Issue / startup error card** uses `errorContainer` (soft pink) + Rust filled "Fix" pill — readable, AA-large compliant per contrast matrix.
- **Track A row collapse / expand** — single-tap toggle works.
- **Capsule Running mode** — buttons + composer hint switch correctly (`Got ideas? Add a note...`).
- **Capsule Stop pending** — "Stopping…" with spinner state, transient feedback works.
- **Drawer** — opens with rounded right edge (folded paper), Sessions header, "+ New Conversation" outlined pill, Recent ledger section.
- **Folded-paper modifier** visually present on capsule and drawer (top hairline + soft shadow).
- **`InkGhost` hairline** above Final block visible in expanded row.

## Recommended next step

One small follow-up PR before archiving D2:

1. Override `primaryContainer / secondaryContainer / tertiaryContainer / errorContainer` (and `on*` counterparts) in `Theme.kt` to Paper/PaperInset with proper warm tones — fixes I-1.
2. Update `ChatViewModel` collapsed-row headline derivation per Track A §5.2 — fixes I-2.
3. Replace `Icons.Rounded.SmartToy` with `R.drawable.ic_paw` (already in res) tinted `InkMuted` — fixes I-3.

Each is a 5-15 line change. All other behavior matches spec.

---

**QA NOT clean — 3 important Issues + 3 Polish items above must be triaged before archive.** Recommend addressing I-1, I-2, I-3 in a single fixer PR, then re-running this QA pass.
