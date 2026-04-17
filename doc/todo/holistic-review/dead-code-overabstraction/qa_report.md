# DCO QA Report

**Task**: `dco-qa-validation`
**Date**: 2026-04-16
**Device**: EP0110MZ0BC101266W (nubia P0110, SDK 36)
**Commits under test**: `43665d44..HEAD` (Phase 1 – Phase 4 + Codex review)
**Main model**: gpt-5.4 / **Executor**: gpt-5.4

## Summary

**ALL SCENARIOS PASS.** No user-facing regressions detected from the dead-code-overabstraction refactor.

| # | Scenario | Result | Evidence |
|---|----------|--------|----------|
| 1 | `./gradlew assembleDebug` | ✅ PASS (exit 0) | — |
| 2 | `./gradlew test` | ✅ PASS (exit 0) | — |
| 3 | `./gradlew lint` | ⚠ PRE-EXISTING baseline (2 errors, 62 warnings — not DCO-related) | see "Lint baseline" below |
| A | Onboarding fresh-install | ✅ PASS | `qa_evidence/04_onboarding_fresh.png` |
| B | Settings UI | ✅ PASS | `qa_evidence/03_settings.png` |
| C | PRO mode delegation (schema w/o `agent_name`) | ✅ PASS | trace `run_20260416_203634` |
| D | Session history | ✅ PASS | `qa_evidence/02_drawer_opened.png` |
| E | Normal single-turn task | ✅ PASS (GoalAchieved) | trace `run_20260416_203613` |
| F | Logcat crash check | ✅ PASS (no `androidagent` fatals) | — |

## Build / Test / Lint

- `assembleDebug` — BUILD SUCCESSFUL (exit 0).
- `test` — BUILD SUCCESSFUL (exit 0).
- `lint` — 2 errors / 62 warnings. Both errors are `NewApi` in `ServiceOverlayController.kt:333` (`PackageManager.getApplicationInfo` API 33 call with minSdk 31). **Pre-existing baseline** — file was not touched by `43665d44..HEAD` (confirmed via `git diff --stat`). No new warnings introduced by DCO.

## Scenario A — Onboarding Flow (DI validation)

- Cleared app data (`pm clear`), re-launched `MainActivity`.
- Screenshot shows **Step 1 of 5** "Let Android Agent control your phone" with progress bar — rendered without crash.
- `OnboardingDemoController` is constructed at MainActivity.onCreate when `onboardingRequired=true` (`MainActivity.kt:156-159`). If the constructor-injection refactor had broken wiring, this path would have thrown on launch.
- No `FATAL EXCEPTION` from `moonkey/androidagent` in logcat during fresh-install boot.
- App restored via `scripts/setup.sh`.

## Scenario B — Settings UI

Drawer → Settings opens a bottom-sheet with all three sections intact:
- **LLM & Authentication** (GPT-5.4 · OAuth)
- **Agent Behavior** (Basic · 20 turns · Accessibility)
- **Permissions & Advanced** (All granted · Debug on)

No missing sections from Phase-1 composable deletions. Dismisses cleanly via back.

## Scenario C — PRO Mode Delegation (agent_name removed)

Trace `debug-output/run_20260416_203634` (goal: "Open settings and tell me the Android version, then go back home"):

```
Tool call args for delegate_task (turn 3):
{
  "agent_thought": "...",
  "query": "Tap the back button in the top-left corner once.",
  "current_subgoal": "...",
  "important_notes": [...]
}
```

**No `agent_name` field present** — Phase-4 plumbing removal validated. Tool was accepted by `ToolRouter`, reached `Policy decision for delegate_task: AskUser(reason=..., appTier=CAUTIOUS)` state — i.e. schema parsed and policy engine ran. Final `success=false` was due to orthogonal approval-timeout (no human at the keyboard), not DCO regression.

A second PRO run (`run_20260416_203912`, "Open the Clock app and tell me what you see") completed with `reason=GoalAchieved` across 3 turns using `open_app` + `complete_task`.

## Scenario D — Session History

Drawer shows 6 prior sessions with titles, relative timestamps, and delete icons. List renders after `SessionHistoryManager` API-surface shrink (Phase-2). Tapping opens a session without error.

## Scenario E — Normal Single-Turn Task

Trace `debug-output/run_20260416_203613` (goal: "What is the current time?"):

```
reason: GoalAchieved
turns_executed: 1
complete_task args: { "status": "success", "answer": "The current time is 8:37 PM." }
```

`CompletionReason=GoalAchieved` — no regression from `ToolCallResult`/`ToolExecutionResult` payload removal.

## Scenario F — Logcat Crash Check

For each of the 3 debug runs and the fresh-install reboot:
```
grep -iE "fatal exception|androidruntime.*fatal" logcat_full.log | grep -i "moonkey\|androidagent"
→ (empty)
```
`adb logcat -d | grep -iE "fatal|crash"` returned no `moonkey/androidagent` matches.

## Notes

- First attempted Basic run with `minimax-m2.5` failed with OpenRouter 402 credit limit — unrelated to DCO; retried with `gpt-5.4` (as confirmed by user) and passed.
- All evidence under `doc/todo/holistic-review/dead-code-overabstraction/qa_evidence/` and `debug-output/run_20260416_203{613,634,912}/`.

## Verdict

**APPROVE for merge.** Dead-code-overabstraction refactor does not regress any validated user-facing behavior.
