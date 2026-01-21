#!/bin/bash
#
# debug-run.sh - Run agent with screenshot capture at each turn
#
# Usage: ./scripts/debug-run.sh "goal"
#
# Output includes:
#   - Screenshots per turn (turn_N.png)
#   - Log excerpts per turn (turn_N_log.txt)
#   - Full agent log (agent.log)
#   - Performance profiling data (perf.log, perf.jsonl)
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
PACKAGE="com.moonkey.androidagent"
# Use timestamped directory for each run
RUN_TIMESTAMP=$(date +%Y%m%d_%H%M%S)
DEBUG_DIR="$PROJECT_ROOT/debug-output/$RUN_TIMESTAMP"
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
log "Debug output: $DEBUG_DIR"
log "Goal: $GOAL"

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
adb shell "am start -n $PACKAGE/.app.MainActivity \
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

log "Extracting performance profiling data..."
# Extract PERF logs
adb logcat -d | grep "PERF|" > "$DEBUG_DIR/perf.log" 2>/dev/null || true

# Parse PERF logs to JSONL format for analysis
if [[ -s "$DEBUG_DIR/perf.log" ]]; then
    # Extract fields from PERF|session|turn|span|duration|metadata format
    awk -F'PERF\\|' '{
        if (NF > 1) {
            split($2, parts, "|");
            session = parts[1];
            turn = parts[2];
            span = parts[3];
            duration = parts[4];
            meta = parts[5];
            # Remove trailing newline/whitespace from meta
            gsub(/[[:space:]]+$/, "", meta);
            printf "{\"ts\":%d,\"session\":\"%s\",\"turn\":%s,\"span\":\"%s\",\"ms\":%s,\"meta\":%s}\n", 
                systime() * 1000, session, turn, span, duration, (meta == "" ? "{}" : meta);
        }
    }' "$DEBUG_DIR/perf.log" > "$DEBUG_DIR/perf.jsonl"
    ok "Performance data: $DEBUG_DIR/perf.jsonl"
else
    warn "No PERF logs captured"
fi

echo ""
echo -e "${GREEN}=============================================================${NC}"
echo -e "${GREEN}Debug output saved to: $DEBUG_DIR${NC}"
echo ""
echo "Files:"
ls -la "$DEBUG_DIR"
echo ""
echo "To view:"
echo "  Screenshots:  open $DEBUG_DIR/turn_*.png"
echo "  Full log:     cat $DEBUG_DIR/agent.log"
echo "  Perf data:    cat $DEBUG_DIR/perf.jsonl"
echo ""
echo "To analyze perf data:"
echo "  python3 -c \"import pandas as pd; df=pd.read_json('$DEBUG_DIR/perf.jsonl', lines=True); print(df.groupby('span')['ms'].describe())\""
echo -e "${GREEN}=============================================================${NC}"
