from __future__ import annotations

from dataclasses import dataclass
import json
from pathlib import Path
from typing import Any

from eval.aw_bridge.jsonl_utils import read_jsonl


@dataclass
class TraceParseResult:
    answer: str | None
    answer_status: str | None
    completion_reason: str | None
    turns_executed: int
    tool_calls: int
    tool_failures: int
    run_summary_path: str | None


def empty_trace_result() -> TraceParseResult:
    return TraceParseResult(
        answer=None,
        answer_status=None,
        completion_reason=None,
        turns_executed=0,
        tool_calls=0,
        tool_failures=0,
        run_summary_path=None,
    )


def parse_trace(trace_dir: Path) -> TraceParseResult:
    trace_file = trace_dir / "trace.jsonl"
    if not trace_file.exists():
        return empty_trace_result()

    latest_answer: str | None = None
    latest_status: str | None = None
    run_summary_rel: str | None = None

    for event in read_jsonl(trace_file):
        event_type = event.get("type")
        if event_type == "tool_call":
            data = event.get("data", {})
            if data.get("name") == "complete_task":
                args_path = _find_artifact_path(event, kind="tool_call_args")
                if args_path:
                    args_obj = _read_json_if_exists(trace_dir / args_path)
                    if isinstance(args_obj, dict):
                        latest_answer = _clean_nullable(args_obj.get("answer"))
                        latest_status = _clean_nullable(args_obj.get("status"))
        if event_type == "session_stopped":
            summary_path = _find_artifact_path(event, kind="run_summary")
            if summary_path:
                run_summary_rel = summary_path

    summary_abs = trace_dir / run_summary_rel if run_summary_rel else None
    summary_obj = _read_json_if_exists(summary_abs) if summary_abs else None
    return TraceParseResult(
        answer=latest_answer,
        answer_status=latest_status,
        completion_reason=(
            _clean_nullable(summary_obj.get("stop_reason"))
            if isinstance(summary_obj, dict)
            else None
        ),
        turns_executed=(
            int(summary_obj.get("turns_executed", 0))
            if isinstance(summary_obj, dict)
            else 0
        ),
        tool_calls=(
            int(summary_obj.get("tool_calls", 0))
            if isinstance(summary_obj, dict)
            else 0
        ),
        tool_failures=(
            int(summary_obj.get("tool_failures", 0))
            if isinstance(summary_obj, dict)
            else 0
        ),
        run_summary_path=(
            str(summary_abs)
            if summary_abs is not None and summary_abs.exists()
            else None
        ),
    )


def _find_artifact_path(event: dict[str, Any], kind: str) -> str | None:
    artifacts = event.get("artifacts", [])
    if not isinstance(artifacts, list):
        return None
    for artifact in artifacts:
        if not isinstance(artifact, dict):
            continue
        if artifact.get("kind") == kind:
            path = artifact.get("path")
            if isinstance(path, str) and path:
                return path
    return None


def _read_json_if_exists(path: Path) -> Any:
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8", errors="replace"))
    except json.JSONDecodeError:
        return None


def _clean_nullable(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    trimmed = value.strip()
    return trimmed if trimmed else None
