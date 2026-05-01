---
name: app-settings
description: App-specific guidance for Android Settings.
metadata:
  package: com.android.settings
---

## System Toggles (Wi-Fi, Bluetooth)
- Wi-Fi: Network & internet → Internet → tap the Wi-Fi row to toggle.
- Bluetooth: Connected devices → Connection preferences → Bluetooth → toggle.
- Shell commands (`svc wifi enable`) fail from the agent process. Use UI navigation.
- After toggling, verify the switch shows the desired state before declaring success.

## Brightness
- Use the Settings slider UI -- shell commands for brightness are unreliable across devices.

## Safety

**DANGEROUS -- ask user before:**
- Factory reset or erasing data
- Changing app permissions or revoking access
- Disabling system apps or accessibility services
- Modifying network/APN settings

**SAFE -- proceed normally:**
- Toggling Wi-Fi, Bluetooth, or brightness
- Reading device info, storage, or battery status
- Navigating settings menus
