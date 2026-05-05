# Tool System

> ToolRegistry, ToolRouter, PolicyEngine, and tool execution lifecycle.
> Last updated: 2026-05-05 (termux_shell)

## Overview

Tools are the agent's interface to the Android device. Every tool execution follows a state machine lifecycle with validation, policy checks, and observation capture.

Tool descriptions are also the canonical owner of tool-local prompt semantics. Cross-tool behavior
stays in agent system prompts; app-specific behavior lives in `app_skills/<package>/SKILL.md`.

---

## Tool Execution Lifecycle

```
┌──────────────┐
│  VALIDATING  │ ─── Invalid ──► ERROR
└──────┬───────┘
       │ Valid
       ▼
┌──────────────┐
│ POLICY_CHECK │ ─── Deny ────► ERROR
└──────┬───────┘
       │ Allow or AskUser
       ▼
┌──────────────┐
│   AWAITING   │ ─── Denied/Timeout ──► CANCELLED
│   APPROVAL   │
└──────┬───────┘
       │ Approved
       ▼
┌──────────────┐
│  EXECUTING   │ ─── Exception ──► ERROR
└──────┬───────┘
       │ Success
       ▼
┌──────────────┐
│   SUCCESS    │
└──────────────┘
```

---

## Core Components

### ToolRegistry

→ See: `tool/ToolRegistry.kt`

Discovery and schema generation:

```kotlin
class ToolRegistry {
    fun register(tool: ToolSpec)
    fun get(name: String): ToolSpec?
    fun getAll(): List<ToolSpec>
    fun generateResponsesApiTools(filter: (ToolSpec) -> Boolean): List<FunctionTool>
}
```

### ToolRouter

→ See: `tool/ToolRouter.kt`

Executes tool calls with lifecycle handling:
- Validates tool exists and parameters are correct
- Checks policy for approval requirements
- Waits for user approval if needed (60s timeout)
- Executes tool and returns result

### PolicyEngine

→ See: `tool/PolicyEngine.kt`

Decides whether tool calls are **allowed**, **denied**, or **require approval** based on **app tier** (where the agent is), not action type.

Decision inputs: `(toolName, params, packageName, destinationPackage?) → PolicyDecision`

Decision flow:

1. **Non-screen-changing tools** (scratchpad, write_todos, remember_experience, complete_task, ask_user, shell, termux_shell) → always `Allow`
2. **Escape actions** (system_button back/home) → always `Allow` (agent must not be trapped in a blocked app)
3. **BLOCKED app** → always `Deny`, even in `AUTO_APPROVE` mode (absolute floor)
4. **`browser_script` special rule** → after the BLOCKED-app floor and before user allow-lists,
   `SMART` mode always asks, even for `com.android.chrome` (`NORMAL` tier)
5. **User allow-list** → session/persistent package allow-list can approve ordinary screen-changing
   tools outside `ALWAYS_ASK`, but it does not bypass the `browser_script` SMART prompt
6. **Approval mode**:

| Mode | NORMAL app | CAUTIOUS app | BLOCKED app |
|------|------------|--------------|-------------|
| `ALWAYS_ASK` | Ask | Ask | Deny |
| `AUTO_APPROVE` | Allow | Allow | Deny |
| `SMART` | Allow | Ask | Deny |

`PolicyDecision` outcomes:
- `Allow` — execute immediately
- `Deny(reason)` — forbidden by policy, error returned to LLM
- `AskUser(reason, appTier)` — requires user approval dialog

### AppClassifier

→ See: `tool/AppClassifier.kt`, `protocol/AppTier.kt`

Classifies Android packages into security tiers.

**Lookup order:** `userOverrides[pkg] → appTiers[pkg] → CAUTIOUS`

| Tier | Meaning | Examples |
|------|---------|---------|
| `BLOCKED` | Financial/auth apps — screen masked, all actions denied | Chase, PayPal, authenticators, crypto wallets |
| `CAUTIOUS` | Unknown/unclassified apps — actions require approval in SMART mode | Any app not in `app_tiers.json` |
| `NORMAL` | Known safe apps — actions auto-approved in SMART mode | Settings, Photos, Calendar, Clock |

**Configuration:** `assets/security/app_tiers.json` defines the base tier map. User overrides can only **tighten** (NORMAL→CAUTIOUS/BLOCKED, CAUTIOUS→BLOCKED), never loosen.

**Masking:** `AppClassifier.maskIfBlocked(snapshot, pkg)` returns an empty snapshot (no elements, no image) for BLOCKED packages. Called at three points to prevent BLOCKED app content from reaching the LLM: (1) pre-turn capture, (2) post-action observation building (threaded via `appClassifier` parameter through all executors and `PostActionAnalysis`), and (3) capture-layer artifact gating. The capture layer (AccessibilityPlatform/VirtualDisplayPlatform) gates on package tier **before** writing any trace artifacts — if package is BLOCKED or unknown (null), no screenshots or tree artifacts are written.

**Fail-closed:** `fromAssets()` throws `IllegalStateException` if `app_tiers.json` is missing, corrupt, or contains unknown tier strings. Session cannot start without a valid classifier.

---

## Built-in Tools

| Tool | Description | Key Parameters |
|------|-------------|----------------|
| `mobile_action` | Screen-targeted touch interactions | `action`, targeting (`element_index`, `text`, coordinates) |
| `open_app` | Launch app by name (denied for BLOCKED apps) | `app_name` |
| `system_button` | Press Android system key | `button` (`back`, `home`, `enter`, `recents`) |
| `wait` | Pause for UI settle | `duration_ms` |
| `complete_task` | Signal completion | `status`, `answer` |
| `write_todos` | Todo list management | `todos` array |
| `scratchpad` | JSON-backed memory | `action`, `content` (JSON string for write) |
| `delegate_task` | Sub-agent delegation (PRO mode, always routes to executor) | `query`, `important_notes` |
| `ask_user` | Request user help mid-task | `type` (`question`/`action`), `message` |
| `shell` | Execute file-related shell commands | `command`, optional `timeout_ms` |
| `termux_shell` | Execute full Linux bash through the Termux bridge | `command`, optional `cwd`, `timeout_seconds`, `env` |
| `remember_experience` | Save reusable learning to long-term memory | `category`, `content`, optional `package_name` |
| `browser_script` | Run a JS automation script against the user's real Chrome over CDP | `script`, optional `timeout_ms` |

`delegate_task` is registered lazily only when the selected agent definition requires delegation.

`ask_user` is registered lazily in `SessionAgentRunner.start()`. It suspends the agent coroutine via `UserResponseChannel` (CompletableDeferred) until the user responds through the capsule UI, or times out after 5 minutes. See [session.md](session.md) for `UserResponseChannel` details.

`shell` executes shell commands on the device via `ProcessBuilder("sh", "-c", command)` with a 10s timeout. Two layers of validation: (1) **metacharacter rejection** — `;`, `|`, `&`, `` ` ``, `>`, `<`, `$`, newline, and CR are rejected at validation time to prevent chaining/bypass; (2) **blocklist** — first token checked against `am`, `pm`, `reboot`, `su`, `env`, `xargs`, `find`. Output is capped at `MAX_OUTPUT_CHARS` (4096) with a truncation indicator when exceeded. Password field text is suppressed at the perception layer (Perceptor checks `AccessibilityNodeInfo.isPassword()`).

`termux_shell` runs bash through a Python bridge daemon inside Termux. It supports pipes,
redirects, git, python, ripgrep, and installed Termux packages. The tool is registered only when
the session's immutable `TermuxCapabilitySnapshot` is enabled and ready. Non-zero exit codes and
command timeouts are returned as structured tool output; bridge transport/protocol failures are
tool failures. The tool is non-screen-changing and non-hoistable, so it keeps the LLM-returned order
with UI tools.

→ See: [app/termux_shell.md](../app/termux_shell.md) for bridge setup, lifecycle, state reasons, and
known OEM limitations.

`remember_experience` writes a timestamped entry to the persistent memory store. Categories: `app` (requires `package_name`), `user_pref`, `device`. Content is prefixed with kind tags (`[workflow]`, `[pitfall]`, `[verification]`). Classified as cognitive (non-screen-changing) and auto-allowed. A **memory gate** blocks writes when the foreground app is BLOCKED (financial/auth), preventing the agent from creating persistent knowledge about blocked app content. Registered eagerly in `SessionServices.create()`.

→ See: [agent/memory.md](../agent/memory.md) for the full memory system.

`browser_script` is the single agent-facing entry point for the Browser CDP runtime. The script body uses `await cdp(method, params, options)` against the user's real Chrome (via Shizuku → `chrome_devtools_remote`); loops, branches, parsing and retries all happen inside the script so a single tool call replaces many CDP round-trips. The tool itself stays thin:

- **Strict validation** — `script` must be a non-blank string; `timeout_ms` must be Int/Long (fractional Doubles and string timeouts are rejected, not silently coerced). Per-call timeout is clamped to a runtime cap (default 120s).
- **Execution-time capability gate** — `BrowserScriptCapabilityGate.acquire()` is consulted on every call, in cheapest-first order: experimental flag → Shizuku availability → Shizuku permission → `ShizukuChromeDevtoolsBridge.preflight()`. Each gate failure surfaces a distinct `Unavailable(code, reason)` (e.g. `experimental_disabled`, `shizuku_permission_missing`, `chrome_not_running`, `devtools_socket_missing`) so the agent and UI can compose the right setup guidance instead of a generic error. `DefaultBrowserScriptCapabilityGate` is the production wiring; the gate is injected so unit tests have an Android-free seam.
- **Cooperative in-flight cancellation** — `execute()` runs `runOnce` inside a `coroutineScope` with a 50ms watchdog launch that polls `context.isCancelled()`; when set mid-call, the watchdog cancels the scope, the runner unwinds via structured concurrency, and the tool returns `ToolExecutionResult.Cancelled` with real elapsed time (not 0ms).
- **Bounded compact output** — successful payloads return as raw JSON; oversized outputs are truncated with an explicit `[truncated: original_chars=N]` marker that fits *inside* the cap (default 8192 chars; constructor `require()`s a minimum of 64 to keep the marker intact).
- **Trace metadata** — every call writes a `BrowserScriptTraceMetadata` record (script, timeout, duration, outcome, outcomeCode, severity, retryable, full serialized runner JSON, error message, char counts) so the trace/debug pipeline sees the full payload while the prompt context stays bounded. Outcome taxonomy: `ok`, `capability_unavailable`, `script_failure` (PERMANENT, agent must rewrite), `runner_timeout` (TRANSIENT, retryable), `cancellation` (TRANSIENT, not retryable), `host_error` (TRANSIENT, retryable). `rawResultJson` always carries the full `ScriptResult` (success payload OR error JSON) — never the user-facing text.

`SessionServices.create()` registers the tool and owns `BrowserSessionManager`; `SessionToolingBootstrapper` stays Android-free and does not receive `Context` or create WebViews.

→ See: [browser.md](browser.md) for session lifecycle, Shizuku/CDP ownership, cleanup, and policy details. The project design source of truth remains [Browser CDP runtime design](../../../projects/active/browser/cn/design_codex.md).

### mobile_action Actions

| Action | Description |
|--------|-------------|
| `click` | Tap target |
| `long_press` | Long tap target |
| `type` | Type into focused or targeted field (`input_text`) |
| `scroll` | Content-direction scroll (`up/down/left/right`), optionally scoped to a scrollable element |
| `swipe` | Precision coordinate gesture using explicit `start`/`end` arrays |

### Single Targeting Constraint

For targeted actions (`click`, `long_press`, `type` with target), each call accepts **exactly one** targeting method:
- `element_index` — index from current screen state (preferred)
- `text` + optional `text_index` — visible text on screen
- `x`, `y` — absolute pixel coordinates (last resort)

Multiple targeting methods in a single call → validation error. No implicit priority or cross-target fallback.

Special cases:
- `type` allows no target (types into the currently focused field)
- `scroll` optionally accepts `element_index` to pick a scroll container
- `swipe` uses `start: [x,y]` and `end: [x,y]` (no target selectors)

---

## mobile_action Architecture

Deep dive:

- [mobile_action.md](tool/mobile_action.md)

```
┌──────────────────────────────────────────────────────────────┐
│  Layer 1: TOOL CONTRACT                                       │
│                                                               │
│  MobileActionTool.kt  — ToolSpec, validation, target parsing  │
│  MobileActionInvocation.kt — thin glue: routes to executor,   │
│    maps ActionOutcome → ToolExecutionResult                   │
└───────────────────────────────┬──────────────────────────────┘
                                │
┌───────────────────────────────▼──────────────────────────────┐
│  Layer 2: ACTION EXECUTORS (the single smart layer)           │
│                                                               │
│  PointActionExecutorCore — shared fallback-chain logic         │
│    ├─ ClickExecutor      — thin wrapper (channel mapping)     │
│    └─ LongPressExecutor  — thin wrapper (channel mapping)     │
│  TypeExecutor        — SetTextOnNodeAt → tap-to-focus fallback│
│  ScrollExecutor      — gesture swipe → node scroll fallback    │
│  SwipeExecutor       — raw coordinate swipe                    │
│  TargetResolver      — Target → Point resolution              │
│  ObservationBuilder  — post-action ToolObservation builder    │
└───────────────────────────────┬──────────────────────────────┘
                                │
┌───────────────────────────────▼──────────────────────────────┐
│  Layer 3: ATOMIC PLATFORM                                     │
│                                                               │
│  AccessibilityPlatform — each UIAction = one Android API call │
└──────────────────────────────────────────────────────────────┘
```

**Key properties:**
- Platform is pure mechanism (no fallback, no target resolution)
- Executors are the single smart layer (fallback chains, UI change verification)
- MobileActionInvocation is thin glue (~60 lines)
- Each executor ~80-130 lines, linear and testable

### Executor Fallback Chains

> Priority order centralized in `tool/action/ActionPriorityOrder.kt`

| Action | Attempt 1 | Attempt 2 | On All Fail |
|--------|-----------|-----------|-------------|
| click | `ClickNodeAt(x,y)` | `TapAt(x,y)` (semantic targets only) | Failed with trail |
| long_press | `LongClickNodeAt(x,y)` | `LongPressAt(x,y,ms)` (semantic targets only) | Failed with trail |
| type (with target) | `SetTextOnNodeAt(x,y)` | `TapAt` → `SetTextOnFocused` | Failed with trail |
| type (no target) | `SetTextOnFocused` | — | Failed |
| scroll | `ScrollNodeAt(x,y,direction)` | `Swipe(center→edge)` | Failed with trail |
| swipe | `Swipe(start,end)` | — | Failed |

**Node-first priority** (click, long_press, scroll): Accessibility node actions are attempted first because they are more reliable with semantic targets (text/element_index). Gesture injection is the fallback for coordinate-only targets or when node lookup fails.

Successful attempts capture a post-action snapshot after a settle delay and attach a `ToolObservation`.

**TypeExecutor note:** Attempt 2 (TapAt → SetTextOnFocused) is guarded by `platform.allowTapToFocus()` and skipped when the platform returns false (Virtual Display mode).

### ActionOutcome

→ See: `tool/action/ActionOutcome.kt`

Executor return type, richer than `ActionResult`:

| Outcome | Meaning |
|---------|---------|
| `Success(verified=true)` | Action dispatched and post-action observation path completed |
| `Success(verified=false)` | Reserved for unverified-success paths (currently rare) |
| `Failed(attemptTrail)` | All attempts exhausted |
| `Cancelled` | Cancelled between attempts |

---

## Tool Abstraction

All tools implement `ToolSpec` directly:

```kotlin
interface ToolSpec {
    val name: String
    val description: String
    val parameterSchema: JSONObject
    fun validate(params: JSONObject): ValidationResult
    fun createInvocation(params: JSONObject): ToolInvocation
}
```

### Invocation Types

| Type | Used By |
|------|---------|
| `MobileActionInvocation` | `MobileActionTool` — routes to executors |
| `UIActionInvocation` | `SystemButtonTool`, `WaitTool` — direct UIAction execution |
| Custom invocations | `OpenAppTool`, `WriteTodosTool`, `ScratchpadTool`, `AskUserTool`, etc. |

---

## Tool Observation

Successful tool execution can include post-action screen context:

→ See: `tool/action/ObservationBuilder.kt`

Used by executors (ClickExecutor, LongPressExecutor, TypeExecutor, ScrollExecutor, SwipeExecutor) to capture post-action screen state for the LLM.

---

## Adding New Tools

1. Implement `ToolSpec` in `tool/impl/`
2. Implement required members:
   - `name`, `description`, `parameterSchema`
   - `validate(params)`, `createInvocation(params)`
3. Register in `SessionToolingBootstrapper` (built-in) or `SessionAgentRunner` (lazy)

---

## File Structure

```
tool/
├── ToolSpec.kt               # Tool interface + types
├── ToolCallState.kt          # State definitions
├── ToolCallResult.kt         # Result types
├── ToolName.kt               # Canonical tool/action names + isScreenChanging
├── ToolSchemaConverters.kt   # Schema conversion utilities
├── ToolRegistry.kt           # Discovery/registration
├── ToolRouter.kt             # Execution state machine
├── PolicyEngine.kt           # App-tier-based approval logic
├── AppClassifier.kt          # Package → AppTier classification + snapshot masking
├── action/                   # Executor layer (mobile_action)
│   ├── Target.kt             # Targeting sealed interface
│   ├── ActionOutcome.kt      # Executor result type
│   ├── ActionPriorityOrder.kt # Centralized action priority (node-first vs gesture-first)
│   ├── PointActionExecutorCore.kt # Shared fallback-chain logic (click + long_press)
│   ├── ClickExecutor.kt      # Click thin wrapper (channel mapping)
│   ├── LongPressExecutor.kt  # Long press thin wrapper (channel mapping)
│   ├── TypeExecutor.kt       # Focus-then-type flow
│   ├── ScrollExecutor.kt     # Content-direction scroll cascade
│   ├── SwipeExecutor.kt      # Precision coordinate gestures
│   ├── TargetResolver.kt     # Target → Point resolution
│   ├── UiChangeDetector.kt   # Snapshot fingerprinting (diagnostics utility)
│   └── ObservationBuilder.kt # Post-action observation (with BLOCKED-app masking)
├── handlers/
│   └── UIActionInvocation.kt # Used by SystemButtonTool, WaitTool
└── impl/
    ├── MobileActionTool.kt
    ├── MobileActionInvocation.kt
    ├── OpenAppTool.kt
    ├── SystemButtonTool.kt
    ├── WaitTool.kt
    ├── CompleteTaskTool.kt
    ├── WriteTodosTool.kt
    ├── ScratchpadTool.kt
    ├── DelegateTaskTool.kt
    ├── AskUserTool.kt
    ├── ShellTool.kt
    ├── BrowserScriptTool.kt              # browser_script tool: validation, gate, output cap, trace
    ├── BrowserScriptTypes.kt             # gate/invoker/sink interfaces, outcome taxonomy, runner JSON serializer
    └── DefaultBrowserScriptCapabilityGate.kt  # production gate: experimental flag → Shizuku → preflight
```

---

## Related Docs

- [Session](session.md) - SessionServices registration
- [Platform](platform.md) - AndroidPlatform execution
- [Protocol](../protocol/overview.md) - Action events
- [Planning](../agent/planning.md) - Planning tools
