# UX Review Checks

Use this checklist while reading `report.md` and step artifacts.

## 1. Interaction correctness

- Does each tap trigger the intended state change?
- Is text input accepted and visible where expected?
- Do disabled/unavailable actions provide clear feedback?

## 2. Transition quality

- Does the app transition to the expected screen within acceptable delay?
- Are there visual jumps, blank frames, or stale state?
- Does back navigation return to the expected state?

## 3. State clarity

- Can user identify current state without ambiguity?
- Are labels/button semantics consistent across states?
- Are Smart Capsule state changes understandable after actions like `接管` / `补充`?

## 4. Error handling UX

- On action failure, is error message visible and actionable?
- Is user blocked without recovery path?
- Are retry/recover controls obvious?

## 5. Severity rubric

- P0: Crash, freeze, cannot proceed.
- P1: Main flow broken or misleading enough to fail task completion.
- P2: Noticeable friction/confusion, workaround exists.
- P3: Minor polish/consistency issue.

## 6. Report template

Use this format for each issue:

- Title
- Severity
- Step index/name
- Expected behavior
- Actual behavior
- Evidence paths (`.png`, `.xml`, `.txt`)
- Suggested fix direction
