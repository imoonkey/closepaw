#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

CONFIG="eval/config/default.yaml"
AVD_NAME="AndroidWorldAvd"
CONSOLE_PORT="5554"
GRPC_PORT="8554"
ADB_SERIAL=""
EMULATOR_BIN="${EMULATOR_BIN:-}"
SNAPSHOT_POLICY="auto_repair"

EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --config)
      CONFIG="$2"; shift 2 ;;
    --avd)
      AVD_NAME="$2"; shift 2 ;;
    --console-port)
      CONSOLE_PORT="$2"; shift 2 ;;
    --grpc-port)
      GRPC_PORT="$2"; shift 2 ;;
    --adb-serial)
      ADB_SERIAL="$2"; shift 2 ;;
    --emulator-bin)
      EMULATOR_BIN="$2"; shift 2 ;;
    --snapshot-policy)
      SNAPSHOT_POLICY="$2"; shift 2 ;;
    *)
      EXTRA_ARGS+=("$1")
      shift ;;
  esac
done

if [[ -z "${ADB_SERIAL}" ]]; then
  ADB_SERIAL="emulator-${CONSOLE_PORT}"
fi

if [[ -z "${EMULATOR_BIN}" ]]; then
  if command -v emulator >/dev/null 2>&1; then
    EMULATOR_BIN="$(command -v emulator)"
  elif [[ -x "${HOME}/Library/Android/sdk/emulator/emulator" ]]; then
    EMULATOR_BIN="${HOME}/Library/Android/sdk/emulator/emulator"
  elif [[ -x "${HOME}/Android/Sdk/emulator/emulator" ]]; then
    EMULATOR_BIN="${HOME}/Android/Sdk/emulator/emulator"
  else
    echo "Cannot find emulator binary. Use --emulator-bin." >&2
    exit 1
  fi
fi

echo "[baseline] Killing existing emulator ${ADB_SERIAL} (if running)"
adb -s "${ADB_SERIAL}" emu kill >/dev/null 2>&1 || true
sleep 2

echo "[baseline] Starting clean emulator: avd=${AVD_NAME} port=${CONSOLE_PORT} grpc=${GRPC_PORT}"
"${EMULATOR_BIN}" \
  -avd "${AVD_NAME}" \
  -port "${CONSOLE_PORT}" \
  -grpc "${GRPC_PORT}" \
  -no-snapshot \
  -wipe-data \
  -no-boot-anim \
  >/dev/null 2>&1 &

echo "[baseline] Waiting for adb device ${ADB_SERIAL}"
adb -s "${ADB_SERIAL}" wait-for-device

echo "[baseline] Waiting for sys.boot_completed"
deadline=$((SECONDS + 240))
while [[ $SECONDS -lt $deadline ]]; do
  boot="$(adb -s "${ADB_SERIAL}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | tr -d '\n')"
  if [[ "${boot}" == "1" ]]; then
    break
  fi
  sleep 2
done

if [[ "${boot:-}" != "1" ]]; then
  echo "Emulator did not report sys.boot_completed=1 within timeout" >&2
  exit 1
fi

echo "[baseline] Running prepare_baseline.py"
cd "${ROOT_DIR}"
python3 eval/aw_bridge/prepare_baseline.py \
  --config "${CONFIG}" \
  --adb-serial "${ADB_SERIAL}" \
  --console-port "${CONSOLE_PORT}" \
  --grpc-port "${GRPC_PORT}" \
  --snapshot-policy "${SNAPSHOT_POLICY}" \
  "${EXTRA_ARGS[@]}"

