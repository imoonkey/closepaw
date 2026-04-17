#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="${ROOT_DIR}/eval/.venv/bin/python"

CONFIG="eval/config/default.yaml"
OUTPUT_ROOT="eval/results"
TASKS_FILE=""
TASKS=""
SUITE=""
N_TASK_COMBINATIONS=""
TASK_RANDOM_SEED=""

AVD_A="AndroidWorldAvd"
CONSOLE_PORT_A="5554"
GRPC_PORT_A="8554"
ADB_SERIAL_A=""

AVD_B="AndroidWorldAvd2"
CONSOLE_PORT_B="5556"
GRPC_PORT_B="8556"
ADB_SERIAL_B=""

EMULATOR_BIN="${EMULATOR_BIN:-}"
HEADLESS="${HEADLESS:-}"
POSITIONAL_ARGS=()

usage() {
  cat <<'EOF'
Usage:
  ./scripts/eval_parallel.sh [options] <tasks-file>
  ./scripts/eval_parallel.sh [options] --tasks-file eval/config/aw_subset_smoke.txt
  ./scripts/eval_parallel.sh [options] --tasks "TaskA,TaskB"

Runs eval/aw_bridge/parallel_runner.py against two fixed AndroidWorld emulators.
This script starts missing emulators if needed, then launches the parallel eval.

Preconditions:
  - Both AVDs have already been baseline-prepared with scripts/prepare_baseline.sh.
  - The eval Python virtualenv exists at eval/.venv/.

Defaults:
  Device A: AndroidWorldAvd  -> emulator-5554 (console 5554, grpc 8554)
  Device B: AndroidWorldAvd2 -> emulator-5556 (console 5556, grpc 8556)

Options:
  --config PATH                 Base eval config (default: eval/config/default.yaml)
  --output-root PATH            Output root (default: eval/results)
  --tasks-file PATH             Task list file (repo-relative or absolute)
  --tasks CSV                   Comma-separated task names
  --suite NAME                  Optional suite override
  --n-task-combinations N       Optional task combination override
  --task-random-seed N          Optional task seed override
  --avd-a NAME                  AVD name for device A
  --console-port-a PORT         Console port for device A
  --grpc-port-a PORT            gRPC port for device A
  --adb-serial-a SERIAL         ADB serial for device A (default: emulator-<console-port-a>)
  --avd-b NAME                  AVD name for device B
  --console-port-b PORT         Console port for device B
  --grpc-port-b PORT            gRPC port for device B
  --adb-serial-b SERIAL         ADB serial for device B (default: emulator-<console-port-b>)
  --emulator-bin PATH           Emulator binary (otherwise autodetected or EMULATOR_BIN)
  --headless                    Run emulators headless (-no-window -no-audio)
  -h, --help                    Show this help

Examples:
  ./scripts/eval_parallel.sh eval/config/autotune_round_3.txt
  ./scripts/eval_parallel.sh --tasks "BrowserDraw,FilesMoveFile"
EOF
}

resolve_emulator_bin() {
  if [[ -n "${EMULATOR_BIN}" ]]; then
    if [[ ! -x "${EMULATOR_BIN}" ]]; then
      echo "Emulator binary is not executable: ${EMULATOR_BIN}" >&2
      exit 1
    fi
    return
  fi

  if command -v emulator >/dev/null 2>&1; then
    EMULATOR_BIN="$(command -v emulator)"
  elif [[ -x "${HOME}/Library/Android/sdk/emulator/emulator" ]]; then
    EMULATOR_BIN="${HOME}/Library/Android/sdk/emulator/emulator"
  elif [[ -x "${HOME}/Android/Sdk/emulator/emulator" ]]; then
    EMULATOR_BIN="${HOME}/Android/Sdk/emulator/emulator"
  elif [[ -x "${HOME}/android-sdk/emulator/emulator" ]]; then
    EMULATOR_BIN="${HOME}/android-sdk/emulator/emulator"
  else
    echo "Cannot find emulator binary. Use --emulator-bin or set EMULATOR_BIN." >&2
    exit 1
  fi
}

list_avds() {
  resolve_emulator_bin
  "${EMULATOR_BIN}" -list-avds
}

ensure_avd_exists() {
  local avd_name="$1"
  if ! list_avds | grep -Fxq "${avd_name}"; then
    echo "AVD does not exist: ${avd_name}" >&2
    echo "Available AVDs:" >&2
    list_avds >&2
    exit 1
  fi
}

adb_device_state() {
  local serial="$1"
  adb devices | awk -v serial="${serial}" '$1 == serial { print $2 }'
}

adb_device_online() {
  local serial="$1"
  adb devices | awk '$2 == "device" { print $1 }' | grep -Fxq "${serial}"
}

running_avd_name() {
  local serial="$1"
  local raw=""
  raw="$(adb -s "${serial}" emu avd name 2>/dev/null || true)"
  printf '%s\n' "${raw}" | tr -d '\r' | awk 'NF && $0 != "OK" { print; exit }'
}

find_running_serial_for_avd() {
  local wanted_avd="$1"
  local serial=""
  local current_avd=""

  while read -r serial; do
    [[ -n "${serial}" ]] || continue
    current_avd="$(running_avd_name "${serial}")"
    if [[ "${current_avd}" == "${wanted_avd}" ]]; then
      echo "${serial}"
      return 0
    fi
  done < <(adb devices | awk '$2 == "device" { print $1 }')

  return 1
}

validate_parallel_contract() {
  if [[ "${AVD_A}" == "${AVD_B}" ]]; then
    echo "Device A and device B must use different AVD names." >&2
    exit 1
  fi

  if [[ "${CONSOLE_PORT_A}" == "${CONSOLE_PORT_B}" ]]; then
    echo "Device A and device B must use different console ports." >&2
    exit 1
  fi

  if [[ "${GRPC_PORT_A}" == "${GRPC_PORT_B}" ]]; then
    echo "Device A and device B must use different gRPC ports." >&2
    exit 1
  fi

  if [[ "${ADB_SERIAL_A}" == "${ADB_SERIAL_B}" ]]; then
    echo "Device A and device B must use different adb serials." >&2
    exit 1
  fi

  ensure_avd_exists "${AVD_A}"
  ensure_avd_exists "${AVD_B}"
}

start_emulator_if_needed() {
  local label="$1"
  local avd_name="$2"
  local adb_serial="$3"
  local console_port="$4"
  local grpc_port="$5"
  local log_path="/tmp/closepaw_eval_parallel_${adb_serial}.log"
  local device_state=""
  local active_avd=""
  local other_serial=""

  device_state="$(adb_device_state "${adb_serial}")"

  if [[ -n "${device_state}" ]]; then
    if [[ "${device_state}" == "device" ]]; then
      active_avd="$(running_avd_name "${adb_serial}")"
      if [[ -n "${active_avd}" && "${active_avd}" != "${avd_name}" ]]; then
        echo "Expected ${adb_serial} to run ${avd_name}, but it is running ${active_avd}." >&2
        exit 1
      fi
      echo "[parallel-eval] Reusing ${label}: ${adb_serial}"
    else
      echo "[parallel-eval] Reusing ${label}: ${adb_serial} (current adb state: ${device_state})"
    fi
    return
  fi

  other_serial="$(find_running_serial_for_avd "${avd_name}" || true)"
  if [[ -n "${other_serial}" && "${other_serial}" != "${adb_serial}" ]]; then
    echo "AVD ${avd_name} is already running on ${other_serial}; refusing to start a second copy for ${adb_serial}." >&2
    echo "Stop the existing emulator or use distinct AVD names for the two parallel devices." >&2
    exit 1
  fi

  resolve_emulator_bin
  echo "[parallel-eval] Starting ${label}: avd=${avd_name} serial=${adb_serial} console=${console_port} grpc=${grpc_port}"

  local -a emu_args=(
    -avd "${avd_name}"
    -port "${console_port}"
    -grpc "${grpc_port}"
    -no-snapshot-load
    -no-snapshot-save
    -no-boot-anim
  )
  if [[ -n "${HEADLESS}" ]]; then
    emu_args+=(-no-window -no-audio)
  fi

  "${EMULATOR_BIN}" "${emu_args[@]}" >"${log_path}" 2>&1 &
  echo "[parallel-eval] Emulator log: ${log_path}"
}

wait_for_device_boot() {
  local label="$1"
  local adb_serial="$2"
  local boot=""
  local online_deadline=$((SECONDS + 120))
  local boot_deadline=$((SECONDS + 240))

  echo "[parallel-eval] Waiting for ${label} (${adb_serial})"

  while [[ ${SECONDS} -lt ${online_deadline} ]]; do
    if adb_device_online "${adb_serial}"; then
      break
    fi
    sleep 2
  done

  if ! adb_device_online "${adb_serial}"; then
    echo "Emulator ${adb_serial} did not appear in adb within timeout" >&2
    exit 1
  fi

  while [[ ${SECONDS} -lt ${boot_deadline} ]]; do
    boot="$(adb -s "${adb_serial}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')"
    if [[ "${boot}" == "1" ]]; then
      echo "[parallel-eval] ${label} boot completed"
      return
    fi
    sleep 2
  done

  echo "Emulator ${adb_serial} did not report sys.boot_completed=1 within timeout" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --config)
      CONFIG="$2"
      shift 2
      ;;
    --output-root)
      OUTPUT_ROOT="$2"
      shift 2
      ;;
    --tasks-file)
      TASKS_FILE="$2"
      shift 2
      ;;
    --tasks)
      TASKS="$2"
      shift 2
      ;;
    --suite)
      SUITE="$2"
      shift 2
      ;;
    --n-task-combinations)
      N_TASK_COMBINATIONS="$2"
      shift 2
      ;;
    --task-random-seed)
      TASK_RANDOM_SEED="$2"
      shift 2
      ;;
    --avd-a)
      AVD_A="$2"
      shift 2
      ;;
    --console-port-a)
      CONSOLE_PORT_A="$2"
      shift 2
      ;;
    --grpc-port-a)
      GRPC_PORT_A="$2"
      shift 2
      ;;
    --adb-serial-a)
      ADB_SERIAL_A="$2"
      shift 2
      ;;
    --avd-b)
      AVD_B="$2"
      shift 2
      ;;
    --console-port-b)
      CONSOLE_PORT_B="$2"
      shift 2
      ;;
    --grpc-port-b)
      GRPC_PORT_B="$2"
      shift 2
      ;;
    --adb-serial-b)
      ADB_SERIAL_B="$2"
      shift 2
      ;;
    --emulator-bin)
      EMULATOR_BIN="$2"
      shift 2
      ;;
    --headless)
      HEADLESS=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
    *)
      POSITIONAL_ARGS+=("$1")
      shift
      ;;
  esac
done

if [[ ${#POSITIONAL_ARGS[@]} -gt 1 ]]; then
  echo "Expected at most one positional tasks file." >&2
  usage >&2
  exit 1
fi

if [[ -z "${TASKS_FILE}" && ${#POSITIONAL_ARGS[@]} -eq 1 ]]; then
  TASKS_FILE="${POSITIONAL_ARGS[0]}"
fi

if [[ -n "${TASKS_FILE}" && -n "${TASKS}" ]]; then
  echo "Use either --tasks-file/<tasks-file> or --tasks, not both." >&2
  exit 1
fi

if [[ -z "${TASKS_FILE}" && -z "${TASKS}" ]]; then
  echo "Missing task selection. Provide <tasks-file>, --tasks-file, or --tasks." >&2
  usage >&2
  exit 1
fi

if [[ ! -x "${PYTHON_BIN}" ]]; then
  echo "Missing eval Python environment: ${PYTHON_BIN}" >&2
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "Cannot find adb in PATH." >&2
  exit 1
fi

if [[ -z "${ADB_SERIAL_A}" ]]; then
  ADB_SERIAL_A="emulator-${CONSOLE_PORT_A}"
fi
if [[ -z "${ADB_SERIAL_B}" ]]; then
  ADB_SERIAL_B="emulator-${CONSOLE_PORT_B}"
fi

adb start-server >/dev/null
validate_parallel_contract

start_emulator_if_needed "device A" "${AVD_A}" "${ADB_SERIAL_A}" "${CONSOLE_PORT_A}" "${GRPC_PORT_A}"
start_emulator_if_needed "device B" "${AVD_B}" "${ADB_SERIAL_B}" "${CONSOLE_PORT_B}" "${GRPC_PORT_B}"

wait_for_device_boot "device A" "${ADB_SERIAL_A}"
wait_for_device_boot "device B" "${ADB_SERIAL_B}"

RUNNER_CMD=(
  "${PYTHON_BIN}"
  "eval/aw_bridge/parallel_runner.py"
  "--config" "${CONFIG}"
  "--output-root" "${OUTPUT_ROOT}"
  "--device" "${ADB_SERIAL_A}:${CONSOLE_PORT_A}:${GRPC_PORT_A}"
  "--device" "${ADB_SERIAL_B}:${CONSOLE_PORT_B}:${GRPC_PORT_B}"
)

if [[ -n "${TASKS_FILE}" ]]; then
  RUNNER_CMD+=("--tasks-file" "${TASKS_FILE}")
else
  RUNNER_CMD+=("--tasks" "${TASKS}")
fi

if [[ -n "${SUITE}" ]]; then
  RUNNER_CMD+=("--suite" "${SUITE}")
fi
if [[ -n "${N_TASK_COMBINATIONS}" ]]; then
  RUNNER_CMD+=("--n-task-combinations" "${N_TASK_COMBINATIONS}")
fi
if [[ -n "${TASK_RANDOM_SEED}" ]]; then
  RUNNER_CMD+=("--task-random-seed" "${TASK_RANDOM_SEED}")
fi

echo "[parallel-eval] Launching parallel runner"
echo "[parallel-eval] ${RUNNER_CMD[*]}"

cd "${ROOT_DIR}"
"${RUNNER_CMD[@]}"
