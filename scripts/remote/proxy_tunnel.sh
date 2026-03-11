#!/usr/bin/env bash
# Start an SSH tunnel from this machine to the laptop's OpenAI proxy.
# Run on the remote eval worker (qiguo-ld1).
#
# The emulator's 10.0.2.2 maps to the host's localhost.
# This tunnel makes localhost:18080 forward to the laptop's proxy.
#
# Usage: bash proxy_tunnel.sh [laptop-tailscale-ip]
set -euo pipefail

LAPTOP_IP="${1:-100.95.23.122}"
PROXY_PORT="${2:-18080}"

echo "[tunnel] Forwarding localhost:${PROXY_PORT} -> ${LAPTOP_IP}:${PROXY_PORT}"
echo "[tunnel] Press Ctrl-C to stop."

exec ssh -N -L "${PROXY_PORT}:127.0.0.1:${PROXY_PORT}" "moonkey@${LAPTOP_IP}"
