# Real-device QA — memory-settings (task/memory-settings)

**Device:** OnePlus P0110 (`100.64.43.95:5555`), Android landscape/portrait.
**Build:** worktree `.worktrees/memory-settings` @ commit 085ac8ab.
**Date:** 2026-05-17/18 (local).

## Per-flow results

| Flow | Description | Status | Evidence |
| --- | --- | --- | --- |
| (a) | User Memory edit / save / reopen | PASS (prior session) | `a17_memory.png` → `a23_reopened.png` |
| (b) | Device Memory edit / save / reopen | PASS (prior session) | `b01_list.xml` → `b04_persist.png` |
| (c) | Delete memory file | PASS (prior session) | `c01_after_delete_tap.png` → `c03_reopened.png` |
| (d) | Live edit lock during active session | BLOCKED-DEVICE | `blocker_screenshot.png`, `blocker_evidence.txt` |
| (e) | Post-unlock reload + Save gate | BLOCKED-DEVICE | same as (d) — depends on a live session |
| (f) | App with bundled skill, no memory → `+ Memory` chip flips to `Memory` | **PASS** | `f08_portrait.png`, `f09_editor.png`, `f13_after_save.png`, `f14_reopened.png` |
| (g) | Agent Behavior → Tools: skill toggle persists | **PASS** | `g02_agent_behavior.png`, `g03_toggled_off.png`, `g04_reopened.png` |
| (h) | Disabled-skill set honored next session (start-session portion) | BLOCKED-DEVICE | same as (d). Toggle persistence to disk validated as part of (g). |

## Flow (f) detail — Chrome (bundled skill, no memory)

1. App Access → search `chrome` → `Chrome` row shows **only** a `Skill` chip (no `Memory`).
2. Tap Chrome to expand → skill viewer renders `App Skill` content from
   `assets/app_skills/com.android.chrome/SKILL.md`; `+ Memory` chip visible
   inside the expansion (`f08_portrait.png`).
3. Tap `+ Memory`:
   - Chip immediately flips to `Memory` (row now shows both `Memory` + `Skill`).
   - `App Memory` editor lands in edit mode with `No memory yet.` placeholder
     and `Discard` / `Save` buttons (`f09_editor.png`).
   - File created on disk: `files/memory/apps/com.android.chrome.md`
     (verified via `run-as ai.closepaw`).
4. Typed `QA_chrome_bullet_realdevice`, tapped `Save` → editor switches to
   view mode (`Edit` / `Delete` buttons) (`f13_after_save.png`).
5. Collapsed and re-expanded Chrome → `Memory` chip still present, saved
   bullet still rendered (`f14_reopened.png`).

## Flow (g) detail — Agent Behavior → Tools skill toggle

1. Settings → `Agent Behavior` → `Tools` section shows `Termux Shell`,
   `Browser Script`, plus `Agent Skills` group with `browser-use` row
   carrying both an `(i)` info icon and a toggle switch
   (`g02_agent_behavior.png`).
2. Toggled `browser-use` OFF → row label flips from `Enabled` to `Disabled`
   (`g03_toggled_off.png`).
3. Disk persistence verified:
   `shared_prefs/agent_prefs.xml` →
   `<string name="disabled_agent_skills">["browser-use"]</string>`.
4. Backed out to Settings, re-opened `Agent Behavior` → `browser-use` still
   `Disabled` (`g04_reopened.png`).
5. Toggled back ON → label returns to `Enabled`, `disabled_agent_skills`
   key removed from prefs.

Per-session enforcement (the "next session does not see browser-use" part)
is covered by emulator + JVM tests
(`SessionServicesBundledSkillInstallTest`, `AgentSkillToggleRowsTest`).

## Blocker — (d), (e), (h-session-start)

On this OnePlus device the AgentService accessibility binder is silently
dropped: secure setting `enabled_accessibility_services` accepts the value,
but `dumpsys accessibility` shows `Enabled services:{AgentService}` with
`Binding services:{}`. The in-app banner reads
**"Accessibility disabled — Agent cannot automate."** with a `Fix` CTA
(`blocker_screenshot.png`).

Without a bound AgentService the SessionCoordinator cannot drive a real
session, so the lock-flow UX paths (d/e/h-start) cannot be reproduced from
the device side. Logic is already covered on emulator and JVM:

- `MemoryEditGateTest`
- `SessionCoordinatorTest`
- `MemoryFileEditorTest` (gate scenarios)
- `AppAccessContentIndexTest`

Evidence file: `blocker_evidence.txt` (dumpsys snippet + logcat line showing
the secure-setting write being accepted with no follow-up bind).

## Summary

real-device QA: 5 PASS, 3 BLOCKED-DEVICE (documented), 0 FAIL → milestone ready
