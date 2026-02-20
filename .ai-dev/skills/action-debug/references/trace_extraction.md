# Trace-to-Action Extraction Reference

Quick reference for extracting action parameters from agent traces for use with `action-test.sh`.

## Trace Artifact Layout

```
debug-output/run_<ID>/trace/
├── trace.jsonl                          # Raw event stream
├── artifacts/
│   ├── tool_call_args/                  # mobile_action arguments (JSON)
│   │   └── <seq>_turn_<N>_mobile_action_<call_id>.json
│   ├── tool_result/                     # Action results (text)
│   │   └── <seq>_turn_<N>_mobile_action_<call_id>_result.txt
│   ├── sanitized_a11y_tree/             # Indexed element list (JSON array)
│   │   └── <seq>_*.json
│   ├── screenshot/                      # Device screenshots (JPEG)
│   │   └── <seq>_*.jpg
│   └── tool_observation_screen/         # Post-action screenshots
│       └── <seq>_*.jpg
└── derived/
    └── steps.jsonl                      # Step-centric compiled view
```

## mobile_action Tool Call Format

```json
{
  "action": "click",
  "element_index": 13,
  "agent_thought": "Clicking the submit button"
}
```

Possible `action` values: `click`, `long_press`, `scroll`, `swipe`, `type`, `wait`, `back`, `home`

## Element Index to Coordinates

The `element_index` maps to an entry in `sanitized_a11y_tree`:

```json
{
  "index": 13,
  "text": "Submit",
  "class": "Button",
  "clickable": true,
  "bounds": [200, 1100, 880, 1300],
  "center": [540, 1200],
  "enabled": true,
  "focused": false
}
```

- `center` → use as `--x` and `--y` for action-test.sh
- `bounds` → `[left, top, right, bottom]` for understanding the target area

## One-Liner Extraction Commands

### Find all failed mobile_action calls in a run

```bash
python3 -c "
import json
for line in open('debug-output/run_<ID>/trace/trace.jsonl'):
    ev = json.loads(line)
    if ev['type'] == 'tool_result' and ev['data'].get('name') == 'mobile_action' and not ev['data'].get('success'):
        print(f\"seq={ev['seq']}  turn={ev.get('turnNumber')}  call_id={ev['data']['id']}\")
"
```

### Read tool call args for a specific call

```bash
cat debug-output/run_<ID>/trace/artifacts/tool_call_args/*mobile_action*<call_id>*.json | python3 -m json.tool
```

### Find the a11y tree for a specific turn

```bash
# Trees are captured at screen_captured events, which precede tool calls in the same turn
ls debug-output/run_<ID>/trace/artifacts/sanitized_a11y_tree/ | grep "turn_<N>"
```

### Look up element coordinates by index

```bash
python3 -c "
import json
tree = json.load(open('debug-output/run_<ID>/trace/artifacts/sanitized_a11y_tree/<file>.json'))
idx = 13
elem = next((e for e in tree if e.get('index') == idx), None)
if elem:
    cx, cy = elem['center']
    print(f'./scripts/action-test.sh click --x {cx} --y {cy} --tag trace_elem{idx}')
    print(f'  text={elem.get(\"text\",\"\")}  class={elem.get(\"class\",\"\")}  clickable={elem.get(\"clickable\")}')
else:
    print(f'Element {idx} not found')
"
```

### Generate action-test.sh commands for all failed actions in a run

```bash
python3 -c "
import json, glob, os

run_dir = 'debug-output/run_<ID>'
trace_file = f'{run_dir}/trace/trace.jsonl'

# Collect failed tool calls
failed = {}
for line in open(trace_file):
    ev = json.loads(line)
    if ev['type'] == 'tool_result' and ev['data'].get('name') == 'mobile_action' and not ev['data']['success']:
        failed[ev['data']['id']] = ev

# For each failure, find args and coordinates
for call_id, ev in failed.items():
    turn = ev.get('turnNumber', '?')
    # Find args file
    args_files = glob.glob(f'{run_dir}/trace/artifacts/tool_call_args/*mobile_action*{call_id}*.json')
    if not args_files:
        print(f'# Turn {turn}: args file not found for {call_id}')
        continue
    args = json.load(open(args_files[0]))
    action = args.get('action', '?')
    elem_idx = args.get('element_index')

    # Find tree file
    tree_files = sorted(glob.glob(f'{run_dir}/trace/artifacts/sanitized_a11y_tree/*turn_{turn}*.json'))
    if not tree_files or elem_idx is None:
        print(f'# Turn {turn}: {action} element_index={elem_idx} (tree not found)')
        continue

    tree = json.load(open(tree_files[0]))
    elem = next((e for e in tree if e.get('index') == elem_idx), None)
    if elem:
        cx, cy = elem['center']
        print(f'# Turn {turn}: {action} \"{elem.get(\"text\",\"\")}\" [{elem.get(\"class\")}] clickable={elem.get(\"clickable\")}')
        print(f'./scripts/action-test.sh {action} --x {cx} --y {cy} --tag turn{turn}_elem{elem_idx}')
    else:
        print(f'# Turn {turn}: element_index={elem_idx} not found in tree')
"
```

## Mapping mobile_action to action-test.sh

| mobile_action | action-test.sh | Extra params |
|---------------|----------------|--------------|
| `click` | `click --x CX --y CY` | `--use-node false` for gesture mode |
| `long_press` | `long_press --x CX --y CY` | `--duration N` |
| `scroll` (down) | `scroll --direction down` | `--x CX --y CY` for scroll container |
| `scroll` (up) | `scroll --direction up` | same |
| `swipe` | `swipe --start-x ... --end-x ...` | `--duration N` |
| `type` | Not yet supported | Phase 2 |
| `back` | Not yet supported | Use `adb shell input keyevent KEYCODE_BACK` |

## Quick Test Sequence

After extracting coordinates from a failed trace:

```bash
# 1. Navigate to the error state on device (manual or adb)
# 2. Verify screen matches the trace screenshot
adb exec-out screencap -p > /tmp/verify_state.png && open /tmp/verify_state.png

# 3. Run the full comparison
./scripts/action-test.sh click --x 540 --y 1200 --compare --tag "issue_investigation"

# 4. Check results
cat debug-output/action-test/issue_investigation/result.json | python3 -m json.tool
open debug-output/action-test/issue_investigation/*.png
```
