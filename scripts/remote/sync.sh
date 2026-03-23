#!/usr/bin/env bash
# Sync local code to remote desktop: push, pull, rebuild, check proxy tunnel.
set -euo pipefail

REMOTE="qiguo@desktop"
REMOTE_DIR="~/androidagent"

echo "==> git push"
git push

echo "==> Remote: git pull + assembleDebug"
ssh "$REMOTE" "cd $REMOTE_DIR && git pull && ./gradlew assembleDebug"

echo "==> Remote: proxy tunnel status"
ssh "$REMOTE" "cd $REMOTE_DIR && ./scripts/remote/proxy_tunnel.sh status" || {
    echo "Tunnel not running. Starting..."
    ssh "$REMOTE" "cd $REMOTE_DIR && ./scripts/remote/proxy_tunnel.sh start"
}

echo "==> Sync complete"
