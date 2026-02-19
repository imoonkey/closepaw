# Alignment Review: Action Exec Direct Debug Harness

## Source Documents

1. **Codex**: `doc/todo/eval_tune/round4/action_exec_direct/20260219_action_exec_direct_codex.md`
2. **Claude**: `doc/todo/eval_tune/round4/action_exec_direct/tech_design_claude.md`
3. **Claude Checklist**: `doc/todo/eval_tune/round4/action_exec_direct/impl_checklist_claude.md`

## Agreements (Both Proposals Aligned)

| Area | Shared Design |
|------|---------------|
| Three execution layers | L0 adb, L1 platform, L2 executor — identical concept |
| BroadcastReceiver approach | Both use broadcast intent for zero-UI trigger |
| Debug-build-only gating | Both gate via BuildConfig.DEBUG |
| Pre/post capture | Both capture screenshot + a11y tree before and after action |
| Effect verification | Both distinguish "action succeeded" from "UI changed" |
| No production code changes | Both are purely additive |
| Host script | Both propose a CLI script for orchestration |

## Differences Resolved

### 1. Receiver Registration: Manifest vs Dynamic

| | Codex | Claude |
|--|-------|--------|
| Approach | Dynamic registration in `AgentService` | Standalone receiver in `app/src/debug/AndroidManifest.xml` |
| Rationale | Matches existing `STOP_AGENT` pattern | Simpler, completely absent from release APK |

**Decision**: **Dynamic registration** (Codex approach).

Reasoning: Aligns with existing `AgentServiceReceiverHelpers.kt` pattern. The receiver needs access to `AgentService.instance` which is only available after `onServiceConnected()` — dynamic registration naturally gates on service availability. Manifest-registered receiver would need to handle the case where service isn't connected yet.

### 2. File Structure: 4 Classes vs 2 Classes

| | Codex | Claude |
|--|-------|--------|
| Classes | `DirectActionModels.kt`, `DirectActionRunner.kt`, `DirectEffectAnalyzer.kt`, `DirectArtifactStore.kt` (4 files) | `ActionDebugReceiver.kt`, `DebugActionExecutor.kt` (2 files) |

**Decision**: **2 classes** (Claude approach).

Reasoning: Effect analysis (~20 lines) and artifact writing (~30 lines) don't justify separate classes. Keep them as methods in `DebugActionExecutor`. Models can be inline data classes. We want the shortest path from "I have an idea" to "it's implemented."

### 3. Intent Extras Format: JSON String vs Individual Extras

| | Codex | Claude |
|--|-------|--------|
| Format | Single `--es payload '{"action":"click","x":540}'` | Individual `--es action "click" --ei x 540 --ei y 1200` |

**Decision**: **Individual extras** (Claude approach).

Reasoning: JSON inside shell commands requires careful escaping (single quotes, double quotes, nested objects). Individual extras via `--es`/`--ei`/`--ez` are more ergonomic from `adb shell am broadcast` and harder to get wrong. Trade-off: less flexible for future complex params, but YAGNI for Phase 1.

### 4. Output Path: run_id vs latest/

| | Codex | Claude |
|--|-------|--------|
| Path | `direct-action/<run_id>/result.json` | `action-test/latest/` (always overwritten) |

**Decision**: **`latest/`** (Claude approach).

Reasoning: For iterative debugging, you want one command to produce output and one path to check. With run_id, you need to know the ID or parse it from output. `latest/` is a known path — `adb pull .../latest/` always works. If archival is needed, the host script saves to `debug-output/action-test/{tag}/` with named tags.

### 5. Naming

| | Codex | Claude | Decision |
|--|-------|--------|----------|
| Script name | `action-direct-debug.sh` | `action-test.sh` | `action-test.sh` (shorter) |
| Intent action | `DEBUG_DIRECT_ACTION` | `ACTION_DEBUG_EXEC` | `ACTION_DEBUG_EXEC` (standard Android naming) |
| Layer names | `tool_executor` / `ui_action` | `platform` / `executor` | `platform` / `executor` (shorter, clearer hierarchy) |

### 6. Effect Verification Detail

| | Codex | Claude |
|--|-------|--------|
| Schema | `effect.a11y_changed`, `effect.pixel_changed`, `effect.pixel_change_ratio`, `effect.verdict` | `ui_changed: true/false/null` (simple boolean) |

**Decision**: **Codex's detailed schema** (adapted).

Reasoning: The explicit split of `a11y_changed` + visual change → verdict is the right design for diagnosing false successes. Simplified to: `ui_changed.a11y_changed`, `ui_changed.verdict`. Dropped `pixel_change_ratio` from Phase 1 (requires bitmap comparison logic, can add later).

### 7. Concurrency Handling

| | Codex | Claude |
|--|-------|--------|
| Agent running | Reject with "busy" error | Not addressed |

**Decision**: **Reject if agent running** (Codex approach). Simple check: `AgentService.instance?.isSessionActive()` or equivalent.

### 8. dump-tree Command

| | Codex | Claude |
|--|-------|--------|
| Included | No | Yes (json + prompt formats) |

**Decision**: **Include** (Claude approach). Essential for L2 executor debugging — need to see element indices and scrollable/clickable flags before testing.

## Code-Level Corrections Applied

Both designs referenced `AgentAccessibilityService` — actual class is `AgentService` (which extends `AccessibilityService`). Corrected in aligned design.

Both designs assumed `AccessibilityPlatform` could be easily constructed — it requires `SessionConfig`. Confirmed that `DebugActionExecutor` directly composing `NodeActionPerformer` + `AccessibilityGestureInjector` is the right approach (matches what `AccessibilityPlatform` does internally at lines 46-48).

## Result

All differences resolved. Aligned design written to `align/design/design.md`.
