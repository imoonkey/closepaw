# UI Polish — QA Report

**Build**: `ui-polish-integration` (HEAD `efc196b2`, 7 polish commits + 5 integration merges on top of `main`)
**Device**: `emulator-5554` — sdk_gphone64_x86_64 · Android 13
**Date**: 2026-05-19
**Verify gate**: `:app:assembleDebug` ✅ · `:app:lint` ✅ · `test` ✅

---

## What is covered

Live screenshots from the merged integration branch, one per visible polish surface. Headless behaviours (a11y semantics, reduced-motion, modifier order) are not screenshottable but were code-reviewed at merge time.

| # | Surface | P/D items verified | Shot |
|---|---|---|---|
| 1 | Chat empty state + alert card + paper grain | P1-3 grain strength · D2 grain layering · P2-1 alert pattern | [01](shots/01_chat_empty_state.png) |
| 2 | Session drawer rows | P2-3 shape tokens (`MaterialTheme.shapes.small`) | [02](shots/02_session_drawer.png) |
| 3 | App Access filter row | **D5** `SingleChoiceSegmentedButtonRow` | [03](shots/03_app_access_segmented.png) |
| 4 | User memory editor (full page) | **P1-5** height-aware fill | [04](shots/04_memory_editor_fills.png) |
| 5 | LLM auth — OTHER provider | **P1-6** `isError` + `supportingText` | [05](shots/05_llm_other_validation.png) |
| 6 | Existing chat session | **D3** user bubble 1 dp Ink/α 0.18 outline | [06](shots/06_user_bubble_outline.png) |
| 7 | Onboarding (Step 1 of 5) | **D7** always-scroll shell · OnboardingShell scaffolding | [07](shots/07_onboarding_step1_shell.png) |
| 8 | **Real-device install** (Nubia P0110 / Android 16) | end-to-end install + launch on Tailscale-attached device | [08](shots/08_real_device_launch.png) |
| 9 | RepairCard narrow-width fix (real device) | P2 follow-up: title/detail stacked vertically, no clipping | [09](shots/09_real_device_repaircard_fixed.png) |

---

## Per-surface findings

### 1 · Chat empty state · paper grain · alert card
![01](shots/01_chat_empty_state.png)

- Hero paw bleeds behind the `[menu] paw ClosePaw` masthead band (intentional empty-state composition).
- Paper grain visible at the new `0.015f` default — texture present but no longer competing with body text. **P1-3 + D2 grain landed correctly.**
- "Accessibility disabled · Agent cannot automate · [Fix]" rendered via `SettingsAlertCard` with `Warning` tone — the same primitive D4 routes all release-time failure surfaces through.

### 2 · Session drawer
![02](shots/02_session_drawer.png)

- `Sessions` masthead in Fraunces italic. `+ New Conversation` Claw-bordered button.
- Recent rows use `MaterialTheme.shapes.small` (replaced the `RoundedCornerShape(8.dp)` literal) — corners visibly match the rest of the system. **P2-3 token cleanup landed.**
- Delete (trash) icon retains 32 dp visual but is now wrapped in a 48 dp `Box(minimumInteractiveComponentSize())` per the project convention. Hit-area expansion is invisible by design.
- Active / corrupted treatments not visible here because the listed sessions are neither — the row recipes are wired but need synthetic state to demonstrate. Recommend capturing during real-device QA.

### 3 · App Access — segmented filter
![03](shots/03_app_access_segmented.png)

- **D5 fully visible**: the connected `All | Allow | Ask | Reject` segmented row replaces the prior custom chip row, mirroring `LlmAuthSettingsPage`'s provider picker exactly.
- `All` is `selected` with `primaryContainer` fill; non-selected segments transparent — Material handles `Role.RadioButton`, `selected` state, and 48 dp targets natively.
- Per-app tier controls below (Allow/Ask/Reject) use the same segmented family, so the page now reads as one consistent decision pattern instead of two.

### 4 · User memory editor — fills the page
![04](shots/04_memory_editor_fills.png)

- **P1-5 visible**: the unbounded editor surface fills available height. Empty state ("No memory yet.") is anchored top-left of a full-height pad; `Edit / Delete` action row pinned at the bottom.
- Pre-fix, this empty case rendered as a small ~96-dp form floating in white space (because the editor lived inside a `verticalScroll(Column)` wrapper). Now the page reads as an editor, not a tile.

### 5 · LLM auth — OTHER provider field validation
![05](shots/05_llm_other_validation.png)

- **P1-6 landed**:
  - `Base URL` field shows `isError = true` (red outline) + supportingText "Base URL must not be blank" (red).
  - `Custom Model Id` field same treatment with "Model id must not be blank".
- Refresh path correctly reports its disabled reason ("Refresh disabled: Enter your API key first") as secondary context, consistent with D4 — direct field errors lead, refresh state follows.
- The "Other" provider segmented button is selected (Claw-tinted fill), matching the App Access pattern in shot 3.

### 6 · User bubble outline (existing session)
![06](shots/06_user_bubble_outline.png)

- **D3 outline visible** on the user bubble at top: 1 dp Ink/α 0.18 border separates the user message from the surrounding paper. Asymmetric corners preserved (`topStart = 4.dp`) — the documented intentional shape.
- Agent reply rendered as flat text on paper below the collapsed trace row (`✓ 14 actions · 195s`) — the documented "agent speaks on the paper itself, no bubble" pattern.
- `SettingsAlertCard` (accessibility disabled) still pinned above the input bar — proves the alert remains anchored after navigating into a session.

### 7 · Onboarding shell (Step 1 of 5, fresh install)
![07](shots/07_onboarding_step1_shell.png)

- Fresh `adb uninstall` + reinstall to reach the first-run wizard.
- **D7 always-scroll** model: page composition shows masthead + 5-paw step indicator + Fraunces italic title + body + status box + CTA — all anchored in a single vertical column, no `BoxWithConstraints` height-branching needed.
- `OnboardingShell` masthead pattern verified: the "Step 1 of 5" counter and the 5 paw glyphs (first one Claw-tinted, others muted) sit above the title with consistent spacing.
- Walking all five steps to capture the Complete page requires real permission grants (system Settings dialogs for accessibility + overlay + battery + signing into OAuth + running a demo task). P0-1's structural change (routing `WizardStep.Complete` through `OnboardingShell`, dropping the duplicate title) was verified at code level during merge review; the visual would only confirm "looks the same as steps 1–4".

---

## Not captured (require setup)

| Surface | Why not | How to capture |
|---|---|---|
| **P0-2 / D1** capsule attention states (`WaitingForApproval`, `WaitingForAction`, `WaitingForInput`, `Error`) | Requires accessibility service granted + an agent run that yields the relevant approval/action prompt | Enable a11y → `./scripts/debug-run.sh "Open Settings"` (triggers system approval) → screenshot each state at 360 dp / fontScale 1.5 |
| **P0-1 / D7** onboarding completion screen | Requires fresh install — current device is past first-run | `adb uninstall ai.closepaw` → reinstall → walk through to the Complete step at gesture-nav + 3-button-nav, fontScale 1.5 |
| **P1-8** session drawer active / corrupted rows | No active or corrupted sessions in current state | Start an agent run for the active marker; rename a `.json` history file to break parse for the corrupted marker |
| **P1-7 / D4** voice feedback alert | Requires permission revoke or recognizer error mid-input | Revoke RECORD_AUDIO permission, tap mic, observe `SettingsAlertCard` rendered above input bar |
| **P1-2** capsule status row semantics | Semantic-only (no visual diff) | Talkback inspection — verify `Role.Button` + "Capsule status: …" announcement |
| **P1-9** destructive clear failure handling | Requires forcing a `deleteRecursively` failure | Make trace dir read-only, tap Clear, expect inline error state instead of "Cleared" |

---

## Recommendation

Integration branch is visually verified on the happy paths. Before opening the PR, the remaining "not captured" surfaces are worth a 20-min device pass — none change the underlying code path, all are state-setup-only checks against the same merged build.

Three deferred open questions from the design (Q3 hero paw clipping · Q8 VD viewer loading · Q9 Paused Amber contrast) need device data, not code work — collect those snapshots in the same pass.
