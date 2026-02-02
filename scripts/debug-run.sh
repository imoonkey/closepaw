#!/bin/bash
#
# debug-run.sh - Run agent with screenshot capture at each turn
#
# Usage: 
#   ./scripts/debug-run.sh "goal"              # Run with OpenAI backend
#   ./scripts/debug-run.sh --local "goal"      # Run with local LLM backend
#
# Environment Variables:
#   LLM_BACKEND: "openai" (default) or "local" - selects LLM backend
#   SCREENSHOT_INPUT: "true"/"false" - whether to send screenshots to the LLM (default: false)
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
PACKAGE="com.moonkey.androidagent"
RUN_ID="$(date +"%Y%m%d_%H%M%S")"
DEBUG_DIR="$PROJECT_ROOT/debug-output/run_${RUN_ID}"

# Parse arguments
USE_LOCAL=false
GOAL=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --local|-l)
            USE_LOCAL=true
            shift
            ;;
        *)
            GOAL="$1"
            shift
            ;;
    esac
done
GOAL="${GOAL:-Open Settings}"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

log() { echo -e "${BLUE}> $1${NC}"; }
ok() { echo -e "${GREEN}✓ $1${NC}"; }
warn() { echo -e "${YELLOW}! $1${NC}"; }

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

# Create debug output directory
mkdir -p "$DEBUG_DIR"
log "Debug output: $DEBUG_DIR"

# Load environment variables
if [[ -f "$PROJECT_ROOT/.env" ]]; then
    source "$PROJECT_ROOT/.env"
fi

# Determine LLM backend
LLM_BACKEND="${LLM_BACKEND:-openai}"
if [[ "$USE_LOCAL" == "true" ]]; then
    LLM_BACKEND="local"
fi

# Default debug mode on for debug-run unless explicitly set
if [[ -z "${DEBUG_MODE+x}" ]]; then
    DEBUG_MODE=true
fi

# Default screenshot input OFF unless explicitly set
if [[ -z "${SCREENSHOT_INPUT+x}" ]]; then
    SCREENSHOT_INPUT=false
fi

SCREENSHOT_INPUT=$(normalize_bool "$SCREENSHOT_INPUT")
DEBUG_MODE=$(normalize_bool "$DEBUG_MODE")

# Check API key for OpenAI backend
if [[ "$LLM_BACKEND" == "openai" && -z "$OPENAI_API_KEY" ]]; then
    warn "No API key found. Set OPENAI_API_KEY in .env or use --local flag."
fi

log "Using LLM backend: $LLM_BACKEND"

# Ensure device connected
if ! adb devices | grep -q "device$"; then
    warn "No Android device detected. Waiting for device..."
    adb wait-for-device
fi

# Clear logs and start streaming capture
adb logcat -c
adb logcat -v threadtime > "$DEBUG_DIR/logcat_full.log" 2>&1 &
LOGCAT_PID=$!
trap 'kill "$LOGCAT_PID" >/dev/null 2>&1 || true' EXIT

# Capture basic device/app info
adb shell getprop > "$DEBUG_DIR/device_getprop.txt" 2>/dev/null || true
adb shell dumpsys package "$PACKAGE" > "$DEBUG_DIR/package_dumpsys.txt" 2>/dev/null || true

# Build intent extras based on backend
SAFE_GOAL=$(escape_shell_arg "$GOAL")
SAFE_BACKEND=$(escape_shell_arg "$LLM_BACKEND")
SAFE_API_KEY=$(escape_shell_arg "${OPENAI_API_KEY:-}")
SAFE_RUN_ID=$(escape_shell_arg "$RUN_ID")

INTENT_EXTRAS="--es goal '$SAFE_GOAL' --es llm_backend '$SAFE_BACKEND' --ez auto_start true --ez fresh_session true --ez screenshot_input $SCREENSHOT_INPUT --ez debug_mode $DEBUG_MODE --ez trace_enabled true --es trace_run_id '$SAFE_RUN_ID'"
if [[ "$LLM_BACKEND" == "openai" ]]; then
    INTENT_EXTRAS="--es api_key '$SAFE_API_KEY' $INTENT_EXTRAS"
fi

# Clear any previous trace folder for this run id (best-effort)
DEVICE_TRACE_DIR="/sdcard/Android/data/$PACKAGE/files/inspection-trace/$RUN_ID"
adb shell "rm -rf '$DEVICE_TRACE_DIR'" >/dev/null 2>&1 || true

# Start agent
log "Starting agent with goal: $GOAL"
adb shell input keyevent KEYCODE_HOME
sleep 0.5
# fresh_session=true ensures we start with a new session, not continuing an existing one
adb shell "am start -n $PACKAGE/.app.MainActivity \
    --activity-clear-top --activity-single-top \
    $INTENT_EXTRAS" >/dev/null

ok "Agent started"
echo ""

# Monitor turns and capture screenshots
# Track which turns we've already captured using file existence check (bash 3.2 compatible)
MAX_TURNS=20
LAST_CAPTURED=0

log "Monitoring turns (max $MAX_TURNS)..."
echo ""

while [[ $LAST_CAPTURED -lt $MAX_TURNS ]]; do
    sleep 1  # Poll more frequently (1s instead of 2s)
    
    # Extract ALL turn numbers from log (not just the last one)
    # This catches multiple turns that may have started in one polling interval
    TURN_NUMBERS=$(tail -n 500 "$DEBUG_DIR/logcat_full.log" | grep -oE "=== TURN ([0-9]+) START ===" | sed 's/[^0-9]//g' | sort -n | uniq 2>/dev/null || true)
    
    for TURN_NUM in $TURN_NUMBERS; do
        # Skip empty
        [[ -z "$TURN_NUM" ]] && continue
        
        SCREENSHOT="$DEBUG_DIR/turn_${TURN_NUM}.png"
        
        # Skip if we already captured this turn (check file existence)
        if [[ -f "$SCREENSHOT" ]]; then
            continue
        fi
        
        # Capture screenshot for this turn
        adb exec-out screencap -p > "$SCREENSHOT" 2>/dev/null || true
        
        # Save recent log context around the turn boundary
        tail -n 400 "$DEBUG_DIR/logcat_full.log" > "$DEBUG_DIR/turn_${TURN_NUM}_log.txt"
        
        echo "  Turn $TURN_NUM captured -> $SCREENSHOT"
        
        if [[ $TURN_NUM -gt $LAST_CAPTURED ]]; then
            LAST_CAPTURED=$TURN_NUM
        fi
    done
    
    # Check if agent finished (V2 patterns)
    if tail -n 500 "$DEBUG_DIR/logcat_full.log" | grep -q "SessionCompleted\\|Goal achieved\\|GoalAchieved\\|DONE:"; then
        echo ""
        ok "Agent completed!"
        break
    fi
    
    # Check for stuck/error
    if tail -n 500 "$DEBUG_DIR/logcat_full.log" | grep -q "Fatal error\\|Max turns reached\\|MaxTurnsReached"; then
        echo ""
        warn "Agent stopped (error or max turns)"
        break
    fi
done

echo ""
log "Saving full agent log..."
grep -E "Agent|Turn|LLMClient|ToolRouter|SessionServices" "$DEBUG_DIR/logcat_full.log" > "$DEBUG_DIR/agent.log" || true

log "Saving full system log..."
grep -E "AgentService|AccessibilityPlatform|AgentSession" "$DEBUG_DIR/logcat_full.log" > "$DEBUG_DIR/system.log" || true

# Pull compressed LLM screenshots saved by debug mode (if any)
DEVICE_LLM_DIR="/sdcard/Android/data/$PACKAGE/files/debug-output"
LOCAL_LLM_DIR="$DEBUG_DIR/llm_screens"
mkdir -p "$LOCAL_LLM_DIR"
if adb shell "ls $DEVICE_LLM_DIR" >/dev/null 2>&1; then
    log "Pulling LLM screenshots..."
    adb pull "$DEVICE_LLM_DIR/." "$LOCAL_LLM_DIR/" >/dev/null 2>&1 || true
fi

# Pull trace (JSONL + artifacts)
LOCAL_TRACE_DIR="$DEBUG_DIR/trace"
mkdir -p "$LOCAL_TRACE_DIR"
if adb shell "ls '$DEVICE_TRACE_DIR' " >/dev/null 2>&1; then
    log "Pulling trace artifacts..."
    adb pull "$DEVICE_TRACE_DIR/." "$LOCAL_TRACE_DIR/" >/dev/null 2>&1 || true
fi

# Save LFMLLMClient specific logs for local LLM debugging
if [[ "$LLM_BACKEND" == "local" ]]; then
    log "Saving local LLM logs..."
    grep -E "LFMLLMClient|Leap|Model" "$DEBUG_DIR/logcat_full.log" > "$DEBUG_DIR/local_llm.log" || true
fi

echo ""
echo -e "${GREEN}=============================================================${NC}"
echo -e "${GREEN}Debug output saved to: $DEBUG_DIR${NC}"
echo ""
echo "Files:"
ls -la "$DEBUG_DIR"
echo ""
echo "To view:"
echo "  Screenshots: open $DEBUG_DIR/turn_*.png"
echo "  Full log:    cat $DEBUG_DIR/agent.log"
echo "  Trace:       cd inspection_tool && ./serve.sh 8080  (open /trace_viewer.html, pick $DEBUG_DIR/trace)"
echo -e "${GREEN}=============================================================${NC}"
