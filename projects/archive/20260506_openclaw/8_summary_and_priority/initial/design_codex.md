# OpenClaw Borrowings: Repo-Specific Priority Design

## Goal

Turn the OpenClaw comparison into an implementation roadmap that matches this repository's actual architecture, not a generic feature wishlist.

Success means:
- we prioritize the next 2-3 investments by leverage on task success and iteration speed;
- we do not re-prioritize work the repo already partially solved;
- each priority maps cleanly onto existing session, prompt, tool, and policy seams.

## Current Reality

This repo already has partial versions of several "future" ideas:

- **Session persistence already exists**: `AgentSession.reload(...)`, `SessionCheckpointCoordinator`, `SessionStorage`, and hot-idle resume make session state durable.
- **Prompt externalization already started**: tool-local guidance lives in tool descriptions, and app-specific guidance already loads from `app/src/main/assets/app_skills/<package>/SKILL.md`.
- **Risk policy already exists**: `PolicyEngine` has approval modes plus low/medium/high risk, but it is still static and code-owned.
- **Tooling is still mostly static**: `SessionToolingBootstrapper` registers a fixed built-in set, with only `delegate_task` and `ask_user` added lazily.
- **Voice and rich UI are mostly absent**: they are product-surface additions, not missing foundations.

That changes the priority order. The main gap is not "add more features"; it is **make runtime capability, prompt/persona, and policy declarative from one shared source of truth**.

## Design Thesis

Prioritize a single foundation first:

**Build a declarative runtime contract that describes what this session can do, what the current agent is allowed to do, and what risk each action carries.**

From that contract, derive:
- registered tools;
- prompt/persona assets;
- approval policy;
- later, memory retrieval and remote/voice entry points.

This turns several OpenClaw lessons into one coherent system instead of six disconnected projects.

## Priority Order

### P1. Runtime Capability Contract

This is the highest-ROI missing piece.

Today, the repo has:
- platform selection (`ACCESSIBILITY` vs `VIRTUAL_DISPLAY`);
- runtime permission checks;
- static tool registration;
- static tool allowlists in `AgentDef`;
- ad hoc platform differences like `allowTapToFocus()`.

But the agent still reasons from a mostly static tool universe. That causes wasted turns on tools/actions that are theoretically compiled in but not appropriate for the current platform, permission state, or session mode.

#### Proposed shape

Add a session-scoped `RuntimeCapabilityContract` with three sections:

1. `capabilities`
   - current platform mode
   - granted permissions
   - optional features (`vision`, `shell`, `delegation`, `ask_user`, etc.)
   - action constraints (`tap_to_focus=false` on VD, destructive shell disallowed, etc.)
2. `toolAvailability`
   - tool name
   - availability: enabled / disabled
   - reason when disabled
3. `policyProfile`
   - risk class and approval requirement for each tool/action family

#### Interaction

Session startup/update flow:

1. Probe platform + permissions + session config
2. Build `RuntimeCapabilityContract`
3. Register only enabled tools
4. Filter agent tool allowlists against enabled tools
5. Inject a short capability summary into the turn context
6. Use the same contract to drive approval behavior

This removes special-casing. Prompt, tool list, and policy all describe the same runtime truth.

### P2. Policy Externalization on Top of the Contract

OpenClaw's security lesson is relevant, but this repo should not build it as a separate subsystem first.

`PolicyEngine` already has the skeleton, but risk is still:
- mostly hardcoded;
- mostly one-dimensional;
- split away from capability discovery.

#### Proposed shape

Move from hardcoded risk tables to a data-owned `PolicyProfile` loaded with the runtime contract.

It should classify actions by:
- **effect**: read / write / destructive / external side effect
- **scope**: in-app / system / cross-app / shell
- **reversibility**: reversible / costly / irreversible

The output can still collapse to current UX primitives (`ALLOW`, `ASK_USER`, `DENY`), so this is a policy refactor, not a UI rewrite.

#### Why second, not first

Without the capability contract, policy stays detached from runtime truth and duplicates the same branching logic in a second place.

### P3. Persona and Prompt Assets

Claude's "system prompt externalization" is directionally right, but too small when treated alone.

The real target is not "move strings out of Kotlin." The target is:

- persona prompt;
- allowed tool families;
- delegation behavior;
- optional capability requirements

all becoming **data-owned persona assets**.

#### Proposed shape

Replace hardcoded `AgentDef` objects with asset-backed persona manifests:

- `manifest.json`: id, role, allowed tools, delegation requirement
- `system_prompt.md`: prompt body

`AgentDefRegistry` becomes a loader for these assets instead of a hand-written switchboard.

#### Why third

This is valuable for eval speed and prompt iteration, but by itself it does not improve agent correctness much. It becomes much more valuable after tool availability and policy are also data-driven.

### P4. Experience Memory

This is the first truly new capability with strong product upside.

Current memory is session-local:
- history;
- todos;
- scratchpad.

What is missing is **cross-session operational memory**: "when working in app X on task pattern Y, this path usually works."

#### Proposed shape

Add a small `ExperienceMemory` store keyed by:
- app package
- task archetype
- success/failure outcome
- compact action strategy summary

Retrieval should inject a short "Relevant Experience" block near app skills, not dump raw transcripts.

#### Why fourth

It can become a real differentiator, but only after the runtime contract is stable. Otherwise memory will learn against unstable tool/policy surfaces and overfit to transient behavior.

### P5. Session Workspace Promotion

Do **not** prioritize raw session persistence work. That already exists.

The real remaining session problem is product-level:
- one stable session identity across MainActivity, overlay, future remote entry points, and possibly voice;
- consistent attach/rebind behavior;
- explicit session metadata beyond chat history.

This is a **workspace unification** project, not a storage project.

### P6. Voice Entry

Voice input should be treated as a new front door into the same session workspace:

- `SpeechRecognizer` produces text input
- text is submitted through the same `SessionCoordinator`
- later phases may add TTS for ask-user/completion

It is useful, but it should not outrank the runtime contract or memory. It improves accessibility and UX more than raw task success.

### P7. Rich Message / Canvas Host

This is explicitly last.

Before the agent has better capability truth, policy truth, and reusable experience, richer rendering mainly improves presentation of the same underlying limitations.

## Components

### New

- `RuntimeCapabilityContract`
- `CapabilityProbe` layer owned by session/platform bootstrap
- asset-backed `PersonaManifest`
- data-backed `PolicyProfile`
- later: `ExperienceMemory`

### Changed

- `SessionToolingBootstrapper`: register tools from capability contract, not a fixed built-in set
- `SessionServices`: create and hold the runtime contract
- `SessionAgentRunner`: resolve persona assets instead of hardcoded defs
- `PolicyEngine`: consume `PolicyProfile`
- `TurnPlanningPhaseRunner`: inject concise capability summary and later retrieved experience

### Unchanged

- `PromptBuilder` section ordering can stay mostly intact
- current approval UI primitives can stay intact
- checkpoint/history architecture can stay intact

## State Machine

Runtime contract lifecycle:

`Unprobed -> Probed -> Advertised -> Enforced -> Updated`

- `Unprobed -> Probed`: session starts, platform/config/permission probe runs
- `Probed -> Advertised`: enabled tools + capability summary exposed to the LLM
- `Advertised -> Enforced`: tool router + policy engine apply the same contract
- `Enforced -> Updated`: permission/platform/session-mode change triggers rebuild

This is the core design move: runtime truth is computed once, then reused everywhere.

## Trade-offs

- **Why not session first?** Because the repo already solved persistence/reload. The unsolved problem is runtime truth, not storage.
- **Why not prompt externalization first?** Because externalized strings without capability/policy ownership mostly improve editing convenience, not execution quality.
- **Why not voice first?** Because it is a UX multiplier, not a capability multiplier.
- **Why not memory first?** Because memory becomes noisy if the underlying tool/policy surface is still unstable.

## Recommended Execution Plan

1. Ship `RuntimeCapabilityContract` plus capability-driven tool registration.
2. Move `PolicyEngine` risk logic into a data-owned policy profile tied to that contract.
3. Externalize agent personas/prompts into assets.
4. Add cross-session experience memory retrieval.
5. Promote sessions into a true multi-entry workspace.
6. Add voice as another workspace input channel.
7. Revisit rich message / canvas only after the agent core is stronger.

## Self-Review

This design intentionally reorders Claude's summary for this repo:
- it **demotes raw session persistence**, because the codebase already has it;
- it **demotes prompt externalization as a standalone project**, because the real missing layer is broader;
- it **promotes capability advertising**, because it is the shared foundation for tooling, policy, personas, and later memory.

That gives the roadmap one center of gravity instead of several parallel "nice ideas."
