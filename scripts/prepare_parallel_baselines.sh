#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PREP_SCRIPT="${ROOT_DIR}/scripts/prepare_baseline.sh"

CONFIG="eval/config/default.yaml"
SNAPSHOT_POLICY="auto_repair"

AVD_A="AndroidWorldAvd"
CONSOLE_PORT_A="5554"
GRPC_PORT_A="8554"
ADB_SERIAL_A=""

AVD_B="AndroidWorldAvd2"
CONSOLE_PORT_B="5556"
GRPC_PORT_B="8556"
ADB_SERIAL_B=""

EMULATOR_BIN="${EMULATOR_BIN:-}"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/prepare_parallel_baselines.sh [options]

Prepares the supported local parallel eval pair sequentially:
  - Device A: AndroidWorldAvd on emulator-5554 / gRPC 8554
  - Device B: AndroidWorldAvd2 on emulator-5556 / gRPC 8556

This is the one-time setup path before running `./scripts/eval_parallel.sh`.

Options:
  --config PATH                 Eval config path (default: eval/config/default.yaml)
  --snapshot-policy POLICY      Snapshot policy for prepare_baseline.py
  --avd-a NAME                  AVD name for device A
  --console-port-a PORT         Console port for device A
  --grpc-port-a PORT            gRPC port for device A
  --adb-serial-a SERIAL         ADB serial for device A (default: emulator-<console-port-a>)
  --avd-b NAME                  AVD name for device B
  --console-port-b PORT         Console port for device B
  --grpc-port-b PORT            gRPC port for device B
  --adb-serial-b SERIAL         ADB serial for device B (default: emulator-<console-port-b>)
  --emulator-bin PATH           Emulator binary path passed through to both runs
  -h, --help                    Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0 ;;
    --config)
      CONFIG="$2"; shift 2 ;;
    --snapshot-policy)
      SNAPSHOT_POLICY="$2"; shift 2 ;;
    --avd-a)
      AVD_A="$2"; shift 2 ;;
    --console-port-a)
      CONSOLE_PORT_A="$2"; shift 2 ;;
    --grpc-port-a)
      GRPC_PORT_A="$2"; shift 2 ;;
    --adb-serial-a)
      ADB_SERIAL_A="$2"; shift 2 ;;
    --avd-b)
      AVD_B="$2"; shift 2 ;;
    --console-port-b)
      CONSOLE_PORT_B="$2"; shift 2 ;;
    --grpc-port-b)
      GRPC_PORT_B="$2"; shift 2 ;;
    --adb-serial-b)
      ADB_SERIAL_B="$2"; shift 2 ;;
    --emulator-bin)
      EMULATOR_BIN="$2"; shift 2 ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1 ;;
  esac
done

if [[ -z "${ADB_SERIAL_A}" ]]; then
  ADB_SERIAL_A="emulator-${CONSOLE_PORT_A}"
fi

if [[ -z "${ADB_SERIAL_B}" ]]; then
  ADB_SERIAL_B="emulator-${CONSOLE_PORT_B}"
fi

COMMON_ARGS=(
  --config "${CONFIG}"
  --snapshot-policy "${SNAPSHOT_POLICY}"
)

if [[ -n "${EMULATOR_BIN}" ]]; then
  COMMON_ARGS+=(--emulator-bin "${EMULATOR_BIN}")
fi

echo "[parallel-baseline] Preparing device A: ${AVD_A} (${ADB_SERIAL_A})"
"${PREP_SCRIPT}" \
  "${COMMON_ARGS[@]}" \
  --avd "${AVD_A}" \
  --console-port "${CONSOLE_PORT_A}" \
  --grpc-port "${GRPC_PORT_A}" \
  --adb-serial "${ADB_SERIAL_A}"

echo "[parallel-baseline] Preparing device B: ${AVD_B} (${ADB_SERIAL_B})"
"${PREP_SCRIPT}" \
  "${COMMON_ARGS[@]}" \
  --avd "${AVD_B}" \
  --console-port "${CONSOLE_PORT_B}" \
  --grpc-port "${GRPC_PORT_B}" \
  --adb-serial "${ADB_SERIAL_B}"

echo "[parallel-baseline] Both baseline-prepared emulators are ready for ./scripts/eval_parallel.sh"
