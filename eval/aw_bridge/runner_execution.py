from __future__ import annotations

from dataclasses import replace
import json
import logging
from pathlib import Path
import subprocess
import time
from typing import Any

from eval.aw_bridge.native_agent_bridge import BridgeOutcome, NativeAgentBridge
from eval.aw_bridge.result_schema import ArtifactPaths, TaskResult
from eval.aw_bridge.task_loader import TaskInstance
from eval.aw_bridge.trace_parser import empty_trace_result, parse_trace


def run_one_task_instance(
    bridge: NativeAgentBridge,
    suite_family: str,
    task_instance: TaskInstance,
    task_index: int,
    run_prefix: str,
    artifact_root: Path,
    runner_log: Path,
    max_infra_retries: int,
    env: Any,
    per_task_jsonl: Path,
    all_attempt_results: list[TaskResult],
) -> TaskResult:
    attempt = 0
    while True:
        run_id = f"{run_prefix}_{safe_token(task_instance.task_name)}_{task_index}_{attempt}"
        logging.info(
            "[%d] Running task=%s instance=%d attempt=%d run_id=%s",
            task_index,
            task_instance.task_name,
            task_instance.instance_index,
            attempt,
            run_id,
        )

        task = task_instance.task
        artifact_dir = artifact_root / run_id
        trace_dir = artifact_dir / "trace"
        initialized = False
        scripted_score: float | None = None
        scripted_success = False
        task_status: str | None = None
        exception: str | None = None
        bridge_outcome = BridgeOutcome(
            bridge_status="infra_failure",
            agent_completion_reason=None,
            duration_sec=0.0,
            logcat_path=str(artifact_dir / "logcat.log"),
            exception=None,
        )
        trace_parse = empty_trace_result()

        try:
            task.initialize_task(env)
            initialized = True

            bridge_outcome = bridge.run_task(
                goal=task_instance.goal,
                run_id=run_id,
                artifact_dir=artifact_dir,
            )
            trace_pulled = bridge.pull_trace_dir(run_id=run_id, local_trace_dir=trace_dir)
            trace_parse = parse_trace(trace_dir) if trace_pulled else empty_trace_result()

            if trace_parse.answer is not None:
                env.interaction_cache = trace_parse.answer

            if bridge_outcome.bridge_status != "infra_failure":
                scoring_ctx = capture_scoring_context(bridge, run_id)
                scripted_score = float(task.is_successful(env))
                scoring_ctx["score"] = scripted_score
                scoring_ctx["scoring_duration_ms"] = int(
                    (time.time() - scoring_ctx["scoring_timestamp"]) * 1000
                )
                scripted_success = scripted_score > 0.5
                task_status = "success" if scripted_success else "failure"
                logging.info(
                    "Scoring: run_id=%s score=%.1f a11y=%s fg=%s elements=%d",
                    run_id,
                    scripted_score,
                    scoring_ctx.get("enabled_a11y_services", "?"),
                    scoring_ctx.get("foreground_activity", "?"),
                    scoring_ctx.get("ui_element_count", -1),
                )
                write_scoring_context(artifact_dir, scoring_ctx)
        except Exception as exc:  # pylint: disable=broad-exception-caught
            exception = str(exc)
            logging.exception(
                "Task execution failed for %s attempt=%d",
                task_instance.task_name,
                attempt,
            )
        finally:
            if initialized:
                try:
                    task.tear_down(env)
                except Exception as teardown_exc:  # pylint: disable=broad-exception-caught
                    if exception:
                        exception = f"{exception}; tear_down={teardown_exc}"
                    else:
                        exception = f"tear_down={teardown_exc}"

            bridge.force_stop()

        result = TaskResult(
            task_name=task_instance.task_name,
            suite_family=suite_family,
            seed=task_instance.seed,
            goal=task_instance.goal,
            run_id=run_id,
            attempt=attempt,
            bridge_status=bridge_outcome.bridge_status,
            agent_completion_reason=(
                trace_parse.completion_reason or bridge_outcome.agent_completion_reason
            ),
            task_status=task_status,
            answer=trace_parse.answer,
            scripted_score=scripted_score,
            scripted_success=scripted_success,
            duration_sec=bridge_outcome.duration_sec,
            turns_executed=trace_parse.turns_executed,
            tool_calls=trace_parse.tool_calls,
            tool_failures=trace_parse.tool_failures,
            artifact_paths=ArtifactPaths(
                trace_dir=str(trace_dir) if trace_dir.exists() else None,
                logcat=bridge_outcome.logcat_path,
                runner_log=str(runner_log),
            ),
            exception=exception or bridge_outcome.exception,
        )

        append_jsonl(per_task_jsonl, result.to_dict())
        all_attempt_results.append(result)

        should_retry = result.bridge_status == "infra_failure" and attempt < max_infra_retries
        if should_retry:
            attempt += 1
            logging.warning(
                "Infra failure for %s (attempt=%d), retrying...",
                task_instance.task_name,
                attempt,
            )
            continue
        return result


def resolve_task_bridge_config(
    base: Any,
    task_name: str,
    overrides: dict[str, dict[str, Any]],
) -> Any:
    for prefix, fields in sorted(overrides.items(), key=lambda kv: -len(kv[0])):
        if task_name.startswith(prefix):
            logging.info("Applying task override for %s (prefix=%s): %s", task_name, prefix, fields)
            return replace(base, **fields)
    return base


def append_jsonl(path: Path, row: dict[str, Any]) -> None:
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(row, ensure_ascii=True))
        stream.write("\n")


def safe_token(value: str) -> str:
    chars = []
    for ch in value:
        if ch.isalnum() or ch in ("-", "_"):
            chars.append(ch)
        else:
            chars.append("_")
    token = "".join(chars).strip("_")
    return token[:80] if token else "task"


def capture_scoring_context(
    bridge: NativeAgentBridge,
    run_id: str,
) -> dict[str, Any]:
    ctx: dict[str, Any] = {
        "scoring_timestamp": time.time(),
        "run_id": run_id,
    }
    cfg = bridge._config  # noqa: SLF001
    serial_args = ["-s", cfg.adb_serial] if cfg.adb_serial else []
    timeout = float(cfg.adb_command_timeout_sec)

    try:
        result = subprocess.run(
            ["adb", *serial_args, "shell", "dumpsys", "activity", "activities"],
            check=False,
            text=True,
            capture_output=True,
            timeout=timeout,
        )
        for line in (result.stdout or "").splitlines():
            line = line.strip()
            if "topResumedActivity=" in line or "mResumedActivity=" in line:
                start = line.find("{")
                end = line.find("}", start)
                if start >= 0 and end > start:
                    component = line[start + 1 : end]
                    pkg, _, activity = component.partition("/")
                    ctx["foreground_package"] = pkg
                    ctx["foreground_activity"] = activity
                break
    except Exception as exc:  # pylint: disable=broad-exception-caught
        ctx["foreground_error"] = str(exc)

    try:
        result = subprocess.run(
            [
                "adb",
                *serial_args,
                "shell",
                "settings",
                "get",
                "secure",
                "enabled_accessibility_services",
            ],
            check=False,
            text=True,
            capture_output=True,
            timeout=timeout,
        )
        ctx["enabled_a11y_services"] = (result.stdout or "").strip()
    except Exception as exc:  # pylint: disable=broad-exception-caught
        ctx["a11y_error"] = str(exc)

    try:
        result = subprocess.run(
            ["adb", *serial_args, "shell", "dumpsys", "accessibility"],
            check=False,
            text=True,
            capture_output=True,
            timeout=timeout,
        )
        ctx["ui_element_count"] = (result.stdout or "").count("nodeId")
    except Exception:
        ctx["ui_element_count"] = -1
    return ctx


def write_scoring_context(artifact_dir: Path, ctx: dict[str, Any]) -> None:
    try:
        artifact_dir.mkdir(parents=True, exist_ok=True)
        path = artifact_dir / "scoring_context.json"
        path.write_text(json.dumps(ctx, indent=2), encoding="utf-8")
    except Exception as exc:  # pylint: disable=broad-exception-caught
        logging.warning("Failed to write scoring_context.json: %s", exc)
