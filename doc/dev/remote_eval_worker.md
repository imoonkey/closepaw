# Remote Eval Worker: qiguo-ld1

Remote eval worker running on `qiguo-ld1` (Ubuntu 18.04, i9-7900X, 62G RAM).

## Prerequisites (already set up)

- JDK 17: `/usr/lib/jvm/java-17-openjdk-amd64`
- Python 3.11: `python3.11` (deadsnakes PPA)
- Android SDK: `~/android-sdk` (provisioned with pinned emulator 32.1.15 for glibc 2.27 compat)
- AVD: `AndroidWorldAvd`, `AndroidWorldAvd2` (Pixel 6, API 33, x86_64)
- Repo: `~/androidagent`
- Eval venv: `~/androidagent/eval/.venv`
- Env profile: `~/.android-agent-env` (sourced from `.bashrc`)

## Running Eval

### 1. Ensure LLM proxy tunnel (gpt-* models only)

The autossh service manages the tunnel automatically (see [Tunnel Management](#tunnel-management)).
Skip this step for OpenRouter models — they don't need the proxy.

```bash
# On qiguo-ld1: check if tunnel is running
./scripts/remote/proxy_tunnel.sh status

# If not running:
./scripts/remote/proxy_tunnel.sh start
```

Verify proxy is reachable:
```bash
curl -sS --max-time 3 http://127.0.0.1:18080/
```

Expected: `{"status":"ok"}`

### 2. Sync code

Push local changes and pull on the remote before every eval run:

```bash
# On laptop
git push

# On remote
ssh qiguo@qiguo-ld1
cd ~/androidagent
git pull
./gradlew assembleDebug  # rebuild APK if code changed
```

A stale checkout is a silent failure mode — the eval will run but produce wrong results (see Notes).

### 3. Run smoke eval (single emulator)

```bash
# Source env (should be automatic via .bashrc)
source ~/.android-agent-env

# Prepare baseline (only needed once per wipe)
./scripts/prepare_baseline.sh --headless --config eval/config/remote.yaml

# Run eval
eval/.venv/bin/python eval/aw_bridge/runner.py \
  --config eval/config/remote.yaml \
  --tasks-file eval/config/aw_subset_smoke.txt
```

### 4. Run full eval (single emulator)

```bash
eval/.venv/bin/python eval/aw_bridge/runner.py \
  --config eval/config/remote.yaml \
  --tasks-file eval/config/autotune_round_N.txt
```

### 5. Check results

```bash
ls eval/results/
cat eval/results/<timestamp>/summary.json
```

## Dual-Emulator Parallel Eval

### One-time setup

```bash
# Baseline prep for both AVDs
./scripts/prepare_baseline.sh --headless --config eval/config/remote.yaml
./scripts/prepare_baseline.sh --headless --config eval/config/remote.yaml \
  --avd AndroidWorldAvd2 --console-port 5556 --grpc-port 8556 --adb-serial emulator-5556
```

### Running parallel eval

```bash
./scripts/eval_parallel.sh --headless --config eval/config/remote.yaml \
  --tasks-file eval/config/<task_file>
```

Same device layout as local: `AndroidWorldAvd` on `emulator-5554` and `AndroidWorldAvd2` on `emulator-5556`.

## Long-Running Eval (tmux)

Use the tmux wrapper to survive SSH disconnects:

```bash
# Start eval in a tmux session
./scripts/remote/eval_tmux.sh --tasks-file eval/config/<task_file>

# Detach: Ctrl-b d
# Reattach later: tmux attach -t eval
# Check if running: tmux has-session -t eval 2>/dev/null && echo "running" || echo "no session"
```

## Tunnel Management

The proxy tunnel can be managed as a systemd user service (auto-reconnects via autossh):

```bash
# Install service (one-time)
./scripts/remote/proxy_tunnel.sh install

# Start/stop/status
./scripts/remote/proxy_tunnel.sh start
./scripts/remote/proxy_tunnel.sh stop
./scripts/remote/proxy_tunnel.sh status

# View logs
./scripts/remote/proxy_tunnel.sh logs
```

Or start manually (foreground):
```bash
./scripts/remote/proxy_tunnel.sh manual [laptop-tailscale-ip]
```

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Tunnel dropped | `./scripts/remote/proxy_tunnel.sh status` then `start` |
| Emulator hung | `adb -s emulator-5554 emu kill`, then re-run |
| Stale checkout | `git pull && ./gradlew assembleDebug` |
| Second emulator won't start | Check `emulator -list-avds` shows `AndroidWorldAvd2` |
| Out of disk | `du -sh ~/android-sdk ~/.android` — AVD snapshots can be large |

## Config

- `eval/config/remote.yaml` — remote-specific config (adb_path, etc.)
- `~/.env` — API keys + `OPENAI_BASE_URL=http://localhost:18080/v1`

## Notes

- `scripts/remote/provision.sh` installs emulator `32.1.15` from `emulator-linux_x64-10696886.zip`; do not replace it with the latest `sdkmanager` emulator on Ubuntu 18.04
- `runner.py` now expands `android_world.adb_path`, so `~/android-sdk/platform-tools/adb` works directly from `eval/config/remote.yaml`
- `runner.py` now fails fast when `OPENAI_BASE_URL` is configured but unreachable from the eval host, so a missing proxy tunnel surfaces as infra setup failure instead of fake task regressions
- Keep the remote checkout synced with current `main` before reruns. A stale `eval/aw_bridge/native_agent_bridge.py` forwards `localhost` unchanged, which makes app tasks fail on turn 1 inside the emulator instead of reaching the host proxy via `10.0.2.2`
- Validation rerun `20260311_102822` passed all five previously failing app tasks after syncing the bridge fix
- Emulator 32.1.15 is used (not latest) due to glibc 2.27 on Ubuntu 18.04
- The `--headless` flag adds `-no-window -no-audio` to emulator
- SSH tunnel must be active for `gpt-*` models (OpenRouter models work without it)
- The autossh service auto-reconnects if the tunnel drops; requires laptop cproxy to be running
