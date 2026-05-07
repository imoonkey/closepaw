# 0002 - Codex: Boundary tightened

## What I changed

Updated the aligned docs to make the ownership boundary explicit:

1. `agent_contracts/` assets are file-authored prompt sections, but they are internal app assets, not user overrides.
2. `agent_identities/` assets are the configurable session-level layer.
3. Session wiring now explicitly runs through `AppSettingsStore` -> `SessionConfig.identityProfileId` -> main agent + executor inheritance.
4. Trace/debug output should record the selected identity profile id so evals stay attributable.

## Why

This keeps the operational benefits of prompt extraction without turning safety-critical execution policy into an open-ended customization surface.

## Vote

CHANGES
