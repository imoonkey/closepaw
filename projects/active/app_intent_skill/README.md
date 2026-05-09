# open_intent Tool

## Problem

Agent currently navigates to app screens via UI clicks (accessibility actions). Many destinations are reachable via exported intents — faster, more reliable, and deterministic. No tool exists to fire arbitrary intents.

## Decision: Tool Only, No Agent Skill

`browser_script` needs a skill because writing CDP/JS is complex and benefits from snippet libraries. `open_intent` is different:

- **Parameters are structured** (action, data, package, extras) — not free-form code
- **Discovery is handled by the intent registry** — not a guidance document
- **Construction is simple** — once you know the intent, the call is trivial

If app-specific deep link complexity emerges later, that knowledge belongs in **app skills** (per-package `SKILL.md`), not a separate agent skill.

## Design

### Tool: `open_intent`

**Parameters:**

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `action` | string | yes | Intent action (e.g., `android.intent.action.VIEW`) |
| `data` | string | no | URI data (e.g., `https://...`, `tel:...`, `content://...`) |
| `package` | string | no | Target package. When set, makes the intent explicit |
| `component` | string | no | Fully-qualified component (`pkg/activity`). Most specific targeting |
| `extras` | object | no | Key-value extras to attach. Values: string, int, boolean, float |
| `category` | string | no | Intent category (e.g., `android.intent.category.BROWSABLE`) |
| `type` | string | no | MIME type (e.g., `image/jpeg`) |
| `flags` | int | no | Intent flags (e.g., `FLAG_ACTIVITY_NEW_TASK`) |

**Behavior:**
1. Validate params → construct `android.content.Intent`
2. Policy check: deny if target package is `BLOCKED` tier
3. Add `FLAG_ACTIVITY_NEW_TASK` by default (launching from service context)
4. Resolve intent → if unresolvable, return failure with actionable message
5. `context.startActivity(intent)` / `context.sendBroadcast(intent)` based on target type
6. Wait for UI settle → capture screen → return observation

**Scope — activity launch only (V1):**
- Only `startActivity`. No broadcast/service for V1
- `FLAG_ACTIVITY_NEW_TASK` always added (accessibility service context)

### Intent Registry

Separate concern — builds the knowledge base the agent queries to decide *whether* to use `open_intent` vs UI navigation.

**Scan at session start:**
```
PackageManager.getInstalledPackages(GET_ACTIVITIES | GET_INTENT_FILTERS)
  → for each package: exported activities + their intent-filters
  → structured cache: Map<String, List<IntentFilterInfo>>
```

**Agent integration:**
- Registry summary injected into system prompt or available via a lightweight query tool
- Agent sees: "Chrome settings → `com.android.chrome/.settings.MainSettings` (action VIEW)" 
- Agent decides: call `open_intent` directly instead of `open_app("Chrome")` + click Settings

**Registry is a V2 concern.** V1 ships the tool with hardcoded common intents in the description or lets the agent construct intents from its training knowledge. Registry adds discoverability later.

### Relationship to `open_app`

`open_app` = "launch app by name" (launcher intent only)
`open_intent` = "fire any intent" (superset)

Keep both:
- `open_app` is simpler for the common case, needs no intent knowledge
- `open_intent` is for deep navigation: specific activities, deep links, share targets
- Agent chooses based on task: "open Gmail" → `open_app`, "compose email to X" → `open_intent`

## Platform Layer Change

`AndroidPlatform` needs a new method:

```kotlin
suspend fun fireIntent(intent: android.content.Intent): ActionResult
```

Implementation in `AccessibilityPlatform`: get service context → `startActivity(intent)`.

## Policy

- Same `AppClassifier` + `AppTier` gating as `open_app`
- Target package extracted from intent (explicit) or resolved via `PackageManager` (implicit)
- `BLOCKED` package → deny
- `CAUTIOUS` + `SMART` mode → ask user
- `NORMAL` + `AUTO_APPROVE` → allow

## Phases

### V1: Core Tool
- [ ] `AndroidPlatform.fireIntent()` method
- [ ] `OpenIntentTool` implementing `ToolSpec`
- [ ] Parameter validation (action required, extras type checking)
- [ ] Policy integration (BLOCKED check via resolved package)
- [ ] Register in `SessionToolingBootstrapper`
- [ ] Post-launch screen capture + observation

### V2: Intent Registry
- [ ] `IntentRegistry` — scan installed packages at session start
- [ ] Cache exported activities + intent-filters as structured data
- [ ] Query API for agent (or inject top-N into prompt context)
- [ ] Refresh on package install/uninstall broadcast

### V3: Smart Routing
- [ ] Agent planner queries registry before choosing navigation strategy
- [ ] `open_intent` for matched intents, UI clicks for everything else
- [ ] Per-app deep link knowledge in app skills where needed
