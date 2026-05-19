# ClosePaw Pre-release UI Polish Design

## Goal

Audit the existing ClosePaw Android UI for release polish, not redesign. The target is a consistent, shippable app across:

- Main chat, session drawer, history rows, empty state, completion handoff.
- Settings: home, LLM/auth, agent behavior, permissions, tools, memory, app access, licenses.
- Smart Capsule in main app and overlay: Running, Takeover, Supplement, WaitingForInput, WaitingForAction, WaitingForApproval, Done, Error.
- Onboarding and permission repair states.

Success means no release-blocking clipping or inaccessible controls, consistent use of the ClosePaw paper/Claw/paw design system, and explicit loading/error/empty states where user actions can fail.

## Approach

Keep the code simple and local:

1. Fix blockers in high-risk interaction surfaces first: onboarding completion and Smart Capsule attention states.
2. Standardize repeated UI behavior through existing primitives instead of inventing a new system: `ClosePawTheme`, `MaterialTheme.closePaw.spacing`, `SettingsCard`, `ToolSettingsCard`, `PageMasthead*`, `ClosePawMotion`.
3. Treat accessibility as a layout constraint: touch targets are at least 48dp, interactive custom surfaces expose role/state/labels, and dynamic type does not hide required actions.
4. Validate visually with device screenshots in light/dark, 360dp width, large font, and each capsule mode.

## P0 Release Blockers

| ID | Reference | Current behavior | Proposed fix | Effort |
| --- | --- | --- | --- | --- |
| P0-1 | `app/src/main/kotlin/ai/closepaw/app/MainActivity.kt:157`, `app/src/main/kotlin/ai/closepaw/ui/onboarding/OnboardingScreen.kt:148`, `app/src/main/kotlin/ai/closepaw/ui/onboarding/OnboardingShell.kt:60`, `app/src/main/kotlin/ai/closepaw/ui/onboarding/OnboardingSteps.kt:618` | MainActivity is edge-to-edge, but the `Complete` onboarding step bypasses `OnboardingShell`. It loses system bar padding and the shared full-screen shell, so the "Start Using ClosePaw" CTA can land too close to or under the navigation bar on first run. | Put completion inside a small completion shell that matches `OnboardingShell` insets and `fillMaxSize`, or route `WizardStep.Complete` through `OnboardingShell` with duplicate title removed. Keep the final CTA pinned above nav/IME insets. | S |
| P0-2 | `app/src/main/kotlin/ai/closepaw/ui/overlay/model/CapsuleRenderSpec.kt:120`, `app/src/main/kotlin/ai/closepaw/ui/overlay/model/CapsuleRenderSpec.kt:195`, `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleControlBar.kt:67`, `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleControlBar.kt:97` | `WaitingForApproval` can render Always, Session, Reject plus VD nav buttons in one non-wrapping row. On narrow devices or large font, required approval controls can clip or crowd, blocking the user from approving or rejecting. | Add an "attention" control layout for `WaitingForApproval`, `WaitingForInput`, `WaitingForAction`, and `Error`: required actions get their own row with 48dp minimum targets; nav actions hide or move to a second row. At minimum, gate `showApp`/`showWatch` off for those modes. | M |

## P1 Consistency Fixes

| ID | Reference | Current behavior | Proposed fix | Effort |
| --- | --- | --- | --- | --- |
| P1-1 | `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleInputBar.kt:229`, `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleInputBar.kt:306`, `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleControlBar.kt:218`, `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/StatusIslandCompose.kt:55`, `app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:59` | Capsule mic/send/nav/island/handoff controls use 32-36dp visual sizes and some custom click surfaces lack full semantics. This is inconsistent with Android touch target expectations. | Introduce small reusable capsule button helpers with 48dp hit targets, compact 32-36dp visuals inside, and explicit role/content descriptions. Apply to capsule input, nav icons, island, live pill, and handoff CTA. | M |
| P1-2 | `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/SmartCapsuleSurface.kt:276`, `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/StatusIslandCompose.kt:42` | The island exposes button semantics, but the clickable capsule status row does not. Same interaction has different accessibility behavior depending on surface. | Add role/button semantics and a stable label such as "Open current app" or "Capsule status: <text>" when `onStatusClick` is present. | S |
| P1-3 | `app/src/main/kotlin/ai/closepaw/ui/settings/AppAccessSettingsPage.kt:248`, `app/src/main/kotlin/ai/closepaw/ui/settings/AppAccessSettingsPage.kt:290`, `app/src/main/kotlin/ai/closepaw/ui/settings/AppAccessSettingsPage.kt:542`, `app/src/main/kotlin/ai/closepaw/ui/settings/AppAccessSettingsPage.kt:711` | App Access search, filters, tier chips, expand chip, and `+ Memory` affordance are custom surfaces. Several have no explicit role/selected state and compact heights below 48dp. | Use Material `FilterChip`/segmented controls where possible. Otherwise add `selectable`/`Role.RadioButton`, `stateDescription`, `contentDescription`, and `defaultMinSize(minHeight = 48.dp)`. | M |
| P1-4 | `app/src/main/kotlin/ai/closepaw/ui/settings/AppAccessSettingsPage.kt:153`, `app/src/main/kotlin/ai/closepaw/ui/settings/AppAccessSettingsPage.kt:193` | App Access `+ Memory` handles lock races, but `WriteFailed` is silently ignored with a comment saying there is no inline error host. | Add per-package transient error state or a toast with the same visual tone as memory editor errors. Disable the chip while the create coroutine is in flight to prevent repeated silent attempts. | S |
| P1-5 | `doc/main/app/settings.md:112`, `doc/main/app/settings.md:113`, `app/src/main/kotlin/ai/closepaw/ui/settings/MemoryFileEditor.kt:352`, `app/src/main/kotlin/ai/closepaw/ui/settings/AppAccessSettingsPage.kt:528` | Docs say bounded App Access memory editors render an "Open" affordance, but App Access passes `onOpenFullMemoryEditor = null`, so no full-page escape exists for long per-app memory. | Add a Settings subpage route for per-app `MemoryFileEditorPage(scope = APP, packageName = pkg)`. Keep inline editor bounded and disable Open while dirty as already modeled in `MemoryFileEditor`. | M |
| P1-6 | `doc/main/app/settings.md:112`, `app/src/main/kotlin/ai/closepaw/ui/settings/MemoryFileEditorPage.kt:39`, `app/src/main/kotlin/ai/closepaw/ui/settings/MemoryFileEditorPage.kt:41`, `app/src/main/kotlin/ai/closepaw/ui/settings/MemoryFileEditor.kt:303` | Docs say unbounded memory editor fills the page, but the page wraps the editor in a vertical scroll and the text field only gets bounded height logic. Empty files render like a small field instead of a full-page editor. | Make the page body height-aware: `Surface`/editor area uses `weight(1f)`, action row stays pinned, and unbounded `OutlinedTextField` fills remaining height with internal scroll. | M |
| P1-7 | `app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt:613`, `app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt:635`, `app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthDiscoveryUi.kt:273` | OTHER provider Base URL and Custom Model Id fields accept invalid values without field-level errors. The only feedback is indirect refresh gating or missing model auto-flip. | Add `isError` and `supportingText` using `OtherBaseUrlValidator` and `ModelIdValidator`. Normalize on persist/blur and keep refresh disabled reason as secondary context. | S |
| P1-8 | `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleInputBar.kt:134` | Voice recognition feedback is logged only through `onToast`, so mic/recognizer failures can appear as a dead button. | Plumb capsule voice feedback to a small transient banner or Android toast. Use the same message path in main app and overlay. | S |
| P1-9 | `app/src/main/kotlin/ai/closepaw/history/model/SessionInfo.kt:31`, `app/src/main/kotlin/ai/closepaw/history/model/SessionInfo.kt:38`, `app/src/main/kotlin/ai/closepaw/ui/navigation/NavigationDrawer.kt:235`, `app/src/main/kotlin/ai/closepaw/ui/navigation/NavigationDrawer.kt:290` | Session rows ignore `isActive` and `isCorrupted`, and the destructive delete icon is a 32dp button. Corrupted sessions look selectable like normal rows. | Add active/corrupted row treatments: active paw/dot, corrupted warning text, disable normal open for corrupted rows, keep delete available with a 48dp hit target. | M |
| P1-10 | `app/src/main/kotlin/ai/closepaw/ui/settings/PermissionsAdvancedSettingsPage.kt:171`, `app/src/main/kotlin/ai/closepaw/ui/settings/PermissionsAdvancedSettingsPage.kt:198` | Clear traces/session history sets the success state after `deleteRecursively()` without surfacing IO failure. | Wrap deletes in `runCatching`, show inline error or retry state, and only set "Cleared" after a successful delete. | S |

## P2 Refinements

| ID | Reference | Current behavior | Proposed fix | Effort |
| --- | --- | --- | --- | --- |
| P2-1 | `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsCard.kt:19`, `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsWidgets.kt:162`, `app/src/main/kotlin/ai/closepaw/ui/settings/PermissionsAdvancedSettingsPage.kt:90` | `SettingsCard` is declared canonical, but several settings rows still hand-roll `Surface`, padding, shape, and folded-paper treatment. | Move `SettingsRow`, `SettingsNavigationRow`, debug row, trace row, and license cards onto `SettingsCard` where behavior matches. This is mechanical polish, not a layout redesign. | M |
| P2-2 | `app/src/main/kotlin/ai/closepaw/ui/onboarding/OnboardingSteps.kt:203`, `app/src/main/kotlin/ai/closepaw/ui/onboarding/OnboardingSteps.kt:677` | Onboarding provider chips sit in a fixed horizontal row, and status/error cards use default Material `Card` styling instead of ClosePaw paper primitives. | Use a wrapping row for provider/auth choices under large font. Replace default cards with ClosePaw paper surfaces and token spacing. | M |
| P2-3 | `app/src/main/kotlin/ai/closepaw/ui/settings/AppAccessSettingsPage.kt:336`, `app/src/main/kotlin/ai/closepaw/ui/settings/AppAccessSettingsPage.kt:374`, `app/src/main/kotlin/ai/closepaw/ui/settings/OpenSourceLicensesPage.kt:63`, `app/src/main/kotlin/ai/closepaw/ui/settings/OpenSourceLicensesPage.kt:86` | Loading/empty/error states are minimal: App Access loading is spinner-only, no-match is one line, license asset parse errors collapse to an empty list. | Add small paper-toned empty/loading/error cards with action copy: clear search/filter, retry license load, or name the missing generated asset. | S |
| P2-4 | `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/StatusColors.kt:58`, `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsWidgets.kt:194` | Settings comments already note Amber contrast failures for text. `GlowState.Paused` still maps directly to tertiary/Amber for capsule paw glyphs and glows. | Add a contrast snapshot for status colors on light/dark capsule surfaces. If Paused fails graphical contrast, keep Amber as accent but add outline, darker token, or swap to an AA-safe foreground. | S |
| P2-5 | `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/ActionVisualizerCompose.kt:297` | Action visualizer runs frame animation while items exist and does not consult `ClosePawMotion.reducedMotion()`. | Under reduced motion, render a static final click/swipe marker for the duration instead of frame-driven expansion. | S |
| P2-6 | `app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthDiscoveryUi.kt:154`, `app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthDiscoveryUi.kt:195`, `app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthDiscoveryUi.kt:247` | Group/model picker rows are compact custom click rows, and refresh timestamp shares one horizontal row with the refresh button. | Give picker rows 48dp minimum target and selectable state. Let refresh timestamp wrap below the button on narrow width. | S |
| P2-7 | `app/src/main/kotlin/ai/closepaw/ui/chat/components/MessageBubble.kt:55`, `app/src/main/kotlin/ai/closepaw/ui/theme/Shape.kt:5` | User bubble uses bespoke radii outside `ClosePawShapes` while most surfaces use theme shapes. This may be intentional, but it is another shape source. | Either document the user bubble as the one intentional asymmetric shape, or express it through a named local constant in the theme package so it is not a hidden one-off. | S |

## Interaction State Checks

Use this matrix for final QA after the fixes:

| Surface | States to capture |
| --- | --- |
| Main chat | empty state, one user message, streaming agent, completed collapsed agent trace, completion handoff, startup error banner |
| Session drawer | empty history, active session, corrupted session placeholder, delete confirmation |
| Settings home | all granted, missing permissions, long model name, dark mode |
| LLM/Auth | Sign In signed out/in/progress/error, API Key OpenAI/OpenRouter/OTHER valid and invalid fields, model refresh loading/error/empty |
| Agent Behavior | perception selector, VD Shizuku unavailable/permission denied/enabled, browser_script probing/not-bound/unknown, Termux states, skills empty/loaded/info dialog |
| Memory | locked/unlocked, loading, too-large save error, IO error, full-page user/device editor, bounded app editor |
| App Access | loading, no apps match, long app names/packages, blocked app warning, skill-only expansion, memory-only expansion, create memory failure |
| Smart Capsule | Hidden input, Running, TakeoverPending, Takeover, Supplement note, WaitingForInput, WaitingForAction, WaitingForApproval, Done, Error, startup error, voice permission/error |
| Overlay | screen-viewing capsule, island, glow, VD viewer, locked touch shield, keyboard focus |
| Onboarding | each step at 360dp width, large font, IME open on API key, final completion screen |

## Implementation Tasks

1. `ui-polish-p0-onboarding-shell`
   Scope: `app/src/main/kotlin/ai/closepaw/ui/onboarding/**`, `app/src/main/kotlin/ai/closepaw/app/MainActivity.kt`
   Acceptance: completion CTA is visible above nav bars on edge-to-edge devices; final screen matches onboarding spacing and background.
   Dependencies: none.

2. `ui-polish-p0-capsule-attention-layout`
   Scope: `app/src/main/kotlin/ai/closepaw/ui/capsule/**`, `app/src/main/kotlin/ai/closepaw/ui/overlay/model/CapsuleRenderSpec.kt`
   Acceptance: approval/action/input/error capsule states have no horizontal clipping at 360dp width and font scale 1.5; required actions remain tappable at 48dp.
   Dependencies: none.

3. `ui-polish-accessibility-targets`
   Scope: `app/src/main/kotlin/ai/closepaw/ui/capsule/**`, `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/**`, `app/src/main/kotlin/ai/closepaw/ui/settings/AppAccessSettingsPage.kt`, `app/src/main/kotlin/ai/closepaw/ui/navigation/NavigationDrawer.kt`
   Acceptance: custom interactive surfaces expose role/state/label and pass 48dp touch target checks.
   Dependencies: task 2 for capsule helpers.

4. `ui-polish-memory-app-access`
   Scope: `app/src/main/kotlin/ai/closepaw/ui/settings/Memory*.kt`, `app/src/main/kotlin/ai/closepaw/ui/settings/AppAccess*.kt`
   Acceptance: App Access create-memory failures are visible; per-app full editor opens; unbounded editor fills the page.
   Dependencies: none.

5. `ui-polish-settings-errors`
   Scope: `app/src/main/kotlin/ai/closepaw/ui/settings/**`
   Acceptance: OTHER provider validation, destructive data clearing, license load failure, and App Access empty/loading states have explicit UI states.
   Dependencies: none.

6. `ui-polish-final-qa`
   Scope: Compose UI tests and device screenshots for affected flows.
   Acceptance: screenshots for the interaction matrix in light/dark, 360dp, font scale 1.5, and reduced motion; no clipping or contrast failures in P0/P1 surfaces.
   Dependencies: tasks 1-5.

## Trade-offs

- Do not redesign the visual language. The app already has a clear Bound Edition paper system, Claw palette, paw motif, and motion tokens. The right move is to remove one-offs and make existing primitives carry more surfaces.
- Prefer hiding or moving secondary nav controls in capsule attention states over squeezing more into the row. Required approval/input/action controls are the canonical case.
- Keep per-app memory full-page routing small. A targeted Settings subpage is enough; no new global navigation architecture is needed.
- Do not chase every custom `Surface` immediately. P0/P1 fixes should land first; P2 component consolidation can follow after release-blocking screenshots are green.
