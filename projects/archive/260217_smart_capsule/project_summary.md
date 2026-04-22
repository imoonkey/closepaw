# Android Agent — Project Summary

Date: 2026-02-17

## What Is This

An AI-powered Android automation app. The user gives a natural-language instruction ("find cheap pencil skirts on Temu"), and an LLM-driven agent operates the phone autonomously — perceiving the screen via accessibility tree, deciding actions, executing taps/swipes/types, and looping until the task is done.

Kotlin/Jetpack Compose. ~26,500 LOC across 219 files. 451 commits.

---

## Project Timeline

| Period | Focus | Key Commits |
|--------|-------|-------------|
| 2026-01-06 – 01-14 | **Foundation**: core agent loop, LLM client, tool system, accessibility platform, overlay UI, scripts | `a4bb910` first commit → infra v2 phases 1–7 |
| 2026-01-19 – 01-20 | **Codebase Review Round 1**: independent reviews by Claude/Codex/Gemini, 6-phase fix sweep (agent core → data infra → session protocol → platform perception → tool system → UI) | PRs #6–#13 |
| 2026-01-20 – 01-22 | **Chat UX & History**: multi-round chat, session history persistence, folder restructure | PRs #14–#23, `30352bb` session history |
| 2026-02-11 | **Virtual Display (Shizuku)**: secondary display for agent to operate on, so user keeps their own screen free | `466382b` → `321c3b9` |
| 2026-02-12 | **Smart Capsule V2**: complete redesign of the floating overlay — thought, takeover, supplement, ask_user | `ba99dcc` → `f8684f9` |
| 2026-02-14 | **Smart Capsule Rounds 5–6**: bug fixes from visual-debug testing, visibility system redesign, session continuity | `a93f2d0` → `cdd19d2` |
| 2026-02-16 | **View→Compose migration** (Round 7): all legacy View overlays migrated to Compose-in-WindowManager | `22bfe0b` → `abf94d6` |
| 2026-02-16 | **Craftsmanship Week** (Overall Review Round 1+2): systematic codebase quality pass — lifecycle hardening, turn pipeline split, VD/Shizuku decomposition, protocol restructuring, DRY | `e088aac` → `74b21c2` |

---

## What Has Been Implemented

### 1. Core Agent Loop

The agent runs a turn-based loop: perceive screen → send to LLM → select tool calls → execute → observe result → repeat.

- **Agent orchestration**: `AgentSession` manages lifecycle; `AgentAgentRunner` runs the turn loop; `TurnPlanningPhaseRunner` and `TurnExecutionPhaseRunner` handle the two phases of each turn; `TurnErrorClassifier` decides retry vs abort.
- **LLM clients**: OpenAI Responses API (`OpenAIResponseClient`) and Chat Completions API (`ChatCompletionClient`), both with shared retry/backoff logic (`CloudLlmRetry`, `CloudStreamRetryRunner`). Local model support via `LocalLlmClient`.
- **Screen perception**: accessibility tree capture, node serialization, screenshot capture — works on both real screen and virtual display.
- **Tool system**: 15+ tools — `click`, `long_press`, `swipe`, `type_text`, `open_app`, `system_button`, `scroll`, `set_text`, `complete_task`, `ask_user`, `agent_thought`, etc. Each tool has validation, invocation, and observation.
- **Session recording**: `SessionRecordingService` persists full conversation history (user messages, agent messages, tool calls, screenshots) to disk. `HistoryManager` handles storage.

### 2. Virtual Display Platform (Shizuku)

Lets the agent operate on a secondary Android display while the user keeps their own screen. Uses Shizuku for privileged system API access.

**Key components** (16 files, ~1,924 LOC in `platform/virtualdisplay/`):

| Component | What It Does |
|-----------|-------------|
| `VirtualDisplayPlatform` | Top-level orchestrator: creates/destroys virtual display, delegates to sub-components |
| `ShizukuClient` | Thin binder wrapper for hidden Android APIs (create display, inject input, launch app) |
| `ShizukuDisplayTransport` | Display creation/release via `DisplayManager` hidden APIs |
| `ShizukuInputTransport` | Input event injection via `InputManager` hidden APIs |
| `ShizukuShellExecutor` | Shell commands via Shizuku binder |
| `ShizukuActivityLauncher` | Launch apps on a specific display via `ActivityOptions.setLaunchDisplayId` |
| `ShizukuRuntimeGateway` | Permission checking, Shizuku availability |
| `ShizukuServiceProxyProvider` | Binder proxy caching for system services |
| `VirtualDisplayCaptureCoordinator` | Frame capture via ImageReader |
| `VirtualDisplaySurfaceController` | Surface switching between ImageReader (capture) and SurfaceView (live preview) |
| `VirtualDisplayScreenshotProcessor` | Bitmap processing from captured frames |
| `VirtualDisplayInputInjector` | Coordinate mapping and input injection on virtual display |
| `VirtualDisplayAppController` | App management on virtual display |
| `VirtualDisplayWindowAccessor` | Accessibility tree queries scoped to virtual display |
| `VirtualDisplayViewerTouchHandler` | Touch forwarding from viewer to virtual display |

**VD Viewer**: `VirtualDisplayViewerActivity` — a transient activity (excluded from recents) that shows the agent's virtual display as a live SurfaceView. User can watch the agent work. Swipe up to return to their own screen.

**Platform auto-detection**: app checks if Shizuku is available at startup. If yes → VirtualDisplayPlatform; if no → AccessibilityPlatform (agent operates on the real screen with the user watching).

### 3. Smart Capsule V2

The agent's face — a compact floating overlay that shows what the agent is doing and lets the user interact. Replaced the original dumb status bar with a full collaboration surface.

**Four capabilities:**

| Capability | What | How |
|-----------|------|-----|
| **Thought** | See what the agent is doing in real time | `agent_thought` parameter extracted from every tool call, displayed as a single line |
| **Takeover** | Grab the phone, operate it yourself, then resume | `Op.Takeover` pauses agent; `Op.Resume` restarts with fresh screen capture; stale tool calls discarded |
| **Supplement** | Inject a mid-task message ("by the way, I want black") | `Op.Supplement` adds user message to conversation history; agent sees it next turn |
| **ask_user** | Agent asks for help (login, choice, captcha) | `AskUserTool` suspends via `CompletableDeferred`; capsule expands to show question/instruction; user responds; agent continues |

**State machine** — `CapsuleStateHolder` (283 LOC) with `CapsuleMode` sealed interface:

```
Hidden → Running → TakeoverPending → Takeover → Running (cycle)
Running → WaitingForInput / WaitingForAction → Running
Any active → Done (auto-hides 3s) / Error (stays until dismissed)
```

- Guards prevent invalid transitions (logged, silently ignored).
- Single source of truth — no boolean soup, no parallel state machines.
- `CapsuleRenderSpec` is a pure stateless derivation from `CapsuleMode` → drives all rendering.

**Three rendering contexts, one implementation:**

| Context | When | Implementation |
|---------|------|---------------|
| In-app | User in Android Agent app | `SmartCapsuleCompose` wrapping `SmartCapsuleSurface` |
| System overlay | User in another app / VD viewer | `CapsuleOverlayHost` (Compose-in-WindowManager) wrapping same `SmartCapsuleSurface` |
| Status island | VD mode, user on their own screen | `IslandOverlayHost` — compact pill with dot + truncated thought, tap opens VD viewer |

**Visibility system** — `ServiceOverlayController` (353 LOC) with `OverlayLocationPolicy`:

- `applyVisibility()` — single function that decides what's visible given current state.
- `ShowPreference` enum (CAPSULE vs ISLAND) — user toggles by minimizing/expanding.
- Invariants: capsule and island never simultaneously visible; MAIN_APP context → no system overlays; `WaitingFor*/Error` force capsule visible.

**Edge glow** — pulsing gradient edges that indicate agent activity state (active, executing, success, error, paused). Shows in both A11y and VD overlay contexts.

**Action visualizer** — ripple circles on tap, trail lines on swipe — visual feedback showing what the agent is doing on screen.

### 4. Overlay View→Compose Migration (Round 7)

All legacy View-based system overlays were migrated to Compose-in-WindowManager:

| Old (Deleted) | New (Compose) |
|---------------|---------------|
| `SmartCapsuleManager` + `SmartCapsuleLayoutBuilder` + `SmartCapsuleRenderer` + `SmartCapsuleAnimator` | `CapsuleOverlayHost` |
| `StatusIslandManager` | `IslandOverlayHost` + `StatusIslandCompose` |
| `EdgeGlowView` + `EdgeGlowManager` | `GlowOverlayHost` + `EdgeGlowCompose` |
| `ActionVisualizerManager` + `ClickRippleView` + `SwipeTrailView` | `VisualizerOverlayHost` + `ActionVisualizerCompose` |

Infrastructure: `ServiceLifecycleOwner` (LifecycleOwner for AccessibilityService) + `OverlayComposeHost` (reusable ComposeView-in-WindowManager wrapper). ~10 legacy View files deleted, ~10 new Compose files created.

### 5. Chat UX & Session History

- Multi-round chat interface with `ChatScreen` (Compose) showing conversation history.
- `ChatViewModel` processes agent events into chat messages (user messages, agent thinking, tool calls as action cards, completion).
- `SessionRecordingService` persists sessions to disk; `HistoryManager` manages session listing/loading.
- Session continuity: returning to the app after pressing Home restores the active session's chat transcript.

### 6. Codebase Quality (Craftsmanship Week)

Two rounds of systematic review and refactoring:

**Round 1** — Independent reviews by Claude, Codex, Gemini. 10 Claude design docs + 4 Codex design docs. Executed:

| Plan | What | Status |
|------|------|--------|
| A: Runtime correctness | Fix `AgentService` shutdown race, `debug-run.sh` completion detection, history finalize consistency | Done |
| B: Turn pipeline split | Extract `TurnErrorClassifier`, `TurnPlanningPhaseRunner`, `TurnExecutionPhaseRunner` | Done |
| C: VD/Shizuku decomposition | Split monolithic `VirtualDisplayPlatform` + `ShizukuClient` into 16 focused files | Done |
| D: Protocol/Config restructuring | `SessionConfig`, `SessionServices` decomposition, `AgentEvent` domain split | Done |
| E: LLM consolidation | `CloudLlmRetry`, `CloudStreamRetryRunner` shared retry logic | Done |
| F: Tool DRY | Shared text-tool helpers, reason suffix formatting | Partial |

**Round 2** — Code review of Round 1 changes by both Claude and Codex, aligned via multi-turn discussion. Fixed:
- Thread safety: `SessionRecordingService` stale-read-then-overwrite race (synchronized blocks)
- Input loss: `sessionRetryScheduled` silently dropping user input during session creation
- Completion metadata: `completedNormally` always true regardless of outcome
- Record overwrite: `recordUserMessage()` stale snapshot overwrite

### 7. Dev Tooling

- `debug-run.sh` — end-to-end test script: installs app, sends task via adb, monitors logcat for `TaskCompleted`, timeout guard.
- `logs.sh` — logcat filtering for agent logs.
- Claude Code skills: `/plan`, `/verify`, `/build-fix`, `/code-review`, `/visual-debug`, `/ux-visual-debug`, `/tdd`, `/orchestrate`, `/align` (Claude+Codex alignment protocol).
- Inspection tool for comparing accessibility tree with screenshots.

---

## Architecture At a Glance

```
┌─────────────────────────────────────────────────┐
│                    UI Layer                      │
│  ChatScreen  SmartCapsuleSurface  VDViewerActivity│
│  CapsuleOverlayHost  IslandOverlayHost  GlowHost │
└────────────────────┬────────────────────────────┘
                     │
┌────────────────────┴────────────────────────────┐
│              App / Orchestration                 │
│  AgentService  ServiceOverlayController          │
│  CapsuleStateHolder  OverlayLocationPolicy       │
│  MainActivity  ChatViewModel                     │
└────────────────────┬────────────────────────────┘
                     │
┌────────────────────┴────────────────────────────┐
│              Session / Agent                     │
│  AgentSession  AgentAgentRunner                  │
│  TurnPlanningPhaseRunner  TurnExecutionPhaseRunner│
│  SessionRecordingService  HistoryManager         │
│  UserResponseChannel                             │
└────────────────────┬────────────────────────────┘
                     │
┌────────────────────┴────────────────────────────┐
│              Protocol / Config                   │
│  Op  AgentEvent  SessionConfig  SessionServices  │
│  CapsuleMode  CapsuleRenderSpec  CapsuleContext  │
└────────────────────┬────────────────────────────┘
                     │
┌────────────────────┴────────────────────────────┐
│              Tool System                         │
│  ToolSpec  ToolInvocation  ToolRegistry          │
│  click  swipe  type_text  open_app  ask_user ... │
└────────────────────┬────────────────────────────┘
                     │
┌────────────────────┴────────────────────────────┐
│              Platform Layer                      │
│  AccessibilityPlatform  VirtualDisplayPlatform   │
│  ShizukuClient + 7 transport/helper files        │
│  VDCaptureCoordinator  VDInputInjector  ...      │
└────────────────────┬────────────────────────────┘
                     │
┌────────────────────┴────────────────────────────┐
│              LLM Layer                           │
│  OpenAIResponseClient  ChatCompletionClient      │
│  LocalLlmClient  CloudLlmRetry                   │
└─────────────────────────────────────────────────┘
```

---

## Key Design Decisions

1. **Sealed class state machine over boolean flags.** `CapsuleMode` is a sealed interface with 8 states. One value drives the entire UI. No `isLoading && !isPaused && hasThought` soup.

2. **Dual platform with shared interface.** `AndroidPlatform` interface implemented by both `AccessibilityPlatform` (real screen) and `VirtualDisplayPlatform` (Shizuku secondary display). Agent code is platform-agnostic.

3. **Compose everywhere.** All UI rendering — in-app and system overlays — uses Jetpack Compose. `SmartCapsuleSurface` is the single shared composable for the capsule across all contexts.

4. **Tool suspension for user interaction.** `ask_user` is a normal tool that suspends on `CompletableDeferred`. No special-casing in the turn loop. The tool just takes longer to execute.

5. **Optimistic state transitions.** Takeover and user response transitions happen immediately on click (before server confirmation). Safe because the session has no rejection paths.

6. **Single visibility authority.** `applyVisibility()` in `ServiceOverlayController` is the one function that decides what overlay windows are shown. No scattered show/hide calls.

7. **KISS principle throughout.** From the owner's original notes: "精髓不在于复杂，反而在于简单" — the essence is simplicity, not complexity. Design docs consistently favor 1-line fixes over multi-file event plumbing, deletion over addition, existing patterns over new abstractions.

---

## Current State

- **Build**: compiles clean (`assembleDebug`, `lint`, `test` all pass).
- **Codebase**: 219 files, ~26,500 LOC. No file exceeds 400-line guideline after Round 1 splits (except a few overlay Compose files that are cohesive rendering code).
- **Test coverage**: `CapsuleStateHolderTest` (384 LOC), `CapsuleRenderSpecTest` (35 LOC), `OverlayLocationPolicyTest` (311 LOC), plus unit tests for core agent logic.
- **Known remaining items**:
  - Prompt composition consolidation (Plan M, deferred).
  - Settings UI generic extraction (Plan K, partial).
  - End-to-end on-device UX validation for overlay focus/IME in WaitingForInput.
  - Visual parity checks for glow pulse and gesture visualizer timing post-migration.
