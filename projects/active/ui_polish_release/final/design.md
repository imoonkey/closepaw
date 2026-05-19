# ClosePaw Pre-release UI Polish - Aligned First Draft

## Goal

Polish the ClosePaw Android UI for release without redesigning the product. The pass covers the whole user-visible app:

- Main chat: header, empty state, message list, streaming, collapsed trace, completion handoff, startup error banner.
- Session drawer: empty history, active session, corrupted session, delete flow.
- Settings: home, LLM/auth, agent behavior, permissions, tools, memory, app access, licenses.
- Smart Capsule: main app and overlay; `Hidden`, `Running`, `TakeoverPending`, `Takeover`, `Supplement`, `WaitingForInput`, `WaitingForAction`, `WaitingForApproval`, `Done`, `Error`.
- Overlay surfaces: island, glow, visualizer, locked touch shield, virtual display viewer handoff.
- Onboarding and permission repair states.

Success means:

- No release-blocking clipping, unreachable actions, or first-run layout failures.
- Required actions remain visible and tappable at 360dp width and font scale 1.5.
- Custom interactive surfaces expose roles, labels, state, and 48dp effective touch targets.
- User-visible failures have explicit loading/error/empty states.
- The UI continues to use the existing ClosePaw paper, Claw palette, paw motif, shape, typography, motion, and settings primitives.

## Alignment Baseline

Both initial cross-reviews picked CODEX as the better base for the first aligned draft. The consensus is:

- Codex had the stronger release-blocker triage.
- Claude had useful design-system drift findings that should be folded in mostly at P1/P2.
- The first release plan should prefer local, evidence-backed fixes over broad refactors or aesthetic changes.
- Open or subjective choices should stay explicit until a screenshot, accessibility check, or product decision resolves them.

## Principles

- Keep the diff local. Reuse `ClosePawTheme`, `MaterialTheme.closePaw.spacing`, `MaterialTheme.shapes`, `foldedPaper`, `SettingsCard`, `ToolSettingsCard`, `SettingsAlertCard`, `PageMasthead*`, `ClosePawMotion`, and existing capsule primitives.
- Fix required-action paths before cosmetic consistency.
- Treat accessibility as layout, not decoration: 48dp effective touch targets, clear semantics, and dynamic type-safe layouts.
- Avoid broad "design system pass" rewrites before P0/P1 screenshots are green.
- Do not change documented product structure unless the docs are updated in the same decision.
- Touch-target convention: enforce 48dp effective hit area by adding `Modifier.minimumInteractiveComponentSize()` to the click modifier of compact controls (keeps the visual at 32–36 dp while expanding the touch slop). Wrap a compact visual in a 48 dp `Box` only when the touch area must extend past the visual frame (e.g., `TextField` leading/trailing icon slots). Do not introduce a new shared button helper unless 3+ call sites need identical chrome — the goal is local fixes, not a new abstraction layer.
- Inset convention: page-level containers reach window insets through `AppWindowInsets` (`ui/theme/WindowInsets.kt`). Feature code should not reference `WindowInsets.statusBars` / `statusBarsPadding()` / `systemBarsPadding()` directly. New code follows this; existing call sites are migrated only when a P0/P1 fix already touches the file. No standalone refactor sweep.

## P0 Release Blockers

| ID | References | Current Behavior | Proposed Fix | Acceptance | Effort |
| --- | --- | --- | --- | --- | --- |
| P0-1 Onboarding completion bypasses shared shell | `app/src/main/kotlin/ai/closepaw/ui/onboarding/OnboardingScreen.kt:148`, `app/src/main/kotlin/ai/closepaw/ui/onboarding/OnboardingShell.kt:63`, `app/src/main/kotlin/ai/closepaw/ui/onboarding/OnboardingSteps.kt:653` | `WizardStep.Complete` calls `CompleteStepContent` directly. The final screen loses the shared `systemBarsPadding()`/`imePadding()` shell, so the "Start Using ClosePaw" CTA can sit too close to or under nav bars on first run. | Route `WizardStep.Complete` through `OnboardingShell` (drop the duplicate "You're All Set!" title) OR introduce a sibling `OnboardingCompleteShell` that shares the same inset and full-screen layout primitives. Per the inset convention in Principles, do not add bespoke `systemBarsPadding()` inside `CompleteStepContent`. | CTA visible above gesture and three-button navigation at 360dp width and font scale 1.5. Final screen matches onboarding background, spacing, and safe areas. | S |
| P0-2 Smart Capsule attention controls can crowd or clip | `app/src/main/kotlin/ai/closepaw/ui/overlay/model/CapsuleRenderSpec.kt:120`, `app/src/main/kotlin/ai/closepaw/ui/overlay/model/CapsuleRenderSpec.kt:195`, `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleControlBar.kt:67`, `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleControlBar.kt:97` | `WaitingForApproval` can render `Always`, `Session`, `Reject`, plus VD nav actions in one non-wrapping row. At narrow width or large font, required approval controls can crowd or clip. | Add an attention-state control layout for `WaitingForApproval`, `WaitingForInput`, `WaitingForAction`, and `Error`: required actions get priority, 48dp effective targets, and nav actions are not placed in the same row when space is constrained. | Approval/action/input/error states show no horizontal clipping at 360dp and font scale 1.5 in main app and overlay. Required actions remain visible, labeled, and tappable. | M |

## P1 Release Consistency and Correctness

| ID | References | Current Behavior | Proposed Fix | Acceptance | Effort |
| --- | --- | --- | --- | --- | --- |
| P1-1 Interactive target and semantics sweep | `CapsuleInputBar.kt:229`, `CapsuleInputBar.kt:306`, `CapsuleControlBar.kt:218`, `StatusIslandCompose.kt:42`, `CompletionHandoffCtaRow.kt:59`, `AppAccessSettingsPage.kt:248`, `AppAccessSettingsPage.kt:290`, `AppAccessSettingsPage.kt:542`, `NavigationDrawer.kt:290`, `PermissionRepairCard.kt:119` | Capsule mic/send/nav/island/handoff, App Access chips, drawer delete, and repair controls use compact visuals or custom surfaces. Some lack explicit role, selected state, state description, or 48dp effective targets. | Apply the touch-target convention from Principles (default `Modifier.minimumInteractiveComponentSize()`; `Box(48.dp)` wrappers reserved for `TextField` icon slots). Add `Role`, `stateDescription`, and stable `contentDescription` where they are missing. Prefer existing Material behavior where it fits. | Accessibility inspection at 360dp / font scale 1.5 shows every interactive custom surface has a role/label/state where applicable and 48dp effective hit area. | M (split across 3-4 PRs by area — capsule, App Access, drawer/repair/handoff — for review tractability) |
| P1-2 Capsule status row semantics parity | `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/SmartCapsuleSurface.kt:276`, `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/StatusIslandCompose.kt:42` | The island exposes button semantics, but the clickable capsule status row only adds `clickable`. | When `onStatusClick` exists, add role/button semantics and a stable label such as "Open current app" or "Capsule status: <text>". | Screen reader behavior is consistent between island and expanded capsule status. | S |
| P1-3 Paper grain strength and layering | `doc/main/ui/style.md:252`, `app/src/main/kotlin/ai/closepaw/ui/theme/PaperGrain.kt:41`, `app/src/main/kotlin/ai/closepaw/ui/theme/PaperGrain.kt:49`, `app/src/main/kotlin/ai/closepaw/ui/chat/ChatScreen.kt:286`, `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsSheet.kt:103` | Docs specify `strength = 0.015f`; code defaults to `0.04f` (PaperGrain.kt:41). The modifier paints `drawContent()` then `drawRect(...)` (lines 49-53), so wherever it sits in a chain it overlays the children beneath it — in `ChatScreen.kt:286` the grain is a sibling box drawn AFTER the Scaffold, and in `SettingsSheet.kt:103` it sits on the content Column above its children. Both surfaces today have grain on top of text. | Default strength → `0.015f` (one literal). Plus pick one implementation of "draw under content" — see Open Question Q3. Apply the same fix to Chat and Settings in one PR. | Light-mode screenshots show paper texture without reducing text/control contrast. Dark mode remains governed by `lanternVignette`. | S |
| P1-4 App Access memory create failure and full editor escape | `app/src/main/kotlin/ai/closepaw/ui/settings/AppAccessSettingsPage.kt:153`, `AppAccessSettingsPage.kt:193`, `AppAccessSettingsPage.kt:528`, `app/src/main/kotlin/ai/closepaw/ui/settings/MemoryFileEditor.kt:352`, `doc/main/app/settings.md:112` | `+ Memory` maps write failure to `WriteFailed` and then shows nothing. Bounded per-app memory passes `onOpenFullMemoryEditor = null`, despite the documented Open affordance. | Surface create-memory failure visibly and add a targeted per-app full-page memory editor route. Preserve the dirty-buffer guard already modeled by `MemoryFileEditor`. | Failed create attempts are visible. Long per-app memory can open full-page. Inline editor remains bounded. | M |
| P1-5 Unbounded memory editor does not fill page | `app/src/main/kotlin/ai/closepaw/ui/settings/MemoryFileEditorPage.kt:39`, `MemoryFileEditorPage.kt:41`, `app/src/main/kotlin/ai/closepaw/ui/settings/MemoryFileEditor.kt:303` | The unbounded page wraps the editor in a scrolling column; empty files render as a small field instead of filling the editor page. | Make the page height-aware: masthead fixed, editor surface/body fills remaining height, action row stays reachable, text field scrolls internally. | User/device memory pages feel like editors, not small form fields, with empty and long content. | M |
| P1-6 OTHER provider field validation | `app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt:613`, `LlmAuthSettingsPage.kt:635`, `app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthDiscoveryUi.kt:273` | Base URL and Custom Model Id validators exist, but fields do not show `isError` or `supportingText`; feedback is indirect through refresh gating or model selection behavior. | Add field-level validation using `OtherBaseUrlValidator` and `ModelIdValidator`. Keep refresh-disable reasons as secondary context. | Invalid OTHER fields explain what is wrong before refresh/save paths fail indirectly. | S |
| P1-7 Voice feedback is logged only | `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleInputBar.kt:134` | Voice recognizer messages go to `Log.i`, so failures can feel like a dead mic button. | Plumb voice feedback to a transient UI path usable from main app and overlay, such as a capsule banner or Android toast. | Permission revoked, unavailable, language, and network failures are visible to the user. | S |
| P1-8 Session drawer ignores active/corrupted state | `app/src/main/kotlin/ai/closepaw/history/model/SessionInfo.kt:32`, `SessionInfo.kt:38`, `app/src/main/kotlin/ai/closepaw/ui/navigation/NavigationDrawer.kt:235`, `NavigationDrawer.kt:290` | `SessionInfo` exposes `isActive` and `isCorrupted`, but drawer rows do not use them. Corrupted sessions look selectable; active sessions have no marker; delete uses a compact icon. | Add active and corrupted row treatments. Disable or guard normal open for corrupted rows, keep delete available, and give delete a 48dp effective target. | Active session is identifiable. Corrupted session is not presented as a normal openable row. Delete remains accessible. | M |
| P1-9 Destructive clear actions can lie about success | `app/src/main/kotlin/ai/closepaw/ui/settings/PermissionsAdvancedSettingsPage.kt:171`, `PermissionsAdvancedSettingsPage.kt:198` | Trace/session clearing sets success after `deleteRecursively()` without checking result or surfacing IO failure. | Wrap deletion in explicit success/failure handling and show inline retry/error state. Only set "Cleared" after success. | UI never reports "Cleared" when deletion failed. | S |

## P2 Refinements

| ID | References | Current Behavior | Conservative Fix | Effort |
| --- | --- | --- | --- | --- |
| P2-1 App Access and licenses loading/empty/error states | `AppAccessSettingsPage.kt:336`, `AppAccessSettingsPage.kt:374`, `OpenSourceLicensesPage.kt:63`, `OpenSourceLicensesPage.kt:86` | Loading/no-match/license-asset failures are minimal or can collapse to empty. | Add small paper-toned loading/empty/error states with clear action copy. | S |
| P2-2 Settings card consistency | `SettingsCard.kt:19`, `SettingsWidgets.kt:162`, `PermissionsAdvancedSettingsPage.kt:90`, `ToolSettingsCard.kt:56` | Several settings rows hand-roll Surface, padding, shape, switch colors, or folded-paper treatment. | Move debug/trace rows and obvious matching rows onto existing card primitives. Avoid a wholesale settings rewrite. | M |
| P2-3 Shape token cleanup | `PermissionRepairCard.kt:50`, `NavigationDrawer.kt:238`, `NavigationDrawer.kt:241`, `MessageBubble.kt:55` | A few surfaces use bare `RoundedCornerShape(N.dp)`. User bubble is intentionally asymmetric but not clearly documented. | Replace equivalent literals with theme shapes. Document user bubble as the intentional local exception. | S |
| P2-4 Onboarding typography, wrapping, and paper primitives | `OnboardingShell.kt:113`, `OnboardingSteps.kt:203`, `OnboardingSteps.kt:677`, `PermissionRepairCard.kt:50` | Onboarding titles may synthesize Fraunces weight/style; provider chips can be fixed-row; some cards use default Material styling. | Use Fraunces italic Regular where the identity style applies, wrap provider/auth choices under large font, and apply ClosePaw paper surfaces where they match the hierarchy. | M |
| P2-5 Empty-state hero paw responsive check | `ChatScreen.kt:180`, `ChatScreen.kt:187`, `doc/main/ui/user_interaction.md:57` | Hero paw uses fixed offset and size. It may clip or overlap at unusual insets, split-screen, tablets, or large font. | Replace magic positioning with constraints if screenshots reproduce clipping. Keep the documented empty-state composition unless product decides otherwise. | S-M |
| P2-6 Status color contrast snapshots | `StatusColors.kt:58`, `SettingsWidgets.kt:194` | Amber contrast risk is known in comments; Paused status still maps to Amber for capsule glyph/glow. | Add contrast snapshots for status colors on light/dark capsule surfaces. Adjust token only if snapshot fails. | S |
| P2-7 Reduced-motion visualizer | `ActionVisualizerCompose.kt:56` | Visualizer runs frame animation while items exist and does not consult `ClosePawMotion.reducedMotion()`. | Under reduced motion, render static final markers or a minimal fade instead of frame-driven expansion. | S |
| P2-8 LLM discovery picker row targets and wrapping | `LlmAuthDiscoveryUi.kt:154`, `LlmAuthDiscoveryUi.kt:195`, `LlmAuthDiscoveryUi.kt:247` | Picker rows and refresh metadata can be compact or crowded. | Ensure 48dp targets/selectable semantics and let refresh metadata wrap below the button on narrow widths. | S |
| P2-9 Island/capsule marquee consistency | `StatusIslandCompose.kt:66`, `SmartCapsuleSurface.kt:320` | Island text width is fixed at 220dp and marquee/reduced-motion logic is duplicated. | Make island max width responsive after screenshot check; extract shared marquee/reduced-motion text only if it reduces duplication without hiding simple layout differences. | S |
| P2-10 Message and ledger edge cases | `MessageBubble.kt:75`, `AgentSummary.kt:45` | Long unbroken user text may overflow; `String.format("%.1fs")` uses default locale. | Add line-break handling for long URLs/hashes if repro confirms overflow. Use `Locale.US` for mono elapsed ledgers. | S |
| P2-11 Overlay inset helper | `IslandOverlayHost.kt:117` | Island host reads status bar height via resource lookup. | Use WindowInsets/current metrics where available, with existing fallback only for unsupported paths. | S |
| P2-12 Capsule and alert polish | `SmartCapsuleSurface.kt:237`, `SmartCapsuleSurface.kt:133`, `CapsuleDivider.kt:195` | Startup error banner and some capsule paddings are inline/hardcoded. | Consider `SettingsAlertCard` for startup error if it fits the compact capsule hierarchy; snap 6dp paddings only after visual comparison. | S |
| P2-13 Local tab warning tone (deferred) | `app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt:568` | LocalTabContent uses `errorContainer` for what is a warning message ("local models are slow…"). | Replace with `SettingsAlertCard(tone = AlertTone.Warning)`. Deferred for the release because `LOCAL_TAB_ENABLED = false` (LlmAuthSettingsPage.kt:72) — no user reaches it. Re-open when the Local tab ships. | S |

## Implementation Tasks

1. `ui-polish-p0-onboarding-shell`
   - Scope: onboarding screen/shell/complete step.
   - Acceptance: final onboarding CTA is visible above nav bars on edge-to-edge devices at 360dp and font scale 1.5.

2. `ui-polish-p0-capsule-attention-layout`
   - Scope: `CapsuleControlBar`, `CapsuleRenderSpec`, nav visibility/layout.
   - Acceptance: approval/action/input/error capsule states have no horizontal clipping at 360dp and font scale 1.5.

3. `ui-polish-accessibility-targets`
   - Scope: capsule controls, island, App Access chips, drawer delete, repair card, handoff CTA.
   - Acceptance: custom interactive surfaces expose roles/state/labels and effective 48dp targets.
   - Dependency: task 2 for capsule control layout.

4. `ui-polish-memory-app-access`
   - Scope: `MemoryFileEditor*`, `AppAccess*`, settings page routing.
   - Acceptance: memory create failure is visible; per-app full editor opens; unbounded editor fills the page.

5. `ui-polish-settings-failure-states`
   - Scope: LLM OTHER fields, permissions advanced clear actions, voice feedback path, license/App Access empty/error states.
   - Acceptance: invalid fields and failed IO/destructive actions are visible and do not report false success.

6. `ui-polish-session-drawer-state`
   - Scope: session drawer rows.
   - Acceptance: active and corrupted session states are visually and semantically distinct; delete target is accessible.

7. `ui-polish-paper-and-visual-system`
   - Scope: paper grain, onboarding style, shape tokens, settings card consistency, status contrast.
   - Acceptance: verified visual-system fixes land after P0/P1 user-action paths are green.

8. `ui-polish-final-qa`
   - Scope: screenshots, accessibility inspection, and targeted UI checks for the matrix below.
   - Acceptance: no P0/P1 clipping, missing semantics, false success, or unreadable contrast in light/dark, 360dp, font scale 1.5, and reduced motion.

## QA Matrix

| Surface | States to Capture |
| --- | --- |
| Main chat | Empty state, one user message, long URL, streaming agent, completed collapsed trace, completion handoff, startup error banner |
| Session drawer | Empty history, active session, corrupted session, delete confirmation |
| Settings home | All granted, missing permissions, long model name, dark mode |
| LLM/Auth | Sign In signed out/in/progress/error, API Key OpenAI/OpenRouter/OTHER valid and invalid fields, model refresh loading/error/empty |
| Agent Behavior | Perception selector, VD Shizuku unavailable/permission denied/enabled, browser_script probing/not-bound/unknown, Termux states, skills empty/loaded/info dialog |
| Memory | Locked/unlocked, loading, too-large save error, IO error, full-page user/device editor, bounded app editor, per-app full editor |
| App Access | Loading, no apps match, long app names/packages, blocked app warning, skill-only expansion, memory-only expansion, create-memory failure |
| Smart Capsule | Hidden input, Running, TakeoverPending, Takeover, Supplement note, WaitingForInput, WaitingForAction, WaitingForApproval, Done, Error, startup error, voice permission/error |
| Overlay | Screen-viewing capsule, island, glow, visualizer, VD viewer, locked touch shield, keyboard focus |
| Onboarding | Each step at 360dp width, font scale 1.5, IME open on API key, final completion screen (gesture nav + three-button nav) |
| Motion/contrast | Reduced motion enabled, light/dark paper grain or vignette, Paused/Running/Error status colors |

## Not In Scope For This Release Polish Pass

- A redesign of the brand language, chat masthead, navigation model, or settings information architecture.
- A new global button abstraction unless implementation proves repeated local fixes are worse.
- Refactoring every custom `Surface` or every raw inset call before P0/P1 checks pass.
- Changing the documented `[menu] + paw + ClosePaw + [+]` chat header structure without an explicit doc/product update.
- Reworking `PageMastheadDrillDown` back/close semantics; keep both for release because the distinction is documented and useful.
- Making `Fleuron` itself screen-reader-visible; it is decorative unless a later accessibility review says otherwise.
- `OverlayComposeHost` window-token cleanup and similar lifecycle/robustness audits. Out of scope for a visual polish pass; revisit as a separate overlay hardening task if defects arise.
- Broad refactor sweeps purely to satisfy the inset or touch-target conventions in Principles. Those conventions land file-by-file as P0/P1 fixes touch each file.

## Resolved Decisions

These resolve former Open Questions Q1, Q2, Q4, Q5, Q6, Q7, Q10, Q11, Q12. The corresponding P-items above are written with the resolution in mind.

- **D1 (was Q1) Capsule attention layout** → **hide secondary nav** in `WaitingForApproval`, `WaitingForInput`, `WaitingForAction`, `Error`. Required actions get the whole row at 48 dp effective targets. Nav returns when state transitions back to `Running`. Applies to P0-2.
- **D2 (was Q2) Paper grain modifier semantics** → keep `Modifier.paperGrain` as an **overlay** (existing semantics, no API change). Call sites that need "grain under content" must place a sibling Box behind the content, not wrap the content. ChatScreen and SettingsSheet are migrated to this pattern in P1-3 together with the strength fix.
- **D3 (was Q4) User bubble material** → keep `surfaceContainerLow` and add a **1 dp outline** border (Ink at α 0.18). Smallest change that visibly separates user bubble from the also-`surfaceContainerLow` capsule. Captured in P2-3 with the bubble's intentional asymmetric shape.
- **D4 (was Q5 + Q7 + Q12) Failure / warning feedback surface** → **inline `SettingsAlertCard`** as the default for settings-tab failures (App Access memory create failure, memory edit lock feedback) and voice recognizer / permission failures inside the chat scaffold. The toast path remains only for cases that have no obvious anchor row. Capsule-internal banner and Snackbar are not introduced in this release. Applies to P1-4, P1-7, and P2-13's deferred local-tab path.
- **D5 (was Q6) App Access filters** → migrate to Material **`SingleChoiceSegmentedButtonRow`**, matching `LlmAuthSettingsPage:398`. Removes most of P1-1's a11y work in this area (segmented row exposes role/state/48 dp targets natively). New leaf P1-10 task below.
- **D6 (was Q10) Action visualizer reduced-motion scope** → P2-7 ships **reduced-motion compliance only**. Item lifetime / loop cleanup is explicitly out of scope and revisited post-release if a defect surfaces.
- **D7 (was Q11) Onboarding shell scrolling** → replace the `BoxWithConstraints` + IME heuristic with a **simple always-scroll vertical model** in `OnboardingShell`. IME insets are handled via `imePadding()`. The heuristic block at `OnboardingShell.kt:60-79` is deleted. Folded into P0-1.

## Open Questions

Three remaining items — all need data (screenshots or device measurement) before a decision is meaningful. Resolve each by first updating the relevant self-contained section above, then removing or revising the question here.

1. Empty-state hero paw (P2-5): do screenshots prove clipping/overlap at target devices (Pixel 6a gesture nav, split-screen, tablet, font scale 1.5), and if so should the fix be constrained positioning, smaller responsive size, lower alpha, or some combination?
2. VirtualDisplayViewerActivity: should black/no-frame loading state (`ui/viewer/VirtualDisplayViewerActivity.kt`) be included in this release polish pass, or deferred as VD pipeline UX work?
3. Paused Amber status color: if contrast snapshots (P2-6) fail on light/dark capsule surfaces, should the fix be outline support, a darker token, or a different semantic foreground?
