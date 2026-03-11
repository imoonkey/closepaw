# OpenClaw Takeaways — Unified Priority & Implementation Design (Claude)

## Goal

Synthesize the 7 Claude analysis docs and the Codex product analysis into a single prioritized roadmap. For each item, define: what changes, where in the codebase, dependencies, and the minimum viable scope. The output should be actionable — a team member can pick up any Tier-1 item and start coding.

## Design Principles (from OpenClaw)

Two meta-lessons matter more than any single feature:

1. **Declarative capability** — devices/tools declare what they can do; the system adapts. No central assumptions.
2. **State externalization** — prompts, memory, session, tool policy are data, not code. Configurable and replaceable at runtime.

Everything below is filtered through these two principles.

---

## Unified Priority Matrix

### Tier 1: Do Now (days, high ROI)

| # | Item | Source | Cost | Key Dependency |
|---|------|--------|------|----------------|
| T1-1 | System prompt externalization | Claude #4 | 0.5d | None |
| T1-2 | Dynamic tool registration | Claude #6 | 1–2d | None |
| T1-3 | Tool risk levels | Claude #5 | 1d | T1-2 (cleaner with dynamic reg) |

### Tier 2: Build Next (1–2 weeks, product differentiation)

| # | Item | Source | Cost | Key Dependency |
|---|------|--------|------|----------------|
| T2-1 | Session persistence & identity | Claude #3, Codex #6 | 2–3d | None |
| T2-2 | App operation memory | Claude #1 | 2–3d | T2-1 (memory attaches to session context) |
| T2-3 | Voice input (Phase 1) | Claude #2 | 1–2d | None |
| T2-4 | Onboarding wizard | Codex #3 | 2–3d | None |
| T2-5 | Security pairing model | Codex #4, Claude #5 | 2–3d | T1-3 (risk levels as foundation) |

### Tier 3: Plan Later (weeks+, long-term vision)

| # | Item | Source | Cost | Key Dependency |
|---|------|--------|------|----------------|
| T3-1 | Web control UI | Codex #2 | 1–2w | T2-1 (shared session model) |
| T3-2 | Rich message types | Claude #7 | 3–5d | None |
| T3-3 | Wake word | Claude #2 | 5d+ | T2-3 (voice input first) |
| T3-4 | WebView canvas | Claude #7 | 1w+ | T3-2 (rich messages first) |
| T3-5 | Skill/plugin marketplace | Codex #5 | 2w+ | T1-2 (dynamic tools), T2-2 (memory) |
| T3-6 | Multi-entry session sharing | Codex #6 | 1w+ | T2-1, T3-1 |

---

## Tier 1 Detailed Design

### T1-1: System Prompt Externalization

**Problem:** System prompt is assembled in `PromptBuilder` from hardcoded strings. Changing behavior requires code changes + rebuild + reinstall.

**Current state:** `PromptBuilder.buildInputItems()` constructs the system instruction inline. App skills are already externalized to `assets/app_skills/<package>/SKILL.md` — this pattern works and should be extended.

**Design:**

Extract the core system prompt into asset files:

```
assets/
├── app_skills/<package>/SKILL.md    # (existing)
└── prompt/
    ├── system.md          # Core identity, rules, behavior
    ├── tools_guide.md     # Tool usage norms (when to click vs type, etc.)
    └── safety.md          # Security boundaries, forbidden actions
```

`PromptBuilder` loads these at session start (not per-turn — they're stable). The files are concatenated in order to form the system instruction.

**Changes:**
- New: `assets/prompt/{system,tools_guide,safety}.md`
- New: `SystemPromptRepository` (mirrors `AppSkillRepository` pattern — interface + asset impl)
- Modify: `PromptBuilder` — replace inline string assembly with repository load
- Modify: `SessionServices` — inject `SystemPromptRepository`

**What this unlocks:**
- Prompt A/B testing in eval without code changes
- User-overridable prompt files (Phase 2, via external storage)
- Cleaner separation between prompt content and prompt assembly logic

---

### T1-2: Dynamic Tool Registration

**Problem:** Tools are registered at session creation time and never change. If a permission is revoked mid-session, or the accessibility service disconnects, the LLM still sees (and tries to use) unavailable tools.

**Current state:** `ToolRegistry` is already a runtime mutable map with `register()`. But there's no `isAvailable()` check — all registered tools are always exposed to the LLM.

**Design:**

Add an availability gate to `ToolSpec`:

```kotlin
interface ToolSpec {
    // ... existing ...
    fun isAvailable(): Boolean = true  // default: always available
}
```

`ToolRegistry.generateResponsesApiTools()` already accepts a filter. Extend it to also check `isAvailable()`:

```kotlin
fun getAvailableTools(): List<ToolSpec> =
    tools.values.filter { it.isAvailable() }
```

Per-turn, `PromptBuilder` (or the turn runner) calls `getAvailableTools()` instead of `getAll()`. The LLM only sees tools that can actually execute right now.

**Concrete availability checks:**
| Tool | `isAvailable()` checks |
|------|------------------------|
| `MobileActionTool` | Accessibility service connected |
| `ShellTool` | Shell session alive |
| `OpenAppTool` | Always (package manager is always available) |
| `DelegateTaskTool` | Multi-agent mode enabled in config |

**Changes:**
- Modify: `ToolSpec` — add `isAvailable()` with default `true`
- Modify: `ToolRegistry` — add `getAvailableTools()`, use it in schema generation
- Modify: Each tool impl — override `isAvailable()` where relevant
- Modify: Turn runner — call `getAvailableTools()` per turn

**What this unlocks:**
- Eliminates wasted turns on unavailable tools
- Foundation for plugin/skill tools that come and go
- Enables capability broadcasting to external control plane (Tier 3)

---

### T1-3: Tool Risk Levels

**Problem:** All tools are treated equally by `PolicyEngine`. No distinction between reading the screen (safe) and sending a message (dangerous).

**Current state:** `PolicyEngine` makes allow/deny/ask decisions, but the policy is uniform — it doesn't consider inherent tool risk. The approval flow (via `CompletableDeferred` with 60s timeout) already exists.

**Design:**

Add risk level to `ToolSpec`:

```kotlin
enum class ToolRiskLevel { SAFE, MODERATE, HIGH }

interface ToolSpec {
    // ... existing ...
    val riskLevel: ToolRiskLevel get() = ToolRiskLevel.MODERATE
}
```

`PolicyEngine` uses risk level as input to its decision:

```kotlin
fun evaluate(tool: ToolSpec, context: ExecutionContext): PolicyDecision {
    return when (tool.riskLevel) {
        SAFE -> ALLOW
        MODERATE -> if (context.autoApproveModerate) ALLOW else ASK_USER
        HIGH -> if (context.userExplicitlyAllowed(tool.name)) ALLOW else ASK_USER
    }
}
```

**Risk assignments:**
| Level | Tools |
|-------|-------|
| SAFE | Screen capture, read UI tree, wait, scratchpad read |
| MODERATE | Click, scroll, swipe, type, open app, scratchpad write |
| HIGH | Shell execute, send message (future), payment confirm (future) |

**Changes:**
- New: `ToolRiskLevel` enum (in `ToolSpec.kt` or `PolicyEngine.kt`)
- Modify: `ToolSpec` — add `riskLevel` property
- Modify: `PolicyEngine` — incorporate risk level into decision logic
- Modify: Each tool impl — declare appropriate risk level
- Modify: `SessionConfig` — add `autoApproveModerate: Boolean` (default true)

**What this unlocks:**
- Foundation for security pairing model (T2-5)
- Remote entry points can enforce stricter policies (always ASK for HIGH)
- UI can display risk indicator per action

---

## Tier 2 Sketch (scope only, detailed design deferred)

### T2-1: Session Persistence & Identity

**Current state:** Sessions have IDs and checkpoints (`SessionCheckpointCoordinator`), but the checkpoint is for process-death recovery, not for user-facing session management. History is persisted via `SessionHistoryManager` + `SessionRecordingService`.

**Direction:** Promote the existing checkpoint into a full session record. Session becomes the anchor object — all history, screenshots, actions, errors hang off it. The `SessionRecord` model in `history/model/` is already close to this; extend it with state and metadata rather than inventing a new model.

### T2-2: App Operation Memory

**Current state:** `ScratchpadState` is session-scoped key-value store (max 20 entries, 3000 chars). Cleared between sessions. No cross-session memory.

**Direction:** Add a persistent memory layer alongside the session-scoped scratchpad. Stored as Markdown files per app package (mirrors app skills structure). Written by the LLM via a new `MemoryTool`. Loaded into context alongside app skills when the matching app is in foreground. Key constraint: only store generalized experience, not raw action logs.

### T2-3: Voice Input (Phase 1)

**Direction:** Integrate Android `SpeechRecognizer` API. Push-to-talk in Smart Capsule overlay. STT result feeds into `SessionCoordinator` as a `UserInput` op. Zero external dependencies.

### T2-4: Onboarding Wizard

**Direction:** Step-through flow checking: accessibility permission → overlay permission → battery optimization → LLM API key → demo task. Each step has a check function and a fix action. Not a settings page — a sequential funnel that tracks completion.

### T2-5: Security Pairing Model

**Direction:** Builds on T1-3 risk levels. Add device binding (local-only by default). Remote entry points require explicit pairing approval on the device. HIGH-risk tools from remote sources always require confirmation, not configurable.

---

## Dependency Graph

```
T1-1 (prompt externalize)     T1-2 (dynamic tools)     T2-3 (voice)     T2-4 (onboarding)
         |                         |          |
         |                    T1-3 (risk)     |
         |                         |          |
         |                    T2-5 (security) |
         |                         |          |
    T2-2 (memory) ←── T2-1 (session persist)  |
                           |                   |
                      T3-1 (web UI) ──── T3-6 (multi-entry)
                           |
                      T3-2 (rich msg) → T3-4 (canvas)
```

T1-1, T1-2, T2-3, T2-4 are independent — can be parallelized.

---

## Trade-offs Considered

### Why prompt externalization before memory system?

Memory (T2-2) is higher-impact long-term, but prompt externalization (T1-1) is a 4-hour change that immediately accelerates eval iteration. Every subsequent tuning round benefits.

### Why not start with Web Control UI?

Codex ranks it #1. But it requires a shared session model (T2-1) to be useful, and it's a 1–2 week investment. The Tier 1 items deliver value in days and are prerequisites for the control plane anyway. Build the data model first, then put a UI on it.

### Why risk levels before security pairing?

Risk levels (T1-3) are a local, self-contained change. Security pairing (T2-5) is a distributed trust problem that needs risk levels as input. Do the easy, foundational one first.

### Dynamic tool registration vs. static filtering?

`ToolRegistry.createFilteredCopy()` already exists for static filtering. We could just expand its use. But static filtering doesn't handle runtime changes (permission revoked, service disconnected). The `isAvailable()` approach is equally simple and handles both cases.

### Markdown files vs. database for memory?

Markdown wins for v1: human-readable, Git-friendly, zero dependencies, matches existing app skills pattern. If we need search/indexing later, we can add SQLite FTS on top without changing the source format.

---

## Self-Review Checklist

- [x] Covers all 7 Claude analysis items + Codex analysis
- [x] Every Tier-1 item has: problem, current state, design, changes list, unlocks
- [x] Dependencies are explicit and acyclic
- [x] No item requires architectural changes beyond its scope
- [x] Tier-1 items are genuinely small (0.5–2 days each)
- [x] Design reuses existing patterns (AppSkillRepository, ToolSpec interface, PolicyEngine)
- [x] No backward-compatibility hacks — each change is clean and self-contained
- [x] Two meta-principles (declarative capability, state externalization) thread through all items
