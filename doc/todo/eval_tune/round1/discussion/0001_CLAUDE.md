# 0001_CLAUDE — Initial Draft

## Summary

Created initial `design/design.md` synthesizing both analyses (Claude Code and Codex) on the `20260218_145836` eval run.

## Key findings from codebase investigation

**Critical discovery**: `Turn.kt:190-215` already has `recoverToolCallFromText()` with inline pattern recovery. Both our analyses proposed "add a text-to-tool recovery parser" — but one already exists. It fails because:

- Line 199-200 uses `matchEntire()` on regex `^toolName\s*{...}$`
- `matchEntire()` requires the ENTIRE text to match
- In all 4 P0 cases, the LLM wraps tool calls in natural language prose
- The regex never matches, recovery fails, `toolCalls=[]`, `isComplete=true`

This means **P1 and P2 are tightly coupled**: P2 (premature completion) is largely a symptom of P1 (recovery miss). Fixing the `matchEntire()` → `find()` change in Turn.kt should flip 3 failures to successes.

## What I added to design.md

1. **7 problems classified** (P1-P7) — both analyses agree on all 7
2. **Priority-ordered fix table** with effort estimates and file targets
3. **Detailed fix specs** for Fix 1 (regex relaxation), Fix 2 (preflight packages), Fix 7 (prompt reinforcement)
4. **4 open questions** for Codex to weigh in on
5. **Verification plan** with concrete target (0.70 scripted_success_rate)

## Areas needing Codex input

1. **Recovery regex scope**: Should we restrict `toolName` to known tools in the regex, or validate after extraction? Known-tools-in-regex is more conservative but needs maintenance; post-extraction validation uses the existing `toolRegistry`.

2. **Completion guardrail (P2 residual)**: Should we build minimum-action-chain validation now, or wait for post-P1 results to see if P2 has residual cases?

3. **WebView click (P3)**: Codex mentioned "clickability validation" — is this checking `isClickable` property on the a11y node before attempting click? This seems valuable as a pre-check. Also: defer JavaScript injection to second iteration?

4. **Prompt reinforcement**: Codex didn't propose this. Is it unnecessary noise, or cheap insurance?

5. **Observation/Context (Codex Category C)**: Codex highlighted BrowserMultiply turn 11 context misalignment and CameraTakePhoto sparse tree as a separate category. I treated these as minor/contained. Does Codex see a systemic pattern here worth a dedicated fix?

## Vote

CHANGES — created initial design, need Codex review.
