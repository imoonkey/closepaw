# OpenClaw - repo study summary + ideas for our Android agent

## Sources (local)
- `.reference/openclaw/README.md`
- `.reference/openclaw/docs/concepts/architecture.md`
- `.reference/openclaw/docs/gateway/protocol.md`
- `.reference/openclaw/docs/concepts/agent.md`
- `.reference/openclaw/docs/concepts/system-prompt.md`
- `.reference/openclaw/docs/concepts/agent-loop.md`
- `.reference/openclaw/docs/concepts/session.md`
- `.reference/openclaw/docs/concepts/queue.md`
- `.reference/openclaw/docs/concepts/memory.md`
- `.reference/openclaw/docs/concepts/multi-agent.md`
- `.reference/openclaw/docs/tools/index.md`
- `.reference/openclaw/docs/tools/skills.md`
- `.reference/openclaw/docs/gateway/sandboxing.md`
- `.reference/openclaw/docs/gateway/sandbox-vs-tool-policy-vs-elevated.md`
- `.reference/openclaw/docs/gateway/security/index.md`
- `.reference/openclaw/docs/nodes/index.md`
- `.reference/openclaw/docs/platforms/android.md`

## What OpenClaw is (high level)
- **Local-first personal assistant** with a single Gateway process that owns messaging channels and sessions.
- **WebSocket control plane**: clients (CLI/UI) and devices (nodes) all connect to the same WS gateway.
- **Nodes** are peripheral devices (macOS/iOS/Android/headless) that expose capabilities like camera, screen record, canvas, and location.
- **Agent runtime** uses an embedded pi-mono loop with a structured system prompt, skills injection, and workspace bootstrap files.

## Core architecture (Gateway + protocol)
- **Single Gateway per host**: owns all messaging surfaces and exposes a typed WS API with requests/responses/events.
- **WS handshake with roles/scopes**: clients connect as `operator` or `node`, declare scopes, caps, commands, and permissions.
- **Device pairing + device tokens**: pairing is required for new device identities; the gateway issues role-scoped device tokens for future connects.
- **Idempotency keys for side-effect calls** (e.g., send/agent) to support safe retries.
- **Protocol typing** via TypeBox schemas with JSON Schema + codegen.

## Agent runtime + prompt/skills
- **Workspace-centric**: the agent has a single working directory (`agents.defaults.workspace`).
- **Bootstrap files** injected into context on new sessions: `AGENTS.md`, `SOUL.md`, `TOOLS.md`, `IDENTITY.md`, `USER.md`, `BOOTSTRAP.md`.
- **System prompt is structured** into fixed sections (tooling, safety, skills, sandbox, time, runtime, etc.) with per-run injection.
- **Skills** are SKILL.md folders with metadata gating (required env/bins/config); precedence: workspace > managed > bundled.
- **Agent loop**: serialized per session, streams lifecycle/tool/assistant deltas, supports hooks around tool calls and compaction.

## Sessions, memory, and queueing
- **Session keys**: DM scope can collapse into `main` or be isolated by peer/channel/account; groups are isolated by channel/group ids.
- **Session resets**: daily + idle policies, with per-type/channel overrides and explicit `/new` or `/reset` triggers.
- **Queue modes**: `collect` (default), `followup`, `steer`, `steer+backlog`, `interrupt`; steer injects messages after tool boundaries.
- **Memory files**: daily logs (`memory/YYYY-MM-DD.md`) + long-term `MEMORY.md` (main session only).
- **Automatic memory flush** before compaction to push durable notes to disk.

## Tooling + sandbox + policy
- **Tool profiles** + `allow/deny` lists, with provider-specific overrides and tool group shorthands.
- **Sandboxing** runs tools inside Docker; modes (`off`, `non-main`, `all`), scopes (session/agent/shared), and workspace access (`none/ro/rw`).
- **Sandbox vs tool policy vs elevated** are distinct layers; `elevated` is an exec-only host escape hatch (still gated by policy).
- **Exec approvals** and node allowlists are part of the default safety model.

## Security model (operational)
- **Security audit** tool to flag risky configs (open DMs, missing auth, exposed browser control, lax permissions).
- **DM access policies**: pairing/allowlist/open/disabled, with allowlists stored locally.
- **Command authorization**: slash commands only for authorized senders; `/exec` is session-scoped and does not override policy.

## Android node specifics (relevant to our Android agent)
- **Android app is a node** (not a gateway); connects to the gateway WS and uses device pairing.
- **Discovery**: mDNS/NSD on LAN, with manual host/port fallback; tailnet discovery via unicast DNS-SD.
- **Foreground service** keeps the node connection alive.
- **Capabilities**: canvas, camera, screen recording, location; commands only work when app is foregrounded.

## What we can borrow for our Android agent (actionable)
- **Add a control-plane protocol**: define a typed WS protocol with roles (operator/node), explicit caps/commands, and idempotency keys for side-effect actions.
- **Device pairing + command allowlists**: require pairing for any remote control; issue per-device tokens and enforce per-command allow/deny rules.
- **Layered tool policy**: global allow/deny plus per-app or per-session overrides; map `elevated` to explicit user approvals for risky actions.
- **Session + queue model**: serialize runs per session; support `steer` to inject new user requests after tool boundaries without corrupting action flow.
- **Workspace bootstrap files**: mirror OpenClaw's AGENTS/TOOLS/USER/IDENTITY injection so the agent consistently sees operating rules.
- **Skill packs with gating**: adopt SKILL.md format and metadata gates (required permissions/apps/binaries) with workspace > global precedence.
- **Memory strategy**: daily + long-term memory files with an automatic pre-compaction memory flush.
- **Hook points**: add pre/post tool-call hooks for logging, safety checks, and tool-result shaping.
- **Android node capabilities**: expose camera/screen record/location as explicit tools with foreground and permission gating.
- **Security audit checklist**: a local “audit” command that flags open accessibility settings, risky overlays, or disabled approvals.
