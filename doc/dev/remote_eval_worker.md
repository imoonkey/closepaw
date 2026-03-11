# Remote Eval Worker: qiguo-ld1

Remote eval worker running on `qiguo-ld1` (Ubuntu 18.04, i9-7900X, 62G RAM).

## Prerequisites (already set up)

- JDK 17: `/usr/lib/jvm/java-17-openjdk-amd64`
- Python 3.11: `python3.11` (deadsnakes PPA)
- Android SDK: `~/android-sdk` (provisioned with pinned emulator 32.1.15 for glibc 2.27 compat)
- AVD: `AndroidWorldAvd` (Pixel 6, API 33, x86_64)
- Repo: `~/androidagent`
- Eval venv: `~/androidagent/eval/.venv`
- Env profile: `~/.android-agent-env` (sourced from `.bashrc`)

## Running Eval

### 1. Start SSH tunnel for LLM proxy

From the laptop:
```bash
ssh -f -N -R 18080:127.0.0.1:18080 qiguo@qiguo-ld1
```

This makes `localhost:18080` on ld1 forward to the laptop's proxy.

Verify on `qiguo-ld1` before running eval:
```bash
curl -sS --max-time 3 http://127.0.0.1:18080/
```

Expected response:
```json
{"status":"ok"}
```

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
- The tunnel will drop if the laptop sleeps — re-establish before eval runs
