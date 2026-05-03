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
- `socket_fd_count` is what we assert on (filtered to fds backed by `socket:` inodes). Total
  `fd_count` drifts ±1–2 with JIT/tracing artifacts and is reported for context, not the
  pass/fail check.

---

## Pass / fail criteria

The run is **PASS** iff every condition holds:

| Condition | Why |
|---|---|
| `socket_fd_after - socket_fd_before <= 2` | The relay's `ServerSocket` is +1, the bridge's persisted Shizuku UserService binder is +1 in worst-case re-bind. Anything beyond +2 indicates per-iteration leak. |
| `pid_before == pid_after` | A process restart invalidates the fd comparison; if Android killed and re-spawned ClosePaw mid-run the run is inconclusive. |
| Zero `EMFILE` / `Too many open files` lines in logcat | These are the symptom users would see in the wild. |
| Each iteration's `iter_NN.log` shows `complete_task` with `outcome` = `success`/`SUCCESS` and `Example Domain` in the answer | Confirms the WebSocket actually carried CDP traffic each time, not just opened-and-failed. |

`STRESS_ALLOWED_FD_DELTA` overrides the +2 allowance (e.g. for a 50-iteration run where some
slack from the agent's screenshot caches is acceptable).

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

This is from the actual PASS run on nubia P0110 at 2026-05-03 11:25–11:33 PDT
(`debug-output/ws-stress-20260503_112535/`):

```
[11:25:43] Baseline: pid=30125 fd=328 socket_fd=21
[11:25:43] Iteration 1 / 10
[11:26:04]   pid=30125 fd=525 socket_fd=20
[11:26:04] Iteration 2 / 10
[11:27:02]   pid=30125 fd=281 socket_fd=20
...
[11:32:44] Iteration 10 / 10
[11:33:31]   pid=30125 fd=279 socket_fd=20
[11:33:36] Summary
[11:33:36]   pid_before=30125 fd_before=328 socket_fd_before=21
[11:33:36]   pid_after=30125  fd_after=279  socket_fd_after=20
[11:33:36]   fd_delta=-49  socket_fd_delta=-1
[11:33:36]   EMFILE/Too-many-open-files lines: 0
[11:33:36] PASS: socket fd delta=-1, no EMFILE
```

Notable points:
- `socket_fd` went from 21 to 20 — *negative* delta. The relay's `ServerSocket` is
  bound and freed cleanly per session; the net `-1` is one pre-existing socket
  unrelated to the relay closing during the run.
- Iteration 1's raw `fd=525` is class-loader / JIT cache loading (not relay-related).
  By iteration 2 it falls to ~280 and stays flat, confirming the wireless transport
  itself is not the source of the spike.
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
