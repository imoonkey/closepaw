#!/usr/bin/env bash
# Run eval inside a tmux session so it survives SSH disconnects.
# Run on the remote eval worker (qiguo-ld1).
#
# Usage:
#   ./scripts/remote/eval_tmux.sh --tasks-file eval/config/autotune_round_N.txt
#   ./scripts/remote/eval_tmux.sh --parallel --tasks-file eval/config/<task_file>
#
# Detach:   Ctrl-b d
# Reattach: tmux attach -t eval
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SESSION_NAME="eval"
PARALLEL=0
CONFIG="eval/config/remote.yaml"
EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --parallel) PARALLEL=1; shift ;;
    --config) CONFIG="$2"; shift 2 ;;
    --session) SESSION_NAME="$2"; shift 2 ;;
    *) EXTRA_ARGS+=("$1"); shift ;;
  esac
done

if tmux has-session -t "${SESSION_NAME}" 2>/dev/null; then
  echo "[eval-tmux] Session '${SESSION_NAME}' already exists. Attaching..."
  exec tmux attach -t "${SESSION_NAME}"
fi

if [[ "${PARALLEL}" -eq 1 ]]; then
  CMD="cd ${ROOT_DIR} && ./scripts/eval_parallel.sh --headless --config ${CONFIG} ${EXTRA_ARGS[*]}"
else
  CMD="cd ${ROOT_DIR} && eval/.venv/bin/python eval/aw_bridge/runner.py --config ${CONFIG} ${EXTRA_ARGS[*]}"
fi

echo "[eval-tmux] Starting session '${SESSION_NAME}'..."
echo "[eval-tmux] Command: ${CMD}"
echo "[eval-tmux] Detach with Ctrl-b d, reattach with: tmux attach -t ${SESSION_NAME}"

tmux new-session -d -s "${SESSION_NAME}" "${CMD}"
exec tmux attach -t "${SESSION_NAME}"
