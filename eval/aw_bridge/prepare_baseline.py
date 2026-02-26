from __future__ import annotations

import argparse
from datetime import datetime
import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from eval.aw_bridge.runner import _load_config
from eval.aw_bridge.runner_preflight import (
    SnapshotPolicy,
    collect_required_app_names,
    create_env,
    ensure_app_snapshots,
    resolve_snapshot_policy,
    run_android_world_connectivity_preflight,
)
from eval.aw_bridge.task_loader import (
    TaskInstance,
    build_task_instances,
    ensure_android_world_importable,
    load_task_names_from_file,
)


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Prepare clean AndroidWorld baseline snapshots and write manifest.",
    )
    parser.add_argument("--config", default="eval/config/default.yaml")
    parser.add_argument("--suite", default=None)
    parser.add_argument("--tasks-file", default=None)
    parser.add_argument("--tasks", default=None)
    parser.add_argument("--apps", default=None, help="Comma-separated logical app names")
    parser.add_argument("--n-task-combinations", type=int, default=None)
    parser.add_argument("--task-random-seed", type=int, default=None)
    parser.add_argument("--adb-serial", default=None)
    parser.add_argument("--console-port", type=int, default=None)
    parser.add_argument("--grpc-port", type=int, default=None)
    parser.add_argument(
        "--snapshot-policy",
        default="auto_repair",
        choices=["strict", "auto_repair", "best_effort", "off"],
    )
    parser.add_argument("--output", default=None, help="Manifest output path")
    return parser.parse_args()


def _build_runner_like_args(args: argparse.Namespace) -> SimpleNamespace:
    return SimpleNamespace(
        config=args.config,
        suite=args.suite,
        tasks=args.tasks,
        tasks_file=args.tasks_file,
        n_task_combinations=args.n_task_combinations,
        task_random_seed=args.task_random_seed,
        output_root=None,
        adb_serial=args.adb_serial,
        snapshot_policy=args.snapshot_policy,
        platform_mode=None,
    )


def _resolve_selected_tasks(workspace_root: Path, args: argparse.Namespace) -> list[str] | None:
    if args.tasks:
        return [t.strip() for t in args.tasks.split(",") if t.strip()]
    if args.tasks_file:
        return load_task_names_from_file((workspace_root / args.tasks_file).resolve())
    return None


def _resolve_app_names(
    workspace_root: Path,
    config: Any,
    args: argparse.Namespace,
    env: Any,
) -> tuple[list[str], list[TaskInstance] | None]:
    if args.apps:
        app_names = sorted(
            {
                str(name).strip().lower()
                for name in args.apps.split(",")
                if str(name).strip()
            }
        )
        return app_names, None

    selected_tasks = _resolve_selected_tasks(workspace_root, args)
    task_instances = build_task_instances(
        suite_family=config.suite_family,
        n_task_combinations=config.n_task_combinations,
        task_random_seed=config.task_random_seed,
        use_identical_params=config.use_identical_params,
        selected_tasks=selected_tasks,
        env=env,
    )
    app_names = sorted(collect_required_app_names(task_instances))
    return app_names, task_instances


def _default_output_path(workspace_root: Path) -> Path:
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    return workspace_root / "eval" / "results" / f"baseline_manifest_{timestamp}.json"


def main() -> None:
    args = _parse_args()
    workspace_root = Path(__file__).resolve().parents[2]
    runner_args = _build_runner_like_args(args)
    config = _load_config(workspace_root, runner_args)

    if args.console_port is not None:
        config.console_port = int(args.console_port)
    if args.grpc_port is not None:
        config.grpc_port = int(args.grpc_port)
    if args.adb_serial:
        config.adb_serial = args.adb_serial
        config.bridge.adb_serial = args.adb_serial

    config.perform_emulator_setup = True
    setup_policy = resolve_snapshot_policy(args.snapshot_policy)
    config.snapshot_policy = setup_policy.value

    ensure_android_world_importable(workspace_root, config.reference_root)
    run_android_world_connectivity_preflight(config)

    env = create_env(config)
    try:
        app_names, task_instances = _resolve_app_names(workspace_root, config, args, env)
        setup_report = ensure_app_snapshots(
            config,
            app_names=app_names,
            env=env,
            snapshot_policy=setup_policy,
        )
        verify_report = ensure_app_snapshots(
            config,
            app_names=app_names,
            env=env,
            snapshot_policy=SnapshotPolicy.STRICT,
        )
    finally:
        env.close()

    output_path = (workspace_root / args.output).resolve() if args.output else _default_output_path(
        workspace_root
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)

    payload = {
        "generated_at": datetime.now().isoformat(),
        "config": {
            "config_path": str((workspace_root / args.config).resolve()),
            "adb_serial": config.adb_serial,
            "console_port": config.console_port,
            "grpc_port": config.grpc_port,
            "perform_emulator_setup": config.perform_emulator_setup,
            "snapshot_policy": setup_policy.value,
        },
        "scope": {
            "apps": app_names,
            "tasks": [] if task_instances is None else sorted({t.task_name for t in task_instances}),
        },
        "setup_report": setup_report.to_dict(),
        "verify_report": verify_report.to_dict(),
    }
    output_path.write_text(json.dumps(payload, ensure_ascii=True, indent=2), encoding="utf-8")
    print(json.dumps({"manifest": str(output_path), "apps": len(app_names)}, ensure_ascii=True))


if __name__ == "__main__":
    main()

