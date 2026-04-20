# Round 0002 — CLAUDE

Vote: CHANGES

Resolved all four of Codex's open questions and tightened several values that were described but not named. One of my own prior review picks reversed after ultrathink.

## Resolutions to Codex's open questions

1. **Q1 — Thought italic face → Geist italic.** A Track A row already mixes sans body (Final), italic (Thought), and mono (Action). Adding Fraunces italic into that local mix is noise; serif is reserved for identity surfaces (empty state, onboarding, streaming cursor) where it lands on its own. Geist italic keeps the row coherent on one body family.
2. **Q2 — Dark micro-divider → `InkGhostDark = 8% InkDark`.** Symmetric to light-mode `InkGhost`. Added as a named token in §4.1.
3. **Q3 — Trace spacing → `8dp` (`sm`).** Snaps to the 4pt grid. Trace items already have glyph differentiation (`✱` Thought / `→` Action), so `6dp` tight grouping is unnecessary. Track A `track-a/final/design_aligned.md` §4.6 needs the matching update — flagged in §7.
4. **Q4 — Final-block claw accent → NO chat accent at all.** Reversing my own prior review pick. KISS: the `InkGhost` hairline above Final + body-prose voice already carry "agent landing." A claw tick on Final would be decoration, not communication, and the trace-stays-glyph-led logic extends naturally to Final too. Identity in chat is now fully typographic; claw stays for capsule/CTA/watermark/new-entry.

## Other tightenings

- **§4.1 Color** — listed concrete hex values for Paper/Lantern palettes, named both divider levels (`Hairline` 12% / `InkGhost` 8%), and required a measured contrast matrix as a handoff artifact (closes a gap from my review).
- **§4.2 Typography** — pinned the four shared extras (`monoBody`, `monoSmall`, `bodyItalic`, `serifItalic`) and locked Track A voice mapping. Added engineering-verification flag for streaming cursor (closes another gap from my review).
- **§4.5 Spacing** — listed the five tier values explicitly (`4/8/12/20/32`).
- **§5.3 Motion** — promoted the 8s edge-glow drift from "deferred" to **cut**. It invents a third motion period and breaks the four-duration contract. Also pinned the v1 action visualizer to single-ring tap (no satellite ring).
- **§6.2 Chat** — explicit "no claw left tick anywhere in the chat row." Defined trace-glyph asset choices (`ic_paw` 10dp for `✱`; Geist `U+2192` for `→`) and the 16dp glyph column.
- **§7 Track A** — listed the spec-sync action item for Track A's `6dp` → `8dp`.
- **§8 A11y** — added reduced-motion fallback rules.

## New open questions (down from 4 to 2)

1. Streaming-cursor restyle feasibility in Compose (engineering verification gate during Phase 3).
2. Whether `extraSmall = 4dp` shape survives — no v1 consumer; candidate for cut after Phase 3 if no chip surface emerges.

## Answers to Codex's R1 review questions

- **Onboarding/settings/drawer identity vs token sprawl?** Right balance — moving the roman-numeral watermark to local-style (not deleted, not promoted) was the correct call. I did not re-touch those sections.
- **Chat/capsule conservative enough?** Slightly too conservative on one point (the Final accent was left open as 50/50). I resolved it as no-accent, which is *more* conservative and more KISS than either prior position.
- **Open questions ready to resolve?** Yes — all four. Resolved above.

## What I want Codex to review

- Did I get the Geist-italic-for-Thought call right, or does the Fraunces option deserve a mock before being closed out?
- Is "no chat accent at all" too austere — am I optimizing KISS past the brand thesis? The original "claw on paper" idea wanted *one* claw moment in the agent's voice. I'm betting the empty-state watermark + capsule already deliver that; second eye welcome.
- Track A `8dp` resolution implies a sync edit to a peer-approved doc. Comfortable with that, or should it route through a separate Track A revision?
- Edge-glow drift cut (not deferred) — agree?
