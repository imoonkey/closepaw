# Phase 3: Operational Hardening

## Goal

Make remote eval runs robust against SSH disconnects and tunnel drops. Three components:

1. **tmux wrapper** — eval survives SSH disconnect
2. **autossh + systemd** — proxy tunnel auto-reconnects
3. **runbook updates** — document the operational workflow

## Component 1: `scripts/remote/eval_tmux.sh`

tmux wrapper that runs eval inside a named session. If SSH disconnects, the eval keeps running.

```bash
# Start eval in tmux
./scripts/remote/eval_tmux.sh --tasks-file eval/config/autotune_round_N.txt

# Detach: Ctrl-b d
# Reattach later: tmux attach -t eval
```

Implementation:
- Check if tmux session `eval` exists; if so, attach to it
- Otherwise create new session, run the eval command inside it
- Forward all args to `eval_parallel.sh` or `runner.py` (detect based on available emulators)
- Default to `eval/config/remote.yaml` config

## Component 2: Proxy Tunnel Service

### `scripts/remote/openai-proxy-tunnel.service`

systemd user unit for the SSH tunnel:

```ini
[Unit]
Description=OpenAI proxy SSH tunnel to laptop
After=network-online.target

[Service]
Type=simple
ExecStart=/usr/bin/autossh -M 0 -N -o "ServerAliveInterval 30" -o "ServerAliveCountMax 3" -L 18080:127.0.0.1:18080 moonkey@100.95.23.122
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
```

Key choices:
- `-M 0` disables autossh's own monitoring port, relies on `ServerAliveInterval` instead (simpler)
- `ServerAliveInterval 30` + `ServerAliveCountMax 3` = detect dead connection in ~90s
- `Restart=on-failure` + `RestartSec=5` = auto-restart on tunnel drop
- User service (not system) — no sudo needed after initial setup

### Update `scripts/remote/proxy_tunnel.sh`

Rewrite as a service manager with subcommands:

```bash
./scripts/remote/proxy_tunnel.sh install   # copy service file, enable
./scripts/remote/proxy_tunnel.sh start     # start tunnel
./scripts/remote/proxy_tunnel.sh stop      # stop tunnel
./scripts/remote/proxy_tunnel.sh status    # check status
./scripts/remote/proxy_tunnel.sh logs      # show recent logs
```

Falls back to direct `autossh` if systemd user services aren't available.

### Prerequisites

- `autossh` installed: `sudo apt-get install -y autossh`
- Add to `provision.sh` system packages
- SSH key auth from ld1 → laptop (already set up)

## Component 3: Runbook Updates

Add to `doc/dev/remote_eval_worker.md`:

### Tunnel Management
```bash
# Install service (one-time)
./scripts/remote/proxy_tunnel.sh install

# Start/stop/status
./scripts/remote/proxy_tunnel.sh start
./scripts/remote/proxy_tunnel.sh status
./scripts/remote/proxy_tunnel.sh stop

# Health check
curl -sS --max-time 3 http://127.0.0.1:18080/
```

### Long-running Eval in tmux
```bash
# Start
./scripts/remote/eval_tmux.sh --tasks-file eval/config/<task_file>

# Detach: Ctrl-b d
# Reattach: tmux attach -t eval

# Check if running
tmux has-session -t eval 2>/dev/null && echo "running" || echo "no session"
```

### Troubleshooting
- Tunnel dropped: `proxy_tunnel.sh status`, then `proxy_tunnel.sh start`
- Emulator hung: `adb -s emulator-5554 emu kill`, then re-run
- Stale checkout: `git pull && ./gradlew assembleDebug`

## Verification

1. Install autossh on ld1
2. Run `proxy_tunnel.sh install && proxy_tunnel.sh start`
3. Verify `curl http://127.0.0.1:18080/` works
4. Kill laptop SSH session, wait 90s, verify tunnel reconnects
5. Run eval via `eval_tmux.sh`, detach SSH, reattach, verify eval still running
