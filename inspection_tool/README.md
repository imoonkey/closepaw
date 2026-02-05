# Android Agent Replay Viewer

This folder only contains the step-centric replay viewer (v2) and compiler.

## Replay Viewer v2 (Step-Centric)

Compile a replay index and open the viewer:

```bash
cd inspection_tool
python3 replay_compiler.py ../debug-output/run_YYYYMMDD_HHMMSS/trace
./serve.sh 8080
# Open http://localhost:8080/replay_v2/index.html
# Select the same trace folder (debug-output/run_YYYYMMDD_HHMMSS/trace)
```

Generated files:

- `derived/replay_index.json`
- `derived/agent_tree.json`
- `derived/steps.jsonl`

## File Structure

```
inspection_tool/
├── replay_compiler.py   # Trace -> derived step index
├── replay_v2/            # Viewer UI
├── serve.sh              # HTTP server
└── README.md             # This file
```
