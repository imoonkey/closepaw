---
name: com.android.settings
description: App-specific guidance for Android Settings.
---

## System Toggles (Wi-Fi, Bluetooth)
- Wi-Fi: Network & internet → Internet → tap the Wi-Fi row to toggle.
- Bluetooth: Connected devices → Connection preferences → Bluetooth → toggle.
- Shell commands (`svc wifi enable`) fail from the agent process. Use UI navigation.
- After toggling, verify the switch shows the desired state before declaring success.

## Brightness
- Use the Settings slider UI — shell commands for brightness are unreliable across devices.
