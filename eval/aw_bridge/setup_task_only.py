from __future__ import annotations

import argparse
import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from eval.aw_bridge.runner import (
    _create_env,
    _load_config,
    _run_android_world_connectivity_preflight,
)
from eval.aw_bridge.task_loader import (
    TaskInstance,
    build_task_instances,
    ensure_android_world_importable,
)


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run AndroidWorld task setup only (initialize_task), without bridge execution."
        )
    )
    parser.add_argument("--config", default="eval/config/default.yaml")
    parser.add_argument("--suite", default=None)
    parser.add_argument("--task", required=True, help="Task name, e.g. FilesMoveFile")
    parser.add_argument(
        "--instance-index",
        type=int,
        default=0,
        help="0-based index among generated instances for this task",
    )
    parser.add_argument("--n-task-combinations", type=int, default=None)
    parser.add_argument("--task-random-seed", type=int, default=None)
    parser.add_argument("--adb-serial", default=None)
    parser.add_argument(
        "--teardown",
        action="store_true",
        help="Also run tear_down after initialize_task (cleanup mode)",
    )
    return parser.parse_args()


def _build_runner_like_args(args: argparse.Namespace) -> SimpleNamespace:
    # Reuse runner config parsing path with only the fields _load_config expects.
    return SimpleNamespace(
        config=args.config,
        suite=args.suite,
        tasks=None,
        tasks_file=None,
        n_task_combinations=args.n_task_combinations,
        task_random_seed=args.task_random_seed,
        output_root=None,
        adb_serial=args.adb_serial,
        snapshot_policy=None,
        platform_mode=None,
    )


def _pick_task_instance(
    task_instances: list[TaskInstance], task_name: str, instance_index: int
) -> TaskInstance:
    matches = [t for t in task_instances if t.task_name == task_name]
    if not matches:
        available = sorted({t.task_name for t in task_instances})
        raise RuntimeError(
            f"Task '{task_name}' not found. Available task names: {available}"
        )
    if instance_index < 0 or instance_index >= len(matches):
        raise RuntimeError(
            f"instance-index out of range: {instance_index}. "
            f"Task '{task_name}' has {len(matches)} instance(s)."
        )
    return matches[instance_index]


def _payload(task_instance: TaskInstance, initialized: bool, teardown: bool) -> dict[str, Any]:
    return {
        "task_name": task_instance.task_name,
        "instance_index": task_instance.instance_index,
        "seed": task_instance.seed,
        "goal": task_instance.goal,
        "params": getattr(task_instance.task, "params", {}),
        "initialized": initialized,
        "teardown": teardown,
    }


def main() -> None:
    args = _parse_args()
    workspace_root = Path(__file__).resolve().parents[2]

    runner_args = _build_runner_like_args(args)
    config = _load_config(workspace_root, runner_args)

    ensure_android_world_importable(workspace_root, config.reference_root)
    _run_android_world_connectivity_preflight(config)

    env = _create_env(config)
    initialized = False
    task_instance: TaskInstance | None = None
    try:
        task_instances = build_task_instances(
            suite_family=config.suite_family,
            n_task_combinations=config.n_task_combinations,
            task_random_seed=config.task_random_seed,
            use_identical_params=config.use_identical_params,
            selected_tasks=[args.task],
            env=env,
        )
        task_instance = _pick_task_instance(
            task_instances=task_instances,
            task_name=args.task,
            instance_index=args.instance_index,
        )

        task_instance.task.initialize_task(env)
        initialized = True

        if args.teardown:
            task_instance.task.tear_down(env)

        print(
            json.dumps(
                _payload(task_instance, initialized=initialized, teardown=args.teardown),
                ensure_ascii=True,
                indent=2,
            )
        )
    finally:
        env.close()


if __name__ == "__main__":
    main()
