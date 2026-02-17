from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from eval.aw_bridge.jsonl_utils import read_jsonl
from eval.aw_bridge.result_schema import ArtifactPaths, TaskResult, summarize_results


def main() -> None:
    parser = argparse.ArgumentParser(description="Summarize per_task.jsonl metrics")
    parser.add_argument("--run-dir", required=True, help="Path to eval/results/<timestamp>")
    args = parser.parse_args()

    run_dir = Path(args.run_dir).resolve()
    per_task_path = run_dir / "per_task.jsonl"
    if not per_task_path.exists():
        raise FileNotFoundError(f"Missing per_task.jsonl: {per_task_path}")

    rows = [_task_result_from_dict(row) for row in read_jsonl(per_task_path)]
    metrics = summarize_results(rows)
    payload = {
        "run_dir": str(run_dir),
        "num_rows": len(rows),
        "metrics": metrics,
    }
    print(json.dumps(payload, ensure_ascii=True, indent=2))


def _task_result_from_dict(row: dict[str, Any]) -> TaskResult:
    artifact_paths = row.get("artifact_paths") or {}
    return TaskResult(
        task_name=row["task_name"],
        suite_family=row["suite_family"],
        seed=row.get("seed"),
        goal=row["goal"],
        run_id=row["run_id"],
        attempt=int(row.get("attempt", 0)),
        bridge_status=row["bridge_status"],
        agent_completion_reason=row.get("agent_completion_reason"),
        task_status=row.get("task_status"),
        answer=row.get("answer"),
        scripted_score=row.get("scripted_score"),
        scripted_success=bool(row.get("scripted_success", False)),
        duration_sec=float(row.get("duration_sec", 0.0)),
        turns_executed=int(row.get("turns_executed", 0)),
        tool_calls=int(row.get("tool_calls", 0)),
        tool_failures=int(row.get("tool_failures", 0)),
        artifact_paths=ArtifactPaths(
            trace_dir=artifact_paths.get("trace_dir"),
            logcat=artifact_paths.get("logcat"),
            runner_log=artifact_paths.get("runner_log"),
        ),
        exception=row.get("exception"),
    )


if __name__ == "__main__":
    main()
