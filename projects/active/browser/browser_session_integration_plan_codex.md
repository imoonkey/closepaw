# Browser Session Integration Plan

## Scope

Wire the accepted browser CDP runtime into session lifecycle, tool registration,
settings, and approval policy without moving Android `Context` or WebView ownership
into `SessionToolingBootstrapper`.

## Phase 1: Session Runtime Ownership

- Add `BrowserSessionManager` under `browser/script`.
- Keep resources lazy: no Chrome/CDP/WebView work until `browser_script` runs.
- Own the bridge, CDP client, runner, and exclusive script lease in the manager.
- Make `close()` cancel active work and release CDP + Shizuku resources.

## Phase 2: Session Wiring

- Create the manager in `SessionServices` with application context, session scope,
  trace recorder, Shizuku bridge dependencies, and settings-backed experimental gate.
- Register `BrowserScriptTool` from `SessionServices`, not from
  `SessionToolingBootstrapper`.
- Keep `SessionToolingBootstrapper` limited to Android-free built-in tools.

## Phase 3: Policy And Settings

- Add `ToolName.BrowserScript` and mark it screen-changing.
- Add `browserScriptEnabled` to `AppSettings` / `AppSettingsState` /
  `AppSettingsStore`.
- Add a browser-specific `PolicyEngine` rule: `browser_script` always asks in SMART
  and ALWAYS_ASK, even when the foreground app is Chrome and classified NORMAL.

## Phase 4: Tests And Verification

- Cover the SMART+Chrome policy rule, including allow-list bypass resistance.
- Cover browser tool registry wiring through the session bootstrap boundary.
- Cover settings round-trip.
- Cover session cleanup calling `BrowserSessionManager.close()`.
- Run `./gradlew test` and the task acceptance `rg` checks.
