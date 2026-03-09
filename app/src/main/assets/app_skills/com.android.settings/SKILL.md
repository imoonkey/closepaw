---
name: com.android.settings
description: App-specific guidance for Android Settings.
---

# Android Settings Skill

## System Toggles (Wi-Fi, Bluetooth)
For tasks that require toggling system settings, prefer shell commands for reliability:
- Wi-Fi: `svc wifi enable` / `svc wifi disable`
- Bluetooth: `svc bluetooth enable` / `svc bluetooth disable`
After toggling, verify the state before declaring success.

## Brightness
Use the Settings slider UI for brightness adjustments — shell commands for brightness are unreliable across devices.
