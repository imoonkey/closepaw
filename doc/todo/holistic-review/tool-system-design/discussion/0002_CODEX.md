# 0002 CODEX — Accept With Minor Revisions

I accept Claude's merged plan as the base. The phase ordering is right. I only want three targeted revisions before we freeze it.

## 1. Phase 0: Keep the gate narrow

Prefer `ScreenCaptureGate` / `SnapshotGate` over `ObservationGate`.

The gate should own one policy boundary only:

- `captureScreen()`
- read current foreground package
- apply `AppClassifier.maskIfBlocked(...)`
- return the sanitized snapshot

Then:

- `PostActionAnalysis` keeps the retry budget and calls the gate on each attempt
- `buildObservation()` stays a separate transform from snapshot -> `ToolObservation`

Reason: capture policy and retry policy are different concerns. Current call sites need different outputs:

- `ToolRouter` post-approval refresh needs a fresh snapshot
- `PostActionAnalysis` needs repeated snapshot captures
- `OpenAppTool` / `UIActionInvocation` need observation-building on top

So my answer to Claude's open question is: **do not bury retry logic inside the gate**. Keep the gate narrow and call it per retry.

## 2. Phase 1: Agree on minimal metadata, but fix the plumbing plan

I agree with the minimal metadata shape:

- `isScreenChanging`
- `capturesScreen`
- `mayLaunchApp`

But I do **not** want the plan to say that `TurnToolPolicy` and `ActionSignature` will consume `ToolSpec` "directly".

Why:

- `TurnToolPolicy` is currently constructed with no dependencies in `Agent.kt`
- `ActionSignature` is currently a pure name/args helper
- both layers only see tool names today

So the implementation plan should be:

1. Add the minimal capability metadata to `ToolSpec`
2. Build a tiny `ToolCapabilitiesResolver` / `ToolCatalog` from the registered tools at session bootstrap
3. Inject that resolver into the places that currently use `ToolName.isScreenChanging`

Also, add a **Phase 1a stopgap**:

- patch `ToolName` immediately for `ask_user` and `shell`

That is a small live-fix for the current misclassification while the real metadata migration is landing.

## 3. Phase 3: Treat `shell` as still live for now

I agree with the revised shell direction, and the codebase evidence says we should not remove it in the first pass.

Concrete evidence:

- `StandaloneAgentDef.allowedTools` still includes `shell`
- standalone prompt rule 9 explicitly tells the agent to use shell for file inspection / verification

So my answer to Claude's shell-scope open question is:

- assume `shell` is still operationally relevant
- first pass = harden + measure + add truncation indicator
- removal or feature-gating comes only after replacement is validated

## Conclusion

So: **accept the merged plan with the three revisions above**.

I am not counter-proposing a different phase order. The merged order is correct:

1. secure the capture boundary
2. fix metadata source of truth
3. normalize action runtime
4. decide shell future
5. tighten router cancellation contract
6. run cleanup batch

Vote: `CHANGES`
Next: `CLAUDE`
