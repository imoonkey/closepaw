# VD Completion Handoff — Independent General Review

> **Historical**: HIGH #1 (viewer auto-finish closes opened viewer) and
> MEDIUM #1 (stale viewer CTA) were addressed by removing the
> `View virtual screen` CTA entirely in commit `2dfc19bd` (see
> `design_codex.md` update). MEDIUM #2 (debug-run.sh --local) was fixed in
> `43956bbe`. LOW #1 (button overflow) is no longer relevant since only one
> button ships. MEDIUM #3 (test coverage) is also moot — the
> CTA-lifecycle tests it called for tracked code that no longer exists.

## Scope
- Branch / base reviewed: `task/vd-completion-handoff`, committed `HEAD` `2eb50f4e`, using the requested local `main..HEAD` range with local `main` at `132de064`.
- Commits reviewed: `9be6c778`, `d0f6ac99`, `7b2a109d`, `44f3323f`, `e0cb3b2b`, `5ae5012c`, `fe215e94`, `2eb50f4e`.
- Files reviewed: changed protocol/session/platform/app/chat production files, new JVM tests, `scripts/debug-run.sh`, and `scripts/setup.sh`.
- Approach: read `design_codex.md`, enumerated `git log --oneline main..HEAD`, inspected the committed diff and line-numbered committed files, then read `codex_code_review.md` and `qa_report.md` only after forming findings. I did not review QA image binaries beyond their presence in the diff.
- Note: uncommitted production edits appeared in the worktree during this review; findings and citations below refer to the committed `HEAD` tree, not those unstaged edits.

## Findings

### Critical
No findings.

### High
1. `View virtual screen` can open and immediately close after the 3-second Done auto-hide, even while the VD platform is still alive.
   - Files: `app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:99`, `app/src/main/kotlin/ai/closepaw/app/MainActivity.kt:255`, `app/src/main/kotlin/ai/closepaw/app/MainActivity.kt:257`, `app/src/main/kotlin/ai/closepaw/ui/viewer/VirtualDisplayViewerActivity.kt:98`, `app/src/main/kotlin/ai/closepaw/ui/viewer/VirtualDisplayViewerActivity.kt:103`, `app/src/main/kotlin/ai/closepaw/ui/overlay/CapsuleStateHolder.kt:256`, `app/src/main/kotlin/ai/closepaw/ui/overlay/CapsuleStateHolder.kt:287`, `app/src/main/kotlin/ai/closepaw/app/OverlayLocationPolicy.kt:120`, `app/src/main/kotlin/ai/closepaw/app/ServiceOverlayController.kt:208`.
   - Why it matters: the CTA only checks that `AgentService.instance?.isVirtualDisplayViewerAvailable()` is true before starting the viewer. That check means the VD arbiter is Running, but it does not account for the existing viewer auto-finish policy. A successful task enters `CapsuleMode.Done` and schedules auto-hide; after 3 seconds it becomes `Hidden`. Opening the viewer then calls `onViewerOpened()`, and `VirtualDisplayViewerActivity.onStart()` immediately finishes when `shouldFinishViewerNow()` returns true for VD viewer + no active task + Hidden mode. This makes a core CTA behave like a no-op in a normal post-completion window, despite the VD still being alive until the 5-minute idle timeout.
   - Suggested fix: distinguish explicit user-opened viewer handoff from the automatic post-task viewer path. For example, launch the CTA viewer with an intent extra that suppresses idle auto-finish while `isVirtualDisplayViewerAvailable()` remains true, and keep the old finish behavior for non-explicit viewer openings.

### Medium
1. The viewer CTA remains visible from stale row metadata after the session tears down, so the UI can show a button that only logs and does nothing.
   - Files: `app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:495`, `app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:69`, `app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:99`, `app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:709`, `app/src/main/kotlin/ai/closepaw/ui/chat/ChatEventReducer.kt:41`, `app/src/main/kotlin/ai/closepaw/app/MainActivity.kt:256`, `app/src/main/kotlin/ai/closepaw/app/MainActivity.kt:259`.
   - Why it matters: `virtualDisplayAvailable` is captured once at `TaskCompleted`, and `CompletionHandoffCtaRow` keeps rendering from that snapshot. When idle timeout later shuts down the session/platform, the chat reducer ignores `SessionCompleted`, so the row is not updated. The click handler revalidates and suppresses launch, which avoids a dead viewer, but the user still sees a stale `View virtual screen` button that does nothing.
   - Suggested fix: make viewer CTA visibility live, not just click-time guarded. Plumb a current viewer-availability state into the row, clear/disable handoff CTAs on session completion, or show disabled feedback instead of a clickable stale CTA.

2. `debug-run.sh --local` can now fail before running because the new setup preflight does not inherit the script's local backend selection.
   - Files: `scripts/debug-run.sh:203`, `scripts/debug-run.sh:205`, `scripts/debug-run.sh:311`, `scripts/setup.sh:109`, `scripts/setup.sh:113`, `scripts/setup.sh:115`.
   - Why it matters: `debug-run.sh --local` sets `LLM_BACKEND="local"` as a shell variable, but it does not export it or pass it to `setup.sh`. The new preflight invokes `setup.sh`, which defaults `LLM_BACKEND` back to `openai` and rejects runs without an `OPENAI_API_KEY`. That breaks the documented `./scripts/debug-run.sh --local "goal"` path before the debug intent is ever sent.
   - Suggested fix: invoke setup as `LLM_BACKEND="$LLM_BACKEND" "$SCRIPT_DIR/setup.sh"` or `export LLM_BACKEND` before the preflight. Add a shell regression check or documented manual test for `--local` without an OpenAI key.

3. Tests do not cover the production viewer-click lifecycle that causes the High issue.
   - Files: `app/src/test/kotlin/ai/closepaw/app/AgentServiceViewerBridgeAvailabilityTest.kt:10`, `app/src/test/kotlin/ai/closepaw/app/AgentServiceViewerBridgeAvailabilityTest.kt:30`, `app/src/test/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaVisibilityTest.kt:26`, `app/src/main/kotlin/ai/closepaw/ui/viewer/VirtualDisplayViewerActivity.kt:103`, `app/src/main/kotlin/ai/closepaw/app/ServiceOverlayController.kt:208`.
   - Why it matters: the new tests prove helper visibility and bridge liveness, but none render/click the real `View virtual screen` CTA and then exercise the viewer activity's `shouldFinishViewerNow()` path after `CapsuleMode.Done` auto-hides. This is why the current suite can pass while the explicit viewer CTA is time-limited or immediately closing in production.
   - Suggested fix: add a focused Robolectric/unit test around the viewer-open path, or extract the explicit-viewer keep-open decision into a pure function and cover `Done -> Hidden -> explicit CTA open` separately from the old automatic idle-finish path.

### Low / Nits
1. The two CTA buttons can overflow on narrow screens with ordinary long app labels.
   - Files: `app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:78`, `app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:88`, `app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:90`, `app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:94`, `app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:102`, `app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:104`, `app/src/main/kotlin/ai/closepaw/ui/chat/components/CompletionHandoffCtaRow.kt:108`.
   - Why it matters: the row is a single non-wrapping `Row`; both buttons use fixed 32dp height, horizontal padding, and unconstrained text such as `Open $label` plus `View virtual screen`. Labels like "Google Play Store" can make the pair exceed a typical phone chat width, leading to clipping or off-screen content.
   - Suggested fix: use `FlowRow`, stack buttons on compact widths, or constrain the app label with ellipsis while preserving the viewer button.

## Coverage Summary
- Spent most time on event/model plumbing, `AgentSession.buildHandoffIfVd`, VD platform liveness, `AgentService.instance` / viewer bridge wiring, `MainActivity` CTA callbacks, Compose row rendering, New Session reset flow, and the two modified scripts.
- Reviewed the new tests for session emission, reducer propagation, CTA visibility helper behavior, bridge liveness, and New Session reset behavior.
- Confirmed `New Session` itself does not read `CompletionHandoff` or call app/viewer handoff callbacks in the committed code path.
- Intentionally skipped full visual QA of the included PNG evidence and did not review unrelated unstaged production edits that appeared after the initial clean status.
- Verification requested by acceptance: `./gradlew :app:testDebugUnitTest` exit 0 in a clean detached worktree at committed `HEAD` (`BUILD SUCCESSFUL in 2s`, 31 actionable tasks: 1 executed, 30 up-to-date). The first clean-worktree invocation failed while storing Gradle configuration cache for `:app:licenseDebugReport`; rerunning the same exact command passed.

## Comparison with prior reviews (read AFTER forming your own opinion)
- Overlap: prior `codex_code_review.md` had already identified stale viewer metadata and weak UI-click coverage. The branch fixed part of that by adding tap-time viewer liveness revalidation and blocked-package capture filtering, and `qa_report.md` confirms those fixes.
- New/different findings: this review found that tap-time liveness is still insufficient because the existing `VirtualDisplayViewerActivity` idle-finish path closes the explicit CTA viewer after the capsule auto-hides, while the VD platform is still Running. The prior QA only documented a stale/dead-platform tap being suppressed after `STOP_AGENT`; it did not validate opening a live viewer after the normal Done auto-hide window.
- Tooling: prior reports did not cover the new `scripts/debug-run.sh` preflight interaction with `--local`; this review found that regression.

## Verdict
block
