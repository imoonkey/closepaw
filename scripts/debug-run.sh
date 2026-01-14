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
rm -f "$DEBUG_DIR"/*.png "$DEBUG_DIR"/*.txt
log "Debug output: $DEBUG_DIR"

# Load API key
source "$PROJECT_ROOT/.env"

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
    
    # Check for new turn markers in logcat
    NEW_TURN_LINE=$(adb logcat -d 2>/dev/null | grep "TURN.*START" | tail -1)
    
    if [[ "$NEW_TURN_LINE" != "$LAST_TURN_LINE" && -n "$NEW_TURN_LINE" ]]; then
        TURN=$((TURN + 1))
        LAST_TURN_LINE="$NEW_TURN_LINE"
        
        # Capture screenshot
        SCREENSHOT="$DEBUG_DIR/turn_${TURN}.png"
        adb exec-out screencap -p > "$SCREENSHOT"
        
        # Get action and snapshot info for this turn
        adb logcat -d 2>/dev/null | grep -E "(TURN $TURN|Action decided|Reflection outcome|SNAPSHOT)" | tail -20 > "$DEBUG_DIR/turn_${TURN}_log.txt"
        
        echo "  Turn $TURN captured -> $SCREENSHOT"
    fi
    
    # Check if agent finished
    if adb logcat -d 2>/dev/null | grep -q "SessionCompleted\|FINISHED\|GoalAchieved"; then
        echo ""
        ok "Agent completed!"
        break
    fi
    
    # Check for stuck/error
    if adb logcat -d 2>/dev/null | grep -q "Fatal error\|Max turns reached"; then
        echo ""
        warn "Agent stopped (error or max turns)"
        break
    fi
done

echo ""
log "Saving full orchestration log..."
adb logcat -d | grep -E "MobileV3Orchestration|Reflector|Executor|Manager" > "$DEBUG_DIR/orchestration.log"

log "Saving full agent log..."
adb logcat -d | grep -E "MobileV3|AgentService|AccessibilityPlatform|LLMClient" > "$DEBUG_DIR/agent.log"

echo ""
echo -e "${GREEN}=============================================================${NC}"
echo -e "${GREEN}Debug output saved to: $DEBUG_DIR${NC}"
echo ""
echo "Files:"
ls -la "$DEBUG_DIR"
echo ""
echo "To view:"
echo "  Screenshots: open $DEBUG_DIR/turn_*.png"
echo "  Full log:    cat $DEBUG_DIR/orchestration.log"
echo -e "${GREEN}=============================================================${NC}"

