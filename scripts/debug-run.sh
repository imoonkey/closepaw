#!/bin/bash
#
# debug-run.sh - Run agent with screenshot capture at each turn
#
# Usage: 
#   ./scripts/debug-run.sh "goal"              # Run with OpenAI backend
#   ./scripts/debug-run.sh --local "goal"      # Run with local LLM backend
#   ./scripts/debug-run.sh --basic "goal"      # Run in Basic (standalone) execution mode
#   ./scripts/debug-run.sh --pro "goal"        # Run in Pro (planner+executor) execution mode
#   ./scripts/debug-run.sh --accessibility-only "goal"  # A11y tree only
#   ./scripts/debug-run.sh --screenshot-only "goal"     # Screenshot only
#   ./scripts/debug-run.sh --hybrid "goal"              # A11y + screenshot
#   ./scripts/debug-run.sh --main-model gpt-5.2 --executor-model glm-4.7 "goal"
#
# Environment Variables:
#   LLM_BACKEND: "openai" (default) or "local" - selects LLM backend
#   AGENT_MODE: "pro" (default) or "basic" - selects execution mode
#   PERCEPTION_MODE: "accessibility_only" (default), "screenshot_only", or "hybrid"
#   MAIN_MODEL: Override main model name (key from llm_models.json)
#   EXECUTOR_MODEL: Override executor model name (key from llm_models.json)
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
PACKAGE="com.moonkey.androidagent"
RUN_ID="$(date +"%Y%m%d_%H%M%S")"
DEBUG_DIR="$PROJECT_ROOT/debug-output/run_${RUN_ID}"

# Parse arguments
USE_LOCAL=false
FORCED_AGENT_MODE=""
FORCED_PERCEPTION_MODE=""
FORCED_MAIN_MODEL=""
FORCED_EXECUTOR_MODEL=""
GOAL=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --local|-l)
            USE_LOCAL=true
            shift
            ;;
        --basic)
            FORCED_AGENT_MODE="basic"
            shift
            ;;
        --pro)
            FORCED_AGENT_MODE="pro"
            shift
            ;;
        --accessibility-only|--a11y-only)
            FORCED_PERCEPTION_MODE="accessibility_only"
            shift
            ;;
        --screenshot-only)
            FORCED_PERCEPTION_MODE="screenshot_only"
            shift
            ;;
        --hybrid)
            FORCED_PERCEPTION_MODE="hybrid"
            shift
            ;;
        --perception|-p)
            if [[ $# -lt 2 ]]; then
                echo "Missing value for --perception. Use accessibility_only|screenshot_only|hybrid"
                exit 1
            fi
            FORCED_PERCEPTION_MODE="$2"
            shift 2
            ;;
        --main-model)
            if [[ $# -lt 2 ]]; then
                echo "Missing value for --main-model"
                exit 1
            fi
            FORCED_MAIN_MODEL="$2"
            shift 2
            ;;
        --executor-model)
            if [[ $# -lt 2 ]]; then
                echo "Missing value for --executor-model"
                exit 1
            fi
            FORCED_EXECUTOR_MODEL="$2"
            shift 2
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

# Stop the agent on the device
stop_agent() {
    adb shell "am broadcast -a $PACKAGE.STOP_AGENT -p $PACKAGE" >/dev/null 2>&1 || true
}

normalize_bool() {
    case "$1" in
        true|TRUE|True|1|yes|YES|Yes|y|Y) echo "true" ;;
        false|FALSE|False|0|no|NO|No|n|N|"") echo "false" ;;
        *) echo "false" ;;
    esac
}

normalize_agent_mode() {
    case "$1" in
        basic|BASIC|Basic) echo "basic" ;;
        pro|PRO|Pro|"") echo "pro" ;;
        *) echo "pro" ;;
    esac
}

normalize_perception_mode() {
    local raw="${1:-}"
    raw=$(echo "$raw" | tr '[:upper:]' '[:lower:]')
    case "$raw" in
        accessibility_only|accessibility-only|accessibility|a11y_only|a11y-only|a11y|"")
            echo "accessibility_only"
            ;;
        screenshot_only|screenshot-only|screenshot)
            echo "screenshot_only"
            ;;
        hybrid)
            echo "hybrid"
            ;;
        *)
            echo "accessibility_only"
            ;;
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

# Determine execution mode
AGENT_MODE="${AGENT_MODE:-pro}"
if [[ -n "$FORCED_AGENT_MODE" ]]; then
    AGENT_MODE="$FORCED_AGENT_MODE"
fi
AGENT_MODE=$(normalize_agent_mode "$AGENT_MODE")

# Default debug mode on for debug-run unless explicitly set
if [[ -z "${DEBUG_MODE+x}" ]]; then
    DEBUG_MODE=true
fi

# Determine perception mode
if [[ -n "$FORCED_PERCEPTION_MODE" ]]; then
    PERCEPTION_MODE="$FORCED_PERCEPTION_MODE"
fi
PERCEPTION_MODE="${PERCEPTION_MODE:-accessibility_only}"
PERCEPTION_MODE=$(normalize_perception_mode "$PERCEPTION_MODE")
DEBUG_MODE=$(normalize_bool "$DEBUG_MODE")

# Check API key for OpenAI backend
if [[ "$LLM_BACKEND" == "openai" && -z "$OPENAI_API_KEY" ]]; then
    warn "No API key found. Set OPENAI_API_KEY in .env or use --local flag."
fi

log "Using LLM backend: $LLM_BACKEND"
log "Using execution mode: $AGENT_MODE"
log "Using perception mode: $PERCEPTION_MODE"

# Ensure device connected
if ! adb devices | grep -q "device$"; then
    warn "No Android device detected. Waiting for device..."
    adb wait-for-device
fi

# Clear logs and start streaming capture
adb logcat -c
adb logcat -v threadtime > "$DEBUG_DIR/logcat_full.log" 2>&1 &
LOGCAT_PID=$!
cleanup() {
    kill "$LOGCAT_PID" >/dev/null 2>&1 || true
}
trap 'echo ""; warn "Interrupted, stopping agent..."; stop_agent; cleanup; exit 0' INT TERM
trap 'cleanup' EXIT

# Capture basic device/app info
adb shell getprop > "$DEBUG_DIR/device_getprop.txt" 2>/dev/null || true
adb shell dumpsys package "$PACKAGE" > "$DEBUG_DIR/package_dumpsys.txt" 2>/dev/null || true

# Build intent extras based on backend
SAFE_GOAL=$(escape_shell_arg "$GOAL")
SAFE_BACKEND=$(escape_shell_arg "$LLM_BACKEND")
SAFE_API_KEY=$(escape_shell_arg "${OPENAI_API_KEY:-}")
SAFE_RUN_ID=$(escape_shell_arg "$RUN_ID")
SAFE_AGENT_MODE=$(escape_shell_arg "$AGENT_MODE")
SAFE_PERCEPTION_MODE=$(escape_shell_arg "$PERCEPTION_MODE")

INTENT_EXTRAS="--es goal '$SAFE_GOAL' --es llm_backend '$SAFE_BACKEND' --es agent_mode '$SAFE_AGENT_MODE' --es perception_mode '$SAFE_PERCEPTION_MODE' --ez auto_start true --ez fresh_session true --ez debug_mode $DEBUG_MODE --ez trace_enabled true --es trace_run_id '$SAFE_RUN_ID'"

if [[ -n "$FORCED_MAIN_MODEL" ]]; then
    SAFE_MAIN_MODEL=$(escape_shell_arg "$FORCED_MAIN_MODEL")
    INTENT_EXTRAS="$INTENT_EXTRAS --es main_model '$SAFE_MAIN_MODEL'"
elif [[ -n "$MAIN_MODEL" ]]; then
    SAFE_MAIN_MODEL=$(escape_shell_arg "$MAIN_MODEL")
    INTENT_EXTRAS="$INTENT_EXTRAS --es main_model '$SAFE_MAIN_MODEL'"
fi

if [[ -n "$FORCED_EXECUTOR_MODEL" ]]; then
    SAFE_EXECUTOR_MODEL=$(escape_shell_arg "$FORCED_EXECUTOR_MODEL")
    INTENT_EXTRAS="$INTENT_EXTRAS --es executor_model '$SAFE_EXECUTOR_MODEL'"
elif [[ -n "$EXECUTOR_MODEL" ]]; then
    SAFE_EXECUTOR_MODEL=$(escape_shell_arg "$EXECUTOR_MODEL")
    INTENT_EXTRAS="$INTENT_EXTRAS --es executor_model '$SAFE_EXECUTOR_MODEL'"
fi

if [[ -n "${OPENAI_API_KEY:-}" ]]; then
    INTENT_EXTRAS="--es api_key '$SAFE_API_KEY' $INTENT_EXTRAS"
fi

if [[ -n "${OPENROUTER_API_KEY:-}" ]]; then
    SAFE_OR_KEY=$(escape_shell_arg "$OPENROUTER_API_KEY")
    INTENT_EXTRAS="$INTENT_EXTRAS --es openrouter_api_key '$SAFE_OR_KEY'"
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
# DEBUG_MAX_TURNS controls how many turn-start events to capture (default 80 for multi-agent runs)
MAX_TURNS="${DEBUG_MAX_TURNS:-80}"
CAPTURE_COUNT=0
LAST_LOG_LINE=0

log "Monitoring turns (max $MAX_TURNS captured turn-start events)..."
echo ""

while [[ $CAPTURE_COUNT -lt $MAX_TURNS ]]; do
    sleep 1  # Poll more frequently (1s instead of 2s)

    TOTAL_LINES=$(wc -l < "$DEBUG_DIR/logcat_full.log" | tr -d ' ')
    TOTAL_LINES=${TOTAL_LINES:-0}

    if [[ $TOTAL_LINES -gt $LAST_LOG_LINE ]]; then
        START_LINE=$((LAST_LOG_LINE + 1))

        while IFS= read -r TURN_ENTRY; do
            [[ -z "$TURN_ENTRY" ]] && continue

            REL_LINE="${TURN_ENTRY%%:*}"
            LOG_LINE="${TURN_ENTRY#*:}"
            TURN_NUM=$(printf "%s" "$LOG_LINE" | sed -n 's/.*=== TURN \([0-9][0-9]*\) START ===.*/\1/p')
            [[ -z "$TURN_NUM" ]] && continue

            ABS_LINE=$((START_LINE + REL_LINE - 1))
            CAPTURE_COUNT=$((CAPTURE_COUNT + 1))
            TURN_PREFIX=$(printf "turn_%03d_n%s" "$CAPTURE_COUNT" "$TURN_NUM")

            SCREENSHOT="$DEBUG_DIR/${TURN_PREFIX}.png"
            adb exec-out screencap -p > "$SCREENSHOT" 2>/dev/null || true

            CONTEXT_START=$((ABS_LINE - 200))
            if [[ $CONTEXT_START -lt 1 ]]; then
                CONTEXT_START=1
            fi
            CONTEXT_END=$((ABS_LINE + 200))
            sed -n "${CONTEXT_START},${CONTEXT_END}p" "$DEBUG_DIR/logcat_full.log" > "$DEBUG_DIR/${TURN_PREFIX}_log.txt"

            echo "  Turn $TURN_NUM captured (#$CAPTURE_COUNT) -> $SCREENSHOT"

            if [[ $CAPTURE_COUNT -ge $MAX_TURNS ]]; then
                break
            fi
        done < <(sed -n "${START_LINE},${TOTAL_LINES}p" "$DEBUG_DIR/logcat_full.log" | grep -nE "=== TURN ([0-9]+) START ===" || true)

        LAST_LOG_LINE=$TOTAL_LINES
    fi

    # Check if main session finished.
    # Avoid generic "GoalAchieved" patterns because sub-agents can emit those too.
    if tail -n 800 "$DEBUG_DIR/logcat_full.log" | grep -q "AgentSession: Emitted event: SessionCompleted\\|AgentService: Session completed\\|AgentService: Task completed"; then
        echo ""
        ok "Agent completed!"
        break
    fi

    # Check for terminal errors at session level.
    if tail -n 800 "$DEBUG_DIR/logcat_full.log" | grep -q "AgentSession: Emitted event: SessionError\\|AgentService: Session error\\|Fatal error"; then
        echo ""
        warn "Agent stopped (session error)"
        break
    fi
done

if [[ $CAPTURE_COUNT -ge $MAX_TURNS ]]; then
    warn "Reached max captured turn-start events ($MAX_TURNS)"
fi

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
    if command -v python3 >/dev/null 2>&1; then
        log "Compiling replay index..."
        python3 "$PROJECT_ROOT/inspection_tool/replay_compiler.py" "$LOCAL_TRACE_DIR" \
            > "$DEBUG_DIR/replay_compile.log" 2>&1 || true
    fi
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
echo "  Replay v2:   cd inspection_tool && ./serve.sh 8080  (open /replay_v2/index.html, pick $DEBUG_DIR/trace)"
echo -e "${GREEN}=============================================================${NC}"
