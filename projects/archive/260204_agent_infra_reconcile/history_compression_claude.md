# History Compression Design (Implemented + Next TODOs)

> **Core Insight**: Mobile-use agents do not need old screenshots/a11y trees in LLM prompt history.  
> **Current direction**: Keep replay/debug artifacts, but keep LLM history compact and text-first.

---

## What Was Implemented

### 1) LLM history is compressed (no raw screen JSON in tool history)

- Tool result history now stores text summaries like:
  - `Success: Clicked element 6: "...label..."`
  - `Screen after action: com.google.android.gm | elements=..., labels=...`
- Raw/sanitized a11y trees and screenshots are **not** appended as past-turn LLM history items.
- Current turn still gets full `Current screen state (...)` JSON (and optional screenshot input).

This preserves grounding for the current action while reducing historical noise.

### 2) Replay history is persisted out-of-band

- Added `ScreenStatePhase` with:
  - `PRE_TURN`
  - `POST_ACTION`
- Added `ScreenStateRecord` and persisted it in `SessionRecord.screenStates`.
- `AgentEvent.ScreenCaptured` now carries replay metadata:
  - turn info, phase, element count
  - raw/sanitized tree paths
  - screenshot path
  - trace run id
- `ChatViewModel` records these events via `SessionRecordingService.recordScreenState(...)`.

So replay/debug keeps full trace references, without polluting prompt history.

### 3) Screen summary heuristic (v1)

- Added `ScreenSnapshot.toSummary(packageName)`:
  - counts: elements/clickable/editable
  - focused label
  - top labels
- Gmail-specific stopwords are filtered to reduce nav chrome noise.

### 4) Regression mitigation already added

Observed regression: repeated reopening of the same Gmail email after compression.

Mitigations implemented:
- Click/tap success strings now include clicked element label snippet.
- Prompt now nudges:
  - write extracted facts to scratchpad before leaving content screens
  - avoid reopening already-visited items
- Summary label filtering improved for Gmail.

---

## Current Trade-off

**Gain**
- Much smaller history context
- Cleaner signal for current-screen reasoning
- Replay/debug still available via artifact references

**Cost**
- Historical screen state in LLM history is lossy (summary-level, not full JSON/image)

---

## TODO: Screen Summary Optimization (next)

### Proposed approach: "retain one extra screen state, then summarize transition"

Keep an additional one-turn temporal window for better continuity:

1. Keep `prev_screen_state` + `current_screen_state` for one step (ephemeral prompt context, not permanent large history).
2. In the next turn, have the main agent naturally produce a short transition summary (what changed after last action).
3. After that summary is captured, drop the older unsummarized screen from prompt context.
4. Replay history still keeps full PRE/POST references independently.

This gives better continuity without adding a separate post-tool LLM call.

### Why this is preferred now

- **Feasible** with current architecture (small changes in prompt assembly/history bookkeeping).
- Avoids an extra "summary-only" LLM call in tool post-processing (which is structurally heavier and costlier).
- Avoids introducing a dedicated summarizer sub-agent too early.

### Future refinements (optional)

- Add hash-based dedupe for identical PRE/POST captures.
- Add explicit "visited item" extraction helper into scratchpad conventions.
- If needed later, introduce a dedicated summarizer agent only when prompt-only natural summarization proves insufficient.

---

## Status

- Phase goal ("history compression + replay separation") is implemented.
- Next recommended step is the one-turn retention transition-summary TODO above.
