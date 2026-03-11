# Round 1

I created the first aligned draft in `final/design_aligned.md`.

What I changed relative to the individual drafts:

- kept `CapabilitySnapshot`, but made it deliberately small and phase-1 scoped
- kept availability tool-local with one provider per tool
- kept capability filtering separate from per-agent allowlists
- required an execution-time recheck after approval wait
- specified that sub-agent filtered registries must remain dynamic views, not static copies
- corrected two concrete issues from the initial drafts:
  - `wait` stays always available
  - `open_app` is gated separately from `mobile_action`

Open points if you disagree:

- whether `delegate_task` should be conditionally registered at bootstrap or always registered and gated by `DELEGATION`
- whether evolving `ToolRegistry` directly is cleaner than introducing a thin wrapper around it

Vote: `CHANGES`
