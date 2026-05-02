#!/usr/bin/env bash
#
# setup-cdp-relay.sh — wire the host-mediated Chrome DevTools relay between a connected
# Android device and the user's PC, so ClosePaw's HostMediatedCdpRelayTransport can talk to
# Chrome's `chrome_devtools_remote` abstract socket through ADB.
#
# Why this exists: on locked production OEM builds (e.g. nubia P0110 Android 16) the device's
# SELinux policy denies BOTH the app UID AND the Shizuku-spawned shell UserService from
# connecting to Chrome's abstract socket. The only context with the right combination of
# `connectto appdomain` and `mlstrustedsubject` is `adbd`. So the only way to reach Chrome's
# CDP socket from inside an untrusted_app is to chain back through ADB:
#
#     ClosePaw (untrusted_app)
#       -> connect 127.0.0.1:<port>                  # (4)
#         -> adbd reverse-tunnel listener            # (3)
#           -> host adbd over USB/network            # (2)
#             -> adb forward                         # (1)
#               -> @chrome_devtools_remote           # (0)
#
# Set up by:
#   adb -s <serial> forward tcp:<port> localabstract:chrome_devtools_remote   # (1)
#   adb -s <serial> reverse tcp:<port> tcp:<port>                              # (3)
#
# This script is idempotent: it removes any existing forward/reverse on the chosen port
# before re-creating them, then verifies the bridge with a single curl GET to /json/version.
#
# Usage:
#   ./scripts/setup-cdp-relay.sh                  # default port 9222, ANDROID_SERIAL from env
#   ./scripts/setup-cdp-relay.sh --port 9223      # override port
#   ./scripts/setup-cdp-relay.sh --serial emulator-5554
#   ./scripts/setup-cdp-relay.sh --teardown       # remove forward/reverse rules and exit
#
# Environment:
#   ANDROID_SERIAL   Pin the device. If unset and multiple devices are attached,
#                    the script refuses rather than guess.

set -euo pipefail

PORT=9222
PORT_RANGE_END=9230
TEARDOWN=false
SERIAL_OVERRIDE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --port)
            PORT="$2"
            shift 2
            ;;
        --serial|-s)
            SERIAL_OVERRIDE="$2"
            shift 2
            ;;
        --teardown)
            TEARDOWN=true
            shift
            ;;
        -h|--help)
            sed -n '2,33p' "$0"
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 2
            ;;
    esac
done

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'
log()  { printf "${GREEN}> %s${NC}\n" "$*"; }
warn() { printf "${YELLOW}! %s${NC}\n" "$*"; }
err()  { printf "${RED}x %s${NC}\n" "$*" >&2; }

if ! command -v adb >/dev/null 2>&1; then
    err "adb not on PATH. Install platform-tools first."
    exit 1
fi

# Pin device. Honour --serial > ANDROID_SERIAL > sole connected device. Refuse to guess.
SERIAL="${SERIAL_OVERRIDE:-${ANDROID_SERIAL:-}}"
if [[ -z "$SERIAL" ]]; then
    DEVICE_LINES="$(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')"
    DEVICE_COUNT="$(printf "%s\n" "$DEVICE_LINES" | sed '/^$/d' | wc -l | tr -d ' ')"
    if [[ "$DEVICE_COUNT" -eq 0 ]]; then
        err "No Android device attached. Plug in a device or start an emulator first."
        exit 1
    fi
    if [[ "$DEVICE_COUNT" -gt 1 ]]; then
        err "Multiple devices attached. Set ANDROID_SERIAL or pass --serial:"
        printf "%s\n" "$DEVICE_LINES"
        exit 1
    fi
    SERIAL="$(printf "%s\n" "$DEVICE_LINES" | head -n 1)"
fi
export ANDROID_SERIAL="$SERIAL"
log "Target device: $SERIAL"

# Validate port (numeric and in 1..65535).
if ! [[ "$PORT" =~ ^[0-9]+$ ]] || (( PORT < 1 || PORT > 65535 )); then
    err "Invalid --port: $PORT (must be 1..65535)"
    exit 2
fi

clear_rules_for_port() {
    local target_port="$1"
    # forward rules look like:  <serial> tcp:<port> localabstract:chrome_devtools_remote
    adb -s "$SERIAL" forward --list 2>/dev/null \
        | awk -v s="$SERIAL" -v p="tcp:$target_port" '$1 == s && $2 == p {print $2}' \
        | while read -r local_spec; do
            adb -s "$SERIAL" forward --remove "$local_spec" >/dev/null 2>&1 || true
        done
    adb -s "$SERIAL" reverse --list 2>/dev/null \
        | awk -v p="tcp:$target_port" '$2 == p {print $2}' \
        | while read -r remote_spec; do
            adb -s "$SERIAL" reverse --remove "$remote_spec" >/dev/null 2>&1 || true
        done
}

if [[ "$TEARDOWN" == "true" ]]; then
    log "Tearing down relay on port $PORT (and the full transport probe range $PORT..$PORT_RANGE_END)"
    for ((p = PORT; p <= PORT_RANGE_END; p++)); do
        clear_rules_for_port "$p"
    done
    log "Done."
    exit 0
fi

# Idempotent reset on the chosen port: the transport probes a small range, so leftovers from
# a previous run on adjacent ports could mask a real failure. Clean only the chosen port; the
# rest of the range is the user's responsibility to pick.
clear_rules_for_port "$PORT"

# Verify host port is free (in case some other tool is bound to it).
if (echo > /dev/tcp/127.0.0.1/"$PORT") >/dev/null 2>&1; then
    warn "Host port $PORT is already in use by another process — adb forward may collide."
    warn "If verification fails, free the port (lsof -i :$PORT) or pass --port <other>."
fi

log "Wiring host-mediated CDP relay on port $PORT"
adb -s "$SERIAL" forward "tcp:$PORT" "localabstract:chrome_devtools_remote" >/dev/null
adb -s "$SERIAL" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null

# Verify end-to-end via the device-side path: ask the device to curl 127.0.0.1:<port>.
# This exercises the EXACT chain ClosePaw will use (untrusted_app would also connect to the
# device-side reverse listener, not the host port).
log "Verifying device-side bridge: device -> 127.0.0.1:$PORT -> host -> chrome_devtools_remote"
RAW=$(adb -s "$SERIAL" shell "command -v curl >/dev/null 2>&1 && curl -sS --max-time 5 http://127.0.0.1:$PORT/json/version || echo __no_curl__" 2>&1)

if [[ "$RAW" == "__no_curl__"* ]]; then
    # Some OEM userdebug shells lack curl. Fall back to host-side verification through the
    # forward leg only — sufficient to prove (1) is wired even if (3) is hard to test from here.
    warn "Device shell has no curl; falling back to host-side verification via the forward leg"
    if curl -sS --max-time 5 "http://127.0.0.1:$PORT/json/version" > /tmp/setup-cdp-relay.json 2>/dev/null; then
        log "Host-side /json/version OK ($(wc -c </tmp/setup-cdp-relay.json) bytes)."
        log "Cannot validate device-side reverse leg without curl on device, but the forward"
        log "rule is wired. ClosePaw will report a clear error on its own probe if (3) failed."
    else
        err "Host-side /json/version probe failed. Is Chrome open on the device with DevTools enabled?"
        exit 3
    fi
elif [[ "$RAW" == *"\"webSocketDebuggerUrl\""* ]]; then
    log "Bridge verified: device curl returned a Chrome /json/version payload."
    BROWSER=$(printf "%s" "$RAW" | sed -n 's/.*"Browser":[[:space:]]*"\([^"]*\)".*/\1/p')
    [[ -n "$BROWSER" ]] && log "  Chrome: $BROWSER"
else
    err "Device-side curl did not see Chrome's /json/version payload. Got:"
    err "$RAW"
    err ""
    err "Common causes: Chrome is not running on the device, or chrome_devtools_remote is not"
    err "bound (Chrome may need to be opened once after install)."
    exit 3
fi

log ""
log "Done. ClosePaw can now use the host-mediated CDP relay on 127.0.0.1:$PORT."
log "Tear down with:  ./scripts/setup-cdp-relay.sh --teardown --serial $SERIAL"
