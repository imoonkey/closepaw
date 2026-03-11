#!/usr/bin/env bash
# Manage the OpenAI proxy SSH tunnel on the remote eval worker.
#
# Usage:
#   ./scripts/remote/proxy_tunnel.sh install   # install systemd user service
#   ./scripts/remote/proxy_tunnel.sh start     # start tunnel
#   ./scripts/remote/proxy_tunnel.sh stop      # stop tunnel
#   ./scripts/remote/proxy_tunnel.sh status    # check status
#   ./scripts/remote/proxy_tunnel.sh logs      # show recent logs
#   ./scripts/remote/proxy_tunnel.sh manual [laptop-ip]  # run in foreground (no systemd)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_NAME="openai-proxy-tunnel"
SERVICE_FILE="${SCRIPT_DIR}/${SERVICE_NAME}.service"
USER_SERVICE_DIR="${HOME}/.config/systemd/user"

LAPTOP_IP="${LAPTOP_IP:-100.95.23.122}"
PROXY_PORT="${PROXY_PORT:-18080}"

usage() {
  sed -n '3,9s/^# //p' "$0"
  exit 1
}

cmd_install() {
  if ! command -v autossh >/dev/null 2>&1; then
    echo "[tunnel] autossh not found. Install: sudo apt-get install -y autossh"
    exit 1
  fi
  mkdir -p "${USER_SERVICE_DIR}"
  cp "${SERVICE_FILE}" "${USER_SERVICE_DIR}/${SERVICE_NAME}.service"
  systemctl --user daemon-reload
  systemctl --user enable "${SERVICE_NAME}"
  echo "[tunnel] Service installed and enabled."
  echo "[tunnel] Start with: $0 start"
}

cmd_start() {
  systemctl --user start "${SERVICE_NAME}"
  echo "[tunnel] Started. Check: $0 status"
}

cmd_stop() {
  systemctl --user stop "${SERVICE_NAME}"
  echo "[tunnel] Stopped."
}

cmd_status() {
  systemctl --user status "${SERVICE_NAME}" --no-pager || true
  echo ""
  echo "[tunnel] Health check:"
  if curl -sS --max-time 3 "http://127.0.0.1:${PROXY_PORT}/" 2>/dev/null; then
    echo ""
    echo "[tunnel] Proxy reachable."
  else
    echo "[tunnel] Proxy NOT reachable on port ${PROXY_PORT}."
  fi
}

cmd_logs() {
  journalctl --user -u "${SERVICE_NAME}" --no-pager -n 50
}

cmd_manual() {
  local ip="${1:-${LAPTOP_IP}}"
  echo "[tunnel] Forwarding localhost:${PROXY_PORT} -> ${ip}:${PROXY_PORT}"
  echo "[tunnel] Press Ctrl-C to stop."
  exec autossh -M 0 -N \
    -o "ServerAliveInterval 30" \
    -o "ServerAliveCountMax 3" \
    -o "ExitOnForwardFailure yes" \
    -L "${PROXY_PORT}:127.0.0.1:${PROXY_PORT}" "moonkey@${ip}"
}

case "${1:-}" in
  install) cmd_install ;;
  start)   cmd_start ;;
  stop)    cmd_stop ;;
  status)  cmd_status ;;
  logs)    cmd_logs ;;
  manual)  cmd_manual "${2:-}" ;;
  *)       usage ;;
esac
