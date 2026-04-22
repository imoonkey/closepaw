# Review Of Claude Design

## Findings

### 1. Two user-interaction tools create avoidable split-brain

The design keeps `ask_user` for simple blocking intervention and introduces `show_canvas` for structured cards. That preserves current behavior, but it also creates two separate ways to ask the user for help:
- `ask_user` with `Op.UserResponse`
- `show_canvas` with `Op.CanvasResponse`

That split leaks into tools, protocol, reducers, and UI policy. The same user intent now has to choose between two call paths, and both need timeout, persistence, cancellation, and transcript rules. This is the main simplicity problem in the proposal.

### 2. Main-app-only resolution makes off-app interaction weaker than today’s goal

The design explicitly keeps complex interaction in chat and reduces the capsule to an "open app to choose" fallback. That is safe, but it underdelivers on the stated intermediate UI goal when the user is outside the app. If the agent finds three options while running in overlay mode, the user still has to context-switch back into the app before responding.

That may be acceptable for a first cut, but it should be called out as a product compromise, not just an implementation detail.

### 3. Canvas display cards need a clearer runtime-history contract

`show_canvas(mode=display)` returns immediately and adds a structured transcript card. That covers the user-facing side, but the design is light on what the LLM-side history should receive beyond the tool call/output pair. If the card contains meaningful structured data that the user can later refer to, the design should say whether:
- the tool output includes a normalized summary of the rendered content, or
- only the visible transcript holds that information

Without that rule, user-visible state and model-visible state can drift.

## What To Keep

- The transcript-first framing is strong.
- Keeping the capsule small is the right instinct.
- Deferring WebView is correct.

## What Should Change Before Alignment

1. Collapse toward one canonical blocking interaction path, even if display-only cards stay separate.
2. Add explicit host policy for which interaction kinds can render inline in the capsule and which must hand off to the app.
3. Define the exact history contract for display-only cards so visible state and prompt state stay aligned.
