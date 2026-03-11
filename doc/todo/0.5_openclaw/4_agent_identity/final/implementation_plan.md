# Agent Identity and Personality - Implementation Plan

## Phase 1: Extract and compose

Goal: ship the new architecture without changing effective behavior for the default profile.

Steps:

1. Add `agent_contracts/` assets by extracting the current standalone/planner/executor prompt text into ordered files.
2. Add one built-in identity profile: `balanced`.
3. Introduce:
   - `AgentContractRepository`
   - `AgentIdentityRepository`
   - `AgentInstructionComposer`
4. Replace `AgentDef.systemPrompt` with `promptRole`.
5. Update `SessionAgentRunner` to compose instructions from role + identity + device environment.
6. Update delegated executor startup so executor instructions are resolved from the same `identityProfileId`.

Acceptance:

- default `balanced` profile yields near-equivalent prompts to current behavior
- no change to tool list ownership
- no change to app skill injection

## Phase 2: Settings and observability

Goal: make identity selection a real session feature rather than a hidden default.

Steps:

1. Add `identityProfileId` to `AppSettingsStore`.
2. Add `identityProfileId` to `SessionConfig`.
3. Add a settings UI control for preset selection.
4. Record selected identity profile id in trace/debug output.
5. Log explicit fallback when an invalid profile id is requested.

Acceptance:

- profile choice persists across app restarts
- new sessions use the selected profile
- traces show which identity was used

## Phase 3: Validation and tests

Goal: keep file-based prompt loading predictable and safe.

Steps:

1. Unit-test repository loading and ordering.
2. Unit-test identity profile validation:
   - required files
   - optional role addenda
   - length bounds
   - invalid profile fallback
3. Unit-test planner/executor inheritance.
4. Snapshot-test composed prompts for each role under `balanced`.

Acceptance:

- invalid or incomplete profiles fail cleanly
- prompt composition is deterministic
- executor receives the same session identity as planner

## Phase 4: Optional product follow-up

Goal: expand personality options without breaking the ownership model.

Possible work:

1. Add `efficient` and `careful` presets.
2. Add validated custom identity imports.
3. Add eval support for comparing identity presets.

Guardrails:

- custom identity may change persona only, not contract
- no automatic task/app persona switching unless separate evidence justifies it

## Risks to watch

1. Prompt drift during extraction.
   - Mitigation: snapshot-test old vs new composed prompts for the default profile.

2. Boundary erosion between contract and identity.
   - Mitigation: keep separate asset roots and validation rules.

3. Planner/executor mismatch.
   - Mitigation: resolve both from the same `SessionConfig.identityProfileId`.

4. Silent fallback bugs.
   - Mitigation: explicit logging and trace recording for fallback behavior.
