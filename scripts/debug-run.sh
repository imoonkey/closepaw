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
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
PACKAGE="com.moonkey.androidagent"
DEBUG_DIR="$PROJECT_ROOT/debug-output"

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

# Create debug output directory
mkdir -p "$DEBUG_DIR"
rm -f "$DEBUG_DIR"/*.png "$DEBUG_DIR"/*.txt "$DEBUG_DIR"/*.log
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

# Default screenshot input on for OpenAI runs unless explicitly set
if [[ -z "${SCREENSHOT_INPUT+x}" ]]; then
    if [[ "$LLM_BACKEND" == "openai" ]]; then
        SCREENSHOT_INPUT=true
    else
        SCREENSHOT_INPUT=false
    fi
fi

# Check API key for OpenAI backend
if [[ "$LLM_BACKEND" == "openai" && -z "$OPENAI_API_KEY" ]]; then
    warn "No API key found. Set OPENAI_API_KEY in .env or use --local flag."
fi

log "Using LLM backend: $LLM_BACKEND"

# Clear logs
adb logcat -c

# Build intent extras based on backend
INTENT_EXTRAS="--es goal '$GOAL' --es llm_backend '$LLM_BACKEND' --ez auto_start true --ez fresh_session true --ez screenshot_input $SCREENSHOT_INPUT --ez debug_mode $DEBUG_MODE"
if [[ "$LLM_BACKEND" == "openai" ]]; then
    INTENT_EXTRAS="--es api_key '$OPENAI_API_KEY' $INTENT_EXTRAS"
fi

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
TURN=0
MAX_TURNS=20
LAST_TURN_LINE=""

log "Monitoring turns (max $MAX_TURNS)..."
echo ""

while [[ $TURN -lt $MAX_TURNS ]]; do
    sleep 2
    
    # Check for new turn markers in logcat (V2 Agent uses "=== TURN X START ===")
    NEW_TURN_LINE=$(adb logcat -d 2>/dev/null | grep -E "TURN [0-9]+ START|TurnStarted" | tail -1)
    
    if [[ "$NEW_TURN_LINE" != "$LAST_TURN_LINE" && -n "$NEW_TURN_LINE" ]]; then
        TURN=$((TURN + 1))
        LAST_TURN_LINE="$NEW_TURN_LINE"
        
        # Capture screenshot
        SCREENSHOT="$DEBUG_DIR/turn_${TURN}.png"
        adb exec-out screencap -p > "$SCREENSHOT"
        
        # Get agent log for this turn (V2 Agent logs)
        adb logcat -d 2>/dev/null | grep -E "(Agent|Turn|LLM|Tool|Screen)" | tail -30 > "$DEBUG_DIR/turn_${TURN}_log.txt"
        
        echo "  Turn $TURN captured -> $SCREENSHOT"
    fi
    
    # Check if agent finished (V2 patterns)
    if adb logcat -d 2>/dev/null | grep -q "SessionCompleted\|Goal achieved\|GoalAchieved\|DONE:"; then
        echo ""
        ok "Agent completed!"
        break
    fi
    
    # Check for stuck/error
    if adb logcat -d 2>/dev/null | grep -q "Fatal error\|Max turns reached\|MaxTurnsReached"; then
        echo ""
        warn "Agent stopped (error or max turns)"
        break
    fi
done

echo ""
log "Saving full agent log..."
adb logcat -d | grep -E "Agent|Turn|LLMClient|ToolRouter|SessionServices" > "$DEBUG_DIR/agent.log"

log "Saving full system log..."
adb logcat -d | grep -E "AgentService|AccessibilityPlatform|AgentSession" > "$DEBUG_DIR/system.log"

# Pull compressed LLM screenshots saved by debug mode (if any)
DEVICE_LLM_DIR="/sdcard/Android/data/$PACKAGE/files/debug-output"
LOCAL_LLM_DIR="$DEBUG_DIR/llm_screens"
mkdir -p "$LOCAL_LLM_DIR"
if adb shell "ls $DEVICE_LLM_DIR" >/dev/null 2>&1; then
    log "Pulling LLM screenshots..."
    adb pull "$DEVICE_LLM_DIR/." "$LOCAL_LLM_DIR/" >/dev/null 2>&1 || true
fi

# Save LFMLLMClient specific logs for local LLM debugging
if [[ "$LLM_BACKEND" == "local" ]]; then
    log "Saving local LLM logs..."
    adb logcat -d | grep -E "LFMLLMClient|Leap|Model" > "$DEBUG_DIR/local_llm.log"
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
echo -e "${GREEN}=============================================================${NC}"
