# UI Polish Report

**Date:** 2026-04-20
**Reviewer:** Claude (Opus 4.7)
**Inputs:** 43 PNGs in `doc/todo/ui-polish/captures/` + INDEX.md
**Specs:** D1 visual baseline, Track A row IA, motion spec, contrast matrix (all under `doc/archive/20260420_frontend-ui-revamp/`)

## Summary

- Captures reviewed: **43**
- Page-state groupings: **18**
- Findings: **6 Issue / 9 Polish / 3 Pass**
- Plus: 1 Investigation item (capsule overlay never rendered) + 2 known bugs already documented in INDEX

The implementation hits the broad strokes — palette, layout, Track A row anatomy, capsule states are conceptually correct. The drift is concentrated in **typography** (serif identity faces missing on every "identity" surface) and **a handful of asset/glyph substitutions** (paw-toe thinking indicator, drawer ledger mono dates, settings chevron). Nothing here is an architectural redo; all of it is local style swaps.

---

## Per-page findings

### 1. Onboarding — chapter titles
**Captures:** `onboarding_step1_welcome.png`, `onboarding_step2.png`, `onboarding_step3.png`, `onboarding_step4_provider_select.png`
**Spec refs:** D1 §4.2 (Fraunces for identity surfaces), §6.4 ("strong chapter title treatment")
**Verdict:** **Issue**

**Drift observed:** Step titles ("Let ClosePaw control your phone", "See controls while the agent works", "Keep long tasks alive", "Connect your model") render in **bold Geist sans**, not Fraunces serif. Onboarding is one of the four identity surfaces D1 §4.2 explicitly reserves for Fraunces (alongside empty-state, capsule streaming cursor, drawer-section heads). The current treatment is indistinguishable from any operational title.
**Fix suggestion:** Apply `displayMedium` / Fraunces variable to step title `Text(...)` in the onboarding step composables. Weight ~600, optical size large.

---

### 2. Onboarding — progress indicator
**Captures:** All 10 onboarding shots
**Spec refs:** D1 §6.4 ("five-paw progress treatment")
**Verdict:** **Issue**

**Drift observed:** Progress is a continuous **linear bar with a single trailing dot** (Material `LinearProgressIndicator` look). Spec calls for a **five-paw treatment** — five paw glyphs filling in as the user advances, mirroring the brand identity. The current bar reads as generic Android.
**Fix suggestion:** Replace the LinearProgressIndicator with a `Row` of 5 `ic_paw` drawables, filled (Moss tint) for completed steps, ghosted (`InkGhost`) for upcoming. Active step paw could carry the breath animation.

---

### 3. Onboarding — primary CTA color inconsistency
**Captures:** `onboarding_step1_welcome.png` (Moss Continue), `onboarding_step2.png` (Moss Continue), `onboarding_step3.png` (Claw "Allow…"), `onboarding_step4_provider_select.png` (Claw "Sign in with OpenAI"), `onboarding_step4_key_entered.png` (Claw "Validate & Continue")
**Spec refs:** D1 §4.1 ("Claw is scarce: …primary CTA…"), contrast matrix Cross-checks (Paper-on-Claw ≈ 4.41 — fine for ≥18pt CTA labels)
**Verdict:** **Polish**

**Drift observed:** Continue buttons on steps 1 & 2 are filled in **Moss** (success green); steps 3, 4, 4-error use **Claw**. Spec reserves Claw for primary CTAs. Either the Continue buttons are mis-colored, or there is an unstated convention "Moss = confirmation after Status:Enabled, Claw = action that reaches outside the app." Worth deciding and applying uniformly.
**Fix suggestion:** Decide intent. If Moss-on-success is intentional, document the rule. Otherwise, switch step 1 & 2 Continue buttons to Claw to match steps 3–4.

---

### 4. Onboarding — landscape (step 4)
**Captures:** `onboarding_step4_landscape.png`
**Spec refs:** D1 §6.4
**Verdict:** **Issue**

**Drift observed:** In landscape the **bottom CTA region is cut off entirely** — no "Sign in with OpenAI" button or "or enter API key manually" link visible. The page does not scroll vertically (provider chips are also pushed below the fold). This is a real usability failure on landscape.
**Fix suggestion:** Wrap the step content in a `verticalScroll(rememberScrollState())` `Column` so the page can be scrolled to reach the CTA, or use a two-column landscape layout (icon+title left, content right). Test on small landscape heights.

---

### 5. Onboarding — provider chips, API key field, validation error
**Captures:** `onboarding_step4_openai_selected.png`, `onboarding_step4_openrouter_selected.png`, `onboarding_step4_openai_manual_key.png`, `onboarding_step4_key_entered.png`, `onboarding_step4_validation_error.png`
**Spec refs:** D1 §4.1 (palette), §4.3 (radii), §6.3 (mono API-key fields)
**Verdict:** **Pass**

Selected chip uses PaperInset fill with thin Hairline border; unselected uses border-only. Field is OutlinedTextField with eye toggle and Claw outline on focus. Error text in Rust, Retry CTA in Claw. All matches spec.

---

### 6. Chat — empty state question typography
**Captures:** `chat_empty_portrait.png`
**Spec refs:** D1 §4.2 (`serifItalic` — Fraunces italic, used only on the empty-state question), §6.2 ("italic question on the empty state")
**Verdict:** **Issue**

**Drift observed:** "What can I help you with?" renders in what appears to be **Geist italic** (sans italic). D1 §4.2 explicitly defines `serifItalic` as **Fraunces italic** and reserves it specifically for this empty-state question. This is the one spot where Fraunces italic was deliberately preserved in the type system — and it's missing.
**Fix suggestion:** Swap the `Text(...)` style on the empty-state question from `bodyItalic` to `serifItalic` (Fraunces italic) once the typography token is wired.

---

### 7. Chat — empty state title + paw watermark
**Captures:** `chat_empty_portrait.png`
**Spec refs:** D1 §4.2 (Fraunces for identity), §6.2 ("large paw watermark on the empty state")
**Verdict:** **Polish**

**Drift observed:** "ClosePaw" title under the paw glyph is rendered in **Geist sans**, not Fraunces. The paw watermark is **small and centered** (~96dp visual) rather than reading as a "large watermark" anchor. Color appears mid-grey rather than `Ink` tint at full opacity.
**Fix suggestion:** Use Fraunces (`headlineMedium` or local style) for "ClosePaw". Bump the paw to ~160–200dp, tinted `Ink` at 100% (or `InkGhost` if it's intended to read as a watermark behind). Current treatment splits the difference and lands as neither.

---

### 8. Chat — thinking indicator (typing dots)
**Captures:** `chat_live_running.png`, `chat_running_supplement_loading.png`
**Spec refs:** D1 §6.2 ("paw-toe thinking indicator"), motion §4 (3 paw-toes + pad fill in sequence)
**Verdict:** **Issue**

**Drift observed:** The "thinking" indicator is **three plain circles in a pill**, fading sequentially. The motion spec §4 calls explicitly for the **paw-toe sequence**: three paw-toes + pad cycling at 225/450/675/900ms — synchronised to the 900ms breath cycle. The current implementation looks like a generic chatbot typing dot cluster.
**Fix suggestion:** Replace `ThinkingDots` with the paw-toe composable described in motion §4. Reuse `ic_paw` parts; animate `alpha` only (no scale needed at this size).

---

### 9. Chat — Track A trace row anatomy
**Captures:** `chat_supplement_action_needed.png` (full trace), `chat_completed_expanded.png` (trace + Final + footer)
**Spec refs:** Track A §4.1–4.5, D1 §4.2 (Thought = bodyItalic, Action = monoBody, Final = bodyLarge), §7
**Verdict:** **Pass**

Thought items use `✱` glyph + italic body. Action items use `→` + monospaced `tool_name(args)` with right-aligned status glyph (`✓` `⌛`). Action results render inline below action when expanded. Outcome footer reads `✓ 2 actions · 8.3s` per Track A §4.5. This is the strongest fidelity area in the build.

---

### 10. Chat — Trace ↔ Final hairline
**Captures:** `chat_completed_expanded.png`
**Spec refs:** Track A §4.4 ("Separated from the trace by a hairline rule (`ink @ 8%`)"), D1 §4.1 (`InkGhost = 8% Ink`)
**Verdict:** **Polish**

**Drift observed:** I cannot positively identify the **InkGhost (8% Ink) hairline** between the last trace item (`Complete task(...)`) and the Final block (`Answer: Opened Settings.` / `Opened Settings.`). It is either absent, or rendered too faint to read as a structural divider.
**Fix suggestion:** Confirm presence. If absent, add `Divider(color = ClosePawColors.InkGhost, thickness = 1.dp)` between trace list and Final composable, with `padding(vertical = 8.dp)`.

---

### 11. Chat — completed-row collapsed headline
**Captures:** `chat_completed_collapsed.png`
**Spec refs:** Track A §5.2 (headline ladder: user message first), §4.5 (footer format)
**Verdict:** **Pass**

Renders as `✓ Open Settings · 2 actions · 8.3s ▸` — exactly matches Track A §5.2 format with user prompt as headline and the right-aligned disclosure caret. Glyphs read clearly, spacing matches `sm`.

---

### 12. Chat — Setup Issue banner (landscape)
**Captures:** `chat_empty_landscape.png`, `chat_setup_issue_banner_landscape.png`
**Spec refs:** D1 §6.5 ("Permission repair: tracked-caps header via existing label style, mono body")
**Verdict:** **Polish**

**Drift observed:** Banner uses warm Rust-tinted surface (✓), Ink body, Fix button in Claw (✓). However the header reads "**Setup Issue**" in **bold sans title-case**, not the **tracked-caps `labelSmall` (10sp / 1.2sp tracked)** the D1 §6.5 spec specifies for repair surfaces. Body text is regular sans, not mono.
**Fix suggestion:** Apply `labelSmall` with `letterSpacing = 1.2.sp` and uppercase to the "SETUP ISSUE" header. Apply `monoBody` (JetBrains Mono 13sp) to the descriptive line.
**Note:** INDEX `Bonus Bug 2` documents that this banner is also stale after a permission grant — that runtime bug is separate.

---

### 13. Chat — supplement panel (Takeover/Stop, Action needed, Done/Stop)
**Captures:** `chat_live_running.png`, `chat_supplement_action_needed.png`, `chat_error_after_stop.png`, `chat_after_stopped.png`
**Spec refs:** Track A §4.3 (inline prompt), §5 (Waiting state)
**Verdict:** **Pass** with one observation

Takeover button uses PaperInset fill, Stop is outlined with Rust tint. "Action needed" prompt block sits below the trace, reading correctly as a `Waiting`-state inline prompt. "Done" and "Stop" affordances are present and visually weighted appropriately. Architecture matches Track A §4.3.
**Note:** INDEX `Bonus Bug 1` documents that tapping Done is a no-op due to a missing `onUserResponseSent` wiring — runtime bug, not a polish issue.

---

### 14. Chat — timestamp locale
**Captures:** `chat_live_running.png`, `chat_completed_expanded.png`, `chat_supplement_action_needed.png`
**Spec refs:** —
**Verdict:** **Polish**

**Drift observed:** Timestamps render "10:58 上午" (Chinese AM/PM) on an otherwise English UI. The device locale is leaking into one specific format that ought to follow app locale or use 24h.
**Fix suggestion:** Format timestamps with the app's display locale (or use `DateFormat.getTimeFormat(context)` with the Compose `LocalConfiguration`'s locales).

---

### 15. Settings — section heads
**Captures:** `settings_home.png`, `settings_llm_authentication.png`, `settings_llm_signin_tab.png`, `settings_agent_behavior.png`, `settings_permissions_advanced.png`
**Spec refs:** D1 §6.3 ("serif section heads")
**Verdict:** **Issue**

**Drift observed:** All settings titles ("Settings", "LLM & Authentication", "Agent Behavior", "Permissions & Advanced") and section heads ("Permissions", "Display Mode", "Debug", "Data & Storage", "Cloud Model", "API Key", "Authentication") render in **bold Geist sans**. D1 §6.3 explicitly retains "**serif section heads**" as a settings-surface keep.
**Fix suggestion:** Apply Fraunces (`titleLarge` for sub-page heads, `labelLarge` or `titleMedium` for section heads inside a sub-page) wherever a section header is rendered. Page title in the sheet handle area should also be Fraunces.

---

### 16. Settings — row trailing arrow (chevron vs mono `→`)
**Captures:** `settings_home.png`
**Spec refs:** D1 §6.3 ("mono `→` glyph instead of decorative arrows")
**Verdict:** **Polish**

**Drift observed:** Each settings home row ends with a **filled chevron `›`** (likely `Icons.Default.ChevronRight`). D1 §6.3 specifies the **mono `→` glyph** (the Geist text glyph `U+2192`) as the row affordance to keep the editorial / typographic tone — same rule mirrored from the Action trace marker.
**Fix suggestion:** Replace the trailing `Icon(Icons.Default.ChevronRight)` with `Text("→", style = AgentExtraTypography.monoBody, color = InkMuted)`.

---

### 17. Settings — provider chips (3 options)
**Captures:** `settings_llm_authentication.png` (OpenAI / OpenRouter / Novita)
**Spec refs:** D1 §4.1 (palette), §4.3 (radii)
**Verdict:** **Pass**

Three-option chip group, selected = PaperInset fill + check mark, unselected = bordered. Tab strip above (Sign In / API Key / Local) uses Claw indicator + Claw active text.

---

### 18. Settings — Local tab
**Captures:** `settings_llm_authentication.png` (Local tab visible, tap inert per INDEX)
**Spec refs:** —
**Verdict:** **Polish**

**Drift observed:** "Local" tab is rendered active-eligible (same Claw color treatment as Sign In and API Key) but does not respond to taps (per INDEX). Either it should be visibly disabled (50% alpha, no Claw underline on tap) or wired up.
**Fix suggestion:** If unimplemented, gate the tab with `enabled = false` and a "Coming soon" tooltip or remove from the strip until ready.

---

### 19. Settings — agent behavior + permissions toggles
**Captures:** `settings_agent_behavior.png`, `settings_permissions_advanced.png`, `settings_display_mode_virtual_display.png`, `settings_permissions_debug_off.png`, `settings_permissions_session_traces_on.png`
**Spec refs:** D1 §4.3, §4.5
**Verdict:** **Pass** (with one note)

Dropdowns (Max Turns, Execution Mode, Cloud Model) use OutlinedTextField look with leading icon. Perception Mode and Display Mode use 2/3-option segmented controls with PaperInset fill on selection. Switches use M3 Switch with Claw tint when ON. Permission rows use `Enabled` pills with Moss dot.
**Note:** Switch tint Claw-on works but wasn't explicitly speced — flag for designer to confirm.

---

### 20. Navigation drawer
**Captures:** `nav_drawer_open.png`
**Spec refs:** D1 §6.6 ("ledger treatment, mono dates, one claw-accented new-entry affordance, restrained settings link treatment")
**Verdict:** **Polish**

**Drift observed:**
- Session list entries show "X messages · Y minutes ago" in **regular sans**, not the **mono dates** D1 §6.6 specifies for the ledger treatment.
- "+ New Conversation" button is **outlined with Ink** (no claw accent visible). Spec calls for "**one claw-accented new-entry affordance**" — this is the canonical place for one of the two permitted Claw elements per screen. Current button reads as neutral.
- Settings entry "gpt-5.4 · v1.0" subtitle is sans, not mono. "v1.0" is exactly the kind of machine-text label `monoSmall` was created for.
- Trash icons per row are heavier than the restrained `→` ledger style — they pull the eye toward destructive affordances on a passive list.

**Fix suggestion:**
- Apply `monoSmall` (JetBrains Mono 11sp) to the timestamp portion ("3 minutes ago" → mono) and to the "v1.0" tag.
- Tint the "+ New Conversation" button border + icon + text with `Claw`. Keep fill `Paper`.
- Consider hiding row trash icons behind a long-press or swipe instead of always-visible.

---

## Cross-cutting findings

### CC-1. Fraunces serif missing across every identity surface (Issue)
The single biggest typography theme. Fraunces is specified for: onboarding chapter titles (#1), empty-state title + question (#6, #7), settings sub-page titles + section heads (#15), drawer "Sessions" header (implicit). Currently **all** of these render in bold Geist sans. The font is presumably wired in the type system — but `displayMedium` / `headlineMedium` / `titleLarge` are not being applied at any of the call sites that should be Fraunces. Fix as a single typography pass rather than per-screen.

### CC-2. Drawer/settings mono-text token drift (Polish)
`monoSmall` (JBMono 11sp) is defined in `AgentExtraTypography` for "ledger dates and small mono labels." It's not used in the drawer ledger or in the settings version footer ("Version 1.0 (1)"), the two places D1 §6.3 and §6.6 explicitly point at. One pass to apply `monoSmall` to all "ledger / version / timestamp" copy.

### CC-3. Material-default substitutions for ClosePaw motifs (Issue)
- Onboarding progress bar = Material LinearProgressIndicator (should be 5 paws).
- Thinking indicator = generic 3 dots (should be paw-toe sequence).
- Settings row chevron = `Icons.Default.ChevronRight` (should be mono `→`).

These are three independent swaps where the brand-specific affordance was specified but the Material default landed instead. Each is a 1-component change; together they shift the app from "calm editorial tool" toward "stock Material chatbot."

### CC-4. Horizontal page padding (Pass)
Onboarding, settings sub-pages, and chat all appear to use `lg = 20dp` horizontal padding consistently. D1 §4.5 hard-invariant satisfied.

### CC-5. Capsule + flat-elevation rule (Pass — capsule unverified)
The bottom supplement panel and the Setup Issue banner appear flat with hairline separation (no card shadow), per D1 §4.4. The capsule itself could not be verified — see Investigation below.

---

## Investigation (separate from polish)

### INV-1. Capsule overlay never rendered
**Captures:** All 11 `capsule/` PNGs (`agent_in_other_app_no_capsule_overlay_1/2/3.png`, `vd_mode_*.png`, `capsule_vd_running_*.png`, `vd_viewer_activity_empty.png`)

The capture worker reported that **no floating capsule overlay was visible** during any basic-mode run, any virtual-display-mode run, or after directly launching `VirtualDisplayViewerActivity` (which renders an entirely black screen). Per Track D1/D2 design and `CapsuleOverlayHost.kt`, the Running capsule should be visible over other apps while a task executes.

This is a **runtime regression**, not a polish drift — flag for separate investigation by whoever owns the capsule overlay path. Without a working overlay, none of the capsule polish (breath animation, mode transitions per motion §1–2, paw glyph, folded-paper elevation, Claw/Moss/Amber/Rust mode tinting) can be reviewed against spec. **A second capture pass is needed once the overlay path is restored.**

Likely starting points (from INDEX): `CapsuleOverlayHost.kt`, `VirtualDisplayViewerActivity.kt:79–87`, the eye-icon affordance the user mentioned (no `content-desc` match found in dump).

---

## Already-known runtime bugs (not polish, but for completeness)

Both surfaced and diagnosed during the capture pass — see INDEX "Bonus Bugs Discovered During Capture":

- **B-1.** Stuck supplement capsule on "Done" — `ChatScreen.onUserResponse` doesn't call `CapsuleStateHolder.onUserResponseSent(callId)`. Fix: add hook in `ChatScreen.kt:153` mirroring Stop pattern.
- **B-2.** Stale Setup Issue banner after a11y grant — `MainActivity.deriveRepairModel()` runs once in `setContent`, not recomputed in `onResume`. Fix: hold `repairModel` in `mutableStateOf`, recompute in `onResume`.

---

## Fix-priority shortlist (suggested, not prescriptive)

1. **Wire Fraunces** at all identity-surface call sites (CC-1) — one PR, biggest perceptual shift.
2. **Restore capsule overlay** (INV-1) — blocks all capsule QA.
3. **Paw-toe thinking indicator** (#8) — replaces the most generic-looking element on the most-viewed screen.
4. **Five-paw onboarding progress** (#2) — first impression.
5. **Landscape onboarding scrollability** (#4) — actual usability bug.
6. **Settings serif heads + mono `→`** (#15, #16) — small change, completes the editorial voice.
7. **Drawer Claw new-entry + mono dates** (#20) — completes the ledger metaphor.

Everything else is polish-tier and can batch.
