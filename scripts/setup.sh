#!/bin/bash
#
# setup.sh - Build, Install & Setup Permissions
#
# Purpose: Deploy a new version of the app with all permissions configured
#
# What it does:
#   - Build APK
#   - Install APK (replacement install, preserves data)
#   - Grant Overlay permission
#   - Enable Accessibility service
#   - Launch app
#
# Usage: ./scripts/setup.sh
#
# Environment Variables:
#   LLM_BACKEND: "openai" (default) or "local" - selects LLM backend
#                Set to "local" to skip API key requirement
#
# Note: Run this after code changes before using debug-run.sh
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
PACKAGE="ai.closepaw"
APK_PATH="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

log() { echo -e "${BLUE}> $1${NC}"; }
ok() { echo -e "${GREEN}✓ $1${NC}"; }
warn() { echo -e "${YELLOW}! $1${NC}"; }
err() { echo -e "${RED}x $1${NC}"; exit 1; }

list_connected_devices() {
    adb devices | awk 'NR > 1 && $2 == "device" {print $1}'
}

select_device() {
    local preferred_serial="${ANDROID_SERIAL:-}"
    local devices
    local physical_devices

    devices="$(list_connected_devices)"
    if [[ -z "$devices" ]]; then
        return 1
    fi

    if [[ -n "$preferred_serial" ]]; then
        if printf "%s\n" "$devices" | grep -Fxq "$preferred_serial"; then
            printf "%s\n" "$preferred_serial"
            return 0
        fi
        warn "ANDROID_SERIAL=$preferred_serial not found; auto-selecting device."
    fi

    physical_devices="$(printf "%s\n" "$devices" | grep -v '^emulator-' || true)"
    if [[ -n "$physical_devices" ]]; then
        printf "%s\n" "$physical_devices" | head -n 1
        return 0
    fi

    printf "%s\n" "$devices" | head -n 1
    return 0
}

echo -e "${GREEN}"
echo "=============================================================="
echo "         ClosePaw - Build & Deploy                        "
echo "=============================================================="
echo -e "${NC}"

# 0. If no device, then start an android emulator.
# ~/Library/Android/sdk/emulator/emulator @Pixel_Test -no-boot-anim &

# 1. Check device
log "Checking device connection..."
DEVICE="$(select_device || true)"
if [[ -z "$DEVICE" ]]; then
    err "No device detected. Please connect device and enable USB debugging."
fi
export ANDROID_SERIAL="$DEVICE"

CONNECTED_DEVICES="$(list_connected_devices)"
CONNECTED_COUNT="$(printf "%s\n" "$CONNECTED_DEVICES" | sed '/^$/d' | wc -l | tr -d ' ')"
if [[ "$CONNECTED_COUNT" -gt 1 ]]; then
    warn "Multiple devices detected; using $DEVICE"
fi

if [[ "$DEVICE" == emulator-* ]]; then
    warn "No physical device found; using emulator: $DEVICE"
else
    ok "Selected physical device: $DEVICE"
fi

# 2. Load .env file and check backend
if [[ -f "$PROJECT_ROOT/.env" ]]; then
    source "$PROJECT_ROOT/.env"
fi

# Determine LLM backend (env var takes precedence)
LLM_BACKEND="${LLM_BACKEND:-openai}"
log "LLM Backend: $LLM_BACKEND"

# Check API key only for OpenAI backend
if [[ "$LLM_BACKEND" == "openai" ]]; then
    log "Checking API Key..."
    if [[ -z "$OPENAI_API_KEY" || ! "$OPENAI_API_KEY" =~ ^sk- ]]; then
        err "Invalid API Key. Please set OPENAI_API_KEY=sk-xxx in .env, or use LLM_BACKEND=local"
    fi
    ok "API Key configured"
else
    ok "Using local LLM backend (no API key required)"
fi

# 3. Build APK
log "Building APK..."
cd "$PROJECT_ROOT"
./gradlew :app:assembleDebug --quiet || err "Build failed"
ok "APK built successfully"

# 4. Install APK (replacement install - preserves data)
log "Installing APK..."
adb install -r "$APK_PATH" || err "Installation failed"
ok "APK installed successfully"

# 5. Ensure Shizuku server is running
#    Wireless-ADB self-pair (and the in-band Shizuku UserService transport) both need the
#    privileged shell-uid binder published by `shizuku_server`. The Shizuku APP (UI) being
#    installed is not the same as the server running — after a reboot, after `adb install -r`
#    that drops the cached binder, or any time the user opens the Shizuku app and sees
#    "Shizuku is not running", the server is dead until restarted via the standard start.sh.
log "Checking Shizuku server..."
SHIZUKU_PKG="moe.shizuku.privileged.api"
SHIZUKU_PID=$(adb shell pidof shizuku_server 2>/dev/null | tr -d '\r ')
if [[ -n "$SHIZUKU_PID" ]]; then
    ok "Shizuku server already running (pid $SHIZUKU_PID)"
else
    SHIZUKU_START="/sdcard/Android/data/${SHIZUKU_PKG}/start.sh"
    if adb shell "[ -f $SHIZUKU_START ]" 2>/dev/null; then
        log "Starting Shizuku server via $SHIZUKU_START..."
        adb shell "sh $SHIZUKU_START" >/dev/null 2>&1 || true
        sleep 2
        SHIZUKU_PID=$(adb shell pidof shizuku_server 2>/dev/null | tr -d '\r ')
        if [[ -n "$SHIZUKU_PID" ]]; then
            ok "Shizuku server started (pid $SHIZUKU_PID)"
        else
            warn "start.sh ran but shizuku_server is still not detected. Open the Shizuku app and start it manually before invoking debug-run.sh."
        fi
    else
        warn "Shizuku not installed (no $SHIZUKU_START). Install Shizuku from Play Store / GitHub and start it before browser_script will work."
    fi
fi

# Verify ClosePaw has Shizuku's signature permission (granted via Shizuku UI, not pm grant).
SHIZUKU_GRANT=$(adb shell dumpsys package "$PACKAGE" 2>/dev/null | grep -E 'API_V23.*granted=true' | head -n 1)
if [[ -z "$SHIZUKU_GRANT" ]]; then
    warn "ClosePaw doesn't yet hold moe.shizuku.manager.permission.API_V23. Open the Shizuku app and grant it before testing wireless-ADB self-pair."
fi

# 5. Grant Overlay permission
log "Granting Overlay permission..."
adb shell appops set "$PACKAGE" SYSTEM_ALERT_WINDOW allow 2>/dev/null
ok "Overlay permission granted"

# 6. Enable Accessibility service
log "Enabling Accessibility service..."
adb shell settings put secure enabled_accessibility_services "$PACKAGE/$PACKAGE.app.AgentService"
adb shell settings put secure accessibility_enabled 1
sleep 1

# Verify (some OEM builds — notably OPPO/ColorOS — silently no-op the put;
# fail loudly so the user knows to toggle the service manually before testing).
ENABLED=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
A11Y_FLAG=$(adb shell settings get secure accessibility_enabled 2>/dev/null | tr -d '\r')
if [[ "$ENABLED" == *"$PACKAGE"* && "$A11Y_FLAG" == "1" ]]; then
    ok "Accessibility service enabled"
else
    err "Accessibility service did NOT stick (settings get returned: enabled_accessibility_services='$ENABLED', accessibility_enabled='$A11Y_FLAG'). This OEM build (e.g. OPPO/ColorOS) blocks adb-driven a11y enablement. Toggle manually: Settings > Accessibility > Downloaded apps > ClosePaw, then re-run."
fi

# 7. Launch app
log "Launching app..."
adb shell am start -n "$PACKAGE/.app.MainActivity" >/dev/null
ok "App launched"

echo ""
echo -e "${GREEN}==============================================================${NC}"
echo -e "${GREEN}✓ Setup complete!${NC}"
echo ""
echo "Next steps:"
echo "  ./scripts/debug-run.sh --basic \"Open Settings\"   # Run agent"
echo "  ./scripts/logs.sh                                # View logs"
echo -e "${GREEN}==============================================================${NC}"
