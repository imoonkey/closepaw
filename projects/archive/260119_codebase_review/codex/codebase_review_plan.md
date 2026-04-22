# Codebase Review Plan

## Scope Breakdown

1. **Agent Core + LLM Integration**
   - Files: `agent/`, `data/llm/`, `data/perception/`, `infra/history/`
   - Focus: ReAct loop correctness, turn lifecycle, prompt/tool parsing, history integrity, token budget handling, LLM error handling.
   - Output: `doc/review/agent_core_review.md`

2. **Session + Protocol + Eventing**
   - Files: `session/`, `protocol/`
   - Focus: Op/Event state machine correctness, lifecycle transitions, cancellation semantics, approval flow wiring, event completeness.
   - Output: `doc/review/session_protocol_review.md`

3. **Tooling + Platform + Policy**
   - Files: `infra/tools/`, `infra/registry/`, `infra/policy/`, `tools/`, `platform/`
   - Focus: tool validation/execution, approval gating, observation capture, UI action mapping, accessibility API correctness.
   - Output: `doc/review/tooling_platform_review.md`

4. **UI + Overlay**
   - Files: `MainActivity`, `AgentService`, `ui/`, `service/OverlayManager`, `util/StatusUtils`
   - Focus: UI state consistency, service orchestration, overlay lifecycle, status handling, user input/permissions.
   - Output: `doc/review/ui_overlay_review.md`

5. **Android Config + Build + Resources**
   - Files: `AndroidManifest.xml`, `res/`, `build.gradle.kts`
   - Focus: permissions, exported components, deprecated APIs, security posture, compatibility with target SDK.
   - Output: `doc/review/android_config_review.md`

## Cross-Cutting Checks

- Security: API key handling, permissions, logging, exposure of services.
- Concurrency: coroutine lifecycles, cancellation, channel/flow completion.
- Reliability: retries, error propagation, timeouts, resource cleanup.
- Performance: repeated screen capture, large prompt payloads, memory retention.
- Docs: reconcile `doc/agent_infra/infra_summary.md`, `doc/agent_infra/protocol.md`, `doc/ui/stack.md` with code.

## Final Deliverables

- Per-module review docs in `doc/review/`
- Overall summary in `doc/review/overall_code_review.md`
- Targeted doc updates to keep architecture/protocol docs in sync
