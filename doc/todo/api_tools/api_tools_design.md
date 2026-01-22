# API-Like Tools Design for Android Agent

> **Goal**: Replace low-level UI actions (click/swipe/type) with high-level API-like tools that directly invoke Android system capabilities, reducing the number of turns and improving reliability.

## Table of Contents

1. [Motivation](#motivation)
2. [Architecture Changes](#architecture-changes)
3. [Tool Categories](#tool-categories)
4. [Tool Specifications](#tool-specifications)
5. [Implementation Plan](#implementation-plan)
6. [Permissions & Manifest](#permissions--manifest)

---

## Motivation

### Current State

The agent currently uses low-level UI tools:
- `click` - Click UI element by index
- `type` - Type text into element
- `scroll`, `swipe` - Navigate within screens
- `back`, `home` - System navigation

**Problem**: To open Gmail and compose an email, the agent might need:
1. `home` → Go to home screen
2. `scroll` → Find Gmail icon
3. `click` → Open Gmail
4. `wait` → Wait for load
5. `click` → Tap compose button
6. `click` → Tap recipient field
7. `type` → Enter email address
8. `click` → Tap subject field
9. `type` → Enter subject
10. ... and so on

This is **fragile** (UI changes break it), **slow** (many turns), and **unreliable** (timing issues).

### Proposed State

With API-like tools:
1. `compose_email` → Opens email compose screen with prefilled fields

One tool call replaces 10+ UI actions.

---

## Architecture Changes

### New UIAction Types

Extend `UIAction.kt` to support intent-based actions:

```kotlin
sealed interface UIAction {
    // ... existing actions ...
    
    /** Launch an app by package name */
    data class LaunchApp(
        val packageName: String
    ) : UIAction
    
    /** Fire an intent (general purpose) */
    data class FireIntent(
        val action: String,
        val data: String? = null,
        val extras: Map<String, Any> = emptyMap(),
        val packageName: String? = null,
        val flags: Int = Intent.FLAG_ACTIVITY_NEW_TASK
    ) : UIAction
    
    /** Open a URI (deep link, URL, tel:, mailto:, etc.) */
    data class OpenUri(
        val uri: String
    ) : UIAction
    
    /** Query data (returns JSON result instead of performing UI action) */
    data class QueryData(
        val queryType: QueryType,
        val params: Map<String, Any> = emptyMap()
    ) : UIAction
}

enum class QueryType {
    INSTALLED_APPS,
    CONTACTS,
    CALENDAR_EVENTS,
    // ... more query types
}
```

### Platform Extension

Add methods to `AndroidPlatform` interface:

```kotlin
interface AndroidPlatform {
    // ... existing methods ...
    
    /** Get list of installed launchable apps */
    suspend fun getInstalledApps(): List<AppInfo>
    
    /** Launch app by package name */
    suspend fun launchApp(packageName: String): ActionResult
    
    /** Fire an intent */
    suspend fun fireIntent(intent: IntentSpec): ActionResult
    
    /** Query contacts (requires READ_CONTACTS permission) */
    suspend fun queryContacts(filter: String? = null): List<ContactInfo>
    
    /** Query calendar events (requires READ_CALENDAR permission) */
    suspend fun queryCalendarEvents(startMs: Long, endMs: Long): List<CalendarEvent>
}
```

### Tool Base Class Extension

Create a new base class for "data tools" that return structured data instead of performing UI actions:

```kotlin
/**
 * Base class for tools that query/return data rather than perform UI actions.
 * These tools don't need post-action screen observation.
 */
abstract class DataTool : ToolSpec {
    abstract suspend fun query(params: JSONObject, context: ToolExecutionContext): ToolExecutionResult
    
    override fun createInvocation(params: JSONObject): ToolInvocation {
        return DataToolInvocation(this, params)
    }
}
```

---

## Tool Categories

### 1. App Management Tools
| Tool | Description |
|------|-------------|
| `list_apps` | Get list of installed launchable apps |
| `open_app` | Launch an app by package name or app name |

### 2. Intent/DeepLink Tools
| Tool | Description |
|------|-------------|
| `open_url` | Open a URL in browser |
| `open_uri` | Open any URI (deep link, custom scheme) |
| `share_text` | Share text to any app |

### 3. Communication Tools
| Tool | Description |
|------|-------------|
| `compose_email` | Open email compose with prefilled fields |
| `send_sms` | Open SMS compose with recipient and message |
| `dial_phone` | Open phone dialer with number |
| `call_phone` | Directly make a phone call (requires approval) |

### 4. Productivity Tools
| Tool | Description |
|------|-------------|
| `create_calendar_event` | Open calendar event creation UI |
| `search_contacts` | Query contacts by name/phone/email |
| `navigate_to` | Open maps/navigation to address |

### 5. System Tools
| Tool | Description |
|------|-------------|
| `open_settings` | Open specific settings screen |
| `open_notifications` | Pull down notification shade |
| `open_quick_settings` | Open quick settings panel |
| `open_recent_apps` | Open recent apps view |

---

## Tool Specifications

### 1. `list_apps` - Get Installed Apps

**Purpose**: Returns a list of installed launchable apps so the agent knows what's available.

**Parameters**:
```json
{
  "type": "object",
  "properties": {
    "include_system": {
      "type": "boolean",
      "description": "Include system apps (default: false, only user-installed apps)"
    },
    "filter": {
      "type": "string",
      "description": "Optional filter by app name (case-insensitive substring match)"
    }
  },
  "required": [],
  "additionalProperties": false
}
```

**Returns**: JSON array of apps
```json
{
  "apps": [
    {
      "package_name": "com.google.android.gm",
      "label": "Gmail",
      "is_system": false
    },
    {
      "package_name": "com.whatsapp",
      "label": "WhatsApp",
      "is_system": false
    }
  ],
  "count": 2
}
```

**Implementation Notes**:
- Uses `PackageManager.queryIntentActivities()` with `ACTION_MAIN` + `CATEGORY_LAUNCHER`
- Requires `<queries>` declaration in manifest for package visibility (API 30+)
- Can use `QUERY_ALL_PACKAGES` permission (restricted, needs Play Store justification)

---

### 2. `open_app` - Launch App

**Purpose**: Launch an app directly by package name or friendly name.

**Parameters**:
```json
{
  "type": "object",
  "properties": {
    "package_name": {
      "type": "string",
      "description": "Package name (e.g., 'com.google.android.gm' for Gmail). Takes precedence if both provided."
    },
    "app_name": {
      "type": "string",
      "description": "App display name (e.g., 'Gmail'). Case-insensitive fuzzy match."
    }
  },
  "required": [],
  "additionalProperties": false
}
```

**Validation**: At least one of `package_name` or `app_name` must be provided.

**Implementation**:
```kotlin
val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
if (launchIntent != null) {
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(launchIntent)
}
```

**Error Cases**:
- App not installed: Return error with suggestion to check `list_apps`
- App not visible (API 30+ visibility): Same as not installed

---

### 3. `open_url` - Open URL in Browser

**Purpose**: Open a web URL in the default browser or a specific browser app.

**Parameters**:
```json
{
  "type": "object",
  "properties": {
    "url": {
      "type": "string",
      "description": "The URL to open (must start with http:// or https://)"
    },
    "browser": {
      "type": "string",
      "description": "Optional: specific browser package (e.g., 'com.android.chrome')"
    }
  },
  "required": ["url"],
  "additionalProperties": false
}
```

**Implementation**:
```kotlin
val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
if (browser != null) {
    intent.setPackage(browser)
}
context.startActivity(intent)
```

---

### 4. `open_uri` - Open Any URI / Deep Link

**Purpose**: Open any URI including deep links, custom schemes, and system URIs.

**Parameters**:
```json
{
  "type": "object",
  "properties": {
    "uri": {
      "type": "string",
      "description": "URI to open (e.g., 'spotify:track:xxx', 'twitter://user?screen_name=xxx', 'geo:37.7749,-122.4194')"
    }
  },
  "required": ["uri"],
  "additionalProperties": false
}
```

**Common URI Schemes**:
| Scheme | Example | Target |
|--------|---------|--------|
| `tel:` | `tel:+1234567890` | Phone dialer |
| `mailto:` | `mailto:user@example.com` | Email client |
| `sms:` | `sms:+1234567890` | SMS app |
| `geo:` | `geo:37.7749,-122.4194` | Maps app |
| `spotify:` | `spotify:track:xxx` | Spotify |
| `twitter:` | `twitter://user?screen_name=xxx` | Twitter/X |
| `instagram:` | `instagram://user?username=xxx` | Instagram |

---

### 5. `compose_email` - Compose Email

**Purpose**: Open email compose screen with pre-filled fields.

**Parameters**:
```json
{
  "type": "object",
  "properties": {
    "to": {
      "type": "array",
      "items": { "type": "string" },
      "description": "Recipient email addresses"
    },
    "cc": {
      "type": "array",
      "items": { "type": "string" },
      "description": "CC email addresses"
    },
    "bcc": {
      "type": "array",
      "items": { "type": "string" },
      "description": "BCC email addresses"
    },
    "subject": {
      "type": "string",
      "description": "Email subject"
    },
    "body": {
      "type": "string",
      "description": "Email body text"
    }
  },
  "required": [],
  "additionalProperties": false
}
```

**Implementation**:
```kotlin
// Option 1: mailto: URI (limited, no attachments)
val uri = Uri.parse("mailto:${to.joinToString(",")}")
val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
    putExtra(Intent.EXTRA_SUBJECT, subject)
    putExtra(Intent.EXTRA_TEXT, body)
    putExtra(Intent.EXTRA_CC, cc.toTypedArray())
    putExtra(Intent.EXTRA_BCC, bcc.toTypedArray())
}

// Option 2: ACTION_SEND with selector (supports more features)
val selectorIntent = Intent(Intent.ACTION_SENDTO).apply {
    data = Uri.parse("mailto:")
}
val sendIntent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_EMAIL, to.toTypedArray())
    putExtra(Intent.EXTRA_SUBJECT, subject)
    putExtra(Intent.EXTRA_TEXT, body)
    selector = selectorIntent
}
context.startActivity(Intent.createChooser(sendIntent, "Send email"))
```

---

### 6. `send_sms` - Send SMS

**Purpose**: Open SMS compose with recipient and message.

**Parameters**:
```json
{
  "type": "object",
  "properties": {
    "phone_number": {
      "type": "string",
      "description": "Recipient phone number"
    },
    "message": {
      "type": "string",
      "description": "Message text to pre-fill"
    }
  },
  "required": ["phone_number"],
  "additionalProperties": false
}
```

**Implementation**:
```kotlin
val intent = Intent(Intent.ACTION_SENDTO).apply {
    data = Uri.parse("smsto:$phoneNumber")
    putExtra("sms_body", message)
}
context.startActivity(intent)
```

---

### 7. `dial_phone` - Open Phone Dialer

**Purpose**: Open phone dialer with a number (doesn't initiate call).

**Parameters**:
```json
{
  "type": "object",
  "properties": {
    "phone_number": {
      "type": "string",
      "description": "Phone number to dial"
    }
  },
  "required": ["phone_number"],
  "additionalProperties": false
}
```

**Implementation**:
```kotlin
val intent = Intent(Intent.ACTION_DIAL).apply {
    data = Uri.parse("tel:$phoneNumber")
}
context.startActivity(intent)
```

---

### 8. `call_phone` - Make Phone Call (High Risk)

**Purpose**: Directly initiate a phone call.

**Parameters**:
```json
{
  "type": "object",
  "properties": {
    "phone_number": {
      "type": "string",
      "description": "Phone number to call"
    }
  },
  "required": ["phone_number"],
  "additionalProperties": false
}
```

**Risk Level**: HIGH (should always require user approval)

**Permission**: `android.permission.CALL_PHONE`

**Implementation**:
```kotlin
val intent = Intent(Intent.ACTION_CALL).apply {
    data = Uri.parse("tel:$phoneNumber")
}
context.startActivity(intent)
```

---

### 9. `create_calendar_event` - Create Calendar Event

**Purpose**: Open calendar app to create an event with pre-filled details.

**Parameters**:
```json
{
  "type": "object",
  "properties": {
    "title": {
      "type": "string",
      "description": "Event title"
    },
    "description": {
      "type": "string",
      "description": "Event description"
    },
    "location": {
      "type": "string",
      "description": "Event location"
    },
    "start_time": {
      "type": "string",
      "description": "Start time in ISO 8601 format (e.g., '2025-01-22T14:00:00')"
    },
    "end_time": {
      "type": "string",
      "description": "End time in ISO 8601 format"
    },
    "all_day": {
      "type": "boolean",
      "description": "Whether this is an all-day event"
    }
  },
  "required": ["title"],
  "additionalProperties": false
}
```

**Implementation**:
```kotlin
val intent = Intent(Intent.ACTION_INSERT).apply {
    data = CalendarContract.Events.CONTENT_URI
    putExtra(CalendarContract.Events.TITLE, title)
    putExtra(CalendarContract.Events.DESCRIPTION, description)
    putExtra(CalendarContract.Events.EVENT_LOCATION, location)
    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startTimeMs)
    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTimeMs)
    putExtra(CalendarContract.Events.ALL_DAY, allDay)
}
context.startActivity(intent)
```

---

### 10. `search_contacts` - Query Contacts

**Purpose**: Search contacts by name, phone, or email (returns data).

**Parameters**:
```json
{
  "type": "object",
  "properties": {
    "query": {
      "type": "string",
      "description": "Search query (name, phone number, or email)"
    },
    "limit": {
      "type": "integer",
      "description": "Maximum number of results (default: 10)"
    }
  },
  "required": ["query"],
  "additionalProperties": false
}
```

**Permission**: `android.permission.READ_CONTACTS`

**Returns**:
```json
{
  "contacts": [
    {
      "id": "123",
      "display_name": "John Doe",
      "phone_numbers": ["+1234567890"],
      "emails": ["john@example.com"]
    }
  ],
  "count": 1
}
```

---

### 11. `navigate_to` - Open Navigation

**Purpose**: Open maps/navigation to a location.

**Parameters**:
```json
{
  "type": "object",
  "properties": {
    "destination": {
      "type": "string",
      "description": "Destination address or place name"
    },
    "mode": {
      "type": "string",
      "enum": ["driving", "walking", "bicycling", "transit"],
      "description": "Navigation mode (default: driving)"
    }
  },
  "required": ["destination"],
  "additionalProperties": false
}
```

**Implementation**:
```kotlin
// Option 1: Google Maps specific
val uri = Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$mode")
val intent = Intent(Intent.ACTION_VIEW, uri)
intent.setPackage("com.google.android.apps.maps")

// Option 2: Generic geo: URI (works with any maps app)
val uri = Uri.parse("geo:0,0?q=${Uri.encode(destination)}")
val intent = Intent(Intent.ACTION_VIEW, uri)
```

---

### 12. `open_settings` - Open Settings Screen

**Purpose**: Open a specific Android settings screen.

**Parameters**:
```json
{
  "type": "object",
  "properties": {
    "screen": {
      "type": "string",
      "enum": [
        "main", "wifi", "bluetooth", "display", "sound",
        "battery", "storage", "apps", "location", "security",
        "privacy", "accessibility", "date_time", "language",
        "developer", "about", "app_info"
      ],
      "description": "Which settings screen to open"
    },
    "package_name": {
      "type": "string",
      "description": "For 'app_info': the package name of the app to show settings for"
    }
  },
  "required": ["screen"],
  "additionalProperties": false
}
```

**Settings Action Mapping**:
```kotlin
val action = when (screen) {
    "main" -> Settings.ACTION_SETTINGS
    "wifi" -> Settings.ACTION_WIFI_SETTINGS
    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
    "display" -> Settings.ACTION_DISPLAY_SETTINGS
    "sound" -> Settings.ACTION_SOUND_SETTINGS
    "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
    "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
    "apps" -> Settings.ACTION_APPLICATION_SETTINGS
    "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
    "security" -> Settings.ACTION_SECURITY_SETTINGS
    "privacy" -> Settings.ACTION_PRIVACY_SETTINGS
    "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
    "date_time" -> Settings.ACTION_DATE_SETTINGS
    "language" -> Settings.ACTION_LOCALE_SETTINGS
    "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
    "about" -> Settings.ACTION_DEVICE_INFO_SETTINGS
    "app_info" -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
    else -> Settings.ACTION_SETTINGS
}
```

---

### 13. `open_notifications` - Open Notification Shade

**Purpose**: Pull down the notification shade (uses accessibility global action).

**Parameters**: None (empty schema)

**Implementation**:
```kotlin
service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
```

---

### 14. `open_quick_settings` - Open Quick Settings

**Purpose**: Open quick settings panel (uses accessibility global action).

**Parameters**: None (empty schema)

**Implementation**:
```kotlin
service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
```

---

### 15. `share_text` - Share Text to Apps

**Purpose**: Open share dialog with text content.

**Parameters**:
```json
{
  "type": "object",
  "properties": {
    "text": {
      "type": "string",
      "description": "Text content to share"
    },
    "title": {
      "type": "string",
      "description": "Title for the share (optional)"
    }
  },
  "required": ["text"],
  "additionalProperties": false
}
```

**Implementation**:
```kotlin
val intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_TEXT, text)
    putExtra(Intent.EXTRA_TITLE, title)
}
context.startActivity(Intent.createChooser(intent, "Share via"))
```

---

## Implementation Plan

### Phase 1: Core Infrastructure
1. Extend `UIAction` with new action types (`LaunchApp`, `FireIntent`, `OpenUri`)
2. Add new methods to `AndroidPlatform` interface
3. Implement in `AccessibilityPlatform`
4. Create `IntentTool` base class for intent-based tools

### Phase 2: App Management Tools
1. `list_apps` - Query installed apps
2. `open_app` - Launch apps

### Phase 3: Intent/URI Tools
1. `open_url` - Open URLs
2. `open_uri` - Open any URI
3. `share_text` - Share content

### Phase 4: Communication Tools
1. `compose_email` - Email compose
2. `send_sms` - SMS compose
3. `dial_phone` - Phone dialer
4. `call_phone` - Direct call (high risk)

### Phase 5: Productivity Tools
1. `create_calendar_event` - Calendar events
2. `search_contacts` - Contact queries
3. `navigate_to` - Maps navigation

### Phase 6: System Tools
1. `open_settings` - Settings screens
2. `open_notifications` - Notification shade
3. `open_quick_settings` - Quick settings

---

## Permissions & Manifest

### Required Manifest Additions

```xml
<manifest>
    <!-- Package visibility for API 30+ -->
    <queries>
        <!-- Allow querying all launcher apps -->
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
        
        <!-- Allow checking for specific handlers -->
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="https" />
        </intent>
        <intent>
            <action android:name="android.intent.action.SENDTO" />
            <data android:scheme="mailto" />
        </intent>
        <intent>
            <action android:name="android.intent.action.DIAL" />
            <data android:scheme="tel" />
        </intent>
    </queries>
    
    <!-- Optional: See ALL packages (restricted, needs Play Store approval) -->
    <!-- <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" /> -->
    
    <!-- For search_contacts tool -->
    <uses-permission android:name="android.permission.READ_CONTACTS" />
    
    <!-- For call_phone tool (high risk) -->
    <uses-permission android:name="android.permission.CALL_PHONE" />
    
    <!-- For calendar queries (if added later) -->
    <uses-permission android:name="android.permission.READ_CALENDAR" />
</manifest>
```

### Runtime Permissions

Some tools require runtime permission checks:

| Tool | Permission | Risk Level |
|------|------------|------------|
| `search_contacts` | `READ_CONTACTS` | Medium |
| `call_phone` | `CALL_PHONE` | High |
| Calendar queries | `READ_CALENDAR` | Medium |

### Policy Engine Integration

Update `PolicyEngine.kt` to handle new tools:

```kotlin
override fun checkPolicy(toolName: String, params: JSONObject): PolicyDecision {
    return when (toolName) {
        // High risk - always ask
        "call_phone" -> PolicyDecision.AskUser(
            reason = "Directly calling ${params.optString("phone_number")}",
            riskLevel = RiskLevel.HIGH
        )
        
        // Medium risk - ask in smart mode
        "compose_email", "send_sms" -> PolicyDecision.AskUser(
            reason = "Opening communication app",
            riskLevel = RiskLevel.MEDIUM
        )
        
        // Low risk - auto-approve
        "list_apps", "open_url", "open_settings", 
        "open_notifications", "dial_phone" -> PolicyDecision.Allow
        
        else -> PolicyDecision.Allow
    }
}
```

---

## Example Usage Scenarios

### Scenario 1: "Send an email to john@example.com about the meeting"

**Before (UI-based)**:
```
Turn 1: home → Go to home screen
Turn 2: scroll down → Look for Gmail
Turn 3: click element 15 → Open Gmail
Turn 4: wait 2000 → Wait for load
Turn 5: click element 3 → Tap compose
Turn 6: click element 8 → Tap To field
Turn 7: type "john@example.com" → Enter recipient
Turn 8: click element 10 → Tap Subject
Turn 9: type "Meeting" → Enter subject
Turn 10: click element 12 → Tap body
Turn 11: type "..." → Enter message
```

**After (API-based)**:
```
Turn 1: compose_email → Opens compose with all fields prefilled
Turn 2: complete_task → Done
```

### Scenario 2: "What apps do I have for messaging?"

**Before**: Impossible without UI navigation and reading screen

**After**:
```
Turn 1: list_apps with filter="message" → Returns WhatsApp, Telegram, Messenger, etc.
Turn 2: complete_task with list → Done
```

### Scenario 3: "Navigate to 1600 Amphitheatre Parkway"

**Before**:
```
Turn 1: home
Turn 2: scroll to find Maps
Turn 3: click Maps
Turn 4: wait for load
Turn 5: click search bar
Turn 6: type address
Turn 7: click search result
Turn 8: click Directions
Turn 9: click Start
```

**After**:
```
Turn 1: navigate_to destination="1600 Amphitheatre Parkway" → Opens navigation directly
Turn 2: complete_task → Done
```

---

## Future Extensions

### Potential Additional Tools

1. **`read_clipboard`** - Read clipboard content
2. **`write_clipboard`** - Copy text to clipboard
3. **`set_alarm`** - Create alarm via Clock app
4. **`set_timer`** - Create timer via Clock app
5. **`play_music`** - Control music playback
6. **`take_screenshot`** - Capture current screen
7. **`toggle_wifi`** / `toggle_bluetooth` - Quick toggles (if permissions allow)
8. **`search_web`** - Open web search with query
9. **`open_file`** - Open file with appropriate app
10. **`scan_qr_code`** - Open camera for QR scanning

### Google Suite Deep Links

| App | Deep Link Pattern | Purpose |
|-----|-------------------|---------|
| Gmail | `googlegmail://co?subject=...&body=...&to=...` | Compose |
| Calendar | `content://com.android.calendar/events` | Events |
| Drive | `googledrive://open?id=FILE_ID` | Open file |
| Maps | `google.navigation:q=...` | Navigate |
| Photos | `google.photos://...` | Open photos |
| Meet | `googlemeet://new` | Start meeting |

---

*This design document will be updated as implementation progresses.*
