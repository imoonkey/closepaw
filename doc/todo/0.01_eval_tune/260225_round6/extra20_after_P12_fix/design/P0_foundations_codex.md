# P0 Foundations Design (Codex)

## Scope
- Recommendation 1: fix `open_app` resolver for `Simple Calendar Pro`
- Recommendation 2: block `ask_user` in eval mode (clean, not hacky)
- Recommendation 3: keep Android Agent + AndroidWorld AccessibilityForwarder enabled together

## Evidence Summary
- `open_app("Simple Calendar Pro")` failed repeatedly, then agent detoured into Google Calendar and launcher search.
- Current resolver alias map in `OpenAppTool.kt` has `calendar -> com.google.android.calendar`, but no `simple calendar pro -> com.simplemobiletools.calendar.pro`.
- Bridge currently strips all other accessibility services and keeps only AgentService in `eval/aw_bridge/native_agent_bridge.py`.
- Runner logs confirm AndroidWorld first enables forwarder, then bridge re-writes enabled services for each task.

## Design 1: Open App Resolver Hardening

### Design goals
- Deterministically resolve benchmark app names.
- Avoid overfitting to only one app while preserving maintainability.

### Proposed changes
1. Add benchmark alias layer in `OpenAppTool.kt`:
- Add aliases:
  - `simple calendar pro` -> `com.simplemobiletools.calendar.pro`
  - `pro expense` -> `com.arduia.expense`
  - `simple draw pro` -> `com.simplemobiletools.draw.pro`
  - `audio recorder` -> `com.dimowner.audiorecorder`
  - `markor` -> `net.gsantner.markor`
2. Keep current resolution order, but add one tie-breaker:
- If multiple label contains matches exist, prefer alias package exact hit when available.
3. Add targeted unit tests for resolver behavior (name -> package) to prevent regression.

### Why this is clean
- Minimal API change.
- Centralized alias table, no evaluator-specific branching in runtime execution.
- Tests make future alias churn safe.

## Design 2: Clean Eval Tool Profile (Block ask_user + disable write_todos)

### Problem
- `ask_user` is valid in interactive mode but invalid in benchmark eval mode.
- Directly deleting tool globally hurts normal UX.

### Proposed changes
1. Add session-level policy profile (new enum), e.g.:
- `DEFAULT`
- `EVAL_CLEAN`
2. In `EVAL_CLEAN` profile:
- Tool exclusions: `ask_user`, `write_todos`
- Prompt overlay:
  - "Do not ask user questions in this run."
  - "Resolve relative dates using device date/time and proceed."
3. Thread this profile from eval runner intent extras into `SessionConfig`, then into agent tool allowlist composition.
4. Keep existing default behavior unchanged for non-eval runs.

### Implementation points
- App intent payload/applier: add `EXTRA_POLICY_PROFILE`.
- `SessionConfig`: add `policyProfile` and `disabledTools` (or equivalent derived set).
- `SessionAgentRunner`: stop unconditional `ensureAskUserToolRegistered()` when profile disables it.
- Agent defs: remove hard dependency on `write_todos` in eval profile prompt rendering.

### Why this is clean
- No ad-hoc string checks like "if run_id contains eval".
- No global tool deletion.
- Profile is extensible for later eval experiments.

## Design 3: Accessibility Services Coexistence (Always Enabled)

### User decision captured
- Keep both services enabled at all times.
- Ensure permissions/service status at start of each task.

### Proposed changes
1. Replace current single-service rewrite logic with merge logic:
- Required services set:
  - `com.moonkey.androidagent/com.moonkey.androidagent.app.AgentService`
  - `com.google.androidenv.accessibilityforwarder/com.google.androidenv.accessibilityforwarder.AccessibilityForwarder`
- Read current enabled list, union required set, write back only if missing.
2. Keep `accessibility_enabled=1` enforcement.
3. Add pre-task health check:
- Verify both services are enabled.
- Verify AgentService bind wait as today.
- Verify forwarder process responsiveness via a lightweight broadcast (best effort log-only).
4. Add pre-scoring re-check in runner:
- Before `task.is_successful(env)`, re-check enabled services and log status snapshot.

### Why this is clean
- Removes brittle "strip others" behavior entirely.
- Explicit invariant: both services must be available for action + scoring.

## Validation Plan
1. Smoke tests
- Run calendar subset: no `open_app` detour to Google Calendar.
- Run one `ask_user`-prone task: no `ask_user` call appears in trace.
2. Service integrity
- During full eval run, log both services at each task start and pre-score; no missing entries.
3. KPI
- Primary KPI remains `1/20 unique tasks` style reporting.
- Add secondary infra KPI: `a11y_service_drop_count == 0`.

## Risks and mitigations
- Risk: forcing both services increases contention.
  - Mitigation: keep explicit per-task health logs; if contention appears, add bounded retry/rebind, not stripping.
- Risk: eval profile diverges too much from default.
  - Mitigation: profile-only deltas are tool/prompt constraints; core runtime remains shared.
