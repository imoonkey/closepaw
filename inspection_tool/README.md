# ClosePaw Replay Viewer

This folder contains the step-centric replay viewer (v2) and a FastAPI server to browse traces.

## Setup

This tool uses `uv` for dependency management.

```bash
cd inspection_tool
uv sync
```

## Running the Viewer

Start the server:

```bash
uv run uvicorn server:app --reload
```

Open [http://localhost:8000](http://localhost:8000).

- **Select Run**: Use the dropdown at the top to select a run from `debug-output` (searched in `../debug-output`).
- **Auto-Compilation**: If a run hasn't been compiled (missing `derived/steps.jsonl`), selecting it will automatically trigger the compiler.

## Features

- **Step-by-step Replay**: Navigate through each turn of the conversation.
- **Visual State**: View screenshots and accessibility trees side-by-side.
- **Tool Calls**: See exact tool parameters and outputs.
- **Performance Stats**: Analyze token usage and latency (using `a11y_token_stats.py`).
- **Auto-Compilation**: Automatically compiles raw traces into a viewable format.
- **Auto-Load**: Automatically loads the most recent run upon opening.
- **Refreshed UI**: Clean, modern light theme with improved typography and readability.
- **Enhanced Navigation**: Sidebar displays tool names and session roles for easier scanning.

## A11y Token Stats

Compute token counts for raw vs sanitized accessibility trees:

```bash
uv run a11y_token_stats.py --run ../debug-output/run_YYYYMMDD_HHMMSS
```

## File Structure

```
inspection_tool/
├── server.py            # FastAPI server
├── replay_compiler.py   # Trace -> derived step index
├── a11y_token_stats.py  # A11y tree token stats
├── replay_v2/           # Viewer frontend (HTML/JS/CSS)
├── pyproject.toml       # Dependencies
└── README.md            # This file
```
