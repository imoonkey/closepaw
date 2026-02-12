import os
import glob
import subprocess
import shutil
from pathlib import Path
from typing import List, Optional
from fastapi import FastAPI, HTTPException, BackgroundTasks
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

app = FastAPI()

# Configuration
# Configuration
INSPECTION_TOOL_DIR = Path(__file__).parent.resolve()
DEBUG_OUTPUT_DIR = (INSPECTION_TOOL_DIR / "../debug-output").resolve()
REPLAY_V2_DIR = INSPECTION_TOOL_DIR / "replay_v2"

class RunInfo(BaseModel):
    id: str
    timestamp: Optional[str] = None
    compiled: bool

@app.get("/api/runs", response_model=List[RunInfo])
async def list_runs():
    runs = []
    if not DEBUG_OUTPUT_DIR.exists():
        return []
    
    # List all directories in debug-output
    # Expecting format run_YYYYMMDD_HHMMSS
    for entry in sorted(os.listdir(DEBUG_OUTPUT_DIR), reverse=True):
        full_path = DEBUG_OUTPUT_DIR / entry
        if full_path.is_dir() and entry.startswith("run_"):
            # Check if compiled
            compiled = (full_path / "trace" / "derived" / "steps.jsonl").exists()
            runs.append(RunInfo(id=entry, compiled=compiled))
            
    return runs

@app.post("/api/runs/{run_id}/compile")
async def compile_run(run_id: str, background_tasks: BackgroundTasks):
    run_dir = DEBUG_OUTPUT_DIR / run_id
    trace_dir = run_dir / "trace"
    
    if not run_dir.exists():
        raise HTTPException(status_code=404, detail="Run not found")
        
    if not trace_dir.exists():
        raise HTTPException(status_code=404, detail="Trace directory not found in run")

    # Run the compiler
    # We use subprocess to run the existing replay_compiler.py
    compiler_script = INSPECTION_TOOL_DIR / "replay_compiler.py"
    
    try:
        result = subprocess.run(
            ["python3", str(compiler_script), str(trace_dir)],
            capture_output=True,
            text=True,
            check=True
        )
        return {"status": "success", "output": result.stdout}
    except subprocess.CalledProcessError as e:
        raise HTTPException(status_code=500, detail=f"Compilation failed: {e.stderr}")

@app.get("/traces/{run_id}/{path:path}")
async def get_trace_file(run_id: str, path: str):
    # Serve files from the trace directory
    # trace directory is ../debug-output/{run_id}/trace
    # path is relative to that
    
    # Security check: ensure we don't traverse up
    safe_path = (DEBUG_OUTPUT_DIR / run_id / "trace" / path).resolve()
    
    # Ensure the path is within the debug-output directory
    if not str(safe_path).startswith(str(DEBUG_OUTPUT_DIR)):
         raise HTTPException(status_code=403, detail="Access denied")
         
    if not safe_path.exists():
        raise HTTPException(status_code=404, detail="File not found")
        
    return FileResponse(safe_path)

# Serve the frontend
if REPLAY_V2_DIR.exists():
    app.mount("/", StaticFiles(directory=str(REPLAY_V2_DIR), html=True), name="static")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
