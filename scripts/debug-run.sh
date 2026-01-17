#!/bin/bash
#
# debug-run.sh - Run agent with screenshot capture at each turn
#
# Usage: ./scripts/debug-run.sh "goal"
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
PACKAGE="com.moonkey.androidagent"
DEBUG_DIR="$PROJECT_ROOT/debug-output"
GOAL="${1:-Open Settings}"

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

# Load API key
if [[ -f "$PROJECT_ROOT/.env" ]]; then
source "$PROJECT_ROOT/.env"
else
    warn "No .env file found. Make sure OPENAI_API_KEY is set."
fi

# Clear logs
adb logcat -c

# Start agent
log "Starting agent with goal: $GOAL"
adb shell input keyevent KEYCODE_HOME
sleep 0.5
adb shell "am start -n $PACKAGE/.MainActivity \
    --activity-clear-top --activity-single-top \
    --es api_key '$OPENAI_API_KEY' \
    --es goal '$GOAL' \
    --ez auto_start true" >/dev/null

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
