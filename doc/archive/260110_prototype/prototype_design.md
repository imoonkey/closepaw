# MVP Tech Design Doc: Android Local UI Agent Kernel (Accessibility based)

## 0. Goal

Build a minimal Android app that runs an agent loop on device:

1. Read the current screen structure (Accessibility node tree)
2. Sanitize it into a small JSON list of interactive elements (similar spirit to the repo sanitizer.py) ([GitHub][1])
3. Ask an LLM for exactly one next action (similar spirit to kernel.py) ([GitHub][2])
4. Execute the action using Accessibility APIs
5. Repeat in a simple for loop until done or max steps

The loop and UI execution run locally on the phone. The model call can be remote for MVP (fastest way to validate the chain). Swapping to an on device model is a later step.

## 1. Non goals (for MVP)

1. No screenshot vision, only Accessibility tree
2. No advanced planning, no memory beyond a short action history
3. No robust stop control, no overlay UI
4. No guaranteed reliability across all apps, the goal is “prove the loop works end to end”

## 2. High level architecture

Core runtime is an `AccessibilityService`:

* Perception: `getRootInActiveWindow()` to get the active window root node (only works if the service can retrieve window content) ([Microsoft Learn][3])
* Action: `AccessibilityNodeInfo.performAction(...)` for click and set text, `dispatchGesture(...)` as coordinate fallback, `performGlobalAction(...)` for back and home ([Android Developers][4])
* Loop: a single coroutine that repeats the same four steps as kernel.py: observe, decide, act, wait ([GitHub][2])

### Minimal file layout

To keep it “two file logic” like the Python repo, structure it as:

1. `AgentService.kt` (the kernel)
2. `Sanitizer.kt` (the sanitizer)

Plus required Android boilerplate resources:

* `AndroidManifest.xml` service registration
* `res/xml/agent_accessibility_config.xml` service config
* A tiny `MainActivity.kt` (or a single Compose screen) to enter a goal and start the run

The kernel and sanitizer are the only files that contain real logic.

## 3. Action interface (your DSL)

Keep it close to kernel.py which emits a single JSON object with a small action set ([GitHub][2])

### Supported actions (MVP)

```json
{
  "action": "tap",
  "target": { "by": "index", "value": 12 },
  "reason": "string"
}
```

```json
{
  "action": "type",
  "target": { "by": "index", "value": 5 },
  "text": "hello",
  "reason": "string"
}
```

```json
{ "action": "scroll", "target": { "by": "index", "value": 3 }, "direction": "down", "reason": "string" }
```

```json
{ "action": "back", "reason": "string" }
```

```json
{ "action": "home", "reason": "string" }
```

```json
{ "action": "wait", "ms": 1200, "reason": "string" }
```

```json
{ "action": "done", "reason": "string" }
```

Notes:

* `index` is the simplest stable reference for MVP: it is the position in the sanitized list you send to the model.
* You can add selector types later (`resourceId`, `text`, `bounds`), but do not start there.

## 4. Sanitized observation format

The sanitizer in the Python repo parses the UIAutomator XML and returns a lean list with center coordinates, basic labels, and clickability ([GitHub][1])

We will build the equivalent from `AccessibilityNodeInfo`:

### Element schema

```json
[
  {
    "index": 0,
    "package": "com.android.settings",
    "class": "android.widget.TextView",
    "id": "com.android.settings:id/title",
    "text": "Wi-Fi",
    "desc": "",
    "clickable": true,
    "editable": false,
    "scrollable": false,
    "bounds": [x1, y1, x2, y2],
    "center": [cx, cy]
  }
]
```

### Sanitizer rules (keep it stupid simple)

1. Traverse the tree preorder from `rootInActiveWindow`
2. For each node compute:

   * `text` and `contentDescription`
   * `viewIdResourceName` (may be empty)
   * flags: clickable, editable, scrollable
   * bounds and center
3. Keep a node if any of these is true:

   * clickable
   * editable
   * scrollable
   * has non empty text or contentDescription
4. Truncate aggressively:

   * max elements, start with 80
   * max length per string field, start with 60 chars
5. Normalize whitespace, drop obvious junk (empty containers)

This mirrors the spirit of the repo sanitizer that filters to interactive or informative nodes and computes centers from bounds ([GitHub][1])

## 5. AccessibilityService setup

### Service config requirements

You need to request window content access and gesture capability in your service config so you can read UI and inject taps. In practice this is done via the accessibility service XML and the corresponding capabilities. ([Android Developers][5])

Minimal config ideas:

* Listen to `TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOW_CONTENT_CHANGED`
* `canRetrieveWindowContent = true`
* `canPerformGestures = true`

### Key APIs you will use

* `getRootInActiveWindow()` to read the tree ([Microsoft Learn][3])
* `AccessibilityNodeInfo.performAction(ACTION_CLICK)` for node clicks ([Android Developers][4])
* `AccessibilityNodeInfo.performAction(ACTION_SET_TEXT, bundle)` for input, using `ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE` ([Android Developers][4])
* `dispatchGesture(...)` for coordinate based tap fallback ([Microsoft Learn][6])
* `performGlobalAction(GLOBAL_ACTION_BACK or HOME)` for navigation ([Microsoft Learn][7])

## 6. Kernel loop design (AgentService.kt)

This is the direct translation of kernel.py’s for loop structure ([GitHub][2])

### State

* `running: AtomicBoolean`
* `currentGoal: String`
* `history: MutableList<String>` (keep last N steps, N like 6)
* `lastUiDigest: String` (optional, detect no progress)

### Loop pseudocode

```kotlin
fun runAgent(goal: String, maxSteps: Int = 20) = scope.launch {
  running.set(true)
  repeat(maxSteps) { step ->
    val obs = Sanitizer.snapshotAndSanitize(rootInActiveWindow)
    val actionJson = llm.nextAction(goal, obs, history)
    val action = Action.parse(actionJson)
    val result = execute(action, obs)
    history.add("step=$step action=${action.action} result=$result")
    if (action.action == "done") return@launch
    waitForUiOrTimeout()
    if (!running.get()) return@launch
  }
  running.set(false)
}
```

This matches the repo flow: scan screen, ask model, execute, sleep, repeat ([GitHub][2])

### Waiting strategy (MVP)

Keep it dumb:

* After each action, sleep 800 to 1500 ms
* Also record a simple UI digest (hash of concatenated element texts and ids). If digest does not change for 3 consecutive steps, inject a `wait` once, then `back`, then fail out.

No event driven complexity for MVP.

## 7. Action execution (AgentService.kt)

Implement execution in one function with a switch on action type, same as kernel.py executes adb input actions based on the `action` field ([GitHub][2])

### tap

1. Find element by `index` in the sanitized list
2. Try node click if you retained a node reference map (index to node) from the sanitizer traversal
3. If node click fails or node is null, do coordinate tap using `dispatchGesture` at `center`

### type

1. Find node by index
2. Use `ACTION_SET_TEXT` with bundle arg `ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE`
3. If fails, fallback is out of scope for MVP (clipboard paste fallback can be a later patch)

`ACTION_SET_TEXT` and its argument constant are defined in the platform and documented in AOSP. ([emanual.github.io][8])

### scroll

If element is scrollable:

* `ACTION_SCROLL_FORWARD` for down
* `ACTION_SCROLL_BACKWARD` for up

Else:

* gesture swipe inside bounds (start near bottom, end near top)

### back and home

Use `performGlobalAction(...)` which is specifically intended for actions like back and home regardless of current app. ([Microsoft Learn][7])

### wait

Sleep for `ms` or a default like 1200

### done

Stop the loop

## 8. LLM prompt and output parsing

### Prompt shape

Keep it almost identical to kernel.py: a system prompt that defines the role, inputs, and the allowed JSON outputs. ([GitHub][2])

Minimal system prompt:

* You are an Android UI driver agent
* You will receive:

  * GOAL
  * SCREEN as a JSON list of elements with index, text, id, class, bounds, center, clickable, editable, scrollable
* Output only a valid JSON object with one of the allowed actions

MVP should force JSON output using a JSON response mode if your client supports it (the Python repo does this via response_format json_object) ([GitHub][2])

### Parsing

Keep it brutally simple:

* Use a permissive JSON parser
* If parsing fails, return `{ "action": "wait", "ms": 1200 }`
* Validate:

  * action is one of allowed
  * index exists when required
  * text exists for type

## 9. MVP build steps

1. Create a new Android app
2. Add `AgentService` as an `AccessibilityService`
3. Add service config XML with retrieve window content and perform gestures enabled ([Android Developers][5])
4. Add a minimal `MainActivity`:

   * text input for goal
   * Start button calls into service (for MVP, simplest is a singleton reference set in `onServiceConnected`)
5. Implement `Sanitizer.snapshotAndSanitize(root)`
6. Implement `execute(action, obs)`
7. Implement `llm.nextAction(...)` with a single network call
8. Test on emulator first:

   * goal: “open Settings and search Wi Fi”
   * goal: “open Chrome and type example.com”
9. Add logs to Logcat for:

   * step number
   * chosen action
   * execution result
   * top 10 elements in sanitized list (optional)

## 10. Known limitations you should accept for MVP

1. Many apps expose weak Accessibility metadata, so your element list may be sparse
2. Nodes can go stale between capture and act, coordinate tap fallback is your MVP escape hatch
3. Without screenshot vision, some flows will fail
4. Long screens can exceed prompt budget, truncation is mandatory

## 11. What “success” means for this MVP

You can run:

* One text goal
* A 10 to 20 step loop
* Cross app actions
* At least one working demo flow end to end

That is the same bar the Python kernel clears using UIAutomator dump plus a sanitizer plus a tight action loop. ([GitHub][2])

---

If you want, I can also include a concrete, copy paste ready skeleton for `AgentService.kt` and `Sanitizer.kt` that matches this design and stays minimal (single file each, no abstractions), while still using the correct Android APIs for tree capture, set text, and gesture tap.

[1]: https://raw.githubusercontent.com/Action-State-Labs/android-action-kernel/refs/heads/main/sanitizer.py "raw.githubusercontent.com"
[2]: https://raw.githubusercontent.com/Action-State-Labs/android-action-kernel/refs/heads/main/kernel.py "raw.githubusercontent.com"
[3]: https://learn.microsoft.com/en-us/dotnet/api/android.accessibilityservices.accessibilityservice.getrootinactivewindow?view=net-android-35.0&utm_source=chatgpt.com "AccessibilityService.GetRootInActiveWindow(Int32) Method"
[4]: https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo?utm_source=chatgpt.com "AccessibilityNodeInfo | API reference"
[5]: https://developer.android.com/guide/topics/ui/accessibility/service?utm_source=chatgpt.com "Create your own accessibility service"
[6]: https://learn.microsoft.com/en-us/dotnet/api/android.accessibilityservices.accessibilityservice.dispatchgesture?view=net-android-35.0&utm_source=chatgpt.com "AccessibilityService.DispatchGesture Method"
[7]: https://learn.microsoft.com/en-us/dotnet/api/android.accessibilityservices.accessibilityservice.performglobalaction?view=net-android-35.0&utm_source=chatgpt.com "AccessibilityService.PerformGlobalAction(GlobalAction) ..."
[8]: https://emanual.github.io/Android-docs/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction.html?utm_source=chatgpt.com "AccessibilityNodeInfo.AccessibilityAction - Android SDK"
