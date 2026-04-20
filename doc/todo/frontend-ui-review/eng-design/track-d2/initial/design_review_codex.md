# Track D2 Review — Codex on Claude Draft

**Reviewed file:** `doc/todo/frontend-ui-review/eng-design/track-d2/initial/design_claude.md`  
**Comparison baseline:** `design_codex.md`  
**Focus:** correctness, gaps, trade-offs, and KISS compliance

## Findings

### 1. Medium — The draft closes D1's streaming-cursor verification too early

**Where:** `design_claude.md` lines 132-177 and 371-378.

The draft treats the cursor problem as solved because `InlineTextContent` can anchor a blinking glyph inside a `Text` layout. That is a useful implementation idea, but it does **not** fully close the D1 concern yet.

Why this is a problem:

- D1 explicitly kept this as a Phase 3 verification item.
- `InlineTextContent` addresses wrapping/alignment in principle, but not the actual device-level behavior with the chosen fonts, baseline metrics, and streaming text cadence.
- The draft also quietly reuses `serifItalic` for the cursor, while D1 only locked that style for the empty-state question; the cursor requirement was "Fraunces", not specifically italic.

What should change:

- Keep the `InlineTextContent` approach as the leading implementation candidate.
- Do **not** mark the question closed in the design.
- Preserve the fallback and make Phase 3 verification explicit.

This is one place where the Codex draft is stricter and safer: it keeps the verification requirement open instead of turning an untested implementation sketch into settled architecture.

### 2. Medium — The font asset plan contains incorrect Android implementation guidance

**Where:** `design_claude.md` lines 254-257.

The draft says bundled fonts under `res/font/` should be added "with `font_certs.xml`" and that otherwise `Type.kt` "crashes at first composition."

That is technically wrong for the path the draft is actually proposing:

- `font_certs.xml` is for downloadable fonts, not ordinary bundled font files in `res/font/`.
- If `Type.kt` references missing bundled font resources, the failure is a compile/resource issue, not a "first composition" runtime crash.

Why this matters:

- It muddies the implementation with an unnecessary Android resource concept.
- It weakens confidence in the rest of the file-level migration details.

What should change:

- Say only: bundle the required fonts in `res/font/`, wire them from `Type.kt`, and handle license attribution separately.
- Drop `font_certs.xml` from the design unless the plan is explicitly to use downloadable fonts, which it is not.

### 3. Medium — The migration plan leaves app-wide D1 rollout under-specified

**Where:** `design_claude.md` lines 12-14, 248-301, and tasks at 346-351.

The draft correctly focuses on theme, capsule, and chat, but it does not include an executable migration phase for settings, drawer, and onboarding. It only asks for screenshot validation in D2-1 and a contrast handoff doc in D2-4.

That is a real gap because current code still imports old chat-specific theme symbols outside chat/capsule, especially under `ui/settings/`.

Why this matters:

- D1 is not just a chat/capsule spec; it includes settings, onboarding, and drawer guidance.
- The user asked for a migration plan from current ad-hoc styles in `app/src/main/kotlin/ai/closepaw/ui/`.
- Without an outward rollout phase, the design leaves a half-migrated token system in place and pushes real cleanup into an implicit "later."

What should change:

- Either add a final rollout phase for settings/drawer/onboarding, or explicitly defer those surfaces with a bounded follow-up track.

The Codex draft is stronger here because it includes a dedicated outward migration phase instead of stopping at chat/capsule.

### 4. Low — `AgentTokens -> AgentColors / AgentTypeExtras / AgentSpacing` is more hierarchy than the current token count justifies

**Where:** `design_claude.md` lines 62-64, 231-233, and 325-332.

The top-level decision is good: one thin extension on top of `MaterialTheme`. The extra nested structure is the part that feels heavier than necessary.

Right now the non-Material residue is small:

- one extra color (`InkFaint`)
- four extra text styles
- one spacing scale

For that payload, `MaterialTheme.tokens.colors.inkFaint` / `.type.monoBody` / `.space.md` is a lot of wrapper surface.

Why this matters:

- It increases the amount of naming and navigation at call sites.
- It makes the design look more framework-like than it needs to be.
- It does not buy real extensibility yet; it mostly groups small bags of values.

What should change:

- Flatten the extra token bag unless a second extra color family or a larger type surface appears.
- A single `MaterialTheme.closePaw` object with a flat or nearly-flat shape is simpler.

### 5. Low — The alias-heavy migration plan keeps two token vocabularies alive longer than necessary

**Where:** `design_claude.md` lines 257-259, 288-301, and 367.

The draft keeps `ChatPrimary`, `Background`, `BubbleShapeUser`, and similar symbols alive as temporary aliases so phases can land separately.

I understand the motivation, but it is still a real trade-off:

- It keeps the old naming system alive inside the new design.
- It weakens the "one true token vocabulary" goal during the most fragile part of migration.
- It conflicts with the project rule to avoid backward-compatibility shims unless they are genuinely necessary.

This is not a fatal flaw, but it needs a tighter justification than "big PRs are hard."

What should change:

- Either avoid aliases entirely and migrate callers directly per phase, or
- constrain aliases to the smallest possible window and call them out as explicit temporary debt.

### 6. Low — The grep-based "zero raw `.dp` / `.sp`" acceptance criteria are too blunt

**Where:** `design_claude.md` lines 279-291 and 349-350.

The draft uses grep-clean rules like "zero raw padding `.dp`" and "zero raw `.sp` font sizes" in migrated files.

That is too absolute for a Compose UI:

- shared layout spacing should come from tokens, yes
- but some local literal values are legitimate component details
- the draft already has to carve out exceptions for icon sizes, which proves the rule is too broad

Why this matters:

- It turns the migration into style policing instead of a design migration.
- It encourages moving values into tokens just to satisfy grep, even when they are not shared design tokens.

What should change:

- Target shared spacing, shared typography roles, and shared motion constants.
- Do not make "no unit literals at all" a success criterion.

## Open Questions / Assumptions

- I assumed bundled fonts in `res/font/`, because that is what the draft describes. If the actual plan is downloadable fonts, the file guidance needs to say that explicitly.
- I assumed D2 is responsible for at least planning the rollout to settings/drawer/onboarding, because D1 includes those surfaces and the current repo still has old theme constants there.
- I assumed Track A owns the new chat-row component anatomy, so D2 should avoid prebuilding chat wrappers that Track A may not need.

## Secondary Context

Claude's draft has real strengths:

- The main API decision is directionally correct: stay on `MaterialTheme`, add one thin extra token surface, keep motion out of Locals.
- The refusal to create a shared component module up front is correct.
- The `InlineTextContent` cursor idea is a good implementation note and should be carried forward as the first experiment in Phase 3.

The places where it loses ground are the same places where it stops being strict about KISS:

- nested token wrappers that the current payload does not need
- temporary alias layers that preserve old names
- overconfident closure of an item D1 intentionally left for verification
- migration scope that is narrower than the actual D1 rollout surface

## Verdict

**Better base for the first aligned draft: CODEX.**

Why:

- It keeps the token API flatter and easier to read.
- It preserves D1's Phase 3 verification stance on the streaming cursor instead of declaring the problem solved too early.
- It includes the missing rollout phase for settings/drawer/onboarding, which makes the migration plan complete instead of chat/capsule-only.

What should be carried over from Claude into the aligned draft:

- the explicit rejection of multi-Local token domains
- the no-shared-component-module stance
- the `InlineTextContent` cursor approach as the first implementation experiment, not as settled architecture
