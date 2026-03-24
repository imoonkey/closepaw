#!/usr/bin/env bash
set -euo pipefail

if [ $# -lt 1 ]; then
  echo "Usage: $0 <scenario.json> [runner options...]"
  echo "Example: $0 references/scenario_template.json --agent-goal 'Open Settings' --agent-link-mode parallel"
  exit 1
fi

SCENARIO="$1"
shift

python3 "$(dirname "$0")/adb_ux_runner.py" --scenario "$SCENARIO" "$@"
