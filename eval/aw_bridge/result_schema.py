from __future__ import annotations

from dataclasses import asdict, dataclass
from statistics import median
from typing import Any


@dataclass
class ArtifactPaths:
    trace_dir: str | None
    logcat: str | None
    runner_log: str | None


@dataclass
class TaskResult:
    task_name: str
    suite_family: str
    seed: int | None
    goal: str
    run_id: str
    attempt: int
    bridge_status: str
    agent_completion_reason: str | None
    task_status: str | None
    answer: str | None
    scripted_score: float | None
    scripted_success: bool
    duration_sec: float
    turns_executed: int
    tool_calls: int
    tool_failures: int
    artifact_paths: ArtifactPaths
    exception: str | None

    def to_dict(self) -> dict[str, Any]:
        raw = asdict(self)
        raw["artifact_paths"] = asdict(self.artifact_paths)
        return raw


def summarize_results(results: list[TaskResult]) -> dict[str, Any]:
    total = len(results)
    if total == 0:
        return {
            "num_results": 0,
            "scripted_success_rate": 0.0,
            "timeout_rate": 0.0,
            "infra_failure_rate": 0.0,
            "error_rate": 0.0,
            "duration_p50_sec": 0.0,
            "duration_p90_sec": 0.0,
            "goal_claim_precision": None,
            "tool_failure_rate": None,
        }

    scripted_successes = sum(1 for r in results if r.scripted_success)
    timeout_count = sum(1 for r in results if r.bridge_status == "timeout")
    infra_count = sum(1 for r in results if r.bridge_status == "infra_failure")
    error_count = sum(1 for r in results if r.bridge_status == "error")
    durations = sorted(r.duration_sec for r in results)

    claimed_goal = [
        r
        for r in results
        if (r.agent_completion_reason or "").strip().lower().replace("_", "")
        == "goalachieved"
    ]
    claimed_goal_successes = sum(1 for r in claimed_goal if r.scripted_success)

    total_tool_calls = sum(r.tool_calls for r in results)
    total_tool_failures = sum(r.tool_failures for r in results)

    return {
        "num_results": total,
        "scripted_success_rate": scripted_successes / total,
        "timeout_rate": timeout_count / total,
        "infra_failure_rate": infra_count / total,
        "error_rate": error_count / total,
        "duration_p50_sec": _percentile(durations, 50),
        "duration_p90_sec": _percentile(durations, 90),
        "goal_claim_precision": (
            claimed_goal_successes / len(claimed_goal) if claimed_goal else None
        ),
        "tool_failure_rate": (
            total_tool_failures / total_tool_calls if total_tool_calls > 0 else None
        ),
    }


def _percentile(sorted_values: list[float], p: int) -> float:
    if not sorted_values:
        return 0.0
    if p == 50:
        return float(median(sorted_values))
    idx = int(round((p / 100.0) * (len(sorted_values) - 1)))
    idx = max(0, min(idx, len(sorted_values) - 1))
    return float(sorted_values[idx])
