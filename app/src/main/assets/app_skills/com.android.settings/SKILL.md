---
name: com.android.settings
description: App-specific guidance for Android Settings.
---

# Android Settings Skill

## System Toggles (Wi-Fi, Bluetooth)
Navigate the Settings UI to toggle system settings:
- Wi-Fi: Network & internet → Internet → tap the Wi-Fi row to toggle
- Bluetooth: Connected devices → Connection preferences → Bluetooth → toggle
Shell commands (`svc wifi enable`) require ADB-level permissions and fail from the agent process. Use UI navigation instead.
After toggling, verify the state before declaring success.

## Brightness
Use the Settings slider UI for brightness adjustments — shell commands for brightness are unreliable across devices.
