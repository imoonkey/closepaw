# Remote Eval Worker: qiguo-ld1

Remote eval worker running on `qiguo-ld1` (Ubuntu 18.04, i9-7900X, 62G RAM).

## Prerequisites (already set up)

- JDK 17: `/usr/lib/jvm/java-17-openjdk-amd64`
- Python 3.11: `python3.11` (deadsnakes PPA)
- Android SDK: `~/android-sdk` (emulator 32.1.15 for glibc 2.27 compat)
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

### 2. SSH into the remote

```bash
ssh qiguo@qiguo-ld1
cd ~/androidagent
```

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

### 4. Check results

```bash
ls eval/results/
cat eval/results/<timestamp>/summary.json
```

## Config

- `eval/config/remote.yaml` — remote-specific config (adb_path, etc.)
- `~/.env` — API keys + `OPENAI_BASE_URL=http://localhost:18080/v1`

## Notes

- Emulator 32.1.15 is used (not latest) due to glibc 2.27 on Ubuntu 18.04
- The `--headless` flag adds `-no-window -no-audio` to emulator
- SSH tunnel must be active for `gpt-*` models (OpenRouter models work without it)
- The tunnel will drop if the laptop sleeps — re-establish before eval runs
