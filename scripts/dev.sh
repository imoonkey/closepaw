#!/bin/bash
#
# dev.sh - Development Testing Script
#
# Purpose: Run agent tests and view logs (assumes app is already installed)
#
# Commands:
#   ./scripts/dev.sh run [goal]              # Run agent with goal (default: "Open Settings")
#   ./scripts/dev.sh run --local [goal]      # Run with local LLM backend
#   ./scripts/dev.sh logs [filter]           # View logs
#   ./scripts/dev.sh status                  # Check device and service status
#
# Environment Variables:
#   LLM_BACKEND: "openai" (default) or "local" - selects LLM backend
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

escape_shell_arg() {
    printf "%s" "$1" | sed "s/'/'\\\\''/g"
}

normalize_bool() {
    case "$1" in
        true|TRUE|True|1|yes|YES|Yes|y|Y) echo "true" ;;
        false|FALSE|False|0|no|NO|No|n|N|"") echo "false" ;;
        *) echo "false" ;;
    esac
}

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

# Load environment variables
load_env() {
    if [[ -f "$PROJECT_ROOT/.env" ]]; then
        source "$PROJECT_ROOT/.env"
    fi
    # Set default backend if not specified
    LLM_BACKEND="${LLM_BACKEND:-openai}"
}

# Check API key (only required for OpenAI backend)
check_api_key() {
    if [[ "$LLM_BACKEND" == "openai" && -z "$OPENAI_API_KEY" ]]; then
        err "API Key not found for OpenAI backend. Set OPENAI_API_KEY in .env or use --local flag"
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

# Stop the agent on the device
stop_agent() {
    adb shell "am broadcast -a $PACKAGE.STOP_AGENT -p $PACKAGE" >/dev/null 2>&1 || true
}

# ===== Command: run =====
cmd_run() {
    local goal=""
    local use_local=false
    
    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --local|-l)
                use_local=true
                shift
                ;;
            *)
                goal="$1"
                shift
                ;;
        esac
    done
    
    # Default goal
    goal="${goal:-Open Settings}"
    
    check_device
    check_app
    load_env
    
    # Override backend if --local flag is used
    if [[ "$use_local" == "true" ]]; then
        LLM_BACKEND="local"
    fi

    # Default screenshot input on for OpenAI runs unless explicitly set
    if [[ -z "${SCREENSHOT_INPUT+x}" ]]; then
        if [[ "$LLM_BACKEND" == "openai" ]]; then
            SCREENSHOT_INPUT=true
        else
            SCREENSHOT_INPUT=false
        fi
    fi

    SCREENSHOT_INPUT=$(normalize_bool "$SCREENSHOT_INPUT")
    
    check_api_key
    
    log "Running agent with goal: $goal (backend: $LLM_BACKEND)"
    
    # Set up cleanup trap to stop agent when interrupted (Ctrl+C only)
    # Don't use EXIT - it fires on normal completion too
    trap 'echo ""; warn "Interrupted, stopping agent..."; stop_agent; exit 0' INT TERM
    
    # Go to home screen first
    adb shell input keyevent KEYCODE_HOME
    sleep 0.5
    
    # Build intent extras based on backend
    local safe_goal
    safe_goal=$(escape_shell_arg "$goal")
    local safe_backend
    safe_backend=$(escape_shell_arg "$LLM_BACKEND")
    local safe_api_key
    safe_api_key=$(escape_shell_arg "${OPENAI_API_KEY:-}")

    local intent_extras="--es goal '$safe_goal' --es llm_backend '$safe_backend' --ez auto_start true --ez fresh_session true --ez screenshot_input $SCREENSHOT_INPUT"
    if [[ "$LLM_BACKEND" == "openai" ]]; then
        intent_extras="--es api_key '$safe_api_key' $intent_extras"
    fi
    
    # Launch app with intent extras
    # Using FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP to trigger onNewIntent
    # Don't use force-stop - it clears accessibility permission!
    # fresh_session=true ensures we start with a new session, not continuing an existing one
    adb shell "am start -n $PACKAGE/.app.MainActivity \
        --activity-clear-top --activity-single-top \
        $intent_extras" >/dev/null
    sleep 1.5
    
    # Check if on correct screen
    local fg=$(get_foreground)
    if [[ "$fg" != "$PACKAGE" ]]; then
        warn "App not in foreground (current: $fg), retrying..."
        adb shell input keyevent KEYCODE_BACK
        sleep 0.3
        # Retry with intent extras
        adb shell "am start -n $PACKAGE/.app.MainActivity \
            --activity-clear-top --activity-single-top \
            $intent_extras" >/dev/null
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
            # Clear the trap since we completed normally
            trap - INT TERM
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
    
    # Clear the trap before normal exit
    trap - INT TERM
    
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
            adb logcat -s OpenAILLMClient:* LFMLLMClient:* LLMClient:*
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
    echo "  run [--local] [goal]   Run agent test (default: 'Open Settings')"
    echo "                         --local: Use local LLM backend instead of OpenAI"
    echo "  logs [filter]          View logs"
    echo "                         filter: default, orch, llm, session, action, all"
    echo "  status                 Check device and service status"
    echo ""
    echo "Environment Variables:"
    echo "  LLM_BACKEND            'openai' (default) or 'local'"
    echo ""
    echo "Examples:"
    echo "  ./scripts/dev.sh run"
    echo "  ./scripts/dev.sh run 'Open Chrome'"
    echo "  ./scripts/dev.sh run --local 'Open Settings'    # Use local LLM"
    echo "  LLM_BACKEND=local ./scripts/dev.sh run          # Same as --local"
    echo "  ./scripts/dev.sh logs orch"
    echo "  ./scripts/dev.sh status"
    echo ""
    echo "Note: Run ./scripts/setup.sh first to build, install and configure"
}

# ===== Main Entry =====
case "${1:-help}" in
    run)
        shift  # Remove "run" from arguments
        cmd_run "$@"  # Pass remaining arguments
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
