# VD Completion Handoff — Implementation Summary

**Status:** Shipped on `task/vd-completion-handoff` (PR #40), `9be6c778..f66d14e9`.

## What shipped

A single **Open <App>** CTA rendered under the final answer of a chat row when:
- The task completed on a `VirtualDisplayPlatform`, AND
- `AgentSession.buildHandoffIfVd()` captured a non-null foreground package (after dropping self/SystemUI/`AppTier.BLOCKED`), AND
- `PackageManager.getLaunchIntentForPackage(pkg)` resolves on the real display.

Tap launches the package's launcher intent with `FLAG_ACTIVITY_NEW_TASK` on display 0. `TaskCompleted` itself starts nothing; only the explicit CTA does. `New Session` clears the chat and drops any handoff-bearing row.

## Key code

| Layer | File | Role |
|---|---|---|
| Protocol | `protocol/CompletionHandoff.kt` | `data class CompletionHandoff(appPackage, appLabel)` + `buildVdCompletionHandoff` helper (capture-time filtering) |
| Protocol | `protocol/TaskLifecycleEvents.kt` | `TaskCompleted` carries optional `handoff` (null on non-VD) |
| Session | `session/AgentSession.kt` | `buildHandoffIfVd()` populates handoff when active platform is VD |
| Chat model | `ui/chat/model/ChatMessage.kt` | `ChatMessage.Agent.handoff: CompletionHandoff?` |
| Chat reducer | `ui/chat/ChatEventReducer.kt` + `ChatViewModel.kt` | Threads `TaskCompleted.handoff` onto the completed agent row |
| UI | `ui/chat/components/CompletionHandoffCtaRow.kt` | Composable + pure `shouldShowOpenAppCta` guard |
| Routing | `app/MainActivity.kt` | `onOpenApp(pkg) { startActivity(getLaunchIntentForPackage(pkg)?.addFlags(NEW_TASK)) }` |

## Trajectory (what reviews removed)

The initial design (`design_codex.md`) included a second **View virtual screen** CTA. Three rounds of codex review surfaced HIGH issues with it (stale-row launches dead viewer, auto-finish closes opened viewer post-`Done`); on-device QA then found that even after those fixes the post-completion viewer had no working takeover, no edge glow, and no exit. Commit `2dfc19bd` deleted the CTA + all CTA-specific plumbing (`EXTRA_KEEP_VIEWER_OPEN_WHEN_IDLE`, `canOpenCompletionViewer`, `_virtualDisplayViewerAvailable`, etc.). A cleanup re-review (`codex_cleanup_rereview.md`) verified the dead viewer-availability data path was also pruned (field, bridge API, test).

## Ancillary fixes (folded into this PR)

- **`AppSettingsStore` persists `openai_base_url`** — was in-memory only, lost on every process restart; QA caught it recurring as "LLM error: quota" against the wrong endpoint. (`f3eb7e92`)
- **`debug-run.sh` runs `setup.sh` as preflight** — accessibility/overlay/Shizuku state kept dropping between runs; preflight is idempotent. Also defines `err()` (was missing) and passes `LLM_BACKEND` through. (`2eb50f4e`, `43956bbe`)
- **`setup.sh` captures caller-set `LLM_BACKEND` before sourcing `.env`** so `debug-run.sh --local` no longer trips the OpenAI API-key gate when `.env` declares `openai`. (`70102ab0`)
- **`setup.sh`'s `assembleDebug`** now passes `--no-configuration-cache` to dodge a pre-existing `licenseDebugReport` config-cache bug. (`2eb50f4e`)

## Verification

- **Unit tests**: `./gradlew :app:testDebugUnitTest` — 183 suites / 1734 tests / 0 failures.
- **Real device** (Tecno P0110, Android 16, Shizuku-granted):
  - VD task completes → only `Open <App>` CTA renders (`qa_evidence/post_cta_removal.png`).
  - Tap `Open <App>` → launcher opens on display 0; `AgentService` logs `Window changed: pkg=com.android.launcher3 ... to=OTHER_APP`.
  - `New Session` → empty chat, no app launches.
  - `debug-run.sh --local` preflight no longer trips the OpenAI gate (verified with `env LLM_BACKEND=local` + `.env LLM_BACKEND=openai` → resolves to `local`).

## Reports and artifacts

- `design_codex.md` — current design (with Update banner noting the viewer-CTA removal)
- `codex_code_review.md`, `codex_general_review.md`, `view_vd_tap_bug.md`, `qa_report.md` — historical artifacts with Historical banners
- `codex_cleanup_review.md`, `codex_cleanup_rereview.md` — cleanup audit + ship verdict
- `qa_evidence/` — on-device screenshots from QA

## Follow-ups

- **`vd-runtime-boundary`** (still blocked) extracts a runtime/session boundary and adds `IdleRetained` so the VD survives session shutdown. When that lands, the **View virtual screen** CTA can return — add the `virtualDisplayAvailable` field back to `CompletionHandoff` and re-render the second button.
