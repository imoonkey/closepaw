#!/bin/bash
#
# ws-relay-stress.sh — WebSocket relay stress harness for the wireless-ADB self-pair
# transport. Runs N sequential `debug-run.sh` agent invocations (each ends in one
# `browser_script` round-trip) on the SAME ClosePaw process, then asserts:
#
#   1. /proc/<pid>/fd count grows by no more than +2 across the N runs.
#   2. logcat carries no `EMFILE` / `Too many open files` lines.
#
# Why these checks: WirelessAdbSelfPairTransport binds one ServerSocket for the
# WebSocket relay (+1 fd) and accepts one AdbStream per WebSocket (+1 fd while
# in flight). Past the first iteration, both should be released. A growing fd
# count means the relay accept loop or per-connection cleanup is leaking.
#
# Usage:
#   ANDROID_SERIAL=<serial> ./scripts/ws-relay-stress.sh [-n ITER] [-p "PROMPT"]
#
# Requires: adb, scripts/debug-run.sh on PATH (uses repo-relative path).
#
# Real-device runbook context: see
# `projects/active/browser/cn/diag_20260503_ws_relay_stress.md`.
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

SERIAL="${ANDROID_SERIAL:-}"
ITER="${STRESS_ITER:-10}"
PROMPT="${STRESS_PROMPT:-Use browser_script to fetch document.title from open https://example.com}"
ALLOWED_DELTA="${STRESS_ALLOWED_FD_DELTA:-2}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        -n|--iterations) ITER="$2"; shift 2 ;;
        -p|--prompt)     PROMPT="$2"; shift 2 ;;
        -s|--serial)     SERIAL="$2"; shift 2 ;;
        --allowed-delta) ALLOWED_DELTA="$2"; shift 2 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done

if [[ -z "$SERIAL" ]]; then
    echo "ERROR: ANDROID_SERIAL not set (use --serial or env)." >&2
    exit 2
fi

OUT="$PROJECT_ROOT/debug-output/ws-stress-$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUT"
LOG="$OUT/stress.log"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }

closepaw_pid() {
    adb -s "$SERIAL" shell pidof ai.closepaw 2>/dev/null | tr -d '\r\n' | awk '{print $1}'
}

fd_count() {
    local pid="$1"
    if [[ -z "$pid" ]]; then echo 0; return; fi
    # `ls /proc/<pid>/fd` from adb-shell is denied on hardened OEMs (e.g. nubia P0110).
    # `run-as ai.closepaw` runs as the app UID so it can read its own /proc/<pid>/fd.
    # Requires a debug-buildable APK; both setup.sh and the production wireless path use
    # the debug APK so this is always available in this harness.
    adb -s "$SERIAL" shell "run-as ai.closepaw ls /proc/$pid/fd 2>/dev/null | wc -l" | tr -d '\r\n '
}

socket_fd_count() {
    # Subset of fds that point at sockets — narrows the leak signal vs total fd count
    # which can drift due to JIT compiler / tracing / GC artifacts.
    local pid="$1"
    if [[ -z "$pid" ]]; then echo 0; return; fi
    adb -s "$SERIAL" shell "run-as ai.closepaw ls -l /proc/$pid/fd 2>/dev/null | grep -c socket:" | tr -d '\r\n '
}

# 1. Force fresh pair so the first iteration exercises the full bootstrap path.
log "Forcing fresh pair (clearing files/adb_self_pair on device)"
adb -s "$SERIAL" shell run-as ai.closepaw rm -rf files/adb_self_pair 2>/dev/null || true

# 2. Confirm no host-mediated forwards are masking the wireless path.
log "Removing any host-mediated forwards/reverses"
adb -s "$SERIAL" forward --remove-all 2>/dev/null || true
adb -s "$SERIAL" reverse --remove-all 2>/dev/null || true

# 3. Ensure ClosePaw's accessibility service is enabled. Some OEMs (incl. nubia) wipe
#    `enabled_accessibility_services` after `settings put`; `cmd settings put` survives.
ensure_a11y() {
    adb -s "$SERIAL" shell "cmd settings put secure enabled_accessibility_services ai.closepaw/ai.closepaw.app.AgentService" >/dev/null 2>&1
    adb -s "$SERIAL" shell "cmd settings put secure accessibility_enabled 1" >/dev/null 2>&1
}
ensure_a11y
sleep 1

# 4. Boot the app once so we can capture a stable baseline fd count.
log "Launching ClosePaw to establish baseline (no agent task yet)"
adb -s "$SERIAL" shell monkey -p ai.closepaw 1 >/dev/null 2>&1 || true
sleep 5
PID_BEFORE="$(closepaw_pid)"
FD_BEFORE="$(fd_count "$PID_BEFORE")"
SOCK_BEFORE="$(socket_fd_count "$PID_BEFORE")"
log "Baseline: pid=$PID_BEFORE fd=$FD_BEFORE socket_fd=$SOCK_BEFORE"

# 4. Clear logcat so post-run grep only sees this session's events.
adb -s "$SERIAL" logcat -c

# 5. Drive N iterations.
for i in $(seq 1 "$ITER"); do
    log "Iteration $i / $ITER"
    ensure_a11y # cheap re-arm; OEM wipes after some activity events
    ANDROID_SERIAL="$SERIAL" DEBUG_AUTO_APPROVE=true DEBUG_BROWSER_SCRIPT_ENABLED=true \
        DEBUG_MAX_WAIT_SECONDS=180 \
        "$PROJECT_ROOT/scripts/debug-run.sh" --basic --accessibility-only "$PROMPT" \
        > "$OUT/iter_$(printf '%02d' "$i").log" 2>&1 \
        || log "  WARN: iteration $i exited non-zero (see iter_$(printf '%02d' "$i").log)"

    PID_NOW="$(closepaw_pid)"
    FD_NOW="$(fd_count "$PID_NOW")"
    SOCK_NOW="$(socket_fd_count "$PID_NOW")"
    log "  pid=$PID_NOW fd=$FD_NOW socket_fd=$SOCK_NOW"
done

# 6. Final state + summary.
sleep 3
PID_AFTER="$(closepaw_pid)"
FD_AFTER="$(fd_count "$PID_AFTER")"
SOCK_AFTER="$(socket_fd_count "$PID_AFTER")"
adb -s "$SERIAL" logcat -d > "$OUT/logcat_post.log"
# `grep -c` returns the count on stdout AND exits 1 when there are zero matches; without
# the redirect, `|| echo 0` would smush a second 0 onto the variable. Compute it cleanly
# and tolerate the no-match exit explicitly.
EMFILE_HITS="$(grep -cE 'EMFILE|Too many open files' "$OUT/logcat_post.log" 2>/dev/null)" || EMFILE_HITS=0

log "----------------------------------------------------------------"
log "Summary"
log "  pid_before=$PID_BEFORE fd_before=$FD_BEFORE socket_fd_before=$SOCK_BEFORE"
log "  pid_after=$PID_AFTER  fd_after=$FD_AFTER  socket_fd_after=$SOCK_AFTER"
log "  fd_delta=$((FD_AFTER - FD_BEFORE))  socket_fd_delta=$((SOCK_AFTER - SOCK_BEFORE))"
log "  EMFILE/Too-many-open-files lines: $EMFILE_HITS"
log "  Output dir: $OUT"
log "----------------------------------------------------------------"

if [[ "$PID_BEFORE" != "$PID_AFTER" ]]; then
    log "WARN: ClosePaw process restarted during the run (pid changed)."
    log "      The fd delta is unreliable — investigate the iter logs."
fi

verdict=0
SOCK_DELTA=$((SOCK_AFTER - SOCK_BEFORE))
if [[ "$SOCK_DELTA" -gt "$ALLOWED_DELTA" ]]; then
    log "FAIL: socket fd grew by $SOCK_DELTA (allowance +$ALLOWED_DELTA)"
    verdict=1
fi
if [[ "$EMFILE_HITS" -gt 0 ]]; then
    log "FAIL: EMFILE / 'Too many open files' detected in logcat ($EMFILE_HITS lines)"
    verdict=1
fi

if [[ "$verdict" -eq 0 ]]; then
    log "PASS: socket fd delta=$SOCK_DELTA, no EMFILE"
fi
exit "$verdict"
