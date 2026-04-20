# Track A - Chat Row Info Architecture (Codex)

## Problem

ClosePaw chat currently preserves actions, but it does not preserve the agent's
thought the way the capsule does. That makes the chat row low-density and hard
to scan after the live capsule state has moved on.

The row should answer three questions at a glance:

1. What is the agent trying to do?
2. What did it do?
3. What happened?

## KISS Decision

Keep the current conversation shape:

- one user bubble
- one agent row for the agent's reply/task segment

Do not add per-phase rows, density modes, nested transcripts, debug timelines,
or persistence for expand/collapse state. This is a simple transcript upgrade,
not a new framework.

## Proposed Row Model

Each agent row has three semantic parts:

1. `Thought` - one short line, always visible
2. `Actions` - compact execution list
3. `Result` - streamed/final answer text

The thought line is the new primary summary. The rest of the row exists to back
that summary up.

The capsule remains the live, ephemeral surface. The chat row becomes the
durable record.

## Event Taxonomy -> UI Surface

| Event | Surface in chat row | Rule |
| --- | --- | --- |
| `TaskStarted` | Create new agent row in live state | Show fallback thought `"Thinking..."` until better data arrives. |
| `ThoughtUpdate` | `Thought` line | Latest value wins. Do not build a thought history. |
| `ActionProposed` | Add action item to `Actions` | Use tool name + human description. |
| `ActionExecuted` | Update matching action item | Show `success`, `failed`, or `skipped` plus one-line result if present. If no prior `ActionProposed` exists, create a resolved action item directly. |
| `MessageDelta` | Append to `Result` | Result section becomes visible on first delta and streams in place. |
| `TaskCompleted` | Finalize row | Mark row complete. If `Result` is still empty, use `TaskCompleted.result` as the result line. |
| `TurnStarted` | No standalone UI | Internal boundary only. |
| `TurnPhaseChanged` | Small live status label in header | Drives `Thinking`, `Acting`, or `Writing`; not persisted as a separate block after completion. |
| `TurnCompleted` | No standalone UI | Used only to settle the live status if needed. |
| `AskUser` | Inline blocking block | Force row open. Show the prompt and required user action. |
| `ApprovalRequired` | Inline blocking block | Force row open. Show action description + target app/context. |
| `SubAgentStarted` / `SubAgentActivity` / `SubAgentCompleted` | One compact delegation item inside `Actions` | Show one line only. Latest activity wins while live; final completion message replaces it. No nested sub-agent UI. |
| `SessionError` | Terminal error in `Result` | Force row open and show error state. |
| `SupplementReceived` | Seal current row, then start new user turn | Current row stops updating. New supplement becomes the next user message. |
| `StatusUpdate` | No default chat surface | Keep this in capsule/status surfaces; too noisy for chat. |
| `Perception`/screen-capture events | No default chat surface | Belong in viewer/debug surfaces, not the chat transcript. |

## Row Anatomy

### 1. Collapsed row

Collapsed is the resting state for completed success rows.

Content:

- leading status icon
- primary line: thought summary
- secondary line: compact outcome summary
- trailing chevron

Primary line fallback order:

1. latest `ThoughtUpdate`
2. first action description
3. first line of result text
4. `"Thinking..."`

Secondary line format:

- success: `"2 actions - Settings opened"`
- blocked: `"Awaiting approval"` / `"Awaiting your response"`
- error: `"Failed"`

### 2. Expanded row

Expanded layout order is fixed:

1. `Thought`
2. `Actions`
3. blocking block, if present
4. `Result`, if present

Rules:

- Omit empty sections.
- Keep one disclosure level only.
- Do not render raw JSON, raw tool args, or screenshots in the default row.
- `SubAgent*` is just another action-list item, not its own nested panel.

### 3. Section behavior

`Thought`

- single short sentence
- visually primary
- updates in place while row is live

`Actions`

- simple stacked list
- each item shows tool, short description, status icon, and optional one-line outcome
- action details are chronological

`Result`

- prose block for streamed/final assistant output
- appears only once text exists
- keeps streaming in place while live

`Blocking block`

- used for `AskUser` and `ApprovalRequired`
- visually stronger than an action row because it needs user attention
- disappears only when the row resumes and a later event replaces it, or it can collapse to a one-line resolved status

## Collapse / Expand State Model

Keep this simple.

### States

1. `Live`
2. `Waiting`
3. `Complete`
4. `Error`

### Transitions

- `TaskStarted` -> `Live`
- `AskUser` or `ApprovalRequired` -> `Waiting`
- resumed agent activity after waiting -> `Live`
- `TaskCompleted` -> `Complete`
- `SessionError` -> `Error`
- `SupplementReceived` -> seal current row and start next user turn

### Presentation defaults

`Live`

- always expanded
- no manual collapse

`Waiting`

- always expanded
- no manual collapse
- required controls stay visible

`Complete`

- collapsed by default
- tapping the header toggles expand/collapse

`Error`

- expanded by default
- tapping the header may collapse it after the error is visible

No expand/collapse state is persisted across app restarts or history reloads.

## Example Shape

Expanded live row:

```text
[thinking] Open Settings and check Accessibility
Acting

Tap Settings                           success
Scroll to Accessibility                running

Looking for the Accessibility menu...
```

Collapsed completed row:

```text
[success] Open Settings and check Accessibility
2 actions - Accessibility settings found
```

## Edge Cases

### Errors

- Preserve the thought and completed actions.
- Append the error message in `Result`.
- Row enters `Error` state and stays open by default.

### Supplements

- Treat supplement as a hard conversation split.
- Freeze the current row as-is.
- Insert the supplement as a new user message.
- Start a fresh agent row after it.

### Sub-agents

- Show one compact delegation line in `Actions`.
- While live: `"Delegating to Researcher - checking Settings path"`
- When done: `"Researcher finished - found Accessibility entry"`
- Do not render nested activity logs.

### Approvals

- Show one blocking block with action description and app context.
- Keep the row open until the user acts.
- After approval/denial, the block may reduce to a one-line resolved status and normal row updates continue.

### Streaming in progress

- Row stays expanded.
- `Result` streams in place.
- If no result text exists yet, the row still shows thought + actions.

### Direct answer with no actions

- Show `Thought` and `Result`.
- Omit `Actions`.

### Multiple thought updates

- Replace the thought line with the latest one.
- Do not accumulate a visible thought log.

## Accessibility

- Expose the whole row header as one clear control: expanded/collapsed state,
  thought summary, and result state.
- Label sections explicitly for TalkBack: `Thought`, `Actions`, `Result`.
- Do not rely on color alone for success/failure/waiting; pair color with icon
  and text.
- Treat streaming result text as a polite live region.
- Do not announce every action-list mutation separately while the row is live.
- In waiting states, move accessibility focus to the first required action.

## Motion

- Expansion/collapse uses one short size/fade transition, around 200-240ms.
- No bounce, spring, or stacked animations.
- Live updates animate only where needed: status icon, streaming cursor, row
  height growth.
- Under reduced-motion settings, remove non-essential animation and keep only
  instant state changes.

## Out of Scope

- per-turn sub-rows inside one agent reply
- raw debug transcript UI
- screen-capture/perception UI in chat
- multiple density modes
- persisted expand/collapse preferences
