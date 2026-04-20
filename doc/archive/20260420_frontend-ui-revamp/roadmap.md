# Roadmap — Phased Delivery

Five phases. Each shippable on its own. Total: ~3 focused weeks of
frontend work + 1 week polish.

---

## Phase 1 — Identity foundation (3–4 days)

**Goal:** the app *feels* different on day one, even if components are unchanged.

- [ ] Add font files: `fraunces_variable.ttf`, `geist_variable.ttf`, `jetbrains_mono_variable.ttf` → `app/src/main/res/font/`
- [ ] Rewrite `Color.kt` with Paper / Ink / Claw / Moss / Amber / Rust palette ([`design-tokens.md §1`](./design-tokens.md#1-color--ink-on-bone-claw-on-paper))
- [ ] Rewrite `Type.kt` to reference the three font families and add `AgentExtraTypography` via a `CompositionLocal`
- [ ] Draw `ic_paw.xml` vector (3 toe-pads + pad, separate paths)
- [ ] Update `Theme.kt` to map Material 3 color roles to new tokens, keep both light + dark (Lantern) palettes
- [ ] Replace app icon foreground with the paw glyph in claw on paper
- [ ] **Verify:** build + install + screenshot chat, capsule running state, settings home. Should already look unmistakably *ClosePaw*.

Commit gate: `feat(ui): adopt Tactile Intelligence palette + paw-print glyph`

---

## Phase 2 — Smart Capsule (4–5 days)

**Goal:** the signature overlay becomes the most refined surface in the app.

- [ ] `SmartCapsuleSurface` — wrap mode render in `AnimatedContent` with the transition spec from [`motion-spec.md §2`](./motion-spec.md#2-capsule-mode-transitions)
- [ ] `StatusIslandCompose` — replace `8.dp` `Box` dot with `ic_paw` tinted by `CapsuleColors`
- [ ] Add breath animation to paw in Running mode only
- [ ] Mode-specific overrides: Takeover freeze, Error shake, Done blink-once
- [ ] Replace `shadowElevation(4.dp)` pill with folded-paper elevation (hairline top + warm under-shadow) from [`design-tokens §5`](./design-tokens.md#5-elevation--folded-paper-not-floating-cards)
- [ ] `EdgeGlowCompose` — swap linear gradient for radial falloff anchored to capsule position, 8s drift, alpha ceiling 0.12
- [ ] Remove all emoji from `CapsuleRenderSpec` (✋, ✓, ⚠, 💬, 🛡) — replace with paw-state + semantic color
- [ ] **Verify:** record screen during a live eval — Running → Takeover → Done should flow, not jump.

Commit gate: `feat(capsule): breathing paw + folded-paper pill + mode animations`

---

## Phase 3 — Chat screen (4–5 days)

**Goal:** the main reading surface reads as editorial, not as chat.

- [ ] `MessageBubble` — **remove agent bubble** entirely; render agent text as paper-prose with a 3px claw left-margin tick
- [ ] User bubble → `PaperInset` symmetric 10dp corners, no tail
- [ ] `ActionCard` — redesign as typeset receipt: top hairline, mono tool name, right-aligned paw status, bottom hairline, expandable output. No background fill.
- [ ] `ThinkingIndicator` — three dots → three paw-toes filling in sequence (900ms cycle, [`motion-spec §4`](./motion-spec.md#4-chat-screen--thinking-indicator))
- [ ] `StreamingText` — block cursor → serif I-beam `|` in Fraunces, same blink cadence
- [ ] `EmptyState` — 160dp paw watermark, Fraunces italic question "What should we look into?", suggestion chips as underlined serif links in paper cards
- [ ] `ChatHeader` — title in Geist Medium, add paw glyph left of title (claw, static)
- [ ] **Verify:** read a long agent response with 3+ action cards. Should feel like reading a report, not scrolling a feed.

Commit gate: `feat(chat): editorial prose treatment + typeset action receipts`

---

## Phase 4 — Settings / Onboarding / Drawer (3–4 days)

**Goal:** first-run and configuration surfaces carry identity.

- [ ] `SettingsHomePage` — Fraunces section heads with hanging numerals ("01 — Permissions"), hairline row dividers, drop arrow indicators for `→` mono-glyph
- [ ] `ApiKeyFields` — JetBrains Mono, 1px ink border, claw on focus, ink `eye` icon for show/hide
- [ ] `OnboardingShell` — replace progress bar with five paw-prints row; chapter spreads with 120sp Fraunces roman numerals as watermarks
- [ ] `PermissionRepairCard` — telegram style: tracked-out caps header, mono body
- [ ] `NavigationDrawer` — ledger treatment: mono dates, Geist titles, faint previews; new-session as claw-red "New entry" serif link at top; settings as `// preferences` mono link at bottom
- [ ] **Verify:** screenshot each surface in both light/dark. Each should be recognizably ClosePaw with no logo visible.

Commit gate: `feat(ui): field-journal treatment for settings, onboarding, drawer`

---

## Phase 5 — Motion + polish (ongoing, ~1 week)

**Goal:** the details that separate great from world-class.

- [ ] Action visualizer — ink-drop tap + satellite ring, long-press hold with pulsing fill, perlin-wobble swipe, paw-stamp at destination ([`motion-spec §3`](./motion-spec.md#3-action-visualizer))
- [ ] Orchestrated cold-start entrance ([`motion-spec §5`](./motion-spec.md#5-cold-start-entrance-orchestrated))
- [ ] Remove Material ripple globally — replace with pressed-color state via `InteractionSource`
- [ ] Remove any list enter animations; audit all `animateDpAsState` calls for durations outside the `{120, 240, 480, 900}` set
- [ ] Haptics pass: single fine-tick on Capsule mode changes (`HapticFeedbackConstants.CONFIRM`), nothing else
- [ ] Screenshot regression set — add a "frontend golden" eval that captures capsule in all 8 modes, chat with 3 message types, settings home, onboarding step 1. Diff visually in PRs.
- [ ] Dark mode pass — verify Lantern palette across every surface; this is often neglected in phase 1 and pays off disproportionately on demo.

Commit gate: per-polish-item small commits. No single "polish" megacommit.

---

## Success criteria

After phase 4, all three should be true:

1. **The screenshot test.** A designer seeing only a cropped screenshot of the capsule, chat, or onboarding can identify it as ClosePaw without a logo.
2. **The calm test.** Record 30 seconds of agent execution. No element on screen changes faster than 120ms or slower than 900ms except the one orchestrated entrance.
3. **The grandma test.** A non-technical user on first run completes onboarding without confusion *and* remembers the paw-print 24 hours later.

---

## Non-goals for this revamp

Explicitly out of scope — don't scope-creep:

- **Redesigning the agent's thinking behavior or tool UX.** This is frontend only.
- **Adding new features.** If it's not on-screen today, it's not on-screen tomorrow.
- **A marketing site / brand book.** Ship the app design; the brand book writes itself from it.
- **iOS/Web parity.** Android-first, Android-only. The paw is the product on this platform.

---

## Risk log

| Risk | Mitigation |
|---|---|
| Fraunces + custom fonts add ~300KB to APK | Variable fonts; subset to Latin + punctuation via `fontFamily` variation settings. Target <180KB total. |
| Removing agent bubbles breaks users' mental model | A/B behind a debug flag for internal dogfood week before default-on. |
| Breath animation drains battery during long Running states | Pause the infinite transition when activity is not foreground; paw reverts to static when `lifecycle.state < RESUMED`. |
| Emoji removal loses screenreader semantics | Replace each removed emoji with a `contentDescription` on the paw glyph (e.g. "running", "paused"). |
| Claw-red accent fails accessibility contrast on Paper | Measured: `C44528` on `F5F1EA` = 5.4:1 (AA large/UI pass). For body text use Ink, never Claw. |

---

## Sequencing note

Phases 1 → 4 can ship as independent commits merged in order. Phase 5
items land opportunistically. Do **not** batch phases into one giant PR —
the point of this roadmap is that each phase delivers a step-change in
identity on its own.
