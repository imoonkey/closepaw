# Mobile Portability Analysis — Design Doc (Claude)

## Goal

Determine what from OpenClaw can and should run on Android, and design the integration strategy. The question is NOT "how do we port OpenClaw to mobile" but "which OpenClaw designs do we absorb into Android Agent's native architecture."

## Portability Verdict

### Hard No — Cannot port, don't need to

| OpenClaw Capability | Why Not | Android Agent Alternative |
|---|---|---|
| WhatsApp Web (Puppeteer/Chromium) | No headless browser runtime on Android; 300-500MB memory overhead | AccessibilityService operates WhatsApp App directly — more reliable, lower cost |
| Docker Sandbox | No container runtime on Android | Android's per-app sandbox + permission model is the native equivalent |
| Gateway as inbound server | NAT blocks inbound connections; Android kills background servers; battery drain | Reverse to client mode — phone connects out to relay |
| Playwright browser automation | No Playwright on Android | AccessibilityService controls any browser app on-device |

### Soft Constraints — Portable with adaptation

| Capability | Constraint | Adaptation |
|---|---|---|
| Node.js/TypeScript runtime (~37k LOC) | Can't run Node natively | Kotlin rewrite of platform-independent logic only; most modules (channels, browser control) aren't needed on mobile |
| Shell/exec tool | No root, minimal CLI tooling | Scoped to file I/O + package manager intents; no general shell |
| npm plugin ecosystem | No npm on Android | Replace with asset-based app skills (already implemented: `assets/app_skills/{package}/SKILL.md`) |
| Multi-channel bot long connections | Background process killed by Android | External lightweight relay server pushes to Android via FCM/WebSocket client |

### No Constraint — Same or better on mobile

| Capability | Android Status |
|---|---|
| LLM API calls | Identical (HTTP) |
| Session management + JSONL storage | SQLite + filesystem, native support |
| Memory (Markdown + FTS) | SQLite FTS is first-class on Android |
| Voice (STT/TTS) | Android SpeechRecognizer + TextToSpeech — free, native |
| Screen perception | AccessibilityService > OpenClaw's desktop approach |
| App operation | AccessibilityService > desktop automation |
| Camera/GPS/sensors | Phone's core advantage |
| Notification interception | NotificationListenerService — phone-exclusive |
| History compaction/pruning | Pure algorithm, platform-independent |
| Tool risk classification | Pure logic, platform-independent |
| Agent identity templates | File config, platform-independent |

## Approach — What to absorb and how

The design principle: **absorb platform-independent patterns from OpenClaw; keep Android-native architecture.** No porting — selective adoption of proven designs into existing Android Agent systems.

### 1. Memory System (HIGH priority)

**OpenClaw pattern**: Three-stage — Retain (daily logs) → Recall (FTS index) → Reflect (structured knowledge extraction).

**Current Android Agent state**: In-session only. `TodoState` and `ScratchpadState` are ephemeral. `HistoryManager` handles conversation history with compression but no cross-session persistence of learned knowledge.

**Design**:

```
memory/
├── apps/{package}.md      # Per-app learned patterns (navigation, UI quirks)
├── user_prefs.md           # User preferences discovered during operation
└── device.md               # Device-specific capabilities/constraints
```

- Storage: Markdown files in app-private storage (human-readable, debuggable)
- Index: SQLite FTS5 table indexing all memory files for retrieval
- Write trigger: End of successful session → extract key learnings
- Read trigger: Start of turn → query FTS with current app package + task keywords
- Injection point: `TurnPlanningPhaseRunner.buildAppSkillMessage()` already injects per-app context; extend to include memory alongside static skills

**Why Markdown + FTS over vector DB**: Simpler, debuggable, no embedding model dependency, fits Android's SQLite-first storage model. OpenClaw proved this works.

### 2. Session Persistence (MEDIUM-HIGH priority)

**OpenClaw pattern**: Sessions as first-class objects with JSONL append-only storage, lane-based concurrency, subject isolation.

**Current Android Agent state**: `AgentSession` manages lifecycle with checkpoint-based resume. `SessionHistoryManager` + `SessionRecordingService` handle file I/O. Sessions are largely transient — Hot Idle provides short-term continuity, checkpoint provides crash recovery.

**Design**:

```kotlin
// Session identity — stable across app restarts
data class SessionId(
    val id: String,           // UUID
    val subject: String?,     // Optional topic isolation
    val createdAt: Instant,
)

// Storage: one JSONL file per session
// /data/sessions/{id}/history.jsonl   — append-only event log
// /data/sessions/{id}/state.json      — latest checkpoint (todos, scratchpad, memory refs)
```

- Lane model: Single-session FIFO (Android Agent processes one task at a time). No global concurrency lane needed — phone is single-user.
- Collect mode: Merge multiple queued messages into single input (already partially implemented in `SessionCoordinator`'s drain pattern).
- Resume: On app reopen, offer to continue last session or start fresh.

**Scope limitation**: No multi-session concurrency. No subject-isolated sessions. These are OpenClaw patterns for multi-user server — unnecessary for single-user phone.

### 3. Security Model Enhancement (HIGH priority)

**OpenClaw pattern**: Three-axis — Sandbox × Tool Policy × Elevated privilege.

**Current Android Agent state**: `PolicyEngine` with three modes (ALWAYS_ASK, AUTO_APPROVE, SMART) and per-tool risk assessment. Functional but context-blind.

**Design — extend existing PolicyEngine**:

```kotlin
// Phase 1: Context-aware risk (current PolicyEngine + app context)
fun assessRisk(toolName: String, params: JsonObject, context: ExecutionContext): RiskLevel {
    val baseRisk = toolRiskMap[toolName] ?: RiskLevel.MEDIUM
    return when {
        context.currentApp.isFinancial && toolName == "mobile_action" -> RiskLevel.HIGH
        context.currentApp.isMessaging && params.hasTextField() -> RiskLevel.HIGH
        else -> baseRisk
    }
}

// Phase 2: Remote entry escalation
// When task arrives from external source (relay/API), force confirmation for HIGH tools
```

- Sandbox axis: Not applicable — Android's app sandbox is the equivalent. No action needed.
- Tool policy axis: Already implemented via `PolicyEngine`. Enhance with context-aware risk.
- Elevated axis: Add for remote task entries — external triggers get stricter approval requirements.

### 4. Dynamic Tool Registration (MEDIUM priority)

**OpenClaw pattern**: Nodes advertise available capabilities at runtime.

**Current Android Agent state**: `ToolRegistry` is a static map populated at session creation by `SessionToolingBootstrapper`. `delegate_task` and `ask_user` are lazy-registered by `SessionAgentRunner.start()`.

**Design**:

```kotlin
interface ToolProvider {
    fun availableTools(): List<ToolSpec>
    fun isAvailable(): Boolean  // Runtime check: permissions granted? Service running?
}

// At session start and on permission changes:
val activeTools = toolProviders
    .filter { it.isAvailable() }
    .flatMap { it.availableTools() }
toolRegistry.replaceAll(activeTools)
```

- Benefit: LLM never sees tools it can't use → fewer wasted turns
- Trigger: Re-evaluate on `onServiceConnected`, permission grant callbacks, app install/uninstall

### 5. Agent Identity Externalization (LOW-MEDIUM priority)

**OpenClaw pattern**: Separate `.dev.md` files for identity, soul, tools, user persona.

**Current Android Agent state**: System prompt assembled in `PromptBuilder` from code. Not configurable without APK rebuild.

**Design**:

```
assets/agent/
├── identity.md     # Name, communication style, role
├── rules.md        # Core behavioral constraints
└── tools.md        # Tool usage guidelines
```

- Loaded by `PromptBuilder` at session start
- Overridable via device-local files (for eval A/B testing without APK rebuild)
- No multi-persona complexity — single identity, configurable

### 6. External Relay for Multi-Channel Input (LOW priority, future)

**OpenClaw pattern**: Gateway accepts WebSocket connections from channels (Telegram, Discord, WhatsApp Web).

**Android Agent adaptation**: Reverse the architecture.

```
[Telegram Bot] → [Lightweight Relay Server] → FCM Push → [Android Agent]
[Web Console]  → [Lightweight Relay Server] → FCM Push → [Android Agent]
                                                          ↓
                                              Agent executes on phone
                                                          ↓
                                              Result → Relay → Channel
```

- Relay is stateless — just message forwarding + push notification
- Agent intelligence stays on phone
- Phone connects out (no inbound NAT issues)
- Foreground Service only while actively executing a task

**Not in scope for initial implementation.**

## Components — What changes

| Component | Change Type | Description |
|---|---|---|
| `HistoryManager` | Extend | Add JSONL persistence for cross-session resume |
| `PromptBuilder` | Extend | Load identity/rules from asset files; inject memory alongside skills |
| `PolicyEngine` | Extend | Context-aware risk assessment based on current app |
| `ToolRegistry` | Refactor | Dynamic registration via `ToolProvider` interface |
| `SessionServices` | Extend | Add `MemoryRepository` dependency |
| New: `MemoryRepository` | New | Markdown file CRUD + SQLite FTS5 index |
| New: `MemoryWriter` | New | Post-session knowledge extraction |
| New: `assets/agent/*.md` | New | Externalized agent identity config |

## Data Flow

### Memory read path (per turn)
```
Turn start → Perceptor captures screen → foreground package known
  → MemoryRepository.query(package, task keywords)
  → PromptBuilder injects: static skill + dynamic memory
  → LLM receives enriched context
```

### Memory write path (session end)
```
Session completes successfully
  → MemoryWriter extracts: app patterns, user preferences, failure learnings
  → MemoryRepository.upsert(app_package, facts)
  → SQLite FTS index updated
```

### Security escalation path
```
Tool call arrives → PolicyEngine.assessRisk(tool, params, context)
  → context includes: current app category, input source (local/remote), action type
  → If HIGH + remote source → force user confirmation
  → If HIGH + local source → SMART mode decides
  → If LOW/MEDIUM → auto-approve per current policy
```

## Trade-offs

### Why selective absorption over full port?
- OpenClaw is ~37k LOC TypeScript designed for server. Porting all of it would be massive effort for features Android doesn't need.
- Android Agent already has working equivalents for the hard parts (perception, action execution, tool routing, compression).
- The valuable parts of OpenClaw are design patterns, not code.

### Why Markdown + SQLite FTS over vector embeddings?
- No embedding model dependency (saves ~100MB+ on device)
- Human-debuggable (critical for eval/tuning workflow)
- SQLite FTS5 is battle-tested on Android
- Keyword search is sufficient for app-specific memory (small corpus per app)

### Why NOT multi-session concurrency?
- Phone is single-user, single-screen. Can only operate one app at a time.
- OpenClaw needs concurrency for multi-channel server. Android Agent doesn't.
- Adding concurrency would complicate the agent loop with no user benefit.

### Why NOT a full relay server now?
- Core product value is local phone automation. Remote triggers are secondary.
- Relay adds infrastructure dependency and security surface.
- Build it when there's user demand for multi-channel input.

## Priority Matrix

| OpenClaw Capability | Android Agent Status | Priority | Related Project |
|---|---|---|---|
| Session management | ✅ Built (`AgentSession`) | — | — |
| Conversation compaction | ✅ Built (3-phase pipeline) | — | — |
| Tool system + safety | ✅ Built (`ToolRouter` + `PolicyEngine`) | — | — |
| Multi-agent delegation | ✅ Built (Planner/Executor) | — | — |
| Screen perception | ✅ Built (a11y tree, superior) | — | — |
| App operation | ✅ Built (a11y actions, superior) | — | — |
| Cross-session memory | ❌ Missing | **P0** | Project 1 |
| Voice-first interaction | 🔶 Partial (native APIs available) | **P0** | Project 2 |
| Session continuity | 🔶 Partial (Hot Idle + checkpoint) | **P1** | Project 3 |
| Agent identity/persona | ❌ Missing | **P1** | Project 4 |
| Tool risk classification | 🔶 Partial (PolicyEngine) | **P2** | Project 5 |
| Device capability ads | ❌ Missing | **P2** | Project 6 |
| Canvas host (multi-app) | ❌ Missing | **P2** | Project 7 |
| Dynamic plugin system | 🔶 Partial (app_skills/) | **P3** | — |
| Channel relay | ❌ Missing | **P3** | Project 3 |

## Key Insight

OpenClaw's architecture reflects a desktop-centric worldview: the computer is the brain, the phone is a sensor. Android Agent inverts this — the phone is the brain _and_ the body. The things OpenClaw does better are all platform-agnostic algorithms (memory retrieval, conversation pruning, identity templates). These port trivially because they're pure logic. The things Android Agent does better are all platform-native capabilities that OpenClaw simulates poorly (app interaction, sensor access, always-with-the-user presence).

**The right frame is not "port OpenClaw to mobile" but "take OpenClaw's best ideas and implement them natively."**

## Self-Review

**Strengths:**
- Maps every OpenClaw capability to concrete Android Agent equivalent or gap
- Grounds analysis in actual codebase (`HistoryManager`, `PolicyEngine`, `app_skills/`, `ToolRegistry`)
- Clear "absorb, don't port" framing with actionable priorities
- Each enhancement extends existing systems rather than adding new layers

**Gaps identified:**
- **Offline/intermittent connectivity** not addressed — mobile agents lose network; how should the agent behave when LLM API is unreachable?
- **Plugin system dynamic tool registration** security implications underexplored — a skill that can register tools is a trust boundary change
- **Compression verification** (OpenClaw's two-pass summarization to catch information loss) not addressed — current Android Agent compression is single-pass
- **Performance budget** — how does cross-session memory injection affect token usage within the 100K budget?
