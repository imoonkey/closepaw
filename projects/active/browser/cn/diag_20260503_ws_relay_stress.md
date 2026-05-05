# Phase 5 Runbook — WebSocket Relay Stress (fd / Thread Leak Detection)

**Date**: 2026-05-03
**Purpose**: Confirm `WirelessAdbSelfPairTransport.ensureWebSocketRelayPort()` and the
in-process TCP relay (one `ServerSocket` accept loop, one `AdbStream` per accepted client) do
not leak file descriptors or threads when `browser_script` runs N times in succession on the
same device.
**Status target**: PASS row in `projects/active/browser/real_device_qa.md` after a successful
run on a Phase-4-PASS device (currently nubia P0110 at `100.64.43.95:5555`).

This is the outstanding-work item #3 from `diag_20260503_phase4_handoff.md`: the relay path
worked in practice during the Phase 4 end-to-end run (Page.navigate / Runtime.evaluate over
CDP did go through it), but no explicit "1 connect, N round trips, no leaks" assertion was
run.

---

## What we are stressing

`WirelessAdbSelfPairTransport` opens, on first WebSocket request:

| Resource | Opened by | Closed by | Per-iteration cost |
|---|---|---|---|
| Relay `ServerSocket` (127.0.0.1:N) | `ensureWebSocketRelayPort()` | `close()` only | +1 fd, total |
| Accept-loop daemon thread | `ensureWebSocketRelayPort()` | `close()` only | +1 thread, total |
| TLS `Socket` to localhost:tlsAdbPort | `wireClient.openLocalAbstract` (per accept) | `AdbStream.close()` in `proxyConnection.finally` | +0 net per round-trip |
| `AdbStream` reader thread (`adb-stream-1`) | `AdbStream.<init>` | `AdbStream.close()` | +0 net per round-trip |
| Per-conn pump threads (`wireless-adb-down/up`) | `proxyConnection` | `pump`'s `read()` returns EOF + `join()` | +0 net per round-trip |

The leak surface is "+0 net per round-trip" rows: a missing close in `proxyConnection.finally`
or in `AdbStream.close()` would scale with iteration count. The asserts below catch this with
linear sensitivity.

---

## Helper script

`scripts/ws-relay-stress.sh` automates the full cycle. Reads `ANDROID_SERIAL`, runs
`STRESS_ITER` (default 10) sequential `debug-run.sh` agent invocations against the same
ClosePaw process, captures `/proc/<pid>/fd` count before and after, scans logcat for
`EMFILE`/`Too many open files`, and exits 0 on PASS / 1 on FAIL.

```bash
ANDROID_SERIAL=100.64.43.95:5555 \
  ./scripts/ws-relay-stress.sh -n 10
```

Outputs land in `debug-output/ws-stress-<timestamp>/`:
- `stress.log` — pre/post fd counts and per-iteration fd snapshot.
- `iter_NN.log` — full `debug-run.sh` output for iteration N.
- `logcat_post.log` — full logcat captured AFTER all iterations complete.

### Scope notes

- The script forces a fresh pair (`run-as ai.closepaw rm -rf files/adb_self_pair`) so the
  first iteration exercises the full bootstrap path (TLS-PSK pair + mTLS handshake). All
  subsequent iterations skip pairing (key already in adb_keys).
- `adb forward --remove-all && adb reverse --remove-all` are run before iteration 1 to prove
  no host-mediated relay is masking the wireless path.
- `socket_fd_count` is the high-signal column (filtered to fds backed by `socket:`
  inodes — relay leaks live here). Total `fd_count` drifts a bit with JIT/GC artifacts,
  but is also asserted (same allowance) so a non-socket fd leak still surfaces.
- Per-iteration verification scans the iteration's own `logcat_full.log` for
  `EMFILE` / `Too many open files`, `USER_STOPPED`, `browser_script outcome=SUCCESS`,
  `outcome: GOAL_ACHIEVED`, and the wireless relay open. `debug-run.sh` calls
  `adb logcat -c` at the start of every invocation so a single post-run `logcat -d`
  would only see the last iteration's tail — per-iter scanning is the only way to
  catch e.g. an iteration-3 EMFILE.

---

## Pass / fail criteria

The run is **PASS** iff every condition holds:

| Condition | Why |
|---|---|
| `pid_before == pid_after` | A process restart invalidates the fd comparison; if Android killed and re-spawned ClosePaw mid-run the run is inconclusive. **FAIL on mismatch.** |
| `socket_fd[iter N] - socket_fd[iter 1] <= 2` | Per-iteration leak signal. The cold-baseline → iter 1 jump includes one-time bootstrap cost (relay `ServerSocket`, persistent binders, OkHttp client init); comparing iter N back to iter 1 isolates the per-iteration delta. The relay's own `ServerSocket` is +1, +1 slack. Override via `STRESS_ALLOWED_SOCK_DELTA`. |
| `fd[iter N] - fd[iter 1] <= 20` | Same shape, but for total fds (catches non-socket leaks: pipes, eventfds, open files). The wider allowance accounts for Android's GPU pipeline (anon_inode `sync_file`/`eventfd`, `/dmabuf:qcom,system`) churn that scales with the per-session UI re-renders the agent does — verified empirically by `ls -l /proc/<pid>/fd`. Override via `STRESS_ALLOWED_FD_DELTA`. |
| Zero `EMFILE` / `Too many open files` lines in **any** iteration's `logcat_full.log` | The symptom users would see in the wild. Scanned per-iter, not just the post-run tail (debug-run.sh clears logcat between iterations). |
| Every iteration shows: `browser_script outcome=SUCCESS` + `outcome: GOAL_ACHIEVED` + `wireless-adb WS relay` opened | Confirms the WebSocket actually carried CDP traffic each time. Catches silent failures where the app launched but the agent loop never ran (a11y wiped, AgentService DEAD, network unreachable, etc.). The `Session completed: ... reason: USER_STOPPED` line is benign — debug-run.sh broadcasts STOP_AGENT after task completion so every successful iter logs it. |
| Iterations 2..N show `wireless-adb pair skipped` (informational only) | After pair-once landed (commit `f4e662af`), every iteration past the first should reuse the persisted key — *if* the shell uid can read `/data/misc/adb/adb_keys`. Some OEMs (e.g. nubia P0110) deny this read; the transport falls back to re-pairing, which is harmless to the relay leak check. Reported in the summary; not asserted as FAIL since pair-once is independently verified by `browser-phase5-pair-once`. |

`STRESS_ALLOWED_SOCK_DELTA` (default 2) and `STRESS_ALLOWED_FD_DELTA` (default 20)
override the per-iter allowances.

### Why iter-1 baseline, not cold baseline?

The cold-baseline → iter 1 jump on nubia P0110 is roughly:
- `socket_fd: +5` (relay `ServerSocket`, OkHttp WebSocket client + pool, persistent
  Shizuku binder socket, `chrome_devtools_remote` AdbStream socket pair, ...)
- `fd: +85` (class loaders, JIT cache, dex prefetch, inspection-trace files, ...)

These are amortized one-time costs of the first browser_script call, not a per-call
leak. A leak is "fds added by iter K that aren't released by the end of iter K and
that scale with K". Iter-N - iter-1 is exactly that signal.

---

## How to run by hand (decomposed, no script)

If you need to capture intermediate state at custom checkpoints:

```bash
SERIAL=100.64.43.95:5555

# 1. Force fresh pair
adb -s $SERIAL shell run-as ai.closepaw rm -rf files/adb_self_pair

# 2. Prove no host plumbing
adb -s $SERIAL forward --remove-all
adb -s $SERIAL reverse --remove-all

# 3. Boot ClosePaw and snapshot baseline fds
adb -s $SERIAL shell monkey -p ai.closepaw 1
sleep 5
PID=$(adb -s $SERIAL shell pidof ai.closepaw | tr -d '\r')
echo "pid=$PID"
# `ls /proc/<pid>/fd` from adb-shell is denied on hardened OEMs (e.g. nubia);
# `run-as ai.closepaw` runs as the app UID and can read its own fd table.
adb -s $SERIAL shell run-as ai.closepaw ls /proc/$PID/fd | wc -l
adb -s $SERIAL shell run-as ai.closepaw ls -l /proc/$PID/fd | grep -c socket:

adb -s $SERIAL logcat -c

# 4. Run N iterations
for i in $(seq 1 10); do
  ANDROID_SERIAL=$SERIAL DEBUG_AUTO_APPROVE=true \
    ./scripts/debug-run.sh --basic --accessibility-only \
    "Use browser_script to fetch document.title from open https://example.com"
done

# 4. Snapshot post-run
adb -s $SERIAL shell run-as ai.closepaw ls /proc/$PID/fd | wc -l
adb -s $SERIAL shell run-as ai.closepaw ls -l /proc/$PID/fd | grep -c socket:
adb -s $SERIAL logcat -d | grep -E 'EMFILE|Too many open files|wireless-adb|WirelessAdbSelfPair'
```

---

## What "good" looks like in `stress.log`

This is from the round-2 PASS run on nubia P0110 at 2026-05-03 12:46–12:54 PDT
(`debug-output/ws-stress-20260503_124604/`):

```
[12:46:11] Baseline: pid=10099 fd=323 socket_fd=20
[12:46:11] Iteration 1 / 10
[12:47:01]   pid=10099 fd=278 socket_fd=20
[12:47:01] Iteration 2 / 10
[12:47:58]   pid=10099 fd=276 socket_fd=19
...
[12:53:45] Iteration 10 / 10
[12:54:37]   pid=10099 fd=276 socket_fd=20
[12:54:43] Summary
[12:54:43]   pid_before=10099 pid_after=10099
[12:54:43]   Cold baseline (no agent yet):  fd=323 socket_fd=20
[12:54:43]   After iter 1 (warmed up):      fd=278 socket_fd=20
[12:54:43]   After iter 10:               fd=276 socket_fd=20
[12:54:43]   Per-iteration deltas (iter 1 -> iter 10, the leak signal):
[12:54:43]       fd_delta=-2  socket_fd_delta=0
[12:54:43]   Bootstrap cost (cold -> iter 1, one-time, NOT a leak):
[12:54:43]       fd=-45  socket_fd=0
[12:54:43]   EMFILE/Too-many-open-files lines (across all iter logcats): 0
[12:54:43]   Iterations with verification failures: 0
[12:54:43] PASS: pid stable, per-iter socket_fd_delta=0, per-iter fd_delta=-2, EMFILE=0, all 10 iterations verified
```

Notable points:
- `socket_fd` is **flat at 19-20** through every iteration. The relay's
  `ServerSocket` is bound and freed cleanly per session; one-fd jitter is
  unrelated sockets opening/closing during the iter.
- The per-iter `fd_delta` of `-2` is *negative* — total fds actually went DOWN
  across iterations 1→10 (Android's GPU pipeline frees `dmabuf`/`sync_file`
  handles between sessions faster than they're created in this workload).
- `socket_fd` is the high-signal column. Track it iteration-by-iteration; if it
  starts climbing monotonically that's a leak.

## What "bad" looks like

A per-iteration leak shows up as monotonically growing `socket_fd` between iterations:

```
[HH:MM:SS]   pid=12345 fd=193 socket_fd=24
[HH:MM:SS]   pid=12345 fd=195 socket_fd=26
[HH:MM:SS]   pid=12345 fd=197 socket_fd=28
...
[HH:MM:SS]   pid=12345 fd=211 socket_fd=42
[HH:MM:SS] FAIL: socket fd grew by 20 (allowance +2)
```

Diagnostic next steps if FAIL fires:
1. `adb shell ls -l /proc/<pid>/fd | grep socket:` — list the leaked sockets to identify
   their type (TCP/Unix abstract).
2. `adb shell cat /proc/<pid>/net/tcp` — match the local ports to identify which side leaks
   (relay accept side vs. localhost:tlsAdbPort outbound side).
3. Inspect `WirelessAdbSelfPairTransport.proxyConnection` (the only place per-connection
   resources are allocated) for missing `runCatching { stream?.close() }` or
   `runCatching { client.close() }` paths.
4. Inspect `AdbStream.close()` — the reader thread + `socket.close()` must both fire.

---

## Caveats — what this stress test does NOT cover

- **Single-session multi-script case**: each `debug-run.sh` invocation creates a new
  `BrowserSessionManager`, so each iteration is a "cold session" — re-bind of the relay
  `ServerSocket`. A single-session, multi-script flow would re-use the same WebSocket and
  not stress the accept loop. That path is dominated by CDP-level resource accounting in
  `ChromeCdpClient`, not the wireless transport, so it is left to per-call CDP tests.
- **Concurrent WebSocket connections**: the relay accept loop spawns one daemon thread per
  connection but they all funnel through the same TLS adb session. Tested in JVM (see
  `WirelessAdbSelfPairTransportRelayStressTest.kt::accept loop drains 20 sequential
  client cycles without thread leak`); not exercised on real device.
- **Long-soak (1+ hours)**: Android's background process killer may evict ClosePaw mid-run.
  The script reports `pid_before != pid_after` as a WARN — investigate manually before
  trusting the fd delta.
