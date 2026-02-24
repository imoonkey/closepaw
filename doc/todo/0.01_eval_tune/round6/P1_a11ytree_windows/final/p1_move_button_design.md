# P1 Unified Design: DocumentsUI "Move" Button A11y Perception Gap

Status: **ALIGNED** (both parties APPROVE)
Scope: `FilesMoveFile` blocker in round6, with reusable multi-window perception design for both platform modes.

## 1. Agreed Problem Statement

Both analyses agree on these facts:
- The task reaches destination folder (e.g., DCIM), but confirmation button (`Move` / `Move here`) is missing from current a11y snapshot.
- Current capture path is single-root in both modes:
  - Accessibility mode: `service.rootInActiveWindow`
  - Virtual display mode: one root selected from display windows (`TYPE_APPLICATION` preferred)
- This is a perception coverage problem, not an LLM reasoning-only problem.

## 2. Design Goals

1. Capture the missing confirmation controls without flooding prompt context.
2. Keep captures strictly display-scoped to avoid cross-display contamination.
3. Keep one unified perception merge path so element ordering/dedup/truncation remains stable.
4. Work in both `ACCESSIBILITY` and `VIRTUAL_DISPLAY` modes with minimal branching.

## 3. Aligned Core Direction

We align on multi-window capture, but with stronger constraints than "collect all windows":

- `display-scoped` first: never mix windows across displays.
- `multi-root` second: collect multiple roots on the target display.
- `simple exclusion` third: exclude known-noisy window types, let Perceptor's existing element-level filtering handle the rest.

## 4. Target Architecture

### 4.1 Window Root Collection Layer

Add a shared collector abstraction (name can vary):
- Input: `mode`, `targetDisplayId`, `windowPolicy`
- Output: `List<RootWithMeta>`

`RootWithMeta` should include:
- `windowId`
- `displayId`
- `windowType`
- `packageName`
- `layer`
- `root: AccessibilityNodeInfo`

Implementation mapping:
- Accessibility mode:
  - Resolve target display from active context (`rootInActiveWindow?.windowId/displayId` fallback to default display).
  - Enumerate windows on that display only (`getWindowsOnAllDisplays` on API 33+, else `service.windows.filter`).
- Virtual display mode:
  - Use `displayIdProvider()` and existing display-filtered window query.

### 4.2 Window Exclusion Policy (Noise Control)

Exclude these window types from collection:
- `TYPE_ACCESSIBILITY_OVERLAY` — our own overlay; always noise.
- `TYPE_INPUT_METHOD` — keyboard; already handled separately by Perceptor's keyboard filter.

All other windows on the target display are collected. Perceptor's existing element-level pipeline (INTERACTIVE_ONLY pass, visibility filter, size filter, dedup, maxElements=80 truncation) handles noise control.

Rationale for no pass1/pass2 staging:
- Perceptor already IS the noise filter at element level. Adding window-type staging creates redundant layers.
- Pass2 triggers (e.g., "Move to… without Move button") are app-specific pattern matching that don't scale.
- Current element counts in the problem scenario (9-10 elements) leave ample headroom under the 80-element cap.
- If post-implementation measurement shows >20% median element count increase on baseline tasks, add window-type narrowing as a follow-up. Don't pre-optimize.

### 4.3 Unified Perceptor Multi-Root Merge

Add a new Perceptor entrypoint for multi-root input:
- Input: `List<RootWithMeta>`
- Pipeline: collect candidates across roots -> dedup -> enrich -> truncate -> spatial sort -> index
- Output: single `ScreenSnapshot`

Rules:
- Dedup key remains compatible with existing single-root behavior.
- Indexing remains global and deterministic after merge.
- Root/window metadata is debug-only (trace artifact), not injected into prompt JSON by default.

## 5. Action-Layer Interaction (Explicit Non-Change for P1)

No mandatory action-layer redesign in this P1.
- Current click path already has fallback: `node_action_click -> gesture_tap`.
- If an element is visible in perception but node-action cannot resolve from active root, gesture fallback can still complete interaction.

This keeps P1 focused on perception correctness and avoids broad execution-layer churn.

## 6. Implementation Plan (Concrete Files)

1. `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt`
- Replace single-root capture with display-scoped root collection + multi-root snapshot call.

2. `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayWindowAccessor.kt`
- Add API returning multiple roots with metadata for a display, not just one root.

3. `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayCaptureCoordinator.kt`
- Switch from `getRootOnDisplay()` to multi-root collection + unified merge.

4. `app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt`
- Add multi-root snapshot entrypoint and shared candidate merge path.

5. Optional small model/support additions
- Debug-only root/window source metadata container for trace diagnostics.

## 7. Validation and Metrics

Functional acceptance:
- In Files move dialog at destination folder, sanitized tree exposes confirmation control (`Move`/equivalent).
- `FilesMoveFile` completes reliably in both platform modes.

Noise regression acceptance:
- No major prompt noise explosion on baseline tasks (settings/contacts/sms flows).
- Suggested threshold: median element count increase under 20% outside target scenario.

Add capture metrics:
- `window_count_total`
- `window_count_selected`
- `selected_window_types`
- `elements_per_window`
- `cross_display_filtered_count`

## 8. Resolved Questions

1. ~~Pass 2 trigger placement~~ — No pass2. Simple exclusion list, no trigger needed.
2. ~~DocumentsUI coordinate fallback~~ — No fallback. Multi-root capture is the principled fix. Coordinate fallbacks are brittle across device/API/DPI variations.
3. ~~Window inclusion defaults~~ — Collect all window types except TYPE_ACCESSIBILITY_OVERLAY and TYPE_INPUT_METHOD. Perceptor handles element-level noise.

## 9. Final Votes

- Codex: **APPROVE**
- Claude: **APPROVE**

Implementation note (non-blocking): Use deterministic window ordering before merge (stable sort by layer/id) to prevent index jitter between turns.
