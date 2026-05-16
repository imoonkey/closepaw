#!/usr/bin/env bash
#
# Resets the device to Settings home and waits for it to settle.
# Used between CTN device QA scenarios.
#
set -euo pipefail
SERIAL="${1:-100.64.43.95:5555}"
adb -s "$SERIAL" shell input keyevent KEYCODE_HOME >/dev/null
sleep 1
adb -s "$SERIAL" shell am force-stop com.android.settings 2>/dev/null || true
adb -s "$SERIAL" shell am start -a android.settings.SETTINGS >/dev/null
sleep 2
