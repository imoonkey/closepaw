import base64
import json
import os
import subprocess
from pathlib import Path
from typing import Any, List, Optional

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

app = FastAPI()

# Configuration
INSPECTION_TOOL_DIR = Path(__file__).parent.resolve()
DEBUG_OUTPUT_DIR = (INSPECTION_TOOL_DIR / "../debug-output").resolve()
EVAL_RESULTS_DIR = (INSPECTION_TOOL_DIR / "../eval/results").resolve()
REPLAY_V2_DIR = INSPECTION_TOOL_DIR / "replay_v2"

class DebugRunInfo(BaseModel):
    id: str
    trace_id: str
    compiled: bool


class EvalTaskInfo(BaseModel):
    id: str
    trace_id: str
    compiled: bool


class EvalRunInfo(BaseModel):
    id: str
    tasks: List[EvalTaskInfo]


class CatalogResponse(BaseModel):
    debug_runs: List[DebugRunInfo]
    eval_runs: List[EvalRunInfo]


class RunInfo(BaseModel):
    # Legacy shape kept for old frontend callers.
    id: str
    timestamp: Optional[str] = None
    compiled: bool


def _is_compiled(trace_dir: Path) -> bool:
    return (trace_dir / "derived" / "steps.jsonl").exists()


def _encode_trace_id(payload: dict[str, Any]) -> str:
    raw = json.dumps(payload, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _decode_trace_id(trace_id: str) -> dict[str, Any]:
    pad = "=" * ((4 - len(trace_id) % 4) % 4)
    try:
        decoded = base64.urlsafe_b64decode((trace_id + pad).encode("ascii")).decode("utf-8")
        payload = json.loads(decoded)
    except Exception as exc:  # pylint: disable=broad-exception-caught
        raise HTTPException(status_code=400, detail="Invalid trace id") from exc

    if not isinstance(payload, dict):
        raise HTTPException(status_code=400, detail="Invalid trace id")
    kind = payload.get("k")
    run_id = payload.get("r")
    task_id = payload.get("t")
    if kind == "debug" and isinstance(run_id, str) and run_id:
        return {"k": "debug", "r": run_id}
    if kind == "eval" and isinstance(run_id, str) and run_id and isinstance(task_id, str) and task_id:
        return {"k": "eval", "r": run_id, "t": task_id}
    raise HTTPException(status_code=400, detail="Invalid trace id")


def _ensure_within(root: Path, target: Path) -> None:
    root_abs = root.resolve()
    target_abs = target.resolve()
    try:
        target_abs.relative_to(root_abs)
    except ValueError:
        raise HTTPException(status_code=403, detail="Access denied")


def _resolve_trace_dir(trace_id: str) -> Path:
    payload = _decode_trace_id(trace_id)
    if payload["k"] == "debug":
        trace_dir = (DEBUG_OUTPUT_DIR / payload["r"] / "trace").resolve()
        _ensure_within(DEBUG_OUTPUT_DIR, trace_dir)
        return trace_dir

    trace_dir = (
        EVAL_RESULTS_DIR / payload["r"] / "artifacts" / payload["t"] / "trace"
    ).resolve()
    _ensure_within(EVAL_RESULTS_DIR, trace_dir)
    return trace_dir


def _build_catalog() -> CatalogResponse:
    debug_runs: List[DebugRunInfo] = []
    if DEBUG_OUTPUT_DIR.exists():
        for entry in sorted(os.listdir(DEBUG_OUTPUT_DIR), reverse=True):
            run_dir = DEBUG_OUTPUT_DIR / entry
            if not run_dir.is_dir() or not entry.startswith("run_"):
                continue
            trace_dir = run_dir / "trace"
            debug_runs.append(
                DebugRunInfo(
                    id=entry,
                    trace_id=_encode_trace_id({"k": "debug", "r": entry}),
                    compiled=_is_compiled(trace_dir),
                )
            )

    eval_runs: List[EvalRunInfo] = []
    if EVAL_RESULTS_DIR.exists():
        for run_entry in sorted(os.listdir(EVAL_RESULTS_DIR), reverse=True):
            run_dir = EVAL_RESULTS_DIR / run_entry
            if not run_dir.is_dir() or run_entry.startswith("."):
                continue
            artifacts_dir = run_dir / "artifacts"
            if not artifacts_dir.is_dir():
                continue

            tasks: List[EvalTaskInfo] = []
            for task_entry in sorted(os.listdir(artifacts_dir)):
                task_dir = artifacts_dir / task_entry
                if not task_dir.is_dir():
                    continue
                trace_dir = task_dir / "trace"
                if not trace_dir.exists():
                    continue
                tasks.append(
                    EvalTaskInfo(
                        id=task_entry,
                        trace_id=_encode_trace_id({"k": "eval", "r": run_entry, "t": task_entry}),
                        compiled=_is_compiled(trace_dir),
                    )
                )

            if tasks:
                eval_runs.append(EvalRunInfo(id=run_entry, tasks=tasks))

    return CatalogResponse(debug_runs=debug_runs, eval_runs=eval_runs)


def _compile_trace_dir(trace_dir: Path) -> dict[str, str]:
    if not trace_dir.exists():
        raise HTTPException(status_code=404, detail="Trace directory not found")
    if not (trace_dir / "trace.jsonl").exists():
        raise HTTPException(status_code=404, detail="trace.jsonl not found")

    compiler_script = INSPECTION_TOOL_DIR / "replay_compiler.py"
    try:
        result = subprocess.run(
            ["python3", str(compiler_script), str(trace_dir)],
            capture_output=True,
            text=True,
            check=True,
        )
        return {"status": "success", "output": result.stdout}
    except subprocess.CalledProcessError as exc:
        raise HTTPException(status_code=500, detail=f"Compilation failed: {exc.stderr}") from exc


@app.get("/api/catalog", response_model=CatalogResponse)
async def get_catalog():
    return _build_catalog()


@app.get("/api/runs", response_model=List[RunInfo])
async def list_runs_legacy():
    catalog = _build_catalog()
    return [RunInfo(id=run.id, compiled=run.compiled) for run in catalog.debug_runs]


@app.post("/api/traces/{trace_id}/compile")
async def compile_trace(trace_id: str):
    trace_dir = _resolve_trace_dir(trace_id)
    return _compile_trace_dir(trace_dir)


@app.post("/api/runs/{run_id}/compile")
async def compile_run_legacy(run_id: str):
    trace_dir = (DEBUG_OUTPUT_DIR / run_id / "trace").resolve()
    _ensure_within(DEBUG_OUTPUT_DIR, trace_dir)
    return _compile_trace_dir(trace_dir)


@app.get("/traces/{trace_id}/{path:path}")
async def get_trace_file(trace_id: str, path: str):
    trace_dir = _resolve_trace_dir(trace_id)
    if not trace_dir.exists():
        raise HTTPException(status_code=404, detail="Trace directory not found")

    safe_path = (trace_dir / path).resolve()
    _ensure_within(trace_dir, safe_path)
    if not safe_path.exists():
        raise HTTPException(status_code=404, detail="File not found")
    return FileResponse(safe_path)

# Serve the frontend
if REPLAY_V2_DIR.exists():
    app.mount("/", StaticFiles(directory=str(REPLAY_V2_DIR), html=True), name="static")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
