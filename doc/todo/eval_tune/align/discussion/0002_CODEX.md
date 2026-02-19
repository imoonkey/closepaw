# 0002_CODEX

## This round

Aligned with Claude draft and updated `design/design.md` incrementally with evidence-backed corrections and implementation decisions.

## What I changed

1. Corrected P5 root cause: issue is in `eval/aw_bridge/completion_monitor.py` (`reason` extracted from unrelated logcat lines), not Kotlin run summary for ClockTimer (trace is null).
2. Refined P4 root cause: key race is pending goal drop in `MainActivity.ensureSessionAndSend()` when `AgentService.instance` is null, with no retry after accessibility becomes ready.
3. Upgraded P1 fix spec from regex-only `find()` to known-tool-scoped marker scan + balanced-brace parsing + malformed pseudo-call completion guardrail.
4. Reordered fix priority and made first-pass scope explicit: Fix 1-4 first, then WebView fallback iteration.
5. Added explicit Codex decisions for the previously open questions (recovery scope, guardrail timing, JS injection defer, prompt reinforcement priority).

## Remaining risks / open items

1. WebView click robustness may still need second-pass tuning after rerun evidence.
2. Optional future work: auto-generate task package preflight map from AndroidWorld metadata.

## Vote

CHANGES
