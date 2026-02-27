# BrowserDraw - Cog-Tune Analysis

## Run Metadata

| Field | Value |
|---|---|
| Run ID | `aw_20260225_225734_BrowserDraw_2_0` |
| Model | qwen3.5 (qwen/qwen3.5-plus-02-15) |
| Goal | Open the file task.html in Downloads in the file manager; when prompted open it with Chrome. Then create a drawing using the three colors shown at the top and hit submit. |
| Completion | Error |
| Task Status | **failure** |
| Turns (actual) | 1 (logcat — LLM error on first turn) |
| Duration | 136s |
| Scripted Score | 0.0 |

## Root Cause

**Category: Infra / LLM Provider**

The agent failed on its very first turn with an LLM provider error: `LLM error: SseException - 200: Provider returned error`. The task requires hybrid perception mode (accessibility + screenshot) for the HTML canvas drawing, but the LLM provider (OpenRouter/qwen3.5) returned an error during streaming.

This is NOT an agent cognition issue — the agent never got to reason or act.

## Turn-by-Turn Analysis (from logcat)

| Turn | Tool | Action | Target | Result |
|------|------|--------|--------|--------|
| 1 | — | — | LLM call | SseException: Provider returned error |

## Key Observations

1. **LLM provider error** on the very first LLM call, before any tool execution.
2. The task uses `perception_mode: ACCESSIBILITY` by default but this task requires visual understanding of canvas/drawing — it likely needs hybrid mode configured via `eval/config/default.yaml` overrides.
3. BrowserDraw is fundamentally challenging for accessibility-only perception since the canvas drawing area has no meaningful accessibility nodes.
4. The 136s duration was spent waiting for the LLM to respond / retrying.

## Recommendation

1. **LLM provider resilience**: Add retry logic for transient LLM provider errors (SseException). The agent should retry the LLM call 2-3 times before giving up.
2. **Perception mode**: Ensure hybrid perception mode is configured for BrowserDraw tasks. Canvas-based HTML tasks need screenshot perception.
3. **Task capability gating**: Consider marking canvas/drawing tasks as requiring screenshot perception and skipping them when only accessibility mode is available.
