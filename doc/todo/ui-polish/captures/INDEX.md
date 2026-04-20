# UI Polish Captures — Stage 1 Index

Captured 2026-04-20 against installed debug build on device EP0110MZ0BC101266W (1264x2800, 560dpi).

## Onboarding (`onboarding/`)

| File | What | How triggered |
|---|---|---|
| `onboarding_step1_welcome.png` | Step 1 of 5 — "Let ClosePaw control your phone" (a11y permission). Status: Enabled. Continue button. | Fresh app launch (no completion flag). Backed up from step 4. |
| `onboarding_step2.png` | Step 2 of 5 — "See controls while the agent works" (overlay permission). Status: Enabled. | Back from step 3. |
| `onboarding_step3.png` | Step 3 of 5 — "Keep long tasks alive" (battery exemption). Status: Not enabled. Allow / Continue without this. | Back from step 4. |
| `onboarding_step4_provider_select.png` | Step 4 of 5 — "Connect your model" (default state, OpenAI selected). Sign in with OpenAI primary, "or enter API key manually" link. | Initial app launch. |
| `onboarding_step4_openai_selected.png` | Step 4 — same as above (OpenAI chip explicit-selected variant). | Tap OpenAI chip. |
| `onboarding_step4_openrouter_selected.png` | Step 4 — OpenRouter chip selected. API Key entry shown immediately. | Tap OpenRouter chip. |
| `onboarding_step4_openai_manual_key.png` | Step 4 OpenAI — after tapping "or enter API key manually". API Key field shown + "or sign in with OpenAI" link below. | Tap link. |
| `onboarding_step4_key_entered.png` | Step 4 — masked key in field, "Validate & Continue" enabled, primary color. | Type key in field. |
| `onboarding_step4_validation_error.png` | Step 4 error — "Provider configuration issue. Please try again." with Retry button. | Tap Validate with current .env key (network/key validation failed). |
| `onboarding_step4_landscape.png` | Step 4 in landscape orientation. Demonstrates layout reflow. | Rotate device while in onboarding. |

**Note:** Only OpenAI + OpenRouter chips visible in onboarding step 4 (no Novita chip — Novita appears only inside the post-onboarding LLM Settings page). **Step 5 NOT REACHED** — validation kept failing for both OpenAI and OpenRouter keys from `.env` (likely network/key issue). Bypassed onboarding for downstream captures via direct write to `onboarding_completed=true` in `shared_prefs/onboarding_prefs.xml`.

## Chat (`chat/`)

| File | What | How triggered |
|---|---|---|
| `chat_empty_portrait.png` | Empty fresh-session state with paw logo, "What can I help you with?", 3 suggestion chips, input. | New conversation via "+" button. |
| `chat_empty_landscape.png` | Same empty state in landscape — but Setup Issue banner re-appeared (a11y service was reset by rotation). | Rotate to landscape. |
| `chat_setup_issue_banner_landscape.png` | Same as above, kept as a banner-state capture. Shows "Setup Issue: Accessibility service is disabled" with Fix button in landscape. | (See `chat_empty_landscape.png`.) |
| `chat_live_running.png` | LIVE state mid-task: user msg + typing dots + bottom panel with task title + Takeover/Stop + "Got ideas? Add a note…" supplement input. | `./scripts/debug-run.sh --basic "Open Settings then go back to home"` (captured ~3s in). |
| `chat_running_supplement_loading.png` | Live with reasoning trail visible mid-task ("Posting a tweet is an irreversible…", action steps). | Mid-run capture during X tweet attempt. |
| `chat_supplement_action_needed.png` | **Supplement state**: "Action needed — X is not logged in. Please sign in to your X account…" with Done / Stop buttons. Shows agent reasoning trail above. | Triggered naturally by `./scripts/debug-run.sh --basic "Send a tweet with the message 'hello world'"` — agent paused for manual login. |
| `chat_error_after_stop.png` | After tapping Stop on supplement: "Stopping…" button state, supplement panel still visible. | Tap Stop on Action needed capsule. |
| `chat_after_stopped.png` | Final state after Stop completes: completed-style row + clean chat. | Force-stop ai.closepaw + relaunch. |
| `chat_completed_collapsed.png` | Task complete, single collapsed row "Open Settings · 2 actions · 8.3s ✓". | After `debug-run.sh "Open Settings"` finished + return to ClosePaw. |
| `chat_completed_expanded.png` | Same task expanded — full reasoning trail (Open the Settings app…, Open app, Complete task, Answer). | Tap collapsed row. |

**Note:** No clean landscape chat capture without Setup Issue banner. The system Settings rotation lock and the rotation-triggered activity recreation conspire to disable a11y when rotating; banner is intrinsic to that state.

## Capsule (`capsule/`)

| File | What | How triggered |
|---|---|---|
| `agent_in_other_app_no_capsule_overlay_1.png` (and `_2`, `_3`) | While agent is operating in system Settings during a basic-mode task. **No capsule overlay drawn.** | `debug-run.sh --basic "Open Settings then go back to home"`, captured during execution. |
| `agent_completed_returned_home_no_overlay.png` | Home screen post-completion. No overlay visible. | After basic task finishes. |
| `vd_mode_agent_in_other_app_no_overlay_1.png` (and `_2`, `_3`) | Same with Display Mode = Virtual Display set in Permissions & Advanced. **Still no overlay.** | Switch Display Mode → run `debug-run.sh --basic`. |
| `vd_mode_agent_completed_no_overlay.png` | Post-completion in VD-mode setting. | (As above.) |
| `vd_viewer_activity_empty.png` | `VirtualDisplayViewerActivity` launched directly via `am start ai.closepaw/.ui.viewer.VirtualDisplayViewerActivity`. Black screen — no active session. | Direct intent. |
| `capsule_vd_running_t1.png`–`t4.png`, `capsule_vd_running_complete.png` | Run with `./scripts/debug-run.sh --vd …`. Live chat shows reasoning trail; agent navigates in system Settings. **No capsule overlay observed.** | `debug-run.sh --vd "Open Settings then return home"`. |

**Capsule limitation:** I could not observe the floating capsule overlay drawn over other apps in any of these captures. Per source (`CapsuleOverlayHost.kt`, `VirtualDisplayViewerActivity.kt:79-87`), the overlay is shown when `VirtualDisplayViewerActivity.onStart` fires + the Status Island is hidden; otherwise the Island shows. Neither was visible in screenshots. The user noted the capsule is reachable via an "eye icon" while a task runs — I was unable to locate that affordance via UI dump (no `content-desc` matching eye/view/virtual). Likely needs `/ux-visual-debug` skill or explicit interaction not yet identified. **States not captured: Running, Waiting/Takeover, Complete, Error capsule overlay variants** — would need a working VD viewer entry point.

## Settings (`settings/`)

| File | What | How triggered |
|---|---|---|
| `settings_home.png` | Settings bottom-sheet home — 3 entries: LLM & Authentication, Agent Behavior, Permissions & Advanced. Version 1.0 (1) footer. | Drawer → Settings. |
| `settings_llm_authentication.png` | LLM sub-page, **API Key tab** active. Provider chips OpenAI/OpenRouter/Novita. Cloud Model dropdown (GPT-5.4). Masked API Key field with eye toggle. | Tap LLM & Authentication. |
| `settings_llm_signin_tab.png` | LLM sub-page **Sign In tab** — Cloud Model "Select a model" placeholder, Authentication "Not signed in", Sign in with OpenAI button. | Tap Sign In tab. |
| `settings_agent_behavior.png` | Agent Behavior sub-page — Max Turns (20 turns dropdown), Execution Mode (Basic Standalone), Perception Mode segmented control (Accessibility Only / Hybrid / Screenshot Only). | Tap Agent Behavior. |
| `settings_permissions_advanced.png` | Permissions & Advanced sub-page — Accessibility Service Enabled, Overlay Permission Enabled, Display Mode (Accessibility / Virtual Display radio, Shizuku ready), Debug Mode toggle ON, Session Traces toggle OFF, Clear Traces / Clear Session History buttons. | Tap Permissions & Advanced. |
| `settings_display_mode_virtual_display.png` | Permissions sub-page with Virtual Display selected (left radio inactive, right active). | Tap Virtual Display radio. |
| `settings_permissions_debug_off.png` | Debug Mode toggle in OFF state (was ON by default). | Tap Debug Mode switch. |
| `settings_permissions_session_traces_on.png` | Session Traces toggle in ON state (was OFF by default). | Tap Session Traces switch. |

**Note:** "Local" tab in LLM sub-page wouldn't switch on tap (possibly disabled). Spec mentioned "Display Mode" as separate page — it's actually a section inside Permissions & Advanced.

## Navigation (`navigation/`)

| File | What | How triggered |
|---|---|---|
| `nav_drawer_open.png` | Sessions drawer open. New Conversation button. Recent sessions list (Open Settings, noop, Check my unread emails, Search for nearby restaurants…). Settings entry at bottom with "gpt-5.4 · v1.0" tagline. | Tap hamburger top-left. |

(Drawer + sessions list + settings entry all visible in single screenshot; no separate captures needed.)

## Coverage Summary

| Category | Captured | Missing / Limitations |
|---|---|---|
| Onboarding | Steps 1–4 (multiple variants of step 4) + landscape + validation error | **Step 5 not reached** (validation failing for available API keys) |
| Chat | Empty (portrait+landscape), live, completed (collapsed/expanded), supplement (Action needed), error/stopped | No clean landscape without Setup Issue banner (state coupling with rotation→a11y reset) |
| Capsule | None of the overlay states | Eye-icon entry path to overlay not located; overlay never drew over other apps in observed runs |
| Settings | Home + 3 sub-pages + Display Mode + 3 toggle/radio variants + 1 LLM tab variant | LLM "Local" tab wouldn't activate |
| Navigation | Drawer + sessions list + settings entry (one screenshot covers all) | — |

## Bonus Bugs Discovered During Capture

Two real UX bugs surfaced and were investigated:

1. **Stuck supplement capsule on "Done":** Tapping Done in the Action-needed capsule does nothing visually. Root cause: `ChatScreen` `onUserResponse` path bypasses `CapsuleStateHolder.onUserResponseSent(callId)` (overlay path calls it; chat path doesn't). Fix: add `onUserResponseSent` hook to `CapsuleBinding` and wire in `ChatScreen.kt:153` mirroring the Stop pattern. Full diagnosis with file:line refs in conversation log.
2. **Stale Setup Issue banner after permission grant:** After tapping Fix → granting a11y in system Settings → returning to ClosePaw, banner stays. Root cause: `MainActivity.deriveRepairModel()` is a plain function call inside `setContent`, reading `AgentService.instance != null` etc. one-shot. `onResume` doesn't recompute. Fix: hold `repairModel` in `mutableStateOf`, recompute in `onResume`. Full diagnosis in conversation log.
