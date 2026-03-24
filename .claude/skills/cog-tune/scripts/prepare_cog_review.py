#!/usr/bin/env python3
"""Prepare a cognition review index for the latest (or specified) debug run.

Produces a markdown report with per-step artifact paths to speed up review.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from typing import Dict, Iterable, List, Optional


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare cognition review report")
    parser.add_argument(
        "--run",
        help="Path to debug-output/run_xxx (defaults to latest under debug-output)",
    )
    parser.add_argument(
        "--root",
        default=".",
        help="Project root (default: current directory)",
    )
    parser.add_argument(
        "--report-name",
        default="cognition_review.md",
        help="Report filename (default: cognition_review.md)",
    )
    parser.add_argument(
        "--latest",
        action="store_true",
        help="Deprecated (latest is default). Included for convenience.",
    )
    return parser.parse_args()


def find_latest_run(debug_root: Path) -> Path:
    runs = [p for p in debug_root.glob("run_*") if p.is_dir()]
    if not runs:
        raise FileNotFoundError(f"No run_* directories under {debug_root}")
    runs.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    for run_dir in runs:
        if (run_dir / "trace" / "trace.jsonl").exists():
            return run_dir
    raise FileNotFoundError(f"No run_* directories with trace.jsonl under {debug_root}")


def ensure_derived(trace_dir: Path, root: Path) -> Path:
    trace_file = trace_dir / "trace.jsonl"
    steps_file = trace_dir / "derived" / "steps.jsonl"

    if not trace_file.exists():
        raise FileNotFoundError(f"Missing trace.jsonl in {trace_dir}")

    needs_compile = not steps_file.exists()
    if steps_file.exists() and steps_file.stat().st_mtime < trace_file.stat().st_mtime:
        needs_compile = True

    if needs_compile:
        compiler = root / "inspection_tool" / "replay_compiler.py"
        if not compiler.exists():
            raise FileNotFoundError(f"Missing replay compiler at {compiler}")
        subprocess.run(
            [sys.executable, str(compiler), str(trace_dir)],
            check=True,
        )

    if not steps_file.exists():
        raise FileNotFoundError(f"Missing steps.jsonl at {steps_file}")

    return steps_file


def load_steps(path: Path) -> List[Dict[str, object]]:
    steps: List[Dict[str, object]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        steps.append(json.loads(line))
    return steps


def artifact_by_kind(artifacts: Iterable[Dict[str, object]], kind: str) -> Optional[Dict[str, object]]:
    for artifact in artifacts:
        if artifact.get("kind") == kind:
            return artifact
    return None


def relpath(path: Optional[Path], base: Path) -> str:
    if path is None:
        return "(missing)"
    try:
        return str(path.relative_to(base))
    except ValueError:
        return str(path)


def collect_tool_calls(step: Dict[str, object], trace_dir: Path) -> List[str]:
    calls = step.get("tool", {}).get("calls") if isinstance(step.get("tool"), dict) else None
    if not isinstance(calls, list):
        return []
    summaries: List[str] = []
    for call in calls:
        if not isinstance(call, dict):
            continue
        data = call.get("data") if isinstance(call.get("data"), dict) else {}
        name = data.get("name") if isinstance(data.get("name"), str) else "unknown"
        call_id = data.get("id") if isinstance(data.get("id"), str) else ""
        artifacts = call.get("artifacts") if isinstance(call.get("artifacts"), list) else []
        args_artifact = artifact_by_kind(artifacts, "tool_call_args")
        args_path = None
        if isinstance(args_artifact, dict):
            raw_path = args_artifact.get("path")
            if isinstance(raw_path, str):
                args_path = trace_dir / raw_path
        suffix = f" (args: {relpath(args_path, trace_dir.parent)})" if args_path else ""
        summaries.append(f"{name}{suffix}{' id=' + call_id if call_id else ''}")
    return summaries


def collect_tool_results(step: Dict[str, object], trace_dir: Path) -> List[str]:
    results = step.get("tool", {}).get("results") if isinstance(step.get("tool"), dict) else None
    if not isinstance(results, list):
        return []
    summaries: List[str] = []
    for result in results:
        if not isinstance(result, dict):
            continue
        data = result.get("data") if isinstance(result.get("data"), dict) else {}
        name = data.get("name") if isinstance(data.get("name"), str) else "unknown"
        success = data.get("success")
        status = "success" if success is True else "failed" if success is False else "unknown"
        artifacts = result.get("artifacts") if isinstance(result.get("artifacts"), list) else []
        result_artifact = artifact_by_kind(artifacts, "tool_result")
        result_path = None
        if isinstance(result_artifact, dict):
            raw_path = result_artifact.get("path")
            if isinstance(raw_path, str):
                result_path = trace_dir / raw_path
        suffix = f" (result: {relpath(result_path, trace_dir.parent)})" if result_path else ""
        summaries.append(f"{name} [{status}]{suffix}")
    return summaries


def main() -> int:
    args = parse_args()
    root = Path(args.root).resolve()
    debug_root = root / "debug-output"

    if args.run:
        run_dir = Path(args.run)
        if not run_dir.is_absolute():
            run_dir = root / run_dir
    else:
        run_dir = find_latest_run(debug_root)

    trace_dir = run_dir / "trace"
    steps_path = ensure_derived(trace_dir, root)
    steps = load_steps(steps_path)

    report_path = run_dir / args.report_name
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    lines: List[str] = []
    lines.append("# Cognition Review Index")
    lines.append("")
    lines.append(f"Run: `{run_dir.name}`")
    lines.append(f"Generated: {now}")
    lines.append(f"Steps: {len(steps)}")
    lines.append("")

    roles: Dict[str, int] = {}
    for step in steps:
        role = step.get("agent_role") if isinstance(step.get("agent_role"), str) else "unknown"
        roles[role] = roles.get(role, 0) + 1
    if roles:
        lines.append("## Agent Roles")
        for role, count in sorted(roles.items()):
            lines.append(f"- {role}: {count}")
        lines.append("")

    lines.append("## Steps")
    lines.append("")

    for step in steps:
        step_id = step.get("step_id") if isinstance(step.get("step_id"), str) else "(missing)"
        turn_number = step.get("turn_number") if isinstance(step.get("turn_number"), int) else "?"
        role = step.get("agent_role") if isinstance(step.get("agent_role"), str) else "unknown"
        events = step.get("event_types") if isinstance(step.get("event_types"), list) else []

        mind = step.get("mind") if isinstance(step.get("mind"), dict) else {}
        llm_request = mind.get("llm_request") if isinstance(mind.get("llm_request"), dict) else {}
        llm_response = mind.get("llm_response") if isinstance(mind.get("llm_response"), dict) else {}
        req_data = llm_request.get("data") if isinstance(llm_request.get("data"), dict) else {}
        model = req_data.get("model") if isinstance(req_data.get("model"), str) else "unknown"

        req_artifacts = llm_request.get("artifacts") if isinstance(llm_request.get("artifacts"), list) else []
        resp_artifacts = llm_response.get("artifacts") if isinstance(llm_response.get("artifacts"), list) else []

        def artifact_path(kind: str) -> Optional[Path]:
            artifact = artifact_by_kind(req_artifacts, kind) or artifact_by_kind(resp_artifacts, kind)
            if not isinstance(artifact, dict):
                return None
            raw_path = artifact.get("path")
            if isinstance(raw_path, str):
                return trace_dir / raw_path
            return None

        world = step.get("world") if isinstance(step.get("world"), dict) else {}
        world_pre = world.get("pre") if isinstance(world.get("pre"), dict) else {}
        pre_screenshot = world_pre.get("screenshot") if isinstance(world_pre.get("screenshot"), dict) else None
        pre_sanitized = world_pre.get("sanitized_a11y_tree") if isinstance(world_pre.get("sanitized_a11y_tree"), dict) else None

        def world_path(node: Optional[Dict[str, object]]) -> Optional[Path]:
            if not isinstance(node, dict):
                return None
            raw_path = node.get("path")
            if isinstance(raw_path, str):
                return trace_dir / raw_path
            return None

        lines.append(f"### Turn {turn_number} ({role})")
        lines.append("")
        lines.append(f"- Step ID: `{step_id}`")
        lines.append(f"- Model: `{model}`")
        lines.append(f"- Events: {', '.join(events) if events else '(none)' }")
        lines.append(f"- Screenshot (pre): `{relpath(world_path(pre_screenshot), run_dir)}`")
        lines.append(f"- A11y (pre, sanitized): `{relpath(world_path(pre_sanitized), run_dir)}`")
        lines.append(f"- System prompt: `{relpath(artifact_path('llm_system_prompt'), run_dir)}`")
        lines.append(f"- User context: `{relpath(artifact_path('llm_user_context'), run_dir)}`")
        lines.append(f"- Full prompt: `{relpath(artifact_path('llm_full_prompt'), run_dir)}`")
        lines.append(f"- Input items: `{relpath(artifact_path('llm_input_items'), run_dir)}`")
        lines.append(f"- History: `{relpath(artifact_path('llm_history'), run_dir)}`")
        lines.append(f"- Tool calls: {', '.join(collect_tool_calls(step, trace_dir)) if collect_tool_calls(step, trace_dir) else '(none)' }")
        lines.append(f"- Tool results: {', '.join(collect_tool_results(step, trace_dir)) if collect_tool_results(step, trace_dir) else '(none)' }")
        tool_calls_artifact = artifact_path("llm_tool_calls")
        lines.append(f"- LLM tool calls artifact: `{relpath(tool_calls_artifact, run_dir)}`")
        lines.append("")

    report_path.write_text("\n".join(lines), encoding="utf-8")
    print(f"[OK] Wrote {report_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
