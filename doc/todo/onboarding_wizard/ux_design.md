status: draft

# Onboarding Wizard UX Design

Date: 2026-04-02
Scope: First-launch onboarding wizard for Android Agent on Android 12+ (`minSdk 31`)

---

## 1. Problem

### 1.1 Actual user problem
Current first-run experience fails late instead of guiding early:

- Users land in the chat UI before they understand setup requirements.
- Accessibility and overlay are discovered only after a failed attempt plus a toast.
- Battery optimization is not guided at all.
- API key entry is hidden inside a settings sheet — feels like developer setup.
- There is no "you are ready" moment and no safe proof that the agent works.

### 1.2 Who has this problem

- Brand-new installs
- Users who cleared app data
- Users who started setup, left for system settings, and never came back

### 1.3 Why it matters
Android Agent only becomes valuable after setup is complete. If the first session feels like permission whack-a-mole, users assume the app is broken and give up.

### 1.4 Behavioral success
After onboarding:

- The user understands why each permission exists.
- The user reaches one verified successful task in the first session.
- The user lands in the main chat ready for a real task.
- Later launches go straight to chat instead of replaying setup.

---

## 2. Experience Decisions

- Dedicated full-screen onboarding route. Do not reuse the existing settings sheet.
- Strictly sequential funnel: Accessibility → Overlay → Battery → API Key → Demo → Complete.
- One job per screen: explain why, show live status, offer one primary action.
- **Hard gates**: Accessibility, Overlay, and a validated API key. Matches current runtime — tasks don't start without these. No skip on hard gates.
- **Soft gate**: Battery optimization. Improves reliability but must not block completion.
- Demo must be safe and verifiable: `Open the Settings app` (no device state mutation).
- Persist progress by step. Kill/background → reopen at first incomplete step.
- Persist onboarding completion separately. Later permission revocation → targeted repair card in chat, not full wizard replay.
- Only ask for the single API key for the default cloud path. Advanced provider/model stays in settings.
- Forward-only — no back navigation between steps. System back exits the app.
- Do not dump user into full chat UI mid-flow. Demo completion returns to onboarding shell first.

---

## 3. Flow Overview

```text
App Launch
  → LaunchCheck
      → onboardingCompleted = true  → Main Chat
      → onboardingCompleted = false → first incomplete step

Accessibility (hard gate)
  → Overlay (hard gate)
  → Battery (grant or skip)
  → API Key (validate)
  → Demo (run or skip)
  → Complete
  → Main Chat
```

### 3.1 Onboarding Router State Machine

#### States

- `LaunchCheck`: decide whether onboarding should appear.
- `AccessibilityStep`: accessibility service setup.
- `OverlayStep`: overlay permission setup.
- `BatteryStep`: battery optimization guidance.
- `ApiKeyStep`: API key entry and validation.
- `DemoStep`: safe proof task.
- `CompleteStep`: summary and handoff.
- `MainChat`: normal app entry.

#### Transition table

| Current | Trigger | Guard | Next | Side effects |
|---|---|---|---|---|
| `LaunchCheck` | App launched | `onboardingCompleted = true` | `MainChat` | Skip onboarding entirely. |
| `LaunchCheck` | App launched | `onboardingCompleted = false` | first incomplete step | Load persisted step outcomes, resume funnel. |
| `AccessibilityStep` | Step satisfied | Service enabled and connected | `OverlayStep` | Persist `accessibility = done`; brief success; auto-advance. |
| `OverlayStep` | Step satisfied | Overlay granted | `BatteryStep` | Persist `overlay = done`; auto-advance. |
| `BatteryStep` | User granted | Battery exemption active | `ApiKeyStep` | Persist `battery = done`; auto-advance. |
| `BatteryStep` | User skipped | — | `ApiKeyStep` | Persist `battery = skipped`; advance. |
| `ApiKeyStep` | Validation succeeded | Auth request returned success | `DemoStep` | Save key; persist `apiKey = valid`; auto-advance. |
| `DemoStep` | Demo succeeded | Task verified | `CompleteStep` | Persist `demo = done`. |
| `DemoStep` | User skipped | — | `CompleteStep` | Persist `demo = skipped`. |
| `CompleteStep` | User taps "Start Using" | — | `MainChat` | Set `onboardingCompleted = true`; clear onboarding from back stack. |
| Any step | App relaunched before completion | `onboardingCompleted = false` | first incomplete step | Resume from persisted progress. |

#### Router rules

- On entry to any step, re-check whether already satisfied. If yes, show brief success and auto-advance.
- "First incomplete step" = ordered scan: Accessibility, Overlay, Battery, API Key, Demo.
- Skipped battery counts as complete for routing.
- Once `onboardingCompleted = true`, later launches always go to `MainChat`. Permission revocation → targeted in-app repair, not wizard.

### 3.2 Permission Step State Machine

Shared template for Accessibility, Overlay, and Battery. Different copy and skip rules per step.

#### States

- `Checking`: live check on step entry or app resume.
- `Ready`: requirement missing, primary CTA available.
- `OpeningSystemSettings`: system intent launched.
- `Satisfied`: requirement met.
- `Unsatisfied`: user returned without satisfying requirement.
- `Skipped`: soft-step only (Battery); user chose to continue.

#### Transition table

| Current | Trigger | Guard | Next | Side effects |
|---|---|---|---|---|
| `Checking` | Step entered or app resumed | Already met | `Satisfied` | Checkmark ~400ms; persist; auto-advance. |
| `Checking` | First entry | Still missing | `Ready` | Render explanation + CTA. |
| `Checking` | Resumed after settings round-trip | Still missing | `Unsatisfied` | Show unmet status, consequence copy, retry. |
| `Ready` | Tap primary CTA | — | `OpeningSystemSettings` | Launch correct system settings page. |
| `OpeningSystemSettings` | App returns | — | `Checking` | Re-read permission state. |
| `Unsatisfied` | Tap retry | — | `OpeningSystemSettings` | Relaunch settings. |
| `Ready` / `Unsatisfied` | Tap "Continue without this" | Battery only | `Skipped` | Persist skipped; advance. |

#### Per-step rules

| Step | Why | Primary CTA | Blocking? | Consequence if missing |
|---|---|---|---|---|
| Accessibility | Read UI state, perform taps in other apps | `Open Accessibility Settings` | **Yes** | "Without Accessibility, Android Agent cannot automate tasks." |
| Overlay | See floating capsule, progress, stop/take-over controls | `Grant Overlay Permission` | **Yes** | "Without Overlay, you won't see controls while the agent works in other apps." |
| Battery | Long tasks survive background execution, screen-off, OEM killing | `Allow Background Running` | No | "Long tasks may stop when the app is backgrounded." |

#### Notes

- Accessibility: not complete until actual service connection is live, not just settings toggle.
- Battery: if system intent unavailable or OEM behavior inconsistent → manual guidance + keep skip visible.
- Overlay: hard gate in Phase 1. If later made non-critical, only this step's skip rule changes.

### 3.3 API Key Validation State Machine

#### States

- `Empty`: field blank.
- `Editing`: user entered or pasted text.
- `Validating`: network validation in progress.
- `Invalid`: provider rejected key (401/403).
- `TransientError`: network/server problem.
- `Valid`: key accepted and saved.

#### Transition table

| Current | Trigger | Guard | Next | Side effects |
|---|---|---|---|---|
| `Empty` | User types/pastes | Field not blank | `Editing` | Enable "Validate & Continue". |
| `Editing` | Tap "Validate & Continue" | Field not blank | `Validating` | Disable editing; call lightweight auth endpoint. |
| `Invalid` | User edits field | — | `Editing` | Clear inline error; keep focus. |
| `TransientError` | Tap retry | Field not blank | `Validating` | Retry validation. |
| `Validating` | Auth success | — | `Valid` | Save key; show success check. |
| `Validating` | Auth failure (401/403) | — | `Invalid` | Keep typed key; show "key rejected" error. |
| `Validating` | Network/timeout/server error | — | `TransientError` | Keep typed key; show connectivity error. |
| `Valid` | Success animation done | — | `DemoStep` | Persist; auto-advance. |

#### Contract

- Must hit real provider endpoint, not local format check.
- Save key only after successful validation.
- Keep field contents on failure (user edits, not retypes).

### 3.4 Demo State Machine

#### States

- `Ready`: explanation and CTA visible.
- `Preflight`: last-minute check that setup is still valid.
- `Running`: demo session executing.
- `ReturningToApp`: task finished while app backgrounded.
- `Success`: demo verified.
- `Failure`: demo did not finish.
- `Skipped`: user skipped.

#### Transition table

| Current | Trigger | Guard | Next | Side effects |
|---|---|---|---|---|
| `Ready` | Tap "Run Demo" | — | `Preflight` | Freeze screen; prepare demo session. |
| `Preflight` | Check passed | A11y + Overlay + API key valid | `Running` | Submit demo task. |
| `Preflight` | Check failed | Hard gate missing | first broken step | Route back with explanation banner. |
| `Running` | Task completed (app foreground) | Success criteria met | `Success` | Persist `demo = done`. |
| `Running` | Task completed (app background) | — | `ReturningToApp` | Bring MainActivity to foreground. |
| `Running` | Error/max turns/timeout/stop | — | `Failure` | Foreground app if needed; show reason. |
| `ReturningToApp` | App foregrounded | Success | `Success` | Persist and render handoff. |
| `ReturningToApp` | App foregrounded | Not success | `Failure` | Show failure state. |
| `Failure` | Tap "Try Again" | — | `Ready` | Reset transient state. |
| `Ready` / `Failure` | Tap "Skip for now" | — | `Skipped` | Persist `demo = skipped`. |
| `Success` / `Skipped` | — | — | `CompleteStep` | Auto-advance to completion. |

#### Demo verification

- Goal: `Open the Settings app`
- Success: task completes and device is visibly in Settings.
- No device state mutation. Proof, not side effects.

---

## 4. Screen Descriptions

### 4.1 Shared shell

All steps use the same full-screen scaffold:

- Top: `Set up Android Agent` title, step count (`Step 2 of 5`), linear progress bar.
- Middle: large icon, one-sentence title, "why this matters" body copy.
- Status card: `Not enabled` / `Enabled` / `Recommended` / `Validating` / etc.
- Bottom: primary CTA, optional secondary CTA, safe-area padding.

Shared behavior:
- Returning from settings → show `Checking...` first, then success or recoverable unmet state.
- Success states: green check, short text, auto-advance after ~400ms.
- Copy explains both value AND consequence before asking.

### 4.2 Accessibility screen

```
┌──────────────────────────────────────────┐
│                                          │
│  Step 1 of 5    ████░░░░░░░░░░░          │
│                                          │
│         (Shield icon)                    │
│                                          │
│  Let Android Agent control your phone    │
│                                          │
│  Android only allows trusted automation  │
│  through Accessibility. This lets the    │
│  agent read screens and perform taps     │
│  in other apps.                          │
│                                          │
│  ┌──────────────────────────────────┐    │
│  │  Status: Not enabled             │    │
│  └──────────────────────────────────┘    │
│                                          │
│      [ Open Accessibility Settings ]     │
│                                          │
└──────────────────────────────────────────┘
```

- No skip — hard gate.
- On return: if service not live → `Still off. Turn on Android Agent in the Accessibility list, then come back.`
- If toggle on but service connection delayed → stay in `Checking` until live or timeout → retry.

### 4.3 Overlay screen

```
┌──────────────────────────────────────────┐
│                                          │
│  Step 2 of 5    ████████░░░░░░░          │
│                                          │
│       (Layers icon)                      │
│                                          │
│  See controls while the agent works      │
│                                          │
│  The floating capsule shows progress     │
│  and lets you stop, take over, or        │
│  return to Android Agent.                │
│                                          │
│  ┌──────────────────────────────────┐    │
│  │  Status: Not enabled             │    │
│  └──────────────────────────────────┘    │
│                                          │
│      [ Grant Overlay Permission ]        │
│                                          │
└──────────────────────────────────────────┘
```

- No skip — hard gate.
- On return unchanged: `Overlay is still off. Without it, you won't see controls while the agent works in other apps.`

### 4.4 Battery screen

```
┌──────────────────────────────────────────┐
│                                          │
│  Step 3 of 5    ████████████░░░          │
│                                          │
│      (Battery icon)                      │
│                                          │
│  Keep long tasks alive                   │
│                                          │
│  Some phones aggressively stop           │
│  background work. Allowing unrestricted  │
│  battery use makes long tasks reliable.  │
│                                          │
│  This is optional but recommended.       │
│                                          │
│      [ Allow Background Running ]        │
│                                          │
│       Continue without this              │
│                                          │
└──────────────────────────────────────────┘
```

- Uses `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (direct dialog).
- No warning on skip — non-critical.
- OEM inconsistency → manual guidance + keep skip visible.

### 4.5 API Key screen

```
┌──────────────────────────────────────────┐
│                                          │
│  Step 4 of 5    ████████████████░        │
│                                          │
│        (Key icon)                        │
│                                          │
│     Connect your model                   │
│                                          │
│  ┌─ API Key ──────────────────────┐     │
│  │  sk-...                    👁  │     │
│  └─────────────────────────────────┘     │
│                                          │
│  ⓘ Your key is encrypted on-device.     │
│    Never sent anywhere except the        │
│    LLM provider.                         │
│                                          │
│  ✕ That key was rejected.               │  ← Error (shown only on failure)
│    Check the value and try again.        │
│                                          │
│      [ Validate & Continue ]             │
│                                          │
└──────────────────────────────────────────┘
```

- Secure text field with visibility toggle + paste affordance.
- Button disabled when field blank or validating.
- Auth failure: `That key was rejected. Check the value and try again.`
- Network failure: `We couldn't reach the model provider. Your key was not changed.`
- Success: save key, show `Key verified ✓`, advance.

### 4.6 Demo screen

**Ready**
```
┌──────────────────────────────────────────┐
│                                          │
│  Step 5 of 5    ████████████████████     │
│                                          │
│       (Rocket icon)                      │
│                                          │
│     Try a safe demo                      │
│                                          │
│  We'll open the Settings app to prove    │
│  everything works. This does not change  │
│  any device setting.                     │
│                                          │
│         [ Run Demo ]                     │
│                                          │
│        Skip for now                      │
│                                          │
└──────────────────────────────────────────┘
```

**Running**: simple progress state (`Opening Settings...`), not full chat transcript.

**Failure**:
```
┌─────────────────────────────────────┐
│  ✕ Demo didn't finish               │
│                                      │
│  The demo timed out before opening   │
│  Settings.                           │
│                                      │
│    [Try Again]     [Skip for now]    │
└─────────────────────────────────────┘
```

### 4.7 Complete screen

```
┌──────────────────────────────────────────┐
│                                          │
│              (Checkmark)                 │
│                                          │
│        You're All Set!                   │
│                                          │
│   ✓ Accessibility service               │
│   ✓ Display overlay                     │
│   ✕ Battery optimization (skipped)      │
│   ✓ API key verified                    │
│   ✓ Demo task passed                    │
│                                          │
│       [ Start Using Android Agent ]      │
│                                          │
└──────────────────────────────────────────┘
```

- Summary checklist showing actual status per step.
- Skipped items shown with ✕ and "(skipped)" — informational, not blocking.
- Tapping "Start Using" sets `onboardingCompleted = true` and navigates to chat.

---

## 5. Edge Cases

| Case | UX behavior |
|---|---|
| User opens Accessibility settings, backs out without enabling | Stay on screen, show unmet status + retry. No dead end. |
| Accessibility toggle on but service not yet connected | Stay in `Checking` until service live or timeout → retry. |
| User opens Overlay settings, returns unchanged | Stay on screen, explain consequence, keep retry. |
| Battery flow hidden or OEM-inconsistent | Generic guidance + keep `Continue without this` visible. |
| User skips Battery | Mark skipped, allow finish, no wizard replay later. |
| API key syntactically present but invalid | Keep typed key, show auth error, do not save. |
| Validation fails due to offline/timeout | Show connectivity error (not "invalid key"); allow retry with same contents. |
| Key validated but becomes invalid before demo | Demo preflight routes back to API Key step with explanation banner. |
| Required permission revoked before demo | Demo preflight routes back to first broken hard gate. |
| Demo fails/times out/user stops | Show failure state with Try Again + Skip for now. |
| App killed mid-onboarding | Resume at first incomplete step on next launch. |
| Onboarding complete, permission later revoked | Main Chat opens; targeted repair card, not wizard replay. |

---

## 6. Success Criteria

- First launch always enters the onboarding wizard.
- User completes required setup without opening settings sheet or guessing system pages.
- Every screen explains why before asking.
- No dead ends: every failure has a retry path; soft steps have skip.
- API validation separates "bad key" from "network problem."
- Demo creates one obvious first-success moment before chat.
- Once done, later launches go directly to Main Chat.

---

## 7. Out of Scope (Phase 1)

- OpenAI OAuth or account-based login
- Multi-provider or model picking inside onboarding
- OEM-specific battery education beyond simple fallback
- Full wizard replay after later permission revocation
- Replacing settings sheet for advanced configuration
- Local/on-device model setup during onboarding
- Onboarding analytics/telemetry
