#!/bin/bash
#
# ws-relay-stress.sh — WebSocket relay stress harness for the wireless-ADB self-pair
# transport. Runs N sequential `debug-run.sh` agent invocations (each ends in one
# `browser_script` round-trip) on the SAME ClosePaw process, then asserts:
#
#   1. /proc/<pid>/fd count grows by no more than +ALLOWED_DELTA across the N runs
#      (default +2). BOTH socket_fd and total fd are checked.
#   2. ClosePaw process pid is the same before and after the run (no Android
#      background-killer eviction, otherwise the fd comparison is meaningless).
#   3. No `EMFILE` / `Too many open files` lines appear in ANY iteration's child
#      `logcat_full.log` (debug-run.sh clears logcat per-iteration, so the post-run
#      `logcat -d` would only show the last iteration's tail — we have to scan each
#      child's captured log).
#   4. Each iteration produced a successful `browser_script outcome=SUCCESS` AND a
#      `Task completed: ... outcome: GOAL_ACHIEVED` AND used the
#      `WIRELESS_ADB_SELF_PAIR` transport. If any iteration was silently abandoned
#      (no GOAL_ACHIEVED, no relay activity, etc.) the run is FAIL.
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
# socket_fd is the relay-leak signal — relay's ServerSocket is +1, +1 slack.
# Total fd has noisy drift on debug builds (anon_inode sync_file/eventfd, /dmabuf, etc.
# from Android's GPU pipeline as the agent re-renders per session) — give it a wider
# allowance. Override either via env var.
ALLOWED_SOCK_DELTA="${STRESS_ALLOWED_SOCK_DELTA:-${STRESS_ALLOWED_FD_DELTA:-2}}"
ALLOWED_FD_DELTA="${STRESS_ALLOWED_FD_DELTA:-20}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        -n|--iterations) ITER="$2"; shift 2 ;;
        -p|--prompt)     PROMPT="$2"; shift 2 ;;
        -s|--serial)     SERIAL="$2"; shift 2 ;;
        --allowed-sock-delta) ALLOWED_SOCK_DELTA="$2"; shift 2 ;;
        --allowed-fd-delta)   ALLOWED_FD_DELTA="$2"; shift 2 ;;
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

# 5. Drive N iterations. Snapshot the latest run dir BEFORE each invocation so we
#    can identify which run_* dir the iteration produced and scrutinize it
#    independently (debug-run.sh clears logcat per-iteration, so we cannot rely on
#    a final tail-of-logcat scan to catch EMFILE in earlier iterations).
declare -a ITER_RUN_DIRS=()
declare -a ITER_FD=()
declare -a ITER_SOCK=()
for i in $(seq 1 "$ITER"); do
    log "Iteration $i / $ITER"
    ensure_a11y # cheap re-arm; OEM wipes after some activity events

    BEFORE_DIRS="$(ls -1d "$PROJECT_ROOT/debug-output/run_"* 2>/dev/null | wc -l | tr -d ' ')"

    ANDROID_SERIAL="$SERIAL" DEBUG_AUTO_APPROVE=true DEBUG_BROWSER_SCRIPT_ENABLED=true \
        DEBUG_MAX_WAIT_SECONDS=180 \
        "$PROJECT_ROOT/scripts/debug-run.sh" --accessibility-only "$PROMPT" \
        > "$OUT/iter_$(printf '%02d' "$i").log" 2>&1 \
        || log "  WARN: iteration $i exited non-zero (see iter_$(printf '%02d' "$i").log)"

    AFTER_DIRS="$(ls -1d "$PROJECT_ROOT/debug-output/run_"* 2>/dev/null | wc -l | tr -d ' ')"
    if [[ "$AFTER_DIRS" -gt "$BEFORE_DIRS" ]]; then
        # Whatever debug-run.sh just created.
        ITER_DIR="$(ls -1td "$PROJECT_ROOT/debug-output/run_"* 2>/dev/null | head -n 1)"
        ITER_RUN_DIRS+=("$ITER_DIR")
        log "  run_dir=$(basename "$ITER_DIR")"
    else
        ITER_RUN_DIRS+=("")
        log "  run_dir=<not-created>"
    fi

    PID_NOW="$(closepaw_pid)"
    FD_NOW="$(fd_count "$PID_NOW")"
    SOCK_NOW="$(socket_fd_count "$PID_NOW")"
    ITER_FD+=("$FD_NOW")
    ITER_SOCK+=("$SOCK_NOW")
    log "  pid=$PID_NOW fd=$FD_NOW socket_fd=$SOCK_NOW"
done

# 6. Final fd snapshot.
sleep 3
PID_AFTER="$(closepaw_pid)"
FD_AFTER="$(fd_count "$PID_AFTER")"
SOCK_AFTER="$(socket_fd_count "$PID_AFTER")"

# 7. Per-iteration outcome verification. debug-run.sh clears logcat each iteration,
#    so the only reliable record of what happened in iteration K is its captured
#    `logcat_full.log`. Scrutinize each one for:
#       * EMFILE / Too many open files
#       * outcome=SUCCESS for browser_script
#       * GOAL_ACHIEVED on Task completion
#       * WIRELESS_ADB_SELF_PAIR transport activated (relay opened)
EMFILE_HITS=0
PER_ITER_FAILED=()
PER_ITER_PAIR_SKIPPED=0
for i in $(seq 1 "$ITER"); do
    IDX=$((i - 1))
    DIR="${ITER_RUN_DIRS[$IDX]:-}"
    if [[ -z "$DIR" || ! -d "$DIR" ]]; then
        PER_ITER_FAILED+=("$i: no run_dir")
        continue
    fi
    LC="$DIR/logcat_full.log"
    if [[ ! -f "$LC" ]]; then
        PER_ITER_FAILED+=("$i: no logcat_full.log")
        continue
    fi

    # `grep -c` writes the count then exits 1 when there are zero matches. Capture
    # stdout cleanly and treat the no-match exit as zero — without this guard, the
    # `|| echo 0` shorthand would smush a second 0 onto HITS and break arithmetic.
    HITS="$(grep -cE 'EMFILE|Too many open files' "$LC" 2>/dev/null)" || HITS=0
    EMFILE_HITS=$((EMFILE_HITS + HITS))

    # The positive signals below (browser_script SUCCESS + GOAL_ACHIEVED + WS relay
    # opened) are sufficient to prove the iteration ran. We don't grep for
    # USER_STOPPED — it's the benign "session ended after task completed" reason
    # because debug-run.sh broadcasts STOP_AGENT once it sees TaskCompleted, and so
    # every successful iteration legitimately logs USER_STOPPED at the end. The
    # actual silent-failure case is "no GOAL_ACHIEVED, no browser_script SUCCESS"
    # which the checks below catch directly.
    if ! grep -qE "browser_script outcome=SUCCESS" "$LC"; then
        PER_ITER_FAILED+=("$i: no browser_script outcome=SUCCESS")
        continue
    fi
    if ! grep -qE "outcome: GOAL_ACHIEVED" "$LC"; then
        PER_ITER_FAILED+=("$i: no GOAL_ACHIEVED")
        continue
    fi
    if ! grep -qE "wireless-adb WS relay" "$LC"; then
        PER_ITER_FAILED+=("$i: WIRELESS_ADB_SELF_PAIR relay not opened")
        continue
    fi

    if grep -qE "wireless-adb pair skipped" "$LC"; then
        PER_ITER_PAIR_SKIPPED=$((PER_ITER_PAIR_SKIPPED + 1))
    fi
done

# Convenience: post-run logcat tail (last iteration's window only — kept for
# diagnostic forensics, not relied on for pass/fail).
adb -s "$SERIAL" logcat -d > "$OUT/logcat_post.log" 2>/dev/null || true

log "----------------------------------------------------------------"
log "Summary"
log "  pid_before=$PID_BEFORE pid_after=$PID_AFTER"
log "  Cold baseline (no agent yet):  fd=$FD_BEFORE socket_fd=$SOCK_BEFORE"
log "  After iter 1 (warmed up):      fd=${ITER_FD[0]:-?} socket_fd=${ITER_SOCK[0]:-?}"
log "  After iter $ITER:               fd=$FD_AFTER socket_fd=$SOCK_AFTER"
# Leak signal: compare end-of-run to end-of-iter-1, NOT to cold baseline.
# Cold→iter1 includes one-time bootstrap cost (relay ServerSocket, persistent binders,
# class loaders) and would pollute the leak-per-iteration signal. The per-iteration leak
# we want to detect is whether iter 2..N each add fds beyond what iter 1 already established.
WARM_FD="${ITER_FD[0]:-$FD_BEFORE}"
WARM_SOCK="${ITER_SOCK[0]:-$SOCK_BEFORE}"
PER_ITER_FD_DELTA=$((FD_AFTER - WARM_FD))
PER_ITER_SOCK_DELTA=$((SOCK_AFTER - WARM_SOCK))
log "  Per-iteration deltas (iter 1 -> iter $ITER, the leak signal):"
log "      fd_delta=$PER_ITER_FD_DELTA  socket_fd_delta=$PER_ITER_SOCK_DELTA"
log "  Bootstrap cost (cold -> iter 1, one-time, NOT a leak):"
log "      fd=$((WARM_FD - FD_BEFORE))  socket_fd=$((WARM_SOCK - SOCK_BEFORE))"
log "  EMFILE/Too-many-open-files lines (across all iter logcats): $EMFILE_HITS"
log "  Iterations with 'pair skipped': $PER_ITER_PAIR_SKIPPED / $ITER"
if [[ "$PER_ITER_PAIR_SKIPPED" -eq 0 ]] && [[ "$ITER" -gt 1 ]]; then
    log "    (informational: pair-once requires shell-uid to read /data/misc/adb/adb_keys;"
    log "     some OEMs deny this — the transport falls back to re-pairing, which is harmless"
    log "     to the relay leak check we're running here.)"
fi
log "  Iterations with verification failures: ${#PER_ITER_FAILED[@]}"
for f in "${PER_ITER_FAILED[@]}"; do
    log "    - $f"
done
log "  Output dir: $OUT"
log "----------------------------------------------------------------"

verdict=0

if [[ "$PID_BEFORE" != "$PID_AFTER" ]]; then
    log "FAIL: ClosePaw process pid changed during the run ($PID_BEFORE -> $PID_AFTER)."
    log "      Background killer evicted the app; fd delta is meaningless. Re-run."
    verdict=1
fi
if [[ "$PER_ITER_SOCK_DELTA" -gt "$ALLOWED_SOCK_DELTA" ]]; then
    log "FAIL: per-iter socket fd grew by $PER_ITER_SOCK_DELTA (allowance +$ALLOWED_SOCK_DELTA)"
    verdict=1
fi
if [[ "$PER_ITER_FD_DELTA" -gt "$ALLOWED_FD_DELTA" ]]; then
    log "FAIL: per-iter total fd grew by $PER_ITER_FD_DELTA (allowance +$ALLOWED_FD_DELTA)"
    log "      socket_fd_delta is the relay-specific signal; total fd_delta also catches"
    log "      non-socket leaks (open file handles, pipes, eventfds). The wider total-fd"
    log "      allowance accounts for Android's GPU pipeline (anon_inode sync_file/eventfd,"
    log "      /dmabuf) churn that scales with per-session UI work."
    verdict=1
fi
if [[ "$EMFILE_HITS" -gt 0 ]]; then
    log "FAIL: EMFILE / 'Too many open files' detected in iteration logs ($EMFILE_HITS lines)"
    verdict=1
fi
if [[ "${#PER_ITER_FAILED[@]}" -gt 0 ]]; then
    log "FAIL: ${#PER_ITER_FAILED[@]} iteration(s) failed verification (see list above)"
    verdict=1
fi

if [[ "$verdict" -eq 0 ]]; then
    log "PASS: pid stable, per-iter socket_fd_delta=$PER_ITER_SOCK_DELTA, per-iter fd_delta=$PER_ITER_FD_DELTA, EMFILE=0, all $ITER iterations verified"
fi
exit "$verdict"
