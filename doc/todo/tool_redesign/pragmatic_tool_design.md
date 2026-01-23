# Pragmatic Tool Redesign for Android Agent

> **Goal**: Consolidate tools into a simpler, more maintainable structure that reduces prefill context length while maintaining semantic clarity.

## Table of Contents

1. [Current Problems](#current-problems)
2. [Design Principles](#design-principles)
3. [Tool Architecture](#tool-architecture)
4. [Tool Specifications](#tool-specifications)
5. [Implementation Plan](#implementation-plan)

---

## Current Problems

### Too Many Separate Tools
Current state has many individual tools:
- `click`, `type`, `scroll`, `swipe`, `back`, `home`, `wait`...

Each tool adds ~100-200 tokens to the prefill context. With 8+ tools, that's 800-1600 tokens just for tool definitions.

### Overly Ambitious API Design
The `api_tools_design.md` proposes 15+ high-level tools (compose_email, navigate_to, etc.) which:
- Requires significant permission work
- Many edge cases and error handling
- Too much scope creep before basics work

---

## Design Principles

### 1. Consolidate by Semantic Category
Like Mobile-Agent-v3's `mobile_use` tool - group related actions under one tool with an `action` enum.

### 2. Three Tool Categories (P0-P1)

| Priority | Category | Tool Name | Purpose |
|----------|----------|-----------|---------|
| P0 | Agent Meta | `complete_task` | End task with success/failure and answer |
| P0 | Simulation | `mobile_action` | User-like interactions (click, type, swipe, etc.) |
| P0 | Android Meta | `app_control` | list_apps, open_app |
| P1 | Intent | `fire_intent` | Generic intent firing (sms, geo, etc.) |

### 3. Single BaseTool Pattern
One abstract base class that handles all tool types with a simple dispatch pattern.

---

## Tool Architecture

### Core Interfaces (Simplified from Gemini CLI + Codex)

```kotlin
/**
 * ToolSpec - Declarative tool specification.
 * 
 * Pattern: Gemini CLI's DeclarativeTool, simplified.
 */
interface ToolSpec {
    val name: String
    val description: String
    val parameterSchema: JSONObject
    
    fun validate(params: JSONObject): ValidationResult
    fun createInvocation(params: JSONObject): ToolInvocation
}

/**
 * ToolInvocation - Ready-to-execute tool call.
 */
interface ToolInvocation {
    val toolName: String
    val params: JSONObject
    
    fun getDescription(): String
    suspend fun execute(context: ToolExecutionContext): ToolExecutionResult
}

/**
 * ToolRegistry - Simple tool management.
 * 
 * Pattern: Codex's builder pattern, simplified.
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, ToolSpec>()
    
    fun register(tool: ToolSpec)
    fun get(name: String): ToolSpec?
    fun generateSchemas(): List<FunctionTool>
}
```

### BaseTool for Action-Based Tools

```kotlin
/**
 * BaseTool - Base class for tools that dispatch to action handlers.
 * 
 * Handles the common pattern of:
 * 1. Validate action parameter
 * 2. Dispatch to specific action handler
 * 3. Execute and capture observation
 */
abstract class BaseTool : ToolSpec {
    
    /**
     * Subclasses define their supported actions and handlers.
     */
    protected abstract val actionHandlers: Map<String, ActionHandler>
    
    override fun validate(params: JSONObject): ValidationResult {
        val action = params.optString("action", "")
        if (action.isEmpty()) {
            return ValidationResult.Invalid("Missing required parameter: action")
        }
        if (!actionHandlers.containsKey(action)) {
            return ValidationResult.Invalid(
                "Unknown action: $action. Valid actions: ${actionHandlers.keys.joinToString()}"
            )
        }
        return actionHandlers[action]!!.validate(params)
    }
    
    override fun createInvocation(params: JSONObject): ToolInvocation {
        val action = params.getString("action")
        return actionHandlers[action]!!.createInvocation(params)
    }
}

/**
 * ActionHandler - Handler for a specific action within a tool.
 */
interface ActionHandler {
    val actionName: String
    fun validate(params: JSONObject): ValidationResult
    fun createInvocation(params: JSONObject): ToolInvocation
}
```

---

## Tool Specifications

### 1. `complete_task` - Agent Metatool (P0)

**Purpose**: End the task with a result (success or failure) and provide an answer to the user.

```kotlin
class CompleteTaskTool : ToolSpec {
    override val name = "complete_task"
    
    override val description = """
Call this when you have finished working on the task.
- Use status="success" when the goal was achieved
- Use status="failure" when the goal cannot be achieved
- Always provide an answer to return to the user
""".trimIndent()
    
    override val parameterSchema = JSONObject("""
{
  "type": "object",
  "properties": {
    "status": {
      "type": "string",
      "enum": ["success", "failure"],
      "description": "Whether the task succeeded or failed"
    },
    "answer": {
      "type": "string",
      "description": "The answer or result to return to the user"
    },
    "reason": {
      "type": "string",
      "description": "If failure, explain why the task could not be completed"
    }
  },
  "required": ["status", "answer"],
  "additionalProperties": false
}
""")
}
```

**Behavior**:
- Does NOT trigger screen observation (it's a terminal action)
- Signals to the agent loop that the task is done
- Returns the answer to the user

---

### 2. `mobile_action` - Simulation Actions (P0)

**Purpose**: Consolidate all user-simulating actions into one tool.

```kotlin
class MobileActionTool : BaseTool() {
    override val name = "mobile_action"
    
    override val description = """
Perform touch interactions on the mobile device.

Actions:
- click: Tap on element by index
- long_press: Long press on element by index
- type: Input text (clears field first, use element_index to focus)
- swipe: Swipe from (x1,y1) to (x2,y2)
- system_button: Press Back, Home, Enter, or Menu
- wait: Wait for UI to update (default 1 second)
""".trimIndent()
    
    override val parameterSchema = JSONObject("""
{
  "type": "object",
  "properties": {
    "action": {
      "type": "string",
      "enum": ["click", "long_press", "type", "swipe", "system_button", "wait"],
      "description": "The action to perform"
    },
    "element_index": {
      "type": "integer",
      "description": "Element index for click, long_press, or type actions"
    },
    "text": {
      "type": "string",
      "description": "Text to input for type action"
    },
    "start": {
      "type": "array",
      "items": {"type": "integer"},
      "description": "[x, y] start coordinates for swipe"
    },
    "end": {
      "type": "array",
      "items": {"type": "integer"},
      "description": "[x, y] end coordinates for swipe"
    },
    "button": {
      "type": "string",
      "enum": ["back", "home", "enter", "menu"],
      "description": "System button for system_button action"
    },
    "duration_ms": {
      "type": "integer",
      "description": "Duration in ms for wait or long_press (default: 1000)"
    }
  },
  "required": ["action"],
  "additionalProperties": false
}
""")
    
    override val actionHandlers = mapOf(
        "click" to ClickActionHandler(),
        "long_press" to LongPressActionHandler(),
        "type" to TypeActionHandler(),
        "swipe" to SwipeActionHandler(),
        "system_button" to SystemButtonActionHandler(),
        "wait" to WaitActionHandler()
    )
}
```

**Action Handlers**:

```kotlin
class ClickActionHandler : ActionHandler {
    override val actionName = "click"
    
    override fun validate(params: JSONObject): ValidationResult {
        if (!params.has("element_index")) {
            return ValidationResult.Invalid("click requires element_index")
        }
        val idx = params.getInt("element_index")
        if (idx < 0) {
            return ValidationResult.Invalid("element_index must be >= 0")
        }
        return ValidationResult.Valid
    }
    
    override fun createInvocation(params: JSONObject): ToolInvocation {
        return UIActionInvocation(
            toolName = "mobile_action",
            params = params,
            description = "Click element ${params.getInt("element_index")}",
            uiAction = UIAction.Click(params.getInt("element_index"))
        )
    }
}

class SwipeActionHandler : ActionHandler {
    override val actionName = "swipe"
    
    override fun validate(params: JSONObject): ValidationResult {
        if (!params.has("start") || !params.has("end")) {
            return ValidationResult.Invalid("swipe requires start and end coordinates")
        }
        // Validate coordinate arrays...
        return ValidationResult.Valid
    }
    
    override fun createInvocation(params: JSONObject): ToolInvocation {
        val start = params.getJSONArray("start")
        val end = params.getJSONArray("end")
        return UIActionInvocation(
            toolName = "mobile_action",
            params = params,
            description = "Swipe from (${start[0]},${start[1]}) to (${end[0]},${end[1]})",
            uiAction = UIAction.Swipe(
                startX = start.getInt(0),
                startY = start.getInt(1),
                endX = end.getInt(0),
                endY = end.getInt(1),
                durationMs = params.optLong("duration_ms", 300)
            )
        )
    }
}

// ... similar for other actions
```

---

### 3. `app_control` - Android Meta Actions (P0)

**Purpose**: App discovery and launching.

```kotlin
class AppControlTool : BaseTool() {
    override val name = "app_control"
    
    override val description = """
Control apps on the device.

Actions:
- list_apps: Get list of installed launchable apps
- open_app: Launch an app by package name or display name
""".trimIndent()
    
    override val parameterSchema = JSONObject("""
{
  "type": "object",
  "properties": {
    "action": {
      "type": "string",
      "enum": ["list_apps", "open_app"],
      "description": "The action to perform"
    },
    "package_name": {
      "type": "string",
      "description": "Package name for open_app (e.g., 'com.google.android.gm')"
    },
    "app_name": {
      "type": "string",
      "description": "Display name for open_app (e.g., 'Gmail'). Fuzzy matched."
    },
    "filter": {
      "type": "string",
      "description": "Filter for list_apps (case-insensitive substring match)"
    }
  },
  "required": ["action"],
  "additionalProperties": false
}
""")
}
```

**Key Difference**: `list_apps` returns data, `open_app` performs UI action.

```kotlin
class ListAppsActionHandler : ActionHandler {
    override fun createInvocation(params: JSONObject): ToolInvocation {
        return DataQueryInvocation(
            toolName = "app_control",
            params = params,
            description = "List installed apps",
            queryFn = { context ->
                val apps = context.platform.getInstalledApps()
                val filter = params.optString("filter", "")
                val filtered = if (filter.isNotEmpty()) {
                    apps.filter { it.label.contains(filter, ignoreCase = true) }
                } else {
                    apps
                }
                JSONObject().apply {
                    put("apps", JSONArray(filtered.map { app ->
                        JSONObject().apply {
                            put("package_name", app.packageName)
                            put("label", app.label)
                        }
                    }))
                    put("count", filtered.size)
                }.toString()
            }
        )
    }
}
```

---

### 4. `fire_intent` - Intent Actions (P1)

**Purpose**: Generic intent firing for common intents.

```kotlin
class FireIntentTool : ToolSpec {
    override val name = "fire_intent"
    
    override val description = """
Fire an Android intent for common actions.

Supported intent types:
- sms: Open SMS compose (phone_number, message)
- dial: Open phone dialer (phone_number)
- geo: Open maps at location (query OR lat,lng)
- web: Open URL in browser (url)
- email: Open email compose (to, subject, body)
""".trimIndent()
    
    override val parameterSchema = JSONObject("""
{
  "type": "object",
  "properties": {
    "intent_type": {
      "type": "string",
      "enum": ["sms", "dial", "geo", "web", "email"],
      "description": "Type of intent to fire"
    },
    "phone_number": {"type": "string", "description": "For sms/dial"},
    "message": {"type": "string", "description": "For sms"},
    "query": {"type": "string", "description": "For geo (address/place name)"},
    "lat": {"type": "number", "description": "For geo (latitude)"},
    "lng": {"type": "number", "description": "For geo (longitude)"},
    "url": {"type": "string", "description": "For web"},
    "to": {"type": "string", "description": "For email"},
    "subject": {"type": "string", "description": "For email"},
    "body": {"type": "string", "description": "For email"}
  },
  "required": ["intent_type"],
  "additionalProperties": false
}
""")
}
```

---

## Implementation Plan

### Phase 1: Core Refactor (P0) ✅ IMPLEMENTED

1. **Update BaseTool** ✅
   - Added `ActionHandler` interface (`tool/handlers/ActionHandler.kt`)
   - Added `MultiActionTool` base class (`tool/MultiActionTool.kt`)
   - Added shared invocations (`UIActionInvocation.kt`, `DataQueryInvocation.kt`)
   - Kept backward compatibility with existing simple tools

2. **Implement `complete_task`** ✅
   - Updated to include success/failure status
   - Added required `answer` parameter
   - Optional `reason` for failures

3. **Implement `mobile_action`** ✅
   - Created action handlers for: click, long_press, type, swipe, system_button, wait
   - Scroll intentionally omitted (subset of swipe)
   - Added `UIAction.LongClick` to platform

4. **Implement `app_control`** ✅
   - list_apps action (query data)
   - open_app action (launch intent with fuzzy name matching)
   - Added `getInstalledApps()` and `launchApp()` to AndroidPlatform

### Phase 2: Intent Support (P1) ⏳ PENDING

5. **Implement `fire_intent`**
   - sms, dial, geo, web, email intent types
   - Add proper URI building
   - Add error handling for missing apps

### File Changes

```
tool/
├── BaseTool.kt              # Update: add ActionHandler pattern
├── ToolSpec.kt              # Keep as-is
├── ToolRegistry.kt          # Keep as-is
├── ToolRouter.kt            # Keep as-is
├── handlers/                # NEW directory for action handlers
│   ├── ActionHandler.kt     # Interface
│   ├── UIActionInvocation.kt  # Shared invocation for UI actions
│   └── DataQueryInvocation.kt # Shared invocation for data queries
├── impl/
│   ├── CompleteTaskTool.kt  # Update: add status param
│   ├── MobileActionTool.kt  # NEW: consolidated simulation actions
│   ├── AppControlTool.kt    # NEW: list_apps, open_app
│   └── FireIntentTool.kt    # NEW (P1): intent firing
└── (DELETE old individual tools after migration)
    ├── ClickTool.kt         # -> MobileActionTool
    ├── TypeTool.kt          # -> MobileActionTool
    ├── SwipeTool.kt         # -> MobileActionTool
    ├── ScrollTool.kt        # DELETE (swipe covers it)
    ├── WaitTool.kt          # -> MobileActionTool
    └── NavigationTools.kt   # -> MobileActionTool (system_button)
```

---

## Context Length Comparison

### Before (8 individual tools)
```
click:        ~120 tokens
type:         ~150 tokens
swipe:        ~180 tokens
scroll:       ~150 tokens
back:         ~80 tokens
home:         ~80 tokens
wait:         ~100 tokens
complete_task: ~120 tokens
─────────────────────────
Total:        ~980 tokens
```

### After (3 consolidated tools)
```
complete_task:  ~150 tokens
mobile_action:  ~350 tokens
app_control:    ~200 tokens
─────────────────────────
Total:          ~700 tokens  (28% reduction)
```

Plus cleaner semantics - the LLM sees "mobile_action" as a coherent concept.

---

## Open Questions

1. **Should `mobile_action` include `open_app`?**
   - Mobile-Agent-v3 includes `open` in their `mobile_use`
   - Could simplify to just 2 tools: `complete_task` and `mobile_action`
   - Trade-off: longer description for mobile_action

2. **Coordinate system for swipe**
   - Current: raw pixels
   - Alternative: use element indices for start/end
   - Mobile-Agent-v3 uses raw pixels

3. **Wait behavior**
   - Should wait automatically observe after?
   - Or require explicit next action?

---

*This design prioritizes getting a working system before expanding scope.*
