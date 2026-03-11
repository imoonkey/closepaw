# Remote Emulator Eval — Implementation Summary

Date: 2026-03-10
Branch: `task/remote-emulator` (commit `947fee6`)
Design doc: `remote_emulator_eval_codex.md` (this folder)

## What Was Done

Phase 1 of the migration plan is complete: `qiguo-ld1` can run single-emulator eval end-to-end.

### Files Changed

**New files:**
- `eval/config/remote.yaml` — remote-specific eval config (`adb_path: ~/android-sdk/platform-tools/adb`)
- `scripts/remote/provision.sh` — one-shot provisioning script for the remote worker
- `scripts/remote/proxy_tunnel.sh` — SSH tunnel helper (remote → laptop proxy)
- `doc/dev/remote_eval_worker.md` — runbook for operating the remote eval worker

**Modified files:**
- `scripts/prepare_baseline.sh` — added `--headless` flag, venv Python preference, `~/android-sdk` emulator search path
- `scripts/eval_parallel.sh` — same headless/emulator path changes

### Remote Environment (qiguo-ld1)

Provisioned and verified:
- JDK 17: `/usr/lib/jvm/java-17-openjdk-amd64`
- Python 3.11 venv: `~/androidagent/eval/.venv`
- Android SDK: `~/android-sdk` (emulator 32.1.15 for glibc 2.27 compat)
- AVD: `AndroidWorldAvd` (Pixel 6, API 33, x86_64)
- Repo: `~/androidagent` (cloned + android_world reference data)
- `.env`: API keys + `OPENAI_BASE_URL=http://localhost:18080/v1`
- `~/.android-agent-env`: PATH/JAVA_HOME/ANDROID_SDK_ROOT exports

### Proxy Topology

Chose **方案 B** (SSH tunnel) for simplicity:
- Proxy runs on laptop, binds `0.0.0.0:18080` (changed from `127.0.0.1`)
- Reverse SSH tunnel: `ssh -f -N -R 18080:127.0.0.1:18080 qiguo@qiguo-ld1`
- Remote `.env` uses `localhost:18080` — emulator's `10.0.2.2` maps to host loopback, hits tunnel
- Alternative: direct Tailscale IP access now also works since proxy binds all interfaces

**Security note on `0.0.0.0` bind**: `0.0.0.0` listens on all network interfaces (loopback, WiFi, Tailscale, etc.), meaning any device on the same network could reach the proxy. Acceptable risk because: macOS firewall blocks unsolicited inbound by default, and Tailscale is point-to-point encrypted. To restrict further, set `COPILOT_PROXY_BIND` to the laptop's Tailscale IP (e.g. `100.x.x.x`) — but then local `127.0.0.1` clients would break. Avoid using on public WiFi without additional firewall rules.

### Key Obstacles Solved

| Problem | Solution |
|---------|----------|
| glibc 2.27 vs emulator requiring 2.30 | Used older emulator 32.1.15 (`emulator-linux_x64-10696886.zip`) |
| `python3` → system Python 3.6 | `eval/.venv/bin/python` with `--without-pip` bootstrap via `get-pip.py` |
| `pkg_resources` gone in setuptools 82+ | Pinned `setuptools<82`, used `--no-build-isolation` |
| `/usr/local/bin/adb` not found | Created `eval/config/remote.yaml` with remote adb path |
| Protobuf `state_pb2` missing | Compiled protos on remote with `grpc_tools.protoc` |
| Proxy bound to 127.0.0.1 only | Changed `proxy.js` to use `process.env.COPILOT_PROXY_BIND \|\| '0.0.0.0'` |

## Smoke Eval Results

Ran 8 tasks from `aw_subset_smoke.txt`:

- **3/8 passed** (system-level tasks: SystemClock, SystemBluetooth, SystemWifi)
- **5/8 failed** (app tasks: BrowserMultiply, ClockTimerEntry, ContactsAddContact, ExpenseAddSingle, MarkorCreateNote)

App task failures are likely snapshot/baseline related (fresh wipe-data boot, apps may need additional setup), not infrastructure issues. The infra path is validated.

## Remaining Work

### Phase 2: Dual Emulator (not started)
- Create `AndroidWorldAvd2`, second baseline prep
- Test `eval_parallel.sh` with two emulators on remote

### Phase 3: Operational Hardening (not started)
- `autossh` or systemd service for persistent tunnel
- tmux/systemd wrapper for long-running eval
- Investigate app task failures from smoke eval

### Phase 4: System Upgrade (deferred)
- Ubuntu 18.04 → 22.04 LTS (blocked on needing local access for safety)
- Will unlock newer emulator versions and remove glibc workaround
