#!/bin/bash
# Wraps ./gradlew with the release signing env vars sourced from
# ~/secrets/closepaw/. Keystore + password live outside the repo and are
# mirrored on desktop and laptop (see projects/active/1_publish/2_release_build_claude.md).
#
# Usage: scripts/release-build.sh :app:assembleRelease
set -euo pipefail

# Pull JAVA_HOME / ANDROID_HOME from the repo's standard env file when present.
# Interactive shells already source this from ~/.bashrc; non-interactive ones
# (e.g. CI, agent runs) do not.
if [[ -f "$HOME/.android-agent-env" ]]; then
  # shellcheck disable=SC1091
  source "$HOME/.android-agent-env"
fi

SECRETS="$HOME/secrets/closepaw"
KEYSTORE_FILE="$SECRETS/release.keystore"
PASSWORD_FILE="$SECRETS/release.keystore.password"

if [[ ! -f "$KEYSTORE_FILE" || ! -f "$PASSWORD_FILE" ]]; then
  echo "release-build: missing $KEYSTORE_FILE or $PASSWORD_FILE" >&2
  echo "release-build: scp from the other machine before building" >&2
  exit 1
fi

export KEYSTORE_PATH="$KEYSTORE_FILE"
export KEYSTORE_PASSWORD="$(cat "$PASSWORD_FILE")"
export KEY_ALIAS=closepaw
export KEY_PASSWORD="$KEYSTORE_PASSWORD"

cd "$(dirname "$0")/.."
exec ./gradlew "$@"
