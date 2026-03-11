# Remote Emulator Eval — Implementation Summary

Date: 2026-03-11
Branch: `task/remote-emulator` (commit `947fee6`)
Design doc: `remote_emulator_eval_codex.md` (this folder)

## What Was Done

Phase 1 of the migration plan is complete: `qiguo-ld1` can run single-emulator eval end-to-end.

### Files Changed

**New files:**
- `eval/config/remote.yaml` — remote-specific eval config (`adb_path: ~/android-sdk/platform-tools/adb`, expanded by `runner.py`)
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
| glibc 2.27 vs emulator requiring 2.30 | Pinned emulator 32.1.15 via `emulator-linux_x64-10696886.zip` instead of latest `sdkmanager` emulator |
| `python3` → system Python 3.6 | `eval/.venv/bin/python` with `--without-pip` bootstrap via `get-pip.py` |
| `pkg_resources` gone in setuptools 82+ | Pinned `setuptools<82`, used `--no-build-isolation` |
| `/usr/local/bin/adb` not found | Created `eval/config/remote.yaml` with remote adb path and expanded it in `runner.py` |
| Protobuf `state_pb2` missing | Compiled protos on remote with `grpc_tools.protoc` |
| Proxy bound to 127.0.0.1 only | Changed `proxy.js` to use `process.env.COPILOT_PROXY_BIND \|\| '0.0.0.0'` |

## Smoke Eval Results

Ran 8 tasks from `aw_subset_smoke.txt`:

- **3/8 passed** (system-level tasks: SystemClock, SystemBluetooth, SystemWifi)
- **5/8 failed** (app tasks: BrowserMultiply, ClockTimerEntry, ContactsAddContact, ExpenseAddSingle, MarkorCreateNote)

Follow-up trace inspection showed the app-task failures were not task regressions. All five stopped on turn 1 with `OpenAIIoException` because `OPENAI_BASE_URL=http://localhost:18080/v1` was configured but the remote proxy tunnel was not listening on `qiguo-ld1:18080`. This should be treated as infra setup failure, not a benchmark capability gap.

That first diagnosis turned out to be incomplete. The remote worker was still running a stale checkout where `eval/aw_bridge/native_agent_bridge.py` forwarded `OPENAI_BASE_URL=http://localhost:18080/v1` unchanged into the emulator. That made the app try `localhost:18080` from inside Android instead of the host alias `10.0.2.2:18080`, so all five app tasks died on turn 1 with `OpenAIIoException`.

### Follow-up Validation Rerun

After syncing the remote worker to the current bridge/runner code, reran the five failed app tasks as `eval/results/20260311_102822`:

- **5/5 passed**
- `BrowserMultiply` — success
- `ClockTimerEntry` — success
- `ContactsAddContact` — success
- `ExpenseAddSingle` — success
- `MarkorCreateNote` — success

Metrics:
- `scripted_success_rate`: `1.0`
- `infra_failure_rate`: `0.0`
- `goal_claim_precision`: `1.0`

One nuance remained in agent behavior: `ExpenseAddSingle` scored `1.0`, but the agent text still reported it could not visually verify the saved category. That is a task-level verification-quality issue, not a remote worker infra blocker.

## Remaining Work

### Phase 2: Dual Emulator (scripts ready, needs remote testing)
- `provision.sh` now creates both `AndroidWorldAvd` and `AndroidWorldAvd2`
- Baseline prep: run `prepare_baseline.sh` twice with different ports
- Parallel eval: `eval_parallel.sh --headless --config eval/config/remote.yaml`
- **Needs**: run on `qiguo-ld1` to verify dual-emulator boot and parallel eval
- Design: `doc/todo/remote_emulator/phase23/phase2_claude.md`

### Phase 3: Operational Hardening (scripts ready, needs remote testing)
- `scripts/remote/eval_tmux.sh` — tmux wrapper for long-running eval
- `scripts/remote/openai-proxy-tunnel.service` — systemd user unit with autossh
- `scripts/remote/proxy_tunnel.sh` — service manager (install/start/stop/status/logs/manual)
- `provision.sh` now installs `autossh` and `tmux`
- `doc/dev/remote_eval_worker.md` — extended with dual-emulator, tmux, tunnel management, troubleshooting sections
- **Needs**: install autossh on `qiguo-ld1`, test service lifecycle and tmux wrapper
- Design: `doc/todo/remote_emulator/phase23/phase3_claude.md`

### Phase 4: System Upgrade (deferred)
- Ubuntu 18.04 → 22.04 LTS (blocked on needing local access for safety)
- Will unlock newer emulator versions and remove glibc workaround
