#!/usr/bin/env bash
# Run all 6 CTN device-QA scenarios and save evidence.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$ROOT"

RESET="projects/active/codex_targeting_normalization/qa-evidence/reset-settings.sh"
RUN="scripts/mobile-action-test.sh"

# Stable targets (Settings home, observed in pre_tree.json)
#   Element 28 "更多连接" (Other connections) row — bounds [0,1743,1264,1946] center (632,1844)
#   Element 3 (top-right search icon) — bounds [1081,168,1222,308] center (1151,238)
#   After clicking search: EditText idx=1 — bounds [196,168,1040,308] center (618,238)
#   RecyclerView idx=0 — scrollable, bounds [0,0,1264,2800]

run() {
    local tag="$1"; shift
    local args="$1"
    echo ""
    echo "=== $tag ==="
    "$RUN" --tag "$tag" "$args"
}

# ── Scenario 1: in-bounds Codex shape ──
"$RESET"
run "ctn-1-inbounds" '{"action":"click","element_index":28,"x":632,"y":1844}'

# ── Scenario 2: ambiguity ──
"$RESET"
run "ctn-2-ambiguous" '{"action":"click","element_index":28,"x":99999,"y":99999}'

# ── Scenario 3: coordinate fallback (semantic miss + valid xy) ──
"$RESET"
run "ctn-3-coord-fallback" '{"action":"click","element_index":9999,"x":632,"y":1844}'

# ── Scenario 4: type fallback ──
# Open search field first so EditText exists; then type via missed semantic + xy on input.
"$RESET"
"$RUN" --tag ctn-4-prep '{"action":"click","element_index":3}' >/dev/null
sleep 1
run "ctn-4-type-fallback" '{"action":"type","element_index":9999,"x":618,"y":238,"input_text":"qa"}'

# ── Scenario 5: scroll guard ──
"$RESET"
run "ctn-5-scroll-guard" '{"action":"scroll","element_index":9999,"x":632,"y":1095,"direction":"down"}'

# ── Scenario 6: regressions ──
"$RESET"
run "ctn-6a-click-element" '{"action":"click","element_index":28}'

"$RESET"
"$RUN" --tag ctn-6b-prep '{"action":"click","element_index":3}' >/dev/null
sleep 1
run "ctn-6b-type-text" '{"action":"type","text":"搜索设置","input_text":"hello"}'

"$RESET"
run "ctn-6c-scroll-down" '{"action":"scroll","direction":"down"}'

"$RESET"
run "ctn-6d-swipe" '{"action":"swipe","start":[632,2200],"end":[632,800],"duration_ms":300}'

echo ""
echo "=== ALL SCENARIOS DONE ==="
