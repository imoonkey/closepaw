#!/usr/bin/env bash
#
# action-test.sh — Direct action execution debug harness
#
# Usage:
#   ./scripts/action-test.sh click --x 540 --y 1200
#   ./scripts/action-test.sh tap --x 540 --y 1200 --adb
#   ./scripts/action-test.sh scroll --direction down
#   ./scripts/action-test.sh swipe --start-x 540 --start-y 1400 --end-x 540 --end-y 600
#   ./scripts/action-test.sh long_press --x 540 --y 800 --duration 1500
#   ./scripts/action-test.sh click --x 540 --y 1200 --compare
#
set -euo pipefail

PACKAGE="com.moonkey.androidagent"
ACTION_INTENT="com.moonkey.androidagent.ACTION_DEBUG_EXEC"
DEVICE_OUTPUT_DIR="/sdcard/Android/data/$PACKAGE/files/action-debug/latest"

# Colors
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

log()  { echo -e "${BLUE}> $1${NC}"; }
ok()   { echo -e "${GREEN}✓ $1${NC}"; }
warn() { echo -e "${YELLOW}! $1${NC}"; }
err()  { echo -e "${RED}✗ $1${NC}"; }
bold() { echo -e "${BOLD}$1${NC}"; }

# ── Device selection (reused from debug-run.sh) ──

list_connected_devices() {
    adb devices | awk 'NR > 1 && $2 == "device" {print $1}'
}

select_device() {
    local preferred_serial="${ANDROID_SERIAL:-}"
    local devices physical_devices
    devices="$(list_connected_devices)"
    [[ -z "$devices" ]] && return 1

    if [[ -n "$preferred_serial" ]]; then
        if printf "%s\n" "$devices" | grep -Fxq "$preferred_serial"; then
            printf "%s\n" "$preferred_serial"
            return 0
        fi
        warn "ANDROID_SERIAL=$preferred_serial not found; auto-selecting."
    fi

    physical_devices="$(printf "%s\n" "$devices" | grep -v '^emulator-' || true)"
    if [[ -n "$physical_devices" ]]; then
        printf "%s\n" "$physical_devices" | head -n 1
    else
        printf "%s\n" "$devices" | head -n 1
    fi
}

# ── ADB helpers ──

adb_cmd() { adb -s "$DEVICE" "$@"; }

adb_screencap() {
    adb_cmd exec-out screencap -p > "$1"
}

adb_broadcast() {
    adb_cmd shell "am broadcast -a $ACTION_INTENT -p $PACKAGE $@"
}

# ── Parse args ──

ACTION=""
MODE="a11y"       # a11y | adb | compare | shizuku
TAG=""
OPEN_IMAGES=false
SETTLE_MS=350
CAPTURE_TREE=true
DISPLAY_ID=0

# Action-specific
X=""; Y=""
USE_NODE=""
DIRECTION=""
START_X=""; START_Y=""; END_X=""; END_Y=""
DURATION_MS=""

usage() {
    cat <<'EOF'
Usage: action-test.sh <action> [options]

Actions:
  click       Click at coordinates (node action or gesture)
  tap         Gesture tap at coordinates
  long_press  Long press at coordinates
  scroll      Scroll in direction
  swipe       Swipe between coordinates

Options:
  --x N, --y N             Coordinates for click/tap/long_press/scroll
  --start-x N, --start-y N Start coords for swipe
  --end-x N, --end-y N     End coords for swipe
  --direction DIR           Scroll direction (up/down/left/right)
  --duration N              Duration in ms (long_press/swipe)
  --use-node true|false     click/long_press: use node action (true) or gesture (false)
  --adb                     Use adb input instead of a11y (L0 baseline)
  --shizuku                 Use Shizuku IInputManager.injectInputEvent (requires Shizuku)
  --display-id N            Target display ID for Shizuku injection (default: 0)
  --compare                 Run both adb and a11y, compare results
  --tag NAME                Name output subdirectory
  --open                    Auto-open screenshots after test
  --no-tree                 Skip a11y tree capture
  --settle N                Post-action settle delay in ms (default: 350)
EOF
    exit 1
}

[[ $# -eq 0 ]] && usage

ACTION="$1"; shift

while [[ $# -gt 0 ]]; do
    case "$1" in
        --x)          X="$2"; shift 2 ;;
        --y)          Y="$2"; shift 2 ;;
        --start-x)    START_X="$2"; shift 2 ;;
        --start-y)    START_Y="$2"; shift 2 ;;
        --end-x)      END_X="$2"; shift 2 ;;
        --end-y)      END_Y="$2"; shift 2 ;;
        --direction)  DIRECTION="$2"; shift 2 ;;
        --duration)   DURATION_MS="$2"; shift 2 ;;
        --use-node)   USE_NODE="$2"; shift 2 ;;
        --adb)        MODE="adb"; shift ;;
        --shizuku)    MODE="shizuku"; shift ;;
        --display-id) DISPLAY_ID="$2"; shift 2 ;;
        --compare)    MODE="compare"; shift ;;
        --tag)        TAG="$2"; shift 2 ;;
        --open)       OPEN_IMAGES=true; shift ;;
        --no-tree)    CAPTURE_TREE=false; shift ;;
        --settle)     SETTLE_MS="$2"; shift 2 ;;
        -h|--help)    usage ;;
        *)            err "Unknown option: $1"; usage ;;
    esac
done

# ── Validate ──

DEVICE="$(select_device)" || { err "No device connected"; exit 1; }
log "Device: $DEVICE"

# Determine output dir
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -n "$TAG" ]]; then
    OUT_DIR="$SCRIPT_DIR/debug-output/action-test/$TAG"
else
    OUT_DIR="$SCRIPT_DIR/debug-output/action-test/latest"
fi
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

# ── ADB mode (L0 baseline) ──

run_adb_action() {
    local prefix="${1:-adb}"
    local before_img="$OUT_DIR/before_${prefix}.png"
    local after_img="$OUT_DIR/after_${prefix}.png"

    adb_screencap "$before_img"

    case "$ACTION" in
        click|tap)
            [[ -z "$X" || -z "$Y" ]] && { err "click/tap requires --x and --y"; exit 1; }
            log "adb input tap $X $Y"
            adb_cmd shell "input tap $X $Y"
            ;;
        long_press)
            [[ -z "$X" || -z "$Y" ]] && { err "long_press requires --x and --y"; exit 1; }
            local dur="${DURATION_MS:-1000}"
            log "adb input swipe $X $Y $X $Y $dur (long press)"
            adb_cmd shell "input swipe $X $Y $X $Y $dur"
            ;;
        scroll)
            [[ -z "$DIRECTION" ]] && { err "scroll requires --direction"; exit 1; }
            # Get screen size
            local size
            size="$(adb_cmd shell wm size | grep -oE '[0-9]+x[0-9]+')"
            local sw="${size%x*}"
            local sh="${size#*x}"
            local cx=$((sw / 2))
            local cy=$((sh / 2))
            local d=$((sh / 4))
            case "$DIRECTION" in
                down)  adb_cmd shell "input swipe $cx $((cy + d)) $cx $((cy - d)) 400" ;;
                up)    adb_cmd shell "input swipe $cx $((cy - d)) $cx $((cy + d)) 400" ;;
                left)  adb_cmd shell "input swipe $((cx + d)) $cy $((cx - d)) $cy 400" ;;
                right) adb_cmd shell "input swipe $((cx - d)) $cy $((cx + d)) $cy 400" ;;
                *)     err "Invalid direction: $DIRECTION"; exit 1 ;;
            esac
            log "adb scroll $DIRECTION"
            ;;
        swipe)
            [[ -z "$START_X" || -z "$START_Y" || -z "$END_X" || -z "$END_Y" ]] && {
                err "swipe requires --start-x/y and --end-x/y"; exit 1
            }
            local dur="${DURATION_MS:-400}"
            log "adb input swipe $START_X $START_Y $END_X $END_Y $dur"
            adb_cmd shell "input swipe $START_X $START_Y $END_X $END_Y $dur"
            ;;
        *)
            err "Unknown action for adb mode: $ACTION"; exit 1 ;;
    esac

    # Settle
    sleep "$(printf '%s\n' "$SETTLE_MS" | awk '{printf "%.3f", $1/1000}')"
    adb_screencap "$after_img"

    ok "ADB action completed"
    echo "  before: $before_img"
    echo "  after:  $after_img"

    if $OPEN_IMAGES; then
        open "$before_img" "$after_img" 2>/dev/null || true
    fi
}

# ── A11y mode (L1 platform) ──

run_a11y_action() {
    local prefix="${1:-a11y}"
    local before_img="$OUT_DIR/before_${prefix}.png"
    local after_img="$OUT_DIR/after_${prefix}.png"

    # Clear stale sentinel before anything else (prevents race with previous run)
    adb_cmd shell "rm -f '$DEVICE_OUTPUT_DIR/.done'" 2>/dev/null || true

    # Pre-screenshot (host-side)
    adb_screencap "$before_img"

    # Build broadcast extras as array (no eval, no injection)
    local -a extras=(--es action "$ACTION" --ei settle_ms "$SETTLE_MS")
    if ! $CAPTURE_TREE; then
        extras+=(--ez capture_tree false)
    fi
    if [[ "$MODE" == "shizuku" ]]; then
        extras+=(--ez use_shizuku true --ei display_id "$DISPLAY_ID")
    fi

    case "$ACTION" in
        click)
            [[ -z "$X" || -z "$Y" ]] && { err "click requires --x and --y"; exit 1; }
            extras+=(--ei x "$X" --ei y "$Y")
            if [[ -n "$USE_NODE" ]]; then
                extras+=(--ez use_node "$USE_NODE")
            fi
            ;;
        tap)
            [[ -z "$X" || -z "$Y" ]] && { err "tap requires --x and --y"; exit 1; }
            extras+=(--ei x "$X" --ei y "$Y")
            ;;
        long_press)
            [[ -z "$X" || -z "$Y" ]] && { err "long_press requires --x and --y"; exit 1; }
            extras+=(--ei x "$X" --ei y "$Y")
            if [[ -n "$DURATION_MS" ]]; then
                extras+=(--ei duration_ms "$DURATION_MS")
            fi
            if [[ -n "$USE_NODE" ]]; then
                extras+=(--ez use_node "$USE_NODE")
            fi
            ;;
        scroll)
            [[ -z "$DIRECTION" ]] && { err "scroll requires --direction"; exit 1; }
            extras+=(--es direction "$DIRECTION")
            if [[ -n "$X" ]]; then extras+=(--ei x "$X"); fi
            if [[ -n "$Y" ]]; then extras+=(--ei y "$Y"); fi
            ;;
        swipe)
            [[ -z "$START_X" || -z "$START_Y" || -z "$END_X" || -z "$END_Y" ]] && {
                err "swipe requires --start-x/y and --end-x/y"; exit 1
            }
            extras+=(--ei start_x "$START_X" --ei start_y "$START_Y")
            extras+=(--ei end_x "$END_X" --ei end_y "$END_Y")
            if [[ -n "$DURATION_MS" ]]; then
                extras+=(--ei duration_ms "$DURATION_MS")
            fi
            ;;
        *)
            err "Unknown action: $ACTION"; exit 1 ;;
    esac

    # Send broadcast
    log "Broadcasting $ACTION..."
    adb_broadcast "${extras[@]}"

    # Poll for .done sentinel
    local waited=0
    local max_wait=15000  # 15s
    while [[ $waited -lt $max_wait ]]; do
        if adb_cmd shell "test -f '$DEVICE_OUTPUT_DIR/.done'" 2>/dev/null; then
            break
        fi
        sleep 0.2
        waited=$((waited + 200))
    done

    if [[ $waited -ge $max_wait ]]; then
        err "Timed out waiting for action to complete"
        exit 1
    fi

    # Post-screenshot (host-side)
    adb_screencap "$after_img"

    # Pull results
    adb_cmd pull "$DEVICE_OUTPUT_DIR/result.json" "$OUT_DIR/result.json" 2>/dev/null || true
    if $CAPTURE_TREE; then
        adb_cmd pull "$DEVICE_OUTPUT_DIR/pre_tree.json" "$OUT_DIR/pre_tree.json" 2>/dev/null || true
        adb_cmd pull "$DEVICE_OUTPUT_DIR/post_tree.json" "$OUT_DIR/post_tree.json" 2>/dev/null || true
    fi

    # Print summary
    if [[ -f "$OUT_DIR/result.json" ]]; then
        echo ""
        bold "─── Result ───"
        local status verdict elapsed
        status="$(python3 -c "import json; d=json.load(open('$OUT_DIR/result.json')); print(d.get('action_accepted',{}).get('status','?'))" 2>/dev/null || echo "?")"
        verdict="$(python3 -c "import json; d=json.load(open('$OUT_DIR/result.json')); print(d.get('ui_changed',{}).get('verdict','?'))" 2>/dev/null || echo "?")"
        elapsed="$(python3 -c "import json; d=json.load(open('$OUT_DIR/result.json')); print(d.get('elapsed_ms','?'))" 2>/dev/null || echo "?")"

        if [[ "$status" == "success" && "$verdict" == "unchanged" ]]; then
            err "FALSE SUCCESS: action_accepted=$status but ui_changed=$verdict"
        elif [[ "$status" == "success" ]]; then
            ok "action_accepted=$status  ui_changed=$verdict  elapsed=${elapsed}ms"
        else
            warn "action_accepted=$status  ui_changed=$verdict  elapsed=${elapsed}ms"
        fi

        # Show action_accepted.message
        local msg
        msg="$(python3 -c "import json; d=json.load(open('$OUT_DIR/result.json')); print(d.get('action_accepted',{}).get('message',''))" 2>/dev/null || true)"
        if [[ -n "$msg" ]]; then
            echo "  message: $msg"
        fi

        echo "  output:  $OUT_DIR/"
    else
        err "No result.json found"
    fi

    if $OPEN_IMAGES; then
        open "$before_img" "$after_img" 2>/dev/null || true
    fi
}

# ── Compare mode ──

run_compare() {
    bold "═══ Phase 1: ADB Baseline ═══"
    run_adb_action "adb"
    echo ""
    echo -e "${CYAN}Reset the screen to the same state, then press Enter...${NC}"
    read -r
    echo ""
    bold "═══ Phase 2: A11y Action ═══"
    run_a11y_action "a11y"
    echo ""
    bold "═══ Comparison ═══"
    echo "  ADB before/after:  $OUT_DIR/before_adb.png  $OUT_DIR/after_adb.png"
    echo "  A11y before/after: $OUT_DIR/before_a11y.png  $OUT_DIR/after_a11y.png"
    echo "  A11y result:       $OUT_DIR/result.json"

    if $OPEN_IMAGES; then
        open "$OUT_DIR/before_adb.png" "$OUT_DIR/after_adb.png" \
             "$OUT_DIR/before_a11y.png" "$OUT_DIR/after_a11y.png" 2>/dev/null || true
    fi
}

# ── Main ──

case "$MODE" in
    adb)     run_adb_action ;;
    a11y)    run_a11y_action ;;
    shizuku) run_a11y_action ;;  # uses same broadcast path with use_shizuku=true
    compare) run_compare ;;
esac
