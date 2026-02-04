#!/usr/bin/env python3
"""Compile raw trace.jsonl into step-centric replay indexes.

Usage:
    python3 replay_compiler.py /path/to/debug-output/run_xxx/trace
"""

from __future__ import annotations

import argparse
import json
import time
from collections import defaultdict
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Compile trace replay indexes")
    parser.add_argument("trace_dir", help="Directory that contains trace.jsonl")
    return parser.parse_args()


def safe_json_loads(line: str) -> Optional[Dict[str, Any]]:
    line = line.strip()
    if not line:
        return None
    try:
        parsed = json.loads(line)
    except json.JSONDecodeError:
        return None
    if not isinstance(parsed, dict):
        return None
    return parsed


def read_events(trace_file: Path) -> List[Dict[str, Any]]:
    events: List[Dict[str, Any]] = []
    for raw_line in trace_file.read_text(encoding="utf-8").splitlines():
        event = safe_json_loads(raw_line)
        if event is not None:
            events.append(event)

    def seq_key(event: Dict[str, Any]) -> Tuple[int, int]:
        seq = event.get("seq")
        if not isinstance(seq, int):
            seq = 0
        ts = event.get("tsMs")
        if not isinstance(ts, int):
            ts = 0
        return (seq, ts)

    events.sort(key=seq_key)
    return events


def event_type(event: Dict[str, Any]) -> str:
    raw = event.get("type")
    if isinstance(raw, str) and raw:
        return raw
    raw = event.get("event")
    if isinstance(raw, str) and raw:
        return raw
    return "unknown"


def event_session_id(event: Dict[str, Any]) -> Optional[str]:
    value = event.get("sessionId")
    if isinstance(value, str) and value:
        return value
    value = event.get("session_id")
    if isinstance(value, str) and value:
        return value
    ctx = event.get("ctx")
    if isinstance(ctx, dict):
        value = ctx.get("session_id")
        if isinstance(value, str) and value:
            return value
    return None


def event_turn_number(event: Dict[str, Any]) -> Optional[int]:
    value = event.get("turnNumber")
    if isinstance(value, int):
        return value
    ctx = event.get("ctx")
    if isinstance(ctx, dict):
        value = ctx.get("turn_number")
        if isinstance(value, int):
            return value
    return None


def event_turn_id(event: Dict[str, Any]) -> Optional[str]:
    value = event.get("turnId")
    if isinstance(value, str) and value:
        return value
    ctx = event.get("ctx")
    if isinstance(ctx, dict):
        value = ctx.get("turn_id")
        if isinstance(value, str) and value:
            return value
    return None


def event_artifacts(event: Dict[str, Any]) -> List[Dict[str, Any]]:
    artifacts = event.get("artifacts")
    if isinstance(artifacts, list):
        return [a for a in artifacts if isinstance(a, dict)]
    return []


def parse_parent_session_id(session_id: str) -> Optional[str]:
    if "::" not in session_id:
        return None
    parts = session_id.split("::")
    if len(parts) <= 1:
        return None
    return "::".join(parts[:-1])


def extract_artifact(artifacts: Iterable[Dict[str, Any]], kind: str) -> Optional[Dict[str, Any]]:
    for artifact in artifacts:
        if artifact.get("kind") == kind:
            return artifact
    return None


def summarize_event(event: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "seq": event.get("seq"),
        "ts_ms": event.get("tsMs"),
        "type": event_type(event),
        "data": event.get("data"),
        "artifacts": event_artifacts(event),
    }


def compile_sessions(events: List[Dict[str, Any]]) -> Tuple[Dict[str, Dict[str, Any]], List[Dict[str, Any]]]:
    sessions: Dict[str, Dict[str, Any]] = {}
    raw_session_nodes: List[Dict[str, Any]] = []

    for event in events:
        if event_type(event) != "session_started":
            continue

        session_id = event_session_id(event)
        if not session_id:
            continue

        data = event.get("data") if isinstance(event.get("data"), dict) else {}
        parent_session_id = data.get("parent_session_id")
        if not isinstance(parent_session_id, str):
            parent_session_id = parse_parent_session_id(session_id)

        role = data.get("agent_role") if isinstance(data.get("agent_role"), str) else "unknown"
        status = "running"
        sessions[session_id] = {
            "session_id": session_id,
            "parent_session_id": parent_session_id,
            "agent_role": role,
            "agent_id": data.get("agent_id") if isinstance(data.get("agent_id"), str) else session_id,
            "goal": data.get("goal"),
            "task_id": data.get("task_id"),
            "delegation_call_id": data.get("delegation_call_id") if isinstance(data.get("delegation_call_id"), str) else None,
            "status": status,
            "started_at_ms": event.get("tsMs"),
            "stopped_at_ms": None,
            "children": [],
        }

    for event in events:
        if event_type(event) != "session_stopped":
            continue
        session_id = event_session_id(event)
        if not session_id:
            continue
        if session_id not in sessions:
            sessions[session_id] = {
                "session_id": session_id,
                "parent_session_id": parse_parent_session_id(session_id),
                "agent_role": "unknown",
                "agent_id": session_id,
                "goal": None,
                "task_id": None,
                "delegation_call_id": None,
                "status": "stopped",
                "started_at_ms": None,
                "stopped_at_ms": event.get("tsMs"),
                "children": [],
            }

        data = event.get("data") if isinstance(event.get("data"), dict) else {}
        reason = data.get("reason") if isinstance(data.get("reason"), str) else "stopped"
        sessions[session_id]["status"] = reason
        sessions[session_id]["stopped_at_ms"] = event.get("tsMs")

    for session_id, info in sessions.items():
        parent_id = info.get("parent_session_id")
        if isinstance(parent_id, str) and parent_id in sessions:
            sessions[parent_id]["children"].append(session_id)

    for session_id in sorted(sessions.keys()):
        raw_session_nodes.append(sessions[session_id])

    return sessions, raw_session_nodes


def compile_steps(events: List[Dict[str, Any]], sessions: Dict[str, Dict[str, Any]]) -> List[Dict[str, Any]]:
    grouped: Dict[Tuple[str, int], List[Dict[str, Any]]] = defaultdict(list)

    for event in events:
        session_id = event_session_id(event)
        turn_number = event_turn_number(event)
        if not session_id or turn_number is None:
            continue
        grouped[(session_id, turn_number)].append(event)

    call_to_step: Dict[str, str] = {}
    steps: List[Dict[str, Any]] = []

    for key in sorted(grouped.keys(), key=lambda item: (item[0], item[1])):
        session_id, turn_number = key
        turn_events = grouped[key]
        turn_events.sort(key=lambda e: (int(e.get("seq", 0)), int(e.get("tsMs", 0))))

        first = turn_events[0]
        last = turn_events[-1]
        turn_id = next((event_turn_id(e) for e in turn_events if event_turn_id(e)), None)
        step_id = f"{session_id}::turn-{turn_number}"

        screen_pre = None
        llm_req = None
        llm_resp = None
        tool_calls: List[Dict[str, Any]] = []
        tool_results: List[Dict[str, Any]] = []
        screen_post = None

        for event in turn_events:
            et = event_type(event)
            data = event.get("data") if isinstance(event.get("data"), dict) else {}
            artifacts = event_artifacts(event)

            if et == "screen_captured" and screen_pre is None:
                screen_pre = {
                    "event": summarize_event(event),
                    "screenshot": extract_artifact(artifacts, "screenshot"),
                    "raw_a11y_tree": extract_artifact(artifacts, "raw_a11y_tree"),
                    "sanitized_a11y_tree": extract_artifact(artifacts, "sanitized_a11y_tree"),
                }
            elif et == "llm_request" and llm_req is None:
                llm_req = summarize_event(event)
            elif et == "llm_response" and llm_resp is None:
                llm_resp = summarize_event(event)
            elif et == "tool_call":
                tool_calls.append(summarize_event(event))
                call_id = data.get("id") if isinstance(data.get("id"), str) else None
                tool_name = data.get("name") if isinstance(data.get("name"), str) else None
                if call_id:
                    call_to_step[call_id] = step_id
                if tool_name == "delegate_task" and call_id:
                    call_to_step[call_id] = step_id
            elif et == "tool_result":
                tool_results.append(summarize_event(event))
                post_screen_candidate = {
                    "event": summarize_event(event),
                    "screenshot": extract_artifact(artifacts, "screenshot"),
                    "raw_a11y_tree": extract_artifact(artifacts, "raw_a11y_tree"),
                    "sanitized_a11y_tree": extract_artifact(artifacts, "sanitized_a11y_tree"),
                }
                if (
                    post_screen_candidate["screenshot"]
                    or post_screen_candidate["raw_a11y_tree"]
                    or post_screen_candidate["sanitized_a11y_tree"]
                ):
                    screen_post = post_screen_candidate

        session_info = sessions.get(session_id, {})
        step = {
            "step_id": step_id,
            "session_id": session_id,
            "agent_id": session_info.get("agent_id", session_id),
            "agent_role": session_info.get("agent_role", "unknown"),
            "turn_number": turn_number,
            "turn_id": turn_id,
            "seq_start": first.get("seq"),
            "seq_end": last.get("seq"),
            "ts_start_ms": first.get("tsMs"),
            "ts_end_ms": last.get("tsMs"),
            "event_types": [event_type(e) for e in turn_events],
            "world": {
                "pre": screen_pre,
                "post": screen_post,
            },
            "mind": {
                "llm_request": llm_req,
                "llm_response": llm_resp,
            },
            "tool": {
                "calls": tool_calls,
                "results": tool_results,
            },
            "links": {
                "parent_step_id": None,
                "child_session_ids": [],
            },
        }
        steps.append(step)

    # Connect parent step by delegation call id captured in child session start metadata.
    for step in steps:
        session_id = step.get("session_id")
        if not isinstance(session_id, str):
            continue
        session_info = sessions.get(session_id)
        if not isinstance(session_info, dict):
            continue
        delegation_call_id = session_info.get("delegation_call_id")
        if isinstance(delegation_call_id, str) and delegation_call_id in call_to_step:
            parent_step_id = call_to_step[delegation_call_id]
            step["links"]["parent_step_id"] = parent_step_id

    step_index = {step["step_id"]: step for step in steps if isinstance(step.get("step_id"), str)}
    for step in steps:
        parent_step_id = step.get("links", {}).get("parent_step_id")
        if not isinstance(parent_step_id, str):
            continue
        parent_step = step_index.get(parent_step_id)
        if not parent_step:
            continue
        child_session_id = step.get("session_id")
        children = parent_step["links"].setdefault("child_session_ids", [])
        if isinstance(child_session_id, str) and child_session_id not in children:
            children.append(child_session_id)

    steps.sort(key=lambda s: (int(s.get("ts_start_ms") or 0), str(s.get("step_id"))))
    return steps


def write_json(path: Path, payload: Any) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def write_jsonl(path: Path, rows: Iterable[Any]) -> None:
    lines = [json.dumps(row, ensure_ascii=False) for row in rows]
    path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")


def main() -> int:
    args = parse_args()
    trace_dir = Path(args.trace_dir).expanduser().resolve()
    trace_file = trace_dir / "trace.jsonl"

    if not trace_file.exists():
        raise SystemExit(f"trace.jsonl not found: {trace_file}")

    events = read_events(trace_file)
    sessions, session_nodes = compile_sessions(events)
    steps = compile_steps(events, sessions)

    derived_dir = trace_dir / "derived"
    derived_dir.mkdir(parents=True, exist_ok=True)

    replay_index = {
        "version": 1,
        "trace_file": str(trace_file.name),
        "events": len(events),
        "sessions": len(session_nodes),
        "steps": len(steps),
        "generated_at_ms": time.time_ns() // 1_000_000,
        "files": {
            "agent_tree": "derived/agent_tree.json",
            "steps": "derived/steps.jsonl",
        },
    }

    write_json(derived_dir / "replay_index.json", replay_index)
    write_json(derived_dir / "agent_tree.json", {"sessions": session_nodes})
    write_jsonl(derived_dir / "steps.jsonl", steps)

    print(f"Compiled replay index: {derived_dir}")
    print(f"  events:   {len(events)}")
    print(f"  sessions: {len(session_nodes)}")
    print(f"  steps:    {len(steps)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
