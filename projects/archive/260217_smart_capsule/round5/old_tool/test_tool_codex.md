# Smart Capsule v2 Test Tool Assessment (Codex)

Date: 2026-02-13  
Scope: `/ux-visual-debug` skill vs current manual workflow

## 1. Your Current Manual Requirement

You currently do:

1. Start agent run:
   - `./scripts/setup.sh && ./scripts/debug-run.sh --basic "play a [random singer] song on youtube"`
   - or `--basic --vd`
2. During active run, manually tap:
   - Smart Capsule buttons
   - Status Island
   - Viewer/nav related controls
3. Judge whether behavior is correct.

## 2. Can `/ux-visual-debug` satisfy this today?

Short answer: `Partially yes`.

- It can already automate the skeleton of your workflow.
- It is not yet robust enough for stable, high-confidence regression across all Smart Capsule v2 flows without some upgrades.

## 3. Capability Fit Matrix

| Requirement | Current support | Notes |
|---|---|---|
| Run UX steps via ADB | Yes | `ux_runner_core.py` supports tap/type/assert/screenshot/dump_ui |
| Run with active agent execution | Yes | linked mode via `--agent-goal` + `--agent-link-mode parallel|serial` |
| Pass debug-run args (`--basic`, `--vd`) | Yes | use repeated `--agent-debug-arg` |
| Capture evidence each step | Yes | png + xml + visible text + report |
| Tap capsule/island controls | Partial | depends on selector stability and timing |
| Validate state transitions reliably | Partial | only text-based asserts; no explicit wait-until-state primitive |
| Random singer per run | No (built-in) | needs external runner/template variable strategy |

## 4. Current Gaps Blocking Full Automation

## 4.1 Flakiness from fixed waits (P0)

Current runner has `wait(ms)` but no `wait_for_text/desc` with timeout.  
For async agent behavior, fixed sleep is unstable and causes false fails.

## 4.2 Selector stability for overlay/island (P0)

- Capsule controls mostly rely on displayed text/icons; dynamic or truncated text can break taps.
- `StatusIslandManager` lacks stable `contentDescription`/ID-style selector, making deterministic targeting harder.

## 4.3 Reference scenarios are stale vs current UI language (P0)

Skill references still use `接管/补充` examples, while current Smart Capsule UI is English (`Takeover`, `Add note`, etc.).  
Out-of-box scenarios are likely to fail on latest build.

## 4.4 No built-in condition assertions for mode-level semantics (P1)

You need to verify `Running -> Takeover -> Running`, `WaitingForInput` exits, `Done -> Hidden`.  
Tool currently checks visible text only, not explicit state markers.

## 4.5 Parallel mode ADB contention risk (P1)

`debug-run.sh` and UX runner both capture screenshots/dumps. In parallel, this can occasionally cause timing contention and transient failures.

## 5. Conclusion

`ux-visual-debug` is good enough to start automating smoke tests now, but not yet enough to fully replace your manual Smart Capsule v2 regression loop.

Practical recommendation:

- Use it immediately for `P0 smoke` coverage.
- Implement the improvements below to graduate to stable full-flow automation.

## 6. Improvements Needed (Priority Ordered)

## 6.1 P0 (must do first)

1. Update scenario references to current English UI labels.
2. Add wait-until actions in runner:
   - `wait_for_text`
   - `wait_for_desc`
   - (optional) `wait_for_not_text`
3. Add stable accessibility selectors in app UI:
   - status island root content description (for deterministic tap)
   - key capsule controls have stable content descriptions independent of displayed text

## 6.2 P1 (strongly recommended)

1. Add retry wrapper for tap actions (`retries`, `retry_interval_ms`).
2. Add `assert_any_text`/`assert_contains_all` helpers for more resilient checks.
3. Add optional `--agent-start-delay-ms` profiles per scenario for synchronization.
4. Add scenario variants for A11y and VD flows matching `UF-01..UF-16`.

## 6.3 P2 (nice to have)

1. Add matrix runner to iterate singer list automatically.
2. Add machine-readable pass/fail export per flow ID (not only step-level report).
3. Add a “strict mode” that fails if a required selector appears multiple ambiguous matches.

## 7. Suggested Immediate Usage Pattern

Use linked parallel mode to match your current habit:

```bash
python3 .ai-dev/skills/ux-visual-debug/scripts/adb_ux_runner.py \
  --scenario <your-round5-scenario.json> \
  --agent-goal "play a Adele song on youtube" \
  --agent-link-mode parallel \
  --agent-setup \
  --agent-debug-arg=--basic
```

VD run:

```bash
python3 .ai-dev/skills/ux-visual-debug/scripts/adb_ux_runner.py \
  --scenario <your-round5-vd-scenario.json> \
  --agent-goal "play a Ed Sheeran song on youtube" \
  --agent-link-mode parallel \
  --agent-setup \
  --agent-debug-arg=--basic \
  --agent-debug-arg=--vd
```

## 8. Recommended Definition of "Tool is usable"

判定 `/ux-visual-debug` 已可替代大部分手测的标准：

- 连续 10 次 run，`UF-01..UF-16` 的 P0 flow 全通过
- 因 timing 导致的 flaky fail < 5%
- 每个 fail 都能从 report artifacts 直接定位（无需再次手动复现）

