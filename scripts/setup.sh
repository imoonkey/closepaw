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
# Note: Run this after code changes before using dev.sh
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
PACKAGE="com.moonkey.androidagent"
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

echo -e "${GREEN}"
echo "=============================================================="
echo "         Android Agent - Build & Deploy                        "
echo "=============================================================="
echo -e "${NC}"

# 0. If no device, then start an android emulator.
# ~/Library/Android/sdk/emulator/emulator @Pixel_Test -no-boot-anim &

# 1. Check device
log "Checking device connection..."
if ! adb get-state >/dev/null 2>&1; then
    err "No device detected. Please connect device and enable USB debugging."
fi
DEVICE=$(adb devices | grep -v "List" | grep "device$" | head -1 | awk '{print $1}')
ok "Device connected: $DEVICE"

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

# 5. Grant Overlay permission
log "Granting Overlay permission..."
adb shell appops set "$PACKAGE" SYSTEM_ALERT_WINDOW allow 2>/dev/null
ok "Overlay permission granted"

# 6. Enable Accessibility service
log "Enabling Accessibility service..."
adb shell settings put secure enabled_accessibility_services "$PACKAGE/$PACKAGE.app.AgentService"
adb shell settings put secure accessibility_enabled 1
sleep 1

# Verify
ENABLED=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
if [[ "$ENABLED" == *"$PACKAGE"* ]]; then
    ok "Accessibility service enabled"
else
    warn "Accessibility service may need manual activation"
    warn "Path: Settings > Accessibility > Downloaded apps > Android Agent"
fi

# 7. Launch app
log "Launching app..."
adb shell am start -n "$PACKAGE/.app.MainActivity" >/dev/null
ok "App launched"

echo ""
echo -e "${GREEN}==============================================================${NC}"
echo -e "${GREEN}✓ Setup complete!${NC}"
echo ""
echo "Now use dev.sh to run tests:"
echo "  ./scripts/dev.sh run              # Run with default goal"
echo "  ./scripts/dev.sh run 'Open Chrome' # Run with custom goal"
echo "  ./scripts/dev.sh logs             # View logs"
echo -e "${GREEN}==============================================================${NC}"
