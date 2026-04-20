# QA Report R3 — commit 785c3cae + 3272923a

**Date:** 2026-04-20  
**Device:** EP0110MZ0BC101266W  
**Task run:** `./scripts/debug-run.sh "Open Settings"` (gpt-5.4, basic mode)  
**A11y status:** ENABLED (verified — Setup Issue banner absent, agent executed successfully)

---

## Setup

`./scripts/setup.sh` ran clean: APK installed, overlay granted, accessibility service enabled.  
A11y confirmed active: agent navigated to Android Settings on turn 1.

---

## I-2 — Collapsed row headline format

**FIXED.**

Completed task produced collapsed row: `✓  Open Settings · 2 actions · 10s  ▸`

- Headline source: `userPrompt` ("Open Settings") — spec ladder step 1 ✓  
- Format: `✓ [prompt] · N actions · elapsed ▸` — matches Track A spec §5.2 exactly ✓  
- NOT "Task completed · Xs" ✓

Evidence: `/tmp/qa_r3_chat2_evidence.png` (chat screen after task completion).

Code path confirmed:  
- `ChatEventReducer.kt:224`: `userPrompt = text` set when task starts  
- `MessageBubble.kt:430`: `collapsedHeadline()` uses `userPrompt` first in ladder  
- `MessageBubble.kt:378`: `collapsedSummary()` builds `headline · N actions · elapsed`  
- `MessageBubble.kt:162`: `CollapsedHeader()` renders `✓ [summary] ▸`

---

## I-1 — Takeover capsule color

**FIXED.**

Takeover button (visible in turn_001 during Running state) renders with warm cream/amber background — `TertiaryContainerLight = #F2E2C7` ("Amber-tinted Paper"). No lavender (`#E6DEF6`) present.

Evidence: `/tmp/qa_r3_takeover_evidence.png` (turn_001 screenshot, capsule visible at bottom with "Takeover" + "Stop" buttons).

Code path confirmed:  
- `CapsuleControlBar.kt:111`: `containerColor = MaterialTheme.colorScheme.tertiaryContainer`  
- `Color.kt:24`: `TertiaryContainerLight = Color(0xFFF2E2C7)` — "Amber-tinted Paper"  
- `Color.kt:22`: Comment: "Avoids Material 3's default lavender leak. See QA report I-1." ✓

---

## Summary

| Issue | Verdict |
|-------|---------|
| I-1 Takeover capsule color | **FIXED** |
| I-2 Collapsed row headline | **FIXED** |

QA CLEAN — ready for archive
