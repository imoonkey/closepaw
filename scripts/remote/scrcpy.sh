#!/usr/bin/env bash
# Mirror remote emulator screen locally via scrcpy over SSH tunnel.
# Tunnels: remote adb server (5037) + scrcpy data ports (27183-27184).
set -euo pipefail

REMOTE="qiguo@desktop"
LOCAL_ADB_PORT=15037
REMOTE_ADB_PORT=5037
SCRCPY_VIDEO_PORT=27183
SCRCPY_CTRL_PORT=27184
DEVICE="emulator-5554"

# Kill stale tunnels
for port in $LOCAL_ADB_PORT $SCRCPY_VIDEO_PORT $SCRCPY_CTRL_PORT; do
  pid=$(lsof -ti "tcp:$port" 2>/dev/null || true)
  if [[ -n "$pid" ]]; then
    echo "==> Killing stale process on port $port (pid $pid)"
    kill "$pid" 2>/dev/null || true
  fi
done
sleep 1

echo "==> SSH tunnel: adb(:$LOCAL_ADB_PORT) + scrcpy(:$SCRCPY_VIDEO_PORT,:$SCRCPY_CTRL_PORT)"
ssh -L "$LOCAL_ADB_PORT:localhost:$REMOTE_ADB_PORT" \
    -L "$SCRCPY_VIDEO_PORT:localhost:$SCRCPY_VIDEO_PORT" \
    -L "$SCRCPY_CTRL_PORT:localhost:$SCRCPY_CTRL_PORT" \
    "$REMOTE" -N &
SSH_PID=$!
trap "kill $SSH_PID 2>/dev/null" EXIT
sleep 2

echo "==> Starting scrcpy (device: $DEVICE)"
ADB_SERVER_SOCKET="tcp:localhost:$LOCAL_ADB_PORT" \
  scrcpy -s "$DEVICE" --force-adb-forward \
    --port "$SCRCPY_VIDEO_PORT:$SCRCPY_CTRL_PORT" \
    --max-fps 15 --video-bit-rate 2M
