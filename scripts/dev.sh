#!/bin/bash
#
# dev.sh - Development Testing Script
#
# Purpose: Run agent tests and view logs (assumes app is already installed)
#
# Commands:
#   ./scripts/dev.sh run [goal]         # Run agent with goal (default: "Open Settings")
#   ./scripts/dev.sh logs [filter]      # View logs
#   ./scripts/dev.sh status             # Check device and service status
#
# Note: Run ./scripts/setup.sh first to build, install and configure permissions
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
PACKAGE="com.moonkey.androidagent"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

log() { echo -e "${BLUE}> $1${NC}"; }
ok() { echo -e "${GREEN}✓ $1${NC}"; }
warn() { echo -e "${YELLOW}! $1${NC}"; }
err() { echo -e "${RED}x $1${NC}"; }

# Check device connection
check_device() {
    if ! adb get-state >/dev/null 2>&1; then
        err "No device detected"
        exit 1
    fi
}

# Check app is installed
check_app() {
    if ! adb shell pm list packages | grep -q "$PACKAGE"; then
        err "App not installed. Run ./scripts/setup.sh first"
        exit 1
    fi
}

# Load API key
load_api_key() {
    if [[ -f "$PROJECT_ROOT/.env" ]]; then
        source "$PROJECT_ROOT/.env"
    fi
    if [[ -z "$OPENAI_API_KEY" ]]; then
        err "API Key not found, please check .env file"
        exit 1
    fi
}

# Get current foreground app
get_foreground() {
    adb shell dumpsys window | grep -E "mCurrentFocus" | grep -oE "com\.[a-zA-Z0-9._]+" | head -1
}

# Get recent agent logs (filtered)
get_agent_logs() {
    adb logcat -d -s Agent AgentSession AgentService Turn LLMClient ToolRouter AccessibilityPlatform 2>/dev/null | tail -200
}

# ===== Command: run =====
cmd_run() {
    local goal="${1:-Open Settings}"
    
    check_device
    check_app
    load_api_key
    
    log "Running agent with goal: $goal"
    
    # Go to home screen first
    adb shell input keyevent KEYCODE_HOME
    sleep 0.5
    
    # Launch app with intent extras
    # Using FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP to trigger onNewIntent
    # Don't use force-stop - it clears accessibility permission!
    adb shell "am start -n $PACKAGE/.MainActivity \
        --activity-clear-top --activity-single-top \
        --es api_key '$OPENAI_API_KEY' \
        --es goal '$goal' \
        --ez auto_start true" >/dev/null
    sleep 1.5
    
    # Check if on correct screen
    local fg=$(get_foreground)
    if [[ "$fg" != "$PACKAGE" ]]; then
        warn "App not in foreground (current: $fg), retrying..."
        adb shell input keyevent KEYCODE_BACK
        sleep 0.3
        # Retry with intent extras
        adb shell "am start -n $PACKAGE/.MainActivity \
            --activity-clear-top --activity-single-top \
            --es api_key '$OPENAI_API_KEY' \
            --es goal '$goal' \
            --ez auto_start true" >/dev/null
        sleep 1
    fi
    
    # Clear logs
    adb logcat -c
    
    ok "Agent started, goal: $goal"
    echo ""
    echo -e "${CYAN}Executing... Press Ctrl+C to stop${NC}"
    echo ""
    
    # Wait and show key logs
    local count=0
    local rate_limit_seen=0
    while [[ $count -lt 120 ]]; do
        sleep 2
        count=$((count + 2))
        local logs
        logs="$(get_agent_logs)"
        
        # Check for completion signal
        if echo "$logs" | grep -q "Emitted event: SessionCompleted\|Session completed\|Goal achieved\|Max turns reached"; then
            echo ""
            ok "Task completed!"
            break
        fi
        
        # Show progress
        local actions
        actions=$(echo "$logs" | grep -c "Emitted event: ActionExecuted" 2>/dev/null || echo "0")
        printf "\r  Executed %s actions... (%ds)" "$actions" "$count"

        # Surface rate limit warnings once
        if [[ $rate_limit_seen -eq 0 ]] && echo "$logs" | grep -q "Rate limit detected"; then
            echo ""
            warn "LLM rate limit detected; waiting for retry..."
            rate_limit_seen=1
        fi
    done
    
    echo ""
    echo ""
    log "Recent logs:"
    get_agent_logs | tail -20
}

# ===== Command: logs =====
cmd_logs() {
    check_device
    
    local filter="${1:-default}"
    
    case "$filter" in
        orch|orchestration)
            log "Orchestration logs (Ctrl+C to stop)..."
            adb logcat -s MobileV3Orchestration:*
            ;;
        llm|api)
            log "LLM/API logs (Ctrl+C to stop)..."
            adb logcat -s LLMClient:*
            ;;
        session)
            log "Session logs (Ctrl+C to stop)..."
            adb logcat -s AgentSession:* AgentService:*
            ;;
        action)
            log "Action logs (Ctrl+C to stop)..."
            adb logcat -s Agent:* ToolRouter:* AccessibilityPlatform:* AgentSession:* | grep -E "(Executing tool|ActionExecuted|Captured screen|Updated snapshot|Tool call|Policy decision)" || true
            ;;
        all)
            log "All logs (Ctrl+C to stop)..."
            adb logcat
            ;;
        *)
            log "Agent logs (Ctrl+C to stop)..."
            adb logcat -s Agent:* AgentService:* AgentSession:* Turn:* LLMClient:* ToolRouter:* AccessibilityPlatform:*
            ;;
    esac
}

# ===== Command: status =====
cmd_status() {
    check_device
    
    echo -e "${BLUE}=============================================================${NC}"
    echo -e "${BLUE}                     Device Status Check                      ${NC}"
    echo -e "${BLUE}=============================================================${NC}"
    
    # Device info
    local model=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    local android=$(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
    echo "Device: $model (Android $android)"
    
    # App installation status
    if adb shell pm list packages | grep -q "$PACKAGE"; then
        ok "App installed"
    else
        err "App not installed - run ./scripts/setup.sh"
    fi
    
    # Accessibility service status
    local enabled=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')
    if [[ "$enabled" == *"$PACKAGE"* ]]; then
        ok "Accessibility service enabled"
    else
        warn "Accessibility service not enabled - run ./scripts/setup.sh"
    fi
    
    # Overlay permission
    local overlay=$(adb shell appops get "$PACKAGE" SYSTEM_ALERT_WINDOW 2>/dev/null | tr -d '\r')
    if [[ "$overlay" == *"allow"* ]]; then
        ok "Overlay permission granted"
    else
        warn "Overlay permission not granted - run ./scripts/setup.sh"
    fi
    
    # API Key
    if [[ -f "$PROJECT_ROOT/.env" ]]; then
        source "$PROJECT_ROOT/.env"
        if [[ -n "$OPENAI_API_KEY" && "$OPENAI_API_KEY" =~ ^sk- ]]; then
            ok "API Key configured"
        else
            warn "API Key invalid"
        fi
    else
        warn ".env file not found"
    fi
    
    echo -e "${BLUE}=============================================================${NC}"
}

# ===== Help =====
show_help() {
    echo "Android Agent Development Script"
    echo ""
    echo "Usage: ./scripts/dev.sh <command> [args]"
    echo ""
    echo "Commands:"
    echo "  run [goal]         Run agent test (default: 'Open Settings')"
    echo "  logs [filter]      View logs"
    echo "                     filter: default, orch, llm, session, action, all"
    echo "  status             Check device and service status"
    echo ""
    echo "Examples:"
    echo "  ./scripts/dev.sh run"
    echo "  ./scripts/dev.sh run 'Open Chrome'"
    echo "  ./scripts/dev.sh logs orch"
    echo "  ./scripts/dev.sh status"
    echo ""
    echo "Note: Run ./scripts/setup.sh first to build, install and configure"
}

# ===== Main Entry =====
case "${1:-help}" in
    run)
        cmd_run "$2"
        ;;
    logs)
        cmd_logs "$2"
        ;;
    status)
        cmd_status
        ;;
    help|--help|-h)
        show_help
        ;;
    *)
        err "Unknown command: $1"
        echo ""
        show_help
        exit 1
        ;;
esac
