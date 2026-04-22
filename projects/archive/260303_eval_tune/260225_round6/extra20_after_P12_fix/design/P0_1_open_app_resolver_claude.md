# P0-1: Fix open_app Resolver for Simple Calendar Pro

## Problem

`open_app("Simple Calendar Pro")` fails because:
1. The app label returned by `getInstalledApps()` doesn't match "Simple Calendar Pro" exactly
2. The `AppAliases.PACKAGE_MAP` (OpenAppTool.kt:21-47) has `"calendar" to "com.google.android.calendar"` which maps to Google Calendar, not Simple Calendar Pro
3. The agent falls back to fuzzy matching → "Calendar" → Google Calendar → GMS sign-in → 12-13 wasted turns

## Current Resolution Strategy (OpenAppTool.kt)

1. Foreground check (skip if already open)
2. Exact label match (case-insensitive)
3. Label-contains match
4. Well-known alias → package (`AppAliases.PACKAGE_MAP`)
5. Package-name-shaped input
6. Fuzzy suggestions

## Fix

Add eval-relevant app aliases to `AppAliases.PACKAGE_MAP` in `OpenAppTool.kt:21-47`:

```kotlin
private object AppAliases {
    val PACKAGE_MAP = mapOf(
        // ... existing entries ...
        "simple calendar" to "com.simplemobiletools.calendar.pro",
        "simple calendar pro" to "com.simplemobiletools.calendar.pro",
        "audio recorder" to "com.dimowner.audiorecorder",
        "pro expense" to "com.arduia.expense",
        "markor" to "net.gsantner.markor",
    )
}
```

## Why This Is Clean

- Same pattern as existing aliases — no new abstraction needed
- `PACKAGE_MAP` already handles the "well-known name → package" mapping; we're just expanding coverage
- Resolution order stays the same: exact label match is tried first, aliases only kick in as fallback
- If the app IS installed with the right label, exact match catches it before the alias is needed

## Impact

- Saves 12-13 turns per calendar task (SimpleCalendarAddOneEvent, SimpleCalendarDeleteOneEvent)
- Prevents the Google Calendar / GMS sign-in trap
- Also helps ASK_USER_BLOCKED tasks if they retry after the `ask_user` fix (they burned 2-4 turns on app resolution before asking)

## Files Changed

| File | Change |
|---|---|
| `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/OpenAppTool.kt` | Add 4 entries to `AppAliases.PACKAGE_MAP` |

## Risks

None. Additive change, no behavioral change for existing aliases.
