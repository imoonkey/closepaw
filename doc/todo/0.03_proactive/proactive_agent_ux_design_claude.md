# Proactive Android Agent — Product & UX Design

## 1. Problem Statement

### Who has the problem?

The user of an AI-powered Android agent.

### What's the problem?

Today the agent is **purely reactive**: the user must context-switch into the agent app, formulate a request in natural language, and wait for the agent to complete it. This interaction model has three structural flaws:

1. **The user is the bottleneck.** The agent has the ability to read screens, navigate apps, and perform multi-step workflows — but it sits idle until a human types a sentence. Every valuable action requires the user to (a) realize an action is needed, (b) know the agent can help, and (c) articulate the request.

2. **Timing is lost.** Many valuable actions are time-sensitive — upcoming meetings, incoming messages, fleeting deals, download completions. By the time the user remembers to ask, the moment has passed or the user has already done it manually.

3. **Repetition is wasteful.** The user issues the same kinds of requests over and over: "open the meeting link," "summarize my notifications," "check the weather." These are patterns, not novel tasks, yet the user must invoke each one manually every time.

### What does success look like?

The agent anticipates what the user needs, prepares it, and surfaces it at the right moment — so the user's only job is to say "go" (or dismiss). The user feels like they have a thoughtful assistant who watches out for them, not a command terminal they have to operate.

---

## 2. Design Principles

### 2.1 Thoughtful assistant, not surveillance tool

Surveillance reports everything it sees. A thoughtful assistant notices things that matter **to you** and brings them up at the **right time**. The agent must earn trust through relevance and restraint, not through volume.

### 2.2 Interrupt budget

Every proactive surfacing costs the user attention. Attention is finite and sacred. The agent has an implicit *interrupt budget* — and it must spend it wisely. An agent that interrupts five times a day with perfect suggestions is more valuable than one that interrupts fifty times with decent ones.

### 2.3 Prepare, don't just notify

This agent's unique capability is **UI automation** — it can actually operate apps. A proactive notification that says "your meeting starts in 10 minutes" is a clock feature. A proactive agent that says "your standup starts in 10 minutes — I've got the Zoom link ready, tap to join" and then *opens Zoom and joins the call on tap* — that's magic. The agent should prepare the full action so the user just confirms.

### 2.4 Progressive autonomy

Users don't hand over control all at once. The system must support a spectrum: from "just tell me what you notice" to "handle it and let me know afterward." The user moves along this spectrum at their own pace, per-category.

### 2.5 Silence is a feature

When there's nothing worth surfacing, the agent stays quiet. No "all clear" messages. No daily summaries nobody asked for. Silence communicates competence.

---

## 3. Architecture Overview — The Three-Layer Model

```
┌─────────────────────────────────────────────────────────────────────┐
│                          Layer 3: AGENT                             │
│  Full LLM reasoning loop. Prepares actions, drafts, summaries.     │
│  Fires only when Layer 2 says "this is worth it."                  │
│  Cost: HIGH (LLM call, battery, latency)                           │
└───────────────────────────────┬─────────────────────────────────────┘
                                │ (triggered selectively)
┌───────────────────────────────┴─────────────────────────────────────┐
│                        Layer 2: TRIAGE                              │
│  Lightweight decision engine. Filters events from Layer 1.         │
│  Rules + heuristics + learned patterns. On-device, fast, cheap.    │
│  Most events die here. That's the point.                           │
└───────────────────────────────┬─────────────────────────────────────┘
                                │ (events flow up)
┌───────────────────────────────┴─────────────────────────────────────┐
│                      Layer 1: AWARENESS                             │
│  Always-on, near-zero cost. Listens to system events.              │
│  Screen context, notifications, calendar, time, app state.         │
│  Normalizes everything into a uniform event stream.                │
└─────────────────────────────────────────────────────────────────────┘
```

### Why three layers?

Battery life. A naive "send everything to the LLM" approach drains the battery in hours and burns API credits. Layer 1 is essentially free (system callbacks). Layer 2 is cheap (on-device logic). Layer 3 fires rarely and only when the payoff justifies the cost.

---

## 4. Layer 1: Awareness — Event Sources

Layer 1 turns Android system signals into a normalized event stream. Each source is an independent module that emits `AwarenessEvent` objects.

### 4.1 Event Sources

| Source | Android Mechanism | What It Captures | Permission Required |
|--------|-------------------|-------------------|---------------------|
| **Notifications** | `NotificationListenerService` | All posted/removed notifications across apps. Structured: package, title, text, channel, timestamp. | Notification access (system setting) |
| **Screen Context** | `AccessibilityService` (already running) | Current foreground app + window title. Not continuous screen reading — just lightweight context on window change events (`TYPE_WINDOW_STATE_CHANGED`). | Already granted |
| **Calendar** | `ContentObserver` on `CalendarContract.Events` | New/modified events. Periodically: "next event in N minutes." | `READ_CALENDAR` |
| **Time/Schedule** | `AlarmManager` / `WorkManager` | Periodic ticks (every 15 min), exact alarms for known upcoming events. | `SCHEDULE_EXACT_ALARM` (for precise timing) |
| **App Lifecycle** | `UsageStatsManager` | App open/close patterns (coarse). Useful for learning routines. | Usage access (system setting) |
| **Media** | `ContentObserver` on `MediaStore` | New photos/screenshots detected. | `READ_MEDIA_IMAGES` |

### 4.2 AwarenessEvent Schema

```kotlin
data class AwarenessEvent(
    val id: String,                    // UUID
    val source: EventSource,           // NOTIFICATION, SCREEN, CALENDAR, TIME, MEDIA
    val timestamp: Long,
    val payload: EventPayload,         // Source-specific structured data
    val dedupeKey: String,             // For throttling/merging
)

sealed interface EventPayload {
    data class Notification(
        val packageName: String,
        val title: String,
        val text: String,
        val category: String?,         // msg, email, social, transport, etc.
        val isOngoing: Boolean,
    ) : EventPayload

    data class ScreenChange(
        val packageName: String,
        val windowTitle: String?,
    ) : EventPayload

    data class CalendarEvent(
        val eventId: Long,
        val title: String,
        val startTime: Long,
        val location: String?,
        val meetingUrl: String?,
        val minutesUntilStart: Int,
    ) : EventPayload

    data class TimeTick(
        val scheduledTime: Long,
        val label: String?,            // "morning_routine", "evening_review", etc.
    ) : EventPayload

    data class NewMedia(
        val uri: String,
        val mediaType: String,         // image, video, screenshot
    ) : EventPayload
}
```

### 4.3 Throttling at Layer 1

Before events leave Layer 1, basic throttling applies:

- **Dedup window**: Same `dedupeKey` within 10 seconds → merged into one event.
- **Burst cap**: Max 5 events per source per minute. Excess events queued, not dropped.
- **Battery guard**: If battery < 15%, only `CALENDAR` and `TIME` sources remain active.

---

## 5. Layer 2: Triage — The Decision Engine

Triage answers one question: **"Is this event worth waking the agent?"**

### 5.1 Triage Architecture

```
AwarenessEvent
      │
      ▼
┌──────────────┐     ┌──────────────────┐
│ Recipe Match │────▶│ Recipe matched?  │──yes──▶ Trigger Agent (Layer 3)
└──────────────┘     │ with confidence  │
                     └────────┬─────────┘
                              │ no
                              ▼
                     ┌──────────────────┐
                     │ Heuristic Score  │──above threshold──▶ Trigger Agent
                     │ (urgency ×       │
                     │  relevance ×     │
                     │  user-state)     │
                     └────────┬─────────┘
                              │ below threshold
                              ▼
                           Discard
```

### 5.2 Recipes — User-Defined Proactive Rules

Recipes are the user's way of telling the agent what "proactive" means **to them**. They are explicit trigger → action mappings.

```kotlin
data class Recipe(
    val id: String,
    val name: String,                      // "Meeting auto-join"
    val trigger: RecipeTrigger,            // When to fire
    val action: RecipeAction,              // What to do
    val autonomyLevel: AutonomyLevel,      // How much permission
    val enabled: Boolean,
    val quietHours: QuietHoursPolicy?,     // When NOT to fire
)

sealed interface RecipeTrigger {
    data class OnNotification(
        val fromPackages: Set<String>?,     // null = any
        val containsKeywords: Set<String>?, // null = any
        val category: String?,
    ) : RecipeTrigger

    data class BeforeCalendarEvent(
        val minutesBefore: Int,             // 5, 10, 15...
        val eventTitlePattern: String?,     // regex or null = any
    ) : RecipeTrigger

    data class OnSchedule(
        val cronExpression: String,         // "0 8 * * MON-FRI"
    ) : RecipeTrigger

    data class OnScreenContext(
        val inApp: String,                  // package name
        val screenPattern: String?,         // title/content heuristic
    ) : RecipeTrigger

    data class OnNewMedia(
        val mediaType: String,              // "screenshot", "photo"
    ) : RecipeTrigger
}

sealed interface RecipeAction {
    data class AgentTask(val prompt: String) : RecipeAction     // Full agent loop
    data class QuickAction(val steps: List<String>) : RecipeAction  // Predefined steps
    data class Notify(val template: String) : RecipeAction       // Just notify user
}
```

**Example recipes:**

| Recipe | Trigger | Action | Autonomy |
|--------|---------|--------|----------|
| Meeting auto-join | Calendar event in 5 min with Zoom/Meet link | Open meeting app, join call | Confirm (user taps "Join") |
| Notification digest | 5+ notifications accumulated in 10 min | Agent summarizes and groups | Auto (agent posts summary) |
| Screenshot share | New screenshot taken | Offer to share, annotate, or OCR | Suggest (capsule pulse) |
| Morning briefing | Every weekday at 8:00 AM | Weather + calendar + unread summary | Auto (agent prepares, user reads when ready) |
| Important DM alert | WhatsApp/Telegram DM from starred contacts | Surface immediately with reply draft | Confirm (user reviews draft, taps send) |

### 5.3 Heuristic Scoring (for events without matching recipes)

When no recipe matches, the triage engine computes a relevance score:

```
score = urgency × relevance × (1 - interruptCost)

urgency:
  - Time-sensitive (meeting in 5 min, expiring offer): 0.8–1.0
  - Moderate (new message from known contact): 0.4–0.6
  - Low (app update, promotional notification): 0.0–0.2

relevance:
  - From apps the user interacts with daily: 0.7–1.0
  - From apps used occasionally: 0.3–0.5
  - From apps rarely used: 0.0–0.2

interruptCost:
  - User is idle (screen off > 5 min, or on home screen): 0.1
  - User is casually browsing (social media, news): 0.3
  - User is actively working (typing, in meeting app): 0.7
  - User is in Do Not Disturb / quiet hours: 0.95
```

**Threshold**: Score must exceed **0.5** to trigger Layer 3. This is intentionally conservative — false positives destroy trust faster than false negatives.

### 5.4 Quiet Hours

Users can define periods when proactive behavior is suppressed:

- **Manual**: "Don't interrupt me for the next 2 hours"
- **Schedule**: "No interruptions 11 PM – 7 AM"
- **Contextual**: "Don't interrupt when I'm in [meeting app / meditation app / etc.]"
- **System**: Respect Android's Do Not Disturb mode

During quiet hours, events are **queued, not dropped**. When quiet hours end, the triage engine batches queued events into a single digest.

---

## 6. Layer 3: Agent — Preparing and Acting

When triage decides an event is worth attention, it triggers the existing agent loop with a proactive context.

### 6.1 Proactive Task Submission

```kotlin
// New Op type for proactive triggers
sealed interface Op {
    // ... existing ops ...
    data class ProactiveTrigger(
        val event: AwarenessEvent,
        val recipe: Recipe?,                // null if heuristic-triggered
        val autonomyLevel: AutonomyLevel,
        val prompt: String,                 // Constructed from event + recipe
    ) : Op
}
```

The session handles `ProactiveTrigger` similarly to `UserInput`, but with additional constraints:

- **Budget**: Max 3 proactive agent loops per hour (to protect battery/credits).
- **Preemption**: If a user-initiated task is running, proactive trigger is queued.
- **Short leash**: Proactive loops have a lower `maxTurns` limit (e.g., 5 vs 25 for user tasks).

### 6.2 Proactive Agent Definition

A new `ProactiveAgentDef` with a tailored system prompt:

```
You are a proactive assistant. You've been triggered because:
{trigger_description}

Your job:
1. Assess whether this truly warrants the user's attention.
2. If yes: prepare the minimal, most useful response or action.
3. If no: respond with DISMISS and explain why.

Rules:
- Be extremely concise. The user hasn't asked for this — earn their attention.
- If you prepare an action, describe it clearly but don't execute unless autonomy = AUTO.
- Never chain long sequences of UI actions proactively. Keep it to 1–3 steps max.
- When in doubt, suggest rather than act.
```

### 6.3 Agent Output for Proactive Triggers

The agent produces a `ProactiveResult`:

```kotlin
sealed interface ProactiveResult {
    // Agent decided this isn't worth surfacing after all
    data class Dismiss(val reason: String) : ProactiveResult

    // Suggestion: show to user, no action prepared
    data class Suggestion(
        val title: String,           // "3 unread DMs"
        val body: String,            // Summary text
        val actions: List<SuggestedAction>,
    ) : ProactiveResult

    // Prepared action: agent knows exactly what to do, waiting for "go"
    data class PreparedAction(
        val title: String,           // "Join standup"
        val body: String,            // "Zoom meeting starts in 5 min"
        val action: String,          // What the agent will do
        val confirmPrompt: String,   // "Join now?"
    ) : ProactiveResult

    // Auto-executed: agent already did it (only for autonomy = AUTO)
    data class Executed(
        val title: String,           // "Notifications summarized"
        val body: String,            // The summary
    ) : ProactiveResult
}

data class SuggestedAction(
    val label: String,               // "Open", "Reply", "Dismiss"
    val taskPrompt: String,          // Agent prompt if user selects this
)
```

---

## 7. UX: Proactive Surfacing

The critical question: **how does the agent tell the user it has something?**

### 7.1 Interruption Levels

Four levels, mapped to the urgency/autonomy of the proactive result:

| Level | Visual Treatment | When Used | User Disruption |
|-------|-----------------|-----------|-----------------|
| **Silent** | Badge dot on capsule | Low-priority suggestions, auto-executed reports | None. User checks at leisure. |
| **Gentle** | Capsule pulses once + subtle glow | Medium relevance. "Something to see when you have a sec." | Peripheral only. |
| **Standard** | Capsule expands briefly showing title | High relevance, prepared actions. "This is probably worth your attention." | Moderate. Capsule visible 3 sec, then collapses. |
| **Urgent** | Capsule expands + system notification | Time-critical items. Meeting in 2 min, important message from VIP. | High. |

### 7.2 Capsule Proactive States

New `CapsuleMode` variants:

```kotlin
sealed interface CapsuleMode {
    // ... existing modes ...

    // Agent has a proactive suggestion ready
    data class ProactiveSuggestion(
        val title: String,
        val body: String,
        val actions: List<SuggestedAction>,
        val interruptionLevel: InterruptionLevel,
    ) : CapsuleMode

    // Agent has a prepared action awaiting confirmation
    data class ProactiveAction(
        val title: String,
        val body: String,
        val confirmLabel: String,       // "Join", "Send", "Open"
        val dismissLabel: String,       // "Not now"
    ) : CapsuleMode

    // Proactive result badge (for silent level)
    data class ProactiveBadge(
        val count: Int,                 // Number of pending suggestions
    ) : CapsuleMode
}
```

### 7.3 Capsule Layout for Proactive Mode

When the capsule surfaces a proactive suggestion, it uses a compact 3-row layout:

```
┌──────────────────────────────────────────────┐
│  ✦  Meeting starts in 5 min                  │  ← Row 1: Icon + Title
│  "Weekly standup" — Zoom link ready           │  ← Row 2: Body
│  [ Join now ]                [ Not now ]      │  ← Row 3: Actions
└──────────────────────────────────────────────┘
```

For multi-suggestion badges (silent level), tapping the capsule opens a stacked card view:

```
┌──────────────────────────────────────────────┐
│  3 suggestions                               │
├──────────────────────────────────────────────┤
│  📅 Standup in 10 min          [ Join ]      │
│  💬 2 DMs from Alice           [ Open ]      │
│  📸 Screenshot taken           [ Share ]     │
├──────────────────────────────────────────────┤
│              [ Dismiss all ]                  │
└──────────────────────────────────────────────┘
```

### 7.4 Interaction Flow

```
          ┌─────────────────────────────┐
          │ Agent surfaces suggestion    │
          │ (capsule expands / pulses)   │
          └──────────┬──────────────────┘
                     │
          ┌──────────▼──────────────────┐
          │ User sees capsule            │
          │                              │
          │  ┌─ Tap action ──▶ Agent executes prepared steps
          │  │                       │
          │  │                       ▼
          │  │               ┌───────────────┐
          │  │               │ Capsule shows  │
          │  │               │ "Done ✓"       │
          │  │               └───────────────┘
          │  │
          │  ├─ Tap body ──▶ Expand to full chat with context
          │  │
          │  ├─ Swipe away ──▶ Dismissed (agent learns)
          │  │
          │  └─ Ignore (timeout 30s) ──▶ Collapse to badge
          │
          └─────────────────────────────┘
```

### 7.5 Proactive Mode in Main App Chat

When the user is in the main app, proactive suggestions appear as chat-style cards instead of capsule overlays:

```
┌──────────────────────────────────────────────┐
│  Agent (proactive)                  2 min ago │
│                                               │
│  ┌────────────────────────────────────────┐  │
│  │ 📅 Your standup starts in 10 min       │  │
│  │ Zoom link ready. Last meeting had 3    │  │
│  │ action items you marked pending.       │  │
│  │                                        │  │
│  │ [ Join meeting ]  [ Show action items ]│  │
│  └────────────────────────────────────────┘  │
│                                               │
│  ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─   │
│  You: Open settings                           │
│  ...                                          │
└──────────────────────────────────────────────┘
```

---

## 8. Trust & Autonomy Model

### 8.1 Autonomy Levels

```kotlin
enum class AutonomyLevel {
    OBSERVE,     // Agent notices, logs it, shows as badge. No LLM call.
    SUGGEST,     // Agent runs LLM to prepare a suggestion. User must act.
    CONFIRM,     // Agent prepares action. One-tap to execute.
    AUTO,        // Agent executes and reports afterward.
}
```

### 8.2 Per-Category Autonomy

Users set autonomy per trigger category, not globally. This reflects how trust works in real life: you might trust your assistant to auto-summarize notifications (AUTO) but want to confirm before they join a meeting for you (CONFIRM).

| Category | Default Autonomy | User Can Set |
|----------|-----------------|--------------|
| Notification digest | SUGGEST | All levels |
| Calendar preparation | CONFIRM | All levels |
| Morning/evening routine | SUGGEST | All levels |
| Message reply drafts | SUGGEST | SUGGEST, CONFIRM |
| App actions (open, navigate) | CONFIRM | CONFIRM, AUTO |
| Screenshot processing | OBSERVE | All levels |
| Financial/sensitive actions | CONFIRM | CONFIRM only (locked) |

### 8.3 Trust Building Loop

The system encourages progressive trust through demonstrated competence:

```
1. Agent surfaces suggestion (SUGGEST level)
2. User accepts → Agent executes successfully
3. After N successful executions of same recipe:
   → System offers: "I've done this 10 times and you've always accepted.
      Want me to just do it automatically?"
4. User can promote to CONFIRM or AUTO
```

This is opt-in, never automatic. The agent never self-promotes its autonomy level.

---

## 9. State Machine — Proactive Subsystem

The proactive subsystem runs as an orthogonal state machine alongside the existing session state machine. They are independent but coordinate on resource contention.

### 9.1 Proactive Monitor States

```
                         ┌─────────┐
              ┌─────────▶│MONITORING│◀──────────────────────┐
              │          └────┬────┘                         │
              │               │ (event received)             │
              │               ▼                              │
              │          ┌─────────┐                         │
              │          │TRIAGING │                         │
              │          └────┬────┘                         │
              │          ┌────┴────┐                         │
              │          │         │                         │
              │    below threshold  above threshold          │
              │          │         │                         │
              │          ▼         ▼                         │
              │      (discard)  ┌──────────┐                │
              │                 │PREPARING │                 │
              │                 │(agent LLM)│                │
              │                 └────┬─────┘                │
              │                 ┌────┴─────┐                │
              │                 │          │                 │
              │             auto-exec   needs user          │
              │                 │          │                 │
              │                 ▼          ▼                 │
              │          ┌──────────┐  ┌─────────┐          │
              │          │EXECUTED  │  │SURFACED │          │
              │          │(notify)  │  │(waiting)│          │
              │          └────┬─────┘  └────┬────┘          │
              │               │        ┌────┴────┐          │
              │               │    accepted   dismissed     │
              │               │        │         │          │
              │               │        ▼         │          │
              │               │   ┌─────────┐    │          │
              │               │   │EXECUTING│    │          │
              │               │   └────┬────┘    │          │
              │               │        │         │          │
              └───────────────┴────────┴─────────┘
```

### 9.2 Coordination with Session State

| Session State | Proactive Behavior |
|---------------|-------------------|
| `Created` | Proactive monitor not started yet. |
| `Idle` | Full proactive monitoring active. This is the primary proactive state. |
| `Running` (user task) | Proactive events are **queued**, not triggered. User task has priority. |
| `Running` (proactive task) | Further proactive events queued. One proactive task at a time. |
| `Paused` | Proactive monitoring paused. Events still collected by Layer 1. |
| `Shutdown` | Everything stopped. |

### 9.3 Contention Resolution

When a proactive trigger fires while a user task is running:

1. The event is **queued** with its triage score.
2. When the user task completes (session → Idle), queued events are re-triaged.
3. Multiple queued events may be batched into a single digest.

When a user task arrives while a proactive task is running:

1. The proactive task is **cancelled gracefully** (agent receives cancellation signal).
2. The user task takes priority immediately.
3. The proactive result (if partially complete) is saved and can be surfaced later.

---

## 10. Recipes UX — How Users Configure Proactive Behavior

### 10.1 Recipe Discovery

Users shouldn't have to configure everything from scratch. The system provides:

**Built-in recipes** (enabled by default at SUGGEST level):
- Notification digest (when 5+ notifications accumulate)
- Calendar preparation (10 min before meetings)
- Screenshot quick actions

**Suggested recipes** (offered based on observed behavior):
- "I noticed you open Slack every morning at 9 AM. Want me to prepare a summary?"
- "You often check weather before leaving. Want me to tell you proactively?"

**Custom recipes** (user creates via natural language):
- User: "Remind me to stretch every 2 hours"
- User: "When I get a WhatsApp from Mom, draft a reply"
- User: "Every Friday at 5 PM, summarize my week"

### 10.2 Recipe Creation Flow

```
User opens Recipes screen
       │
       ├──▶ Browse built-in recipes → toggle on/off, set autonomy
       │
       ├──▶ View suggested recipes → accept or dismiss
       │
       └──▶ "Create new recipe" → natural language input
                │
                ▼
           Agent interprets the request:
           "When [trigger], do [action] at [autonomy] level"
                │
                ▼
           Confirmation card:
           ┌───────────────────────────────────────┐
           │ New Recipe: "Mom's WhatsApp reply"     │
           │                                        │
           │ When: WhatsApp message from Mom        │
           │ Do:   Draft a reply and show it to me  │
           │ Mode: Confirm before sending           │
           │                                        │
           │ [ Create ]           [ Edit ]          │
           └───────────────────────────────────────┘
```

### 10.3 Recipe Management

Each recipe displays:
- **Name** and **description**
- **Trigger count**: how many times it's fired in the last 7 days
- **Accept rate**: percentage of times user accepted the suggestion
- **Autonomy level**: adjustable slider (OBSERVE → SUGGEST → CONFIRM → AUTO)
- **Enabled/disabled toggle**

Low accept-rate recipes (< 20% over 30 days) trigger: "This recipe rarely helps. Adjust, disable, or remove?"

---

## 11. Edge Cases & Error States

### 11.1 No dead ends

| Scenario | Handling |
|----------|----------|
| Agent prepares action, but app state changed since | Agent re-checks screen before executing. If state changed, re-evaluates or reports "context changed, action may no longer apply." |
| User dismisses a proactive suggestion repeatedly (same recipe) | After 3 consecutive dismissals: "You keep dismissing this. Want me to turn it off?" |
| Proactive action fails mid-execution | Same error handling as reactive tasks. Capsule shows error state with retry option. |
| Multiple proactive triggers fire simultaneously | Batch into digest. Show highest-priority first. |
| Agent can't determine relevance (ambiguous event) | Default to OBSERVE. Log the event, don't surface it. |
| LLM call fails during proactive preparation | Silent failure. Log it. Don't bother the user about infrastructure issues for something they didn't ask for. |
| Battery critically low (< 10%) | All proactive monitoring pauses. Only user-initiated tasks proceed. |
| User in DND mode | All proactive surfacing suppressed. Events queued for when DND ends. |

### 11.2 Privacy guardrails

- **No content logging by default.** Layer 1 events are processed in-memory and discarded unless the user enables history.
- **Notification content is never sent to LLM unless the user has opted into that recipe.** The triage engine can pattern-match on metadata (app, category, keyword presence) without reading full content.
- **Screen context is app-name + window-title only** in Layer 1. Full screen perception (a11y tree) only happens in Layer 3 when the agent is actively preparing an action.
- **Recipes show exactly what data they access.** Each recipe card lists: "This recipe reads: notification titles from WhatsApp."

---

## 12. Integration with Existing Architecture

### 12.1 Where Proactive Plugs In

```
Existing System                    Proactive Addition
─────────────────                  ─────────────────────
AgentService                       + AwarenessManager
(AccessibilityService)               (manages Layer 1 sources)
       │                                    │
       ▼                                    ▼
AgentSession                       + TriageEngine
(handles Op → state               (Layer 2, decides
 transitions)                      whether to trigger)
       │                                    │
       ▼                                    ▼
Agent / AgentTurnRunner            + ProactiveTaskRunner
(LLM loop)                        (creates Op.ProactiveTrigger,
       │                           submits to AgentSession)
       ▼                                    │
SmartCapsule / ChatUI               + New CapsuleModes
(surfaces results)                 (ProactiveSuggestion,
                                    ProactiveAction,
                                    ProactiveBadge)
```

### 12.2 New Components

| Component | Responsibility | Location |
|-----------|---------------|----------|
| `AwarenessManager` | Manages Layer 1 event sources. Start/stop/configure. | `agent/proactive/awareness/` |
| `NotificationMonitor` | `NotificationListenerService` wrapper. Emits notification events. | `agent/proactive/awareness/` |
| `CalendarMonitor` | ContentObserver + WorkManager for calendar events. | `agent/proactive/awareness/` |
| `ScheduleMonitor` | Time-based triggers (cron-like). | `agent/proactive/awareness/` |
| `TriageEngine` | Layer 2 decision logic. Recipes + heuristic scoring. | `agent/proactive/triage/` |
| `RecipeStore` | Persists and manages recipes. Room database. | `agent/proactive/recipe/` |
| `ProactiveTaskRunner` | Bridges triage decisions to AgentSession. | `agent/proactive/` |
| `ProactiveAgentDef` | Agent definition with proactive system prompt. | `agent/definition/` |

### 12.3 Modified Existing Components

| Component | Change |
|-----------|--------|
| `AgentSession` | Handle `Op.ProactiveTrigger`. Enforce proactive budget. Queue during user tasks. |
| `CapsuleStateHolder` | Support new proactive `CapsuleMode` variants. |
| `SmartCapsuleManager` | Render proactive suggestion/action/badge UI. |
| `ChatViewModel` | Display proactive suggestions as chat cards in main app. |
| `AgentService` | Initialize `AwarenessManager` on service start. |

---

## 13. MVP Scope — What to Build First

### Phase 1: Foundation

**Goal**: Prove the proactive pipeline works end-to-end with one trigger source.

**Build**:
1. `AwarenessManager` with `NotificationMonitor` only (simplest, most impactful source).
2. `TriageEngine` with hardcoded rules (no recipes yet): surface when 5+ notifications accumulate OR notification from starred contact.
3. `ProactiveTaskRunner` that submits `Op.ProactiveTrigger` to session.
4. `ProactiveAgentDef` with a notification-summarization prompt.
5. One new `CapsuleMode.ProactiveSuggestion` with accept/dismiss actions.
6. Budget enforcement: max 3 proactive triggers per hour.

**Skip for now**: Recipes UI, calendar integration, heuristic scoring, autonomy configuration, quiet hours, trust building loop.

**Success metric**: User gets a useful notification digest via the capsule without having asked for it, and can tap to expand or dismiss.

### Phase 2: Calendar + Recipes

**Build**:
1. `CalendarMonitor` with meeting preparation.
2. Recipe system (store, match, CRUD UI).
3. Per-category autonomy settings.
4. Quiet hours support.

### Phase 3: Intelligence

**Build**:
1. Heuristic scoring with learned patterns.
2. Recipe suggestions based on observed behavior.
3. Trust building loop (auto-promote offer).
4. `ScheduleMonitor` for time-based routines.

### Phase 4: Full Proactive Agent

**Build**:
1. Screen context monitoring (contextual assistance).
2. Media monitoring (screenshot/photo processing).
3. Multi-step prepared actions (not just suggestions, but full pre-planned workflows).
4. Cross-recipe coordination (don't fire meeting prep AND notification digest about the same meeting).

---

## 14. Summary

The proactive agent transforms the Android Agent from a tool you operate into an assistant that works for you. The design rests on three convictions:

1. **Restraint is the product.** The hard part isn't detecting events — it's deciding which ones deserve the user's attention. The three-layer architecture exists to say "no" to 95% of events so the 5% that surface are genuinely valuable.

2. **Preparation is the magic.** The agent's superpower is UI automation. When the agent says "your meeting is starting," it should mean "I've found the Zoom link, I know how to join, and I'm one tap away from doing it." That's the difference between a notification and an assistant.

3. **Trust is earned, not configured.** The autonomy model starts conservative and lets users unlock more automation as the agent proves it can be trusted. This isn't a settings page — it's a relationship that develops over time.
