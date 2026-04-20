# ClosePaw — Frontend Design Revamp

> A holistic review of the current Android UI, and a proposal to move it from
> *"clean Material 3 with ChatGPT influence"* to something **unforgettable**.

**Author:** design review, 2026-04-17
**Scope:** `app/src/main/kotlin/ai/closepaw/ui/**` + theme + overlays
**Companion docs:**
- [`design-tokens.md`](./design-tokens.md) — drop-in color/type/shape tokens
- [`motion-spec.md`](./motion-spec.md) — state transitions and micro-interactions
- [`roadmap.md`](./roadmap.md) — phased delivery plan

---

## 1. The honest audit

ClosePaw today is **engineered well, designed safely**. The Smart Capsule
state machine is genuinely novel — a sealed `CapsuleMode` driving a
deterministic `CapsuleRenderSpec` for 8 overlay states is the kind of
disciplined UI architecture most apps never reach. But the *visual* surface
is a lightly-tinted Material 3 theme that reads as "yet another AI wrapper."

### What's working (preserve)
- **State-first overlay architecture** — `CapsuleMode` → `RenderSpec` → view is pure and correct. Don't touch the contract, only the pixels.
- **High-contrast text** — no alpha-washed placeholders. Readable at a glance.
- **Semantic color coding** — blue=running, amber=paused, teal=done, red=error. Keep the semantics; change the palette.
- **Action Visualizer canvas overlay** — expanding circle for taps, animated line for swipes. Already close to great.
- **Approval-scope UX** (Once / Session / Always) — legitimately sophisticated; most agents ship a single "Approve" button.
- **Edge-glow** presence indicator — subtle and distinctive.

### What's holding it back
| # | Issue | Evidence |
|---|---|---|
| 1 | **No brand voice.** Palette is ChatGPT teal + soft black. Nothing says *ClosePaw*. | `Accent = Color(0xFF10A37F) // ChatGPT green` (`Color.kt:23`) |
| 2 | **System default typography** — no custom font files, just Compose defaults. | `Type.kt` — no `Font(R.font.*)` anywhere |
| 3 | **Emoji used as icons** in production UI — ✋ ✓ ⚠ 💬 🛡 mixed with Material icons. Tonal whiplash. | `CapsuleRenderSpec.kt`, button labels |
| 4 | **Abrupt state transitions.** Capsule mode switches are instant; no slide/fade/morph between Running → Takeover → Done. | `SmartCapsuleSurface.kt` lacks `AnimatedContent` between mode renders |
| 5 | **Settings & Onboarding are stock Material 3** — indistinguishable from a Gradle template. No identity. | `SettingsHomePage.kt`, `OnboardingShell.kt` |
| 6 | **Flat depth.** Shadow is `0x0A000000`. No layering, no grain, no tactility — despite being an app literally named after *touch*. | `ShadowColor = Color(0x0A000000)` |
| 7 | **Unused icon tint tokens** (`ChatIconPrimary/Secondary`) and dead palette entries signal drift. | `Color.kt:109-112` |
| 8 | **No empty/error/success illustrations** — just icons + text. Missed storytelling surfaces. | `EmptyState.kt` |

---

## 2. The aesthetic direction: **"Tactile Intelligence"**

ClosePaw is not a chatbot. It is a companion that *reaches into your phone
and moves things*. The name — a paw, closing around the task — is
extraordinarily specific. The design should feel like:

> **An editorial field notebook for an intelligent animal.**
> Warm bone-white paper. Deep ink for thinking. One clay-red
> claw-mark for moments that matter. Serif headlines that look hand-set.
> A monospaced undercurrent where the agent is doing technical work.
> Motion that *breathes* instead of bounces.

Three anchors:

### a. Palette — "Ink on bone, claw on paper"
Retire the ChatGPT teal. Commit to a palette that reads as **made, not
generated**. Full values in [`design-tokens.md`](./design-tokens.md).

- **Paper** `#F5F1EA` — warm bone, replaces pure white as canvas
- **Ink** `#14110F` — deep warm black, not pure `#000`
- **Claw** `#C44528` — burnt sienna, the single signature accent. Used **sparingly** — only for Running state, primary CTA, and the paw-print presence dot. Rarity = impact.
- **Moss** `#4A5D3A` — muted olive for Success (replaces teal)
- **Amber** `#E8A33D` — warmer than current `F5A623`, for Pause/Takeover
- **Rust** `#8B2E1F` — deep, not fire-engine, for Error
- Dark mode is **not** a color-inverted copy — it's a separate "lantern" palette: `#0F0D0B` paper, `#F0EAE0` ink, claw glows warmer at `#E56B4A`.

### b. Typography — two voices, one system
- **Display / Headlines: [Fraunces](https://fonts.google.com/specimen/Fraunces)** (variable serif, OFL). Optical size + soft/wonky axes enable a hand-set feel. Used at `displayLarge/Medium`, empty-state headlines, onboarding hero.
- **Body / UI: [Geist](https://vercel.com/font)** or **[Inter Tight](https://rsms.me/inter/)** (pick one — Geist if we want to feel new, Inter Tight if we want to feel classic). All chat text, buttons, labels.
- **Technical: [JetBrains Mono](https://www.jetbrains.com/lp/mono/)** — shown only when the agent is doing something verifiably technical: action card code, shell output, tool names, thought lines in debug view. Signals "this is the machine speaking."

Three voices, clearly zoned. The reader always knows whether they're reading
the product, the agent's thought, or the agent's action.

### c. The Paw — one iconic motif
Introduce a **3-dot paw-print** as the app's signature glyph (three toe-pads
above a larger pad). It replaces:
- The generic circle "presence dot" in the Status Island → becomes a **paw-print that pulses on breath** (4s inhale/exhale, not a nervous 600ms blink).
- The app icon foreground (currently a launcher icon placeholder).
- The loading indicator (three toe-pads fill in sequence instead of three dots).
- Empty state illustration (a single paw-print in claw-red on paper).

This is the thing users will remember. Everything else supports it.

---

## 3. Component-by-component revamp

### 3.1 Status Island / Capsule — the crown jewel

**Current:** rounded pill, small colored dot + thought text, 4dp elevation.

**Proposed:**
- Replace the `8.dp` colored circle with the **paw-print glyph** in the mode's semantic color. The paw **breathes** (scale 1.0 → 1.04, 4s, `EaseInOutSine`) while Running; **freezes** in Takeover; **blinks once** on Done then fades.
- Pill becomes a **folded-paper card** — 1px ink border at 8% alpha + a single `0 2px 0` "under-shadow" in warm brown, giving a tactile lift instead of a generic material drop shadow.
- Paper (`#F5F1EA`) background in light mode; lantern (`#1A1612`) in dark.
- Thought text in Geist Medium 14sp, **Ink**; when Agent is *executing* (not narrating), swap to JetBrains Mono Medium 13sp to signal "machine in motion."
- Mode transitions: `AnimatedContent` with `slideInVertically + fadeIn` (240ms, `EaseOutCubic`) between Running / Takeover / WaitingForInput. Done state slides **up** and out with a soft `scaleOut(0.95f)`; Error shakes once (±3dp, 120ms) on entry.

### 3.2 Edge glow

**Current:** Canvas linear gradient at 4 edges, 40dp, semantic color.

**Proposed:**
- Replace hard linear gradient with a **soft radial falloff** that's stronger near the Status Island's anchor edge (so the glow visibly *emanates from* the paw, not the frame).
- Reduce opacity ceiling from visible → **barely-there** (max 12% alpha). A world-class presence indicator is the one you only notice when it's gone.
- In Running, add a slow **drift** (2px amplitude, 8s period) so the glow feels like a living halo, not a stroke.

### 3.3 Action Visualizer (tap/swipe overlays)

**Current:** blue expanding ring for tap, blue line for swipe.

**Proposed:**
- Tap → an **ink-drop**: claw-red filled circle at 6dp, rapidly expanding ring in same color, plus a *second* smaller satellite ring delayed 80ms. Reads as a deliberate touch, not a generic ripple.
- Swipe → a **hand-drawn stroke** simulation: path drawn with a slight wobble (perlin-noise offset, ±1.5px) and tapered endpoints, not a rigid line. At the destination, a tiny paw-print stamps and fades.
- Long-press → the ring *holds* at max radius with a subtle pulsing inner fill — communicates "pressure" viscerally.

### 3.4 Chat Screen

**Current:** user bubble light gray right; agent left in raw text blocks + inline ActionCards.

**Proposed:**
- **User bubble:** pill → **subtle inset card** on paper: `#EDE7DC` on `#F5F1EA`, no border, no shadow. Text in Ink. Feels written-on, not spoken-at.
- **Agent messages:** remove the bubble entirely. Render agent text as **flowing editorial prose** directly on the paper canvas, with a 3px claw-red left-margin tick aligned to the first line. This is the single biggest readability + identity win available.
- **ActionCard:** stop tinting with 5% alpha. Instead, render as a **typeset receipt**:
  - Top rule (hairline, ink 20%).
  - Monospaced tool name + args.
  - Status glyph right-aligned (small paw, claw-red while executing, moss when done).
  - Bottom rule + expandable output.
  - No background color at all — the paper shows through.
- **ThinkingIndicator:** three dots → three paw-toes filling in sequence, 900ms cycle. Currently 600ms pulse feels *nervous*; 900ms reads as *considered*.
- **StreamingText cursor:** replace `█` with a **serif I-beam** (`|`) in Fraunces, same blink cadence. A block cursor reads as terminal; a serif I-beam reads as *writing*.
- **EmptyState:** replace the 64dp Material icon with a **single claw-red paw-print watermark** (20% opacity, 160dp), offset upper-right. Headline in Fraunces Italic, 32sp: *"What should we look into?"* (not "How can I help?" — change the voice). Suggestion chips become **underlined serif links** in paper cards with hairline borders.

### 3.5 Settings

**Current:** page-based, rows with title/subtitle/arrow. Reads like stock.

**Proposed:**
- Treat settings as an **index page of a field journal**. Section headers in Fraunces SemiBold 22sp, **left-aligned with hanging numerals** ("01 — Permissions", "02 — Model", "03 — Behavior"). Hairline dividers (ink 8%) between rows, not surface color changes.
- Rows: no arrows; instead, a tiny monospaced key hint on the right (`→`). Title in Geist Medium; subtitle in Geist Regular 13sp at 60% ink. The weight difference alone is enough hierarchy — drop the color difference.
- **API-key fields**: monospace, ink on paper with a 1px ink border that becomes claw-red on focus. Show/hide toggle is a small ink icon, not a text button.

### 3.6 Onboarding

**Current:** progress bar + title + step content in generic Material layout.

**Proposed:**
- Each step is a **chapter spread**: large Fraunces roman numeral (I, II, III, IV, V) in 120sp ink at 8% opacity as a watermark, chapter title in 28sp Fraunces, short editorial paragraph, then the action.
- Progress bar retires. Replace with **five paw-prints in a row** at the bottom — unfilled outline, filled ink as each step completes, claw-red for the current one.
- The permission-repair card becomes a **telegram**: monospaced, with a top-bar "MISSING — ACCESSIBILITY SERVICE" in 11sp tracked-out caps. Feels urgent and system-level, not another Material dialog.

### 3.7 Navigation drawer (session history)

**Current:** 85%-width modal drawer, header, new-session button, list, settings.

**Proposed:**
- Re-imagine as a **ledger**. Each session row is a dated entry: date in monospace (13sp), title in Geist Medium, first-message preview in 12sp at 50% ink. Delete is an ink `×` that appears on hover/swipe, no destructive button chrome.
- New-session button becomes a **claw-red fountain-pen "New entry"** at the top — the only claw element in the drawer. Rare and earned.
- Settings entry at bottom: monospaced `// preferences` link. Signals the seam between journal and machine.

---

## 4. Motion system

Full spec in [`motion-spec.md`](./motion-spec.md). Three rules:

1. **Breath, not bounce.** Nothing in ClosePaw should `spring` or overshoot. Every easing curve is `EaseInOutSine` or `EaseOutCubic`. The agent is calm and deliberate; its UI should be too.
2. **Four durations only:** `120ms` (micro), `240ms` (transition), `480ms` (mode change), `900ms` (breath cycle). Every animation picks one — no `300ms` outliers.
3. **One orchestrated entrance.** When the app cold-starts, the Fraunces title settles in, paw-print stamps, and chat area fades in — staggered by 120ms. That's it. Everything else after is small and local. Delight is rare by construction.

---

## 5. What to actually change (priority order)

See [`roadmap.md`](./roadmap.md) for the phased plan. Summary:

**Phase 1 — Identity (1 week).** Ship the palette, fonts, and paw-print glyph. Even without component rewrites, the app will feel different on day one.

**Phase 2 — Capsule (1 week).** Breath-animation paw dot, folded-paper pill, mode-transition animations. This is the signature UI — it should be the most refined surface in the app.

**Phase 3 — Chat (1 week).** Kill agent bubbles, introduce editorial-prose treatment, typeset ActionCard receipts, serif I-beam cursor, toe-pad thinking indicator.

**Phase 4 — Settings / Onboarding / Drawer (1 week).** Field-journal treatment. Low user-visible frequency but huge identity payoff on first run and first screenshot.

**Phase 5 — Motion + polish (ongoing).** Edge-glow radial falloff, action-visualizer ink-drop, long-press hold, perlin stroke swipes.

---

## 6. What this is worth

The current design is a local maximum of *safe*. The Smart Capsule
architecture is already world-class *engineering*. Putting a world-class
*surface* on top of it is mostly a palette swap, two font files, one
hand-drawn paw-print, and disciplined motion restraint. Three weeks of
focused work.

The test: a reviewer screenshotting ClosePaw in 2026 should be able to
tell, from the paw-print and the claw-red accent alone, which app it is —
without seeing a logo or a name.

Today, they can't. That's the gap this revamp closes.
