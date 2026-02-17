from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
from datetime import datetime
import json
import logging
from pathlib import Path
from typing import Any

import yaml

from eval.aw_bridge.native_agent_bridge import BridgeConfig, BridgeOutcome, NativeAgentBridge
from eval.aw_bridge.result_schema import ArtifactPaths, TaskResult, summarize_results
from eval.aw_bridge.task_loader import (
    TaskInstance,
    build_task_instances,
    ensure_android_world_importable,
    load_task_names_from_file,
)
from eval.aw_bridge.trace_parser import empty_trace_result, parse_trace


@dataclass
class RunnerConfig:
    suite_family: str
    output_root: str
    task_random_seed: int
    n_task_combinations: int
    use_identical_params: bool
    retry_infra_failures: int
    adb_serial: str | None
    reference_root: str
    console_port: int
    grpc_port: int
    adb_path: str | None
    perform_emulator_setup: bool
    freeze_datetime: bool
    bridge: BridgeConfig


def main() -> None:
    args = _parse_args()
    workspace_root = Path(__file__).resolve().parents[2]
    config = _load_config(workspace_root, args)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    run_dir = (workspace_root / config.output_root / timestamp).resolve()
    artifact_root = run_dir / "artifacts"
    artifact_root.mkdir(parents=True, exist_ok=True)
    runner_log = run_dir / "runner.log"
    _setup_logging(runner_log)

    logging.info("Run directory: %s", run_dir)
    logging.info("Config: %s", asdict(config))

    ensure_android_world_importable(workspace_root, config.reference_root)
    env = _create_env(config)

    all_attempt_results: list[TaskResult] = []
    final_results: list[TaskResult] = []
    per_task_jsonl = run_dir / "per_task.jsonl"

    try:
        selected_tasks = _resolve_selected_tasks(workspace_root, args)
        task_instances = build_task_instances(
            suite_family=config.suite_family,
            n_task_combinations=config.n_task_combinations,
            task_random_seed=config.task_random_seed,
            use_identical_params=config.use_identical_params,
            selected_tasks=selected_tasks,
            env=env,
        )
        logging.info("Loaded %d task instances", len(task_instances))

        bridge = NativeAgentBridge(config.bridge)
        for task_idx, task_instance in enumerate(task_instances):
            final_result = _run_one_task_instance(
                bridge=bridge,
                suite_family=config.suite_family,
                task_instance=task_instance,
                task_index=task_idx,
                run_prefix=f"aw_{timestamp}",
                artifact_root=artifact_root,
                runner_log=runner_log,
                max_infra_retries=config.retry_infra_failures,
                env=env,
                per_task_jsonl=per_task_jsonl,
                all_attempt_results=all_attempt_results,
            )
            final_results.append(final_result)
    finally:
        env.close()

    summary = summarize_results(final_results)
    summary_payload = {
        "run_timestamp": timestamp,
        "suite_family": config.suite_family,
        "num_task_instances": len(final_results),
        "num_attempts": len(all_attempt_results),
        "config": asdict(config),
        "metrics": summary,
    }
    summary_path = run_dir / "summary.json"
    summary_path.write_text(
        json.dumps(summary_payload, ensure_ascii=True, indent=2),
        encoding="utf-8",
    )
    logging.info("Wrote summary: %s", summary_path)
    print(json.dumps(summary_payload["metrics"], ensure_ascii=True, indent=2))


def _run_one_task_instance(
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
        run_id = (
            f"{run_prefix}_{_safe_token(task_instance.task_name)}_"
            f"{task_index}_{attempt}"
        )
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
                scripted_score = float(task.is_successful(env))
                scripted_success = scripted_score > 0.5
                task_status = "success" if scripted_success else "failure"
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

        _append_jsonl(per_task_jsonl, result.to_dict())
        all_attempt_results.append(result)

        should_retry = (
            result.bridge_status == "infra_failure" and attempt < max_infra_retries
        )
        if should_retry:
            attempt += 1
            logging.warning(
                "Infra failure for %s (attempt=%d), retrying...",
                task_instance.task_name,
                attempt,
            )
            continue

        return result


def _create_env(config: RunnerConfig) -> Any:
    from android_world.env import env_launcher  # type: ignore

    kwargs: dict[str, Any] = {
        "console_port": config.console_port,
        "emulator_setup": config.perform_emulator_setup,
        "freeze_datetime": config.freeze_datetime,
        "grpc_port": config.grpc_port,
    }
    if config.adb_path:
        kwargs["adb_path"] = config.adb_path
    return env_launcher.load_and_setup_env(**kwargs)


def _resolve_selected_tasks(workspace_root: Path, args: argparse.Namespace) -> list[str] | None:
    if args.tasks:
        return [t.strip() for t in args.tasks.split(",") if t.strip()]
    if args.tasks_file:
        return load_task_names_from_file((workspace_root / args.tasks_file).resolve())
    return None


def _load_config(workspace_root: Path, args: argparse.Namespace) -> RunnerConfig:
    config_path = (workspace_root / args.config).resolve()
    if not config_path.exists():
        raise FileNotFoundError(f"Config not found: {config_path}")
    raw = yaml.safe_load(config_path.read_text(encoding="utf-8")) or {}

    suite_family = args.suite or raw.get("suite_family", "android_world")
    runner_cfg = raw.get("runner", {})
    aw_cfg = raw.get("android_world", {})
    bridge_cfg = raw.get("bridge", {})

    n_task_combinations = (
        args.n_task_combinations
        if args.n_task_combinations is not None
        else int(runner_cfg.get("n_task_combinations", 1))
    )
    task_random_seed = (
        args.task_random_seed
        if args.task_random_seed is not None
        else int(runner_cfg.get("task_random_seed", 30))
    )

    bridge = BridgeConfig(
        package_name=str(bridge_cfg.get("package_name", "com.moonkey.androidagent")),
        activity=str(
            bridge_cfg.get("activity", "com.moonkey.androidagent/.app.MainActivity")
        ),
        llm_backend=str(bridge_cfg.get("llm_backend", "openai")),
        agent_mode=str(bridge_cfg.get("agent_mode", "pro")),
        perception_mode=str(bridge_cfg.get("perception_mode", "accessibility_only")),
        platform_mode=str(bridge_cfg.get("platform_mode", "accessibility")),
        main_model=str(bridge_cfg.get("main_model", "minimax-m2.5")),
        executor_model=str(bridge_cfg.get("executor_model", "")),
        auto_start=bool(bridge_cfg.get("auto_start", True)),
        fresh_session=bool(bridge_cfg.get("fresh_session", True)),
        debug_mode=bool(bridge_cfg.get("debug_mode", False)),
        trace_enabled=bool(bridge_cfg.get("trace_enabled", True)),
        max_wait_seconds=int(bridge_cfg.get("max_wait_seconds", 900)),
        poll_interval_seconds=float(bridge_cfg.get("poll_interval_seconds", 1)),
        adb_serial=_nullable_str(args.adb_serial or runner_cfg.get("adb_serial")),
        stop_agent_after_task=bool(runner_cfg.get("stop_agent_after_task", True)),
        adb_command_timeout_sec=int(runner_cfg.get("adb_command_timeout_sec", 60)),
        adb_pull_timeout_sec=int(runner_cfg.get("adb_pull_timeout_sec", 300)),
    )

    return RunnerConfig(
        suite_family=suite_family,
        output_root=str(args.output_root or runner_cfg.get("output_root", "eval/results")),
        task_random_seed=task_random_seed,
        n_task_combinations=n_task_combinations,
        use_identical_params=bool(runner_cfg.get("use_identical_params", False)),
        retry_infra_failures=int(runner_cfg.get("retry_infra_failures", 1)),
        adb_serial=_nullable_str(args.adb_serial or runner_cfg.get("adb_serial")),
        reference_root=str(aw_cfg.get("reference_root", ".reference/eval/android_world")),
        console_port=int(aw_cfg.get("console_port", 5554)),
        grpc_port=int(aw_cfg.get("grpc_port", 8554)),
        adb_path=_nullable_str(aw_cfg.get("adb_path")),
        perform_emulator_setup=bool(aw_cfg.get("perform_emulator_setup", False)),
        freeze_datetime=bool(aw_cfg.get("freeze_datetime", True)),
        bridge=bridge,
    )


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="AndroidWorld bridge runner")
    parser.add_argument("--config", default="eval/config/default.yaml")
    parser.add_argument("--suite", default=None)
    parser.add_argument("--tasks-file", default=None)
    parser.add_argument("--tasks", default=None, help="Comma-separated task names")
    parser.add_argument("--n-task-combinations", type=int, default=None)
    parser.add_argument("--task-random-seed", type=int, default=None)
    parser.add_argument("--output-root", default=None)
    parser.add_argument("--adb-serial", default=None)
    return parser.parse_args()


def _setup_logging(log_path: Path) -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        handlers=[
            logging.FileHandler(log_path, encoding="utf-8"),
            logging.StreamHandler(),
        ],
    )


def _append_jsonl(path: Path, row: dict[str, Any]) -> None:
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(row, ensure_ascii=True))
        stream.write("\n")


def _safe_token(value: str) -> str:
    chars = []
    for ch in value:
        if ch.isalnum() or ch in ("-", "_"):
            chars.append(ch)
        else:
            chars.append("_")
    token = "".join(chars).strip("_")
    return token[:80] if token else "task"


def _nullable_str(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


if __name__ == "__main__":
    main()
