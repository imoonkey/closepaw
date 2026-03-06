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

usage() {
  cat <<'EOF'
Usage:
  ./scripts/prepare_baseline.sh [options] [-- <extra prepare_baseline.py args>]

Prepares one AndroidWorld AVD from a clean wipe-data boot, then runs
`eval/aw_bridge/prepare_baseline.py` to install required apps and generate
baseline snapshots.

Options:
  --config PATH              Eval config path (default: eval/config/default.yaml)
  --avd NAME                 AVD name (default: AndroidWorldAvd)
  --console-port PORT        Emulator console port (default: 5554)
  --grpc-port PORT           Emulator gRPC port (default: 8554)
  --adb-serial SERIAL        ADB serial (default: emulator-<console-port>)
  --emulator-bin PATH        Emulator binary path
  --snapshot-policy POLICY   Snapshot policy for prepare_baseline.py
  -h, --help                 Show this help

Examples:
  ./scripts/prepare_baseline.sh --avd AndroidWorldAvd --console-port 5554 --grpc-port 8554 --adb-serial emulator-5554
  ./scripts/prepare_baseline.sh --avd AndroidWorldAvd2 --console-port 5556 --grpc-port 8556 --adb-serial emulator-5556
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0 ;;
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

if ! command -v adb >/dev/null 2>&1; then
  echo "Cannot find adb in PATH." >&2
  exit 1
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

if [[ ! -x "${EMULATOR_BIN}" ]]; then
  echo "Emulator binary is not executable: ${EMULATOR_BIN}" >&2
  exit 1
fi

if ! "${EMULATOR_BIN}" -list-avds | grep -Fxq "${AVD_NAME}"; then
  echo "AVD does not exist: ${AVD_NAME}" >&2
  echo "Available AVDs:" >&2
  "${EMULATOR_BIN}" -list-avds >&2
  exit 1
fi

adb start-server >/dev/null

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
boot=""
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
PREPARE_CMD=(
  python3
  eval/aw_bridge/prepare_baseline.py
  --config "${CONFIG}"
  --adb-serial "${ADB_SERIAL}"
  --console-port "${CONSOLE_PORT}"
  --grpc-port "${GRPC_PORT}"
  --snapshot-policy "${SNAPSHOT_POLICY}"
)

if [[ ${#EXTRA_ARGS[@]} -gt 0 ]]; then
  PREPARE_CMD+=(-- "${EXTRA_ARGS[@]}")
fi

"${PREPARE_CMD[@]}"
