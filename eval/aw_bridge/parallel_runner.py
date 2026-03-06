"""Parallel multi-emulator eval runner.

Orchestrates N independent runner.py subprocesses, each targeting a different
emulator, to reduce wall-clock eval time through horizontal scaling.

Usage:
    python3 eval/aw_bridge/parallel_runner.py \
        --config eval/config/default.yaml \
        --tasks-file eval/config/aw_subset_core.txt \
        --device emulator-5554:5554:8554 \
        --device emulator-5556:5556:8556
"""

from __future__ import annotations

import argparse
import copy
import json
import signal
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, TextIO

import yaml

from eval.aw_bridge.runner import load_config_from_path
from eval.aw_bridge.jsonl_utils import read_jsonl
from eval.aw_bridge.result_schema import ArtifactPaths, TaskResult, summarize_results
from eval.aw_bridge.runner_preflight import build_bridge_apk, install_bridge_apk
from eval.aw_bridge.task_loader import load_task_names_from_file


# ---------------------------------------------------------------------------
# Data structures
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class DeviceSpec:
    serial: str
    console_port: int
    grpc_port: int


@dataclass
class ShardResult:
    shard_index: int
    device: DeviceSpec
    tasks: list[str]
    shard_dir: Path
    output_root: Path
    config_path: Path
    tasks_path: Path
    stdout_log: Path
    exit_code: int | None = None
    start_time: float | None = None
    end_time: float | None = None


# ---------------------------------------------------------------------------
# Argument parsing & validation
# ---------------------------------------------------------------------------

def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Parallel multi-emulator eval runner",
    )
    parser.add_argument(
        "--config", default="eval/config/default.yaml",
        help="Base config YAML (default: eval/config/default.yaml)",
    )
    parser.add_argument(
        "--tasks-file", default=None,
        help="Path to task list file (one task name per line)",
    )
    parser.add_argument(
        "--tasks", default=None,
        help="Comma-separated task names",
    )
    parser.add_argument(
        "--device", action="append", required=True, dest="devices",
        help="Device spec SERIAL:CONSOLE_PORT:GRPC_PORT (repeat for each device)",
    )
    parser.add_argument(
        "--output-root", default="eval/results",
        help="Root output directory (default: eval/results)",
    )
    parser.add_argument("--suite", default=None)
    parser.add_argument("--n-task-combinations", type=int, default=None)
    parser.add_argument("--task-random-seed", type=int, default=None)
    return parser.parse_args(argv)


def parse_device_spec(raw: str) -> DeviceSpec:
    """Parse ``SERIAL:CONSOLE_PORT:GRPC_PORT`` into a DeviceSpec."""
    parts = raw.strip().split(":")
    if len(parts) != 3:
        raise ValueError(
            f"Invalid --device format '{raw}'. "
            "Expected SERIAL:CONSOLE_PORT:GRPC_PORT"
        )
    serial = parts[0].strip()
    if not serial:
        raise ValueError(f"Empty serial in --device '{raw}'")
    try:
        console_port = int(parts[1])
        grpc_port = int(parts[2])
    except ValueError:
        raise ValueError(
            f"Non-integer port in --device '{raw}'. "
            "Expected SERIAL:CONSOLE_PORT:GRPC_PORT"
        )
    return DeviceSpec(serial=serial, console_port=console_port, grpc_port=grpc_port)


def validate_device_specs(specs: list[DeviceSpec]) -> None:
    """Reject duplicate serials, console ports, or gRPC ports."""
    if not specs:
        raise ValueError("At least one --device is required")
    serials = [s.serial for s in specs]
    console_ports = [s.console_port for s in specs]
    grpc_ports = [s.grpc_port for s in specs]
    if len(set(serials)) != len(serials):
        raise ValueError(f"Duplicate device serial(s): {serials}")
    if len(set(console_ports)) != len(console_ports):
        raise ValueError(f"Duplicate console port(s): {console_ports}")
    if len(set(grpc_ports)) != len(grpc_ports):
        raise ValueError(f"Duplicate gRPC port(s): {grpc_ports}")


# ---------------------------------------------------------------------------
# Task resolution & sharding
# ---------------------------------------------------------------------------

def resolve_task_list(
    workspace_root: Path, args: argparse.Namespace,
) -> list[str]:
    """Load task names from ``--tasks-file`` or ``--tasks``."""
    if args.tasks:
        tasks = [t.strip() for t in args.tasks.split(",") if t.strip()]
        if not tasks:
            raise ValueError("--tasks provided but no task names found")
        return tasks
    if args.tasks_file:
        path = (workspace_root / args.tasks_file).resolve()
        if not path.exists():
            raise FileNotFoundError(f"Tasks file not found: {path}")
        tasks = load_task_names_from_file(path)
        if not tasks:
            raise ValueError(f"No task names found in tasks file: {path}")
        return tasks
    raise ValueError("Either --tasks-file or --tasks is required")


def shard_tasks(tasks: list[str], num_shards: int) -> list[list[str]]:
    """Deterministic round-robin distribution of tasks into shards."""
    shards: list[list[str]] = [[] for _ in range(num_shards)]
    for i, task in enumerate(tasks):
        shards[i % num_shards].append(task)
    return shards


def filter_active_device_shards(
    devices: list[DeviceSpec],
    shards: list[list[str]],
) -> tuple[list[DeviceSpec], list[list[str]]]:
    active_pairs = [
        (device, shard)
        for device, shard in zip(devices, shards)
        if shard
    ]
    if not active_pairs:
        raise ValueError("No task shards contain runnable tasks")
    active_devices = [device for device, _ in active_pairs]
    active_shards = [shard for _, shard in active_pairs]
    return active_devices, active_shards


# ---------------------------------------------------------------------------
# Config overlay & directory setup
# ---------------------------------------------------------------------------

def _load_base_config(workspace_root: Path, config_path: str) -> dict[str, Any]:
    path = (workspace_root / config_path).resolve()
    if not path.exists():
        raise FileNotFoundError(f"Config not found: {path}")
    raw = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    return raw  # type: ignore[return-value]


def create_worker_config(
    base: dict[str, Any],
    device: DeviceSpec,
    output_root: str,
    args: argparse.Namespace,
) -> dict[str, Any]:
    """Deep-copy base config and overlay device-specific fields."""
    cfg = copy.deepcopy(base)
    cfg.setdefault("runner", {})
    cfg.setdefault("android_world", {})

    # Device overrides
    cfg["runner"]["adb_serial"] = device.serial
    cfg["runner"]["output_root"] = output_root
    cfg["runner"]["perform_bridge_setup"] = False
    cfg["android_world"]["console_port"] = device.console_port
    cfg["android_world"]["grpc_port"] = device.grpc_port
    cfg["android_world"]["auto_start_emulator"] = False

    # Forward optional CLI overrides into YAML so that runner.py picks them up
    # without extra CLI args (runner.py reads these from YAML when CLI is None).
    if args.suite is not None:
        cfg["suite_family"] = args.suite
    if args.n_task_combinations is not None:
        cfg["runner"]["n_task_combinations"] = args.n_task_combinations
    if args.task_random_seed is not None:
        cfg["runner"]["task_random_seed"] = args.task_random_seed

    return cfg


def _setup_shard_dirs(
    run_dir: Path,
    devices: list[DeviceSpec],
    shards: list[list[str]],
    base_config: dict[str, Any],
    args: argparse.Namespace,
) -> list[ShardResult]:
    results: list[ShardResult] = []
    for idx, (device, task_list) in enumerate(zip(devices, shards)):
        safe_serial = device.serial.replace("-", "_")
        shard_name = f"shard_{idx:02d}_{safe_serial}"
        shard_dir = run_dir / "parallel" / "shards" / shard_name
        output_root = shard_dir / "run"
        shard_dir.mkdir(parents=True, exist_ok=True)

        # Write tasks file
        tasks_path = shard_dir / "tasks.txt"
        tasks_path.write_text(
            "\n".join(task_list) + "\n", encoding="utf-8",
        )

        # Write worker config YAML overlay
        worker_cfg = create_worker_config(
            base_config, device, str(output_root), args,
        )
        config_path = shard_dir / "worker_config.yaml"
        config_path.write_text(
            yaml.dump(worker_cfg, default_flow_style=False),
            encoding="utf-8",
        )

        results.append(ShardResult(
            shard_index=idx,
            device=device,
            tasks=task_list,
            shard_dir=shard_dir,
            output_root=output_root,
            config_path=config_path,
            tasks_path=tasks_path,
            stdout_log=shard_dir / "runner_stdout.log",
        ))
    return results


def _build_and_install_bridge_once_per_device(
    shard_results: list[ShardResult],
    workspace_root: Path,
) -> None:
    apk_path = build_bridge_apk(workspace_root)
    for sr in shard_results:
        worker_config = load_config_from_path(workspace_root, sr.config_path)
        install_bridge_apk(worker_config, apk_path)


def should_perform_bridge_setup(base_config: dict[str, Any]) -> bool:
    runner_cfg = base_config.get("runner") or {}
    return bool(runner_cfg.get("perform_bridge_setup", True))


def run_supervisor_bridge_setup(
    shard_results: list[ShardResult],
    workspace_root: Path,
    base_config: dict[str, Any],
) -> bool:
    if not should_perform_bridge_setup(base_config):
        return False
    _build_and_install_bridge_once_per_device(shard_results, workspace_root)
    return True


# ---------------------------------------------------------------------------
# Subprocess launch & signal handling
# ---------------------------------------------------------------------------

# Module-level list so the signal handler can access running workers.
_active_workers: list[tuple[subprocess.Popen[str], ShardResult, TextIO]] = []


def _install_signal_handlers() -> None:
    def handler(signum: int, _frame: Any) -> None:
        sig_name = signal.Signals(signum).name
        print(f"\n[parallel] Received {sig_name}, forwarding to workers...")
        for proc, sr, _ in _active_workers:
            if proc.poll() is None:
                try:
                    proc.send_signal(signum)
                except OSError:
                    pass
        for proc, sr, log_fh in _active_workers:
            try:
                proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=5)
            sr.exit_code = proc.returncode
            sr.end_time = time.time()
            _close_log(log_fh)
        raise SystemExit(128 + signum)

    signal.signal(signal.SIGINT, handler)
    signal.signal(signal.SIGTERM, handler)


def _launch_workers(
    shard_results: list[ShardResult],
    workspace_root: Path,
) -> list[tuple[subprocess.Popen[str], ShardResult, TextIO]]:
    runner_script = str(workspace_root / "eval" / "aw_bridge" / "runner.py")
    workers: list[tuple[subprocess.Popen[str], ShardResult, TextIO]] = []
    for sr in shard_results:
        log_fh: TextIO = open(sr.stdout_log, "w", encoding="utf-8")  # noqa: SIM115
        cmd = [
            sys.executable,
            runner_script,
            "--config", str(sr.config_path),
            "--tasks-file", str(sr.tasks_path),
        ]
        sr.start_time = time.time()
        proc = subprocess.Popen(
            cmd,
            stdout=log_fh,
            stderr=subprocess.STDOUT,
            cwd=str(workspace_root),
            text=True,
        )
        workers.append((proc, sr, log_fh))
        print(
            f"[parallel] Launched shard {sr.shard_index} "
            f"(device={sr.device.serial}, tasks={len(sr.tasks)}, pid={proc.pid})"
        )
    return workers


def _wait_for_workers(
    workers: list[tuple[subprocess.Popen[str], ShardResult, TextIO]],
) -> None:
    pending = list(workers)
    while pending:
        still_running: list[tuple[subprocess.Popen[str], ShardResult, TextIO]] = []
        for proc, sr, log_fh in pending:
            ret = proc.poll()
            if ret is not None:
                sr.exit_code = ret
                sr.end_time = time.time()
                _close_log(log_fh)
                elapsed = sr.end_time - (sr.start_time or sr.end_time)
                status = "OK" if ret == 0 else f"FAILED (exit={ret})"
                print(
                    f"[parallel] Shard {sr.shard_index} finished: "
                    f"{status} ({elapsed:.0f}s)"
                )
            else:
                still_running.append((proc, sr, log_fh))
        pending = still_running
        if pending:
            time.sleep(2)


def _close_log(fh: TextIO) -> None:
    try:
        if not fh.closed:
            fh.close()
    except OSError:
        pass


# ---------------------------------------------------------------------------
# Result merging
# ---------------------------------------------------------------------------

def _find_shard_run_dir(shard: ShardResult) -> Path | None:
    """Locate the timestamp-named subdirectory created by runner.py."""
    if not shard.output_root.exists():
        return None
    subdirs = sorted(
        [d for d in shard.output_root.iterdir() if d.is_dir()],
        key=lambda d: d.name,
    )
    return subdirs[-1] if subdirs else None


def task_result_from_dict(row: dict[str, Any]) -> TaskResult:
    """Deserialize a per_task.jsonl row into a TaskResult.

    Mirrors ``eval.analysis.summarize._task_result_from_dict`` so that
    parallel_runner stays decoupled from that module's private API.
    """
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


def merge_results(
    shard_results: list[ShardResult],
    run_dir: Path,
) -> dict[str, Any]:
    """Merge per-shard outputs into a single summary."""
    all_rows: list[dict[str, Any]] = []
    shard_summaries: list[dict[str, Any]] = []

    for sr in shard_results:
        shard_run_dir = _find_shard_run_dir(sr)
        shard_info: dict[str, Any] = {
            "shard_index": sr.shard_index,
            "device": sr.device.serial,
            "console_port": sr.device.console_port,
            "grpc_port": sr.device.grpc_port,
            "num_tasks": len(sr.tasks),
            "tasks": sr.tasks,
            "exit_code": sr.exit_code,
            "shard_dir": str(sr.shard_dir),
            "duration_sec": (
                (sr.end_time - sr.start_time)
                if sr.start_time is not None and sr.end_time is not None
                else None
            ),
        }
        if shard_run_dir:
            per_task_path = shard_run_dir / "per_task.jsonl"
            if per_task_path.exists():
                rows = read_jsonl(per_task_path)
                all_rows.extend(rows)
                shard_info["per_task_count"] = len(rows)
            else:
                shard_info["per_task_count"] = 0
            shard_info["run_dir"] = str(shard_run_dir)
        else:
            shard_info["per_task_count"] = 0
            shard_info["run_dir"] = None
        shard_summaries.append(shard_info)

    # Write merged per_task.jsonl (all attempts from all shards)
    merged_jsonl = run_dir / "per_task.jsonl"
    with merged_jsonl.open("w", encoding="utf-8") as f:
        for row in all_rows:
            f.write(json.dumps(row, ensure_ascii=True) + "\n")

    # Select final attempt per task instance: group by (task_name, seed),
    # take the highest attempt number (runner.py already resolved retries
    # within each shard; this handles the cross-shard view).
    all_results = [task_result_from_dict(r) for r in all_rows]
    final_by_key: dict[tuple[str, int | None], TaskResult] = {}
    for result in all_results:
        key = (result.task_name, result.seed)
        existing = final_by_key.get(key)
        if existing is None or result.attempt > existing.attempt:
            final_by_key[key] = result
    final_results = list(final_by_key.values())

    metrics = summarize_results(final_results)

    return {
        "num_shards": len(shard_results),
        "num_devices": len(shard_results),
        "num_task_instances": len(final_results),
        "num_attempts": len(all_results),
        "metrics": metrics,
        "shards": shard_summaries,
    }


def _write_shard_manifest(
    run_dir: Path,
    shard_results: list[ShardResult],
) -> None:
    manifest = {
        "num_shards": len(shard_results),
        "shards": [
            {
                "shard_index": sr.shard_index,
                "device_serial": sr.device.serial,
                "console_port": sr.device.console_port,
                "grpc_port": sr.device.grpc_port,
                "tasks": sr.tasks,
                "shard_dir": str(sr.shard_dir),
                "config_path": str(sr.config_path),
                "tasks_path": str(sr.tasks_path),
                "stdout_log": str(sr.stdout_log),
                "exit_code": sr.exit_code,
                "start_time": sr.start_time,
                "end_time": sr.end_time,
            }
            for sr in shard_results
        ],
    }
    path = run_dir / "parallel" / "shard_manifest.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(manifest, ensure_ascii=True, indent=2),
        encoding="utf-8",
    )


def _build_summary_config(
    base_config: dict[str, Any],
    args: argparse.Namespace,
    devices: list[DeviceSpec],
) -> dict[str, Any]:
    cfg = copy.deepcopy(base_config)
    runner_cfg = cfg.setdefault("runner", {})
    if args.suite is not None:
        cfg["suite_family"] = args.suite
    runner_cfg["output_root"] = args.output_root
    runner_cfg["perform_bridge_setup"] = should_perform_bridge_setup(base_config)
    if args.n_task_combinations is not None:
        runner_cfg["n_task_combinations"] = args.n_task_combinations
    if args.task_random_seed is not None:
        runner_cfg["task_random_seed"] = args.task_random_seed

    parallel_cfg = cfg.setdefault("parallel", {})
    parallel_cfg["devices"] = [
        {
            "serial": device.serial,
            "console_port": device.console_port,
            "grpc_port": device.grpc_port,
        }
        for device in devices
    ]
    parallel_cfg["worker_perform_bridge_setup"] = False
    parallel_cfg["worker_auto_start_emulator"] = False
    return cfg


# ---------------------------------------------------------------------------
# Main orchestrator
# ---------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> None:
    args = _parse_args(argv)
    workspace_root = Path(__file__).resolve().parents[2]

    # 1. Parse and validate device specs
    devices = [parse_device_spec(d) for d in args.devices]
    validate_device_specs(devices)
    print(f"[parallel] Devices: {len(devices)}")
    for d in devices:
        print(f"  {d.serial} console={d.console_port} grpc={d.grpc_port}")

    # 2. Resolve task list
    tasks = resolve_task_list(workspace_root, args)
    print(f"[parallel] Tasks: {len(tasks)}")
    if len(tasks) < len(devices):
        print(
            f"[parallel] WARNING: fewer tasks ({len(tasks)}) than devices "
            f"({len(devices)}); some devices will be idle"
        )

    # 3. Shard tasks
    shards = shard_tasks(tasks, len(devices))
    devices, shards = filter_active_device_shards(devices, shards)
    for i, shard in enumerate(shards):
        print(f"  shard {i}: {len(shard)} tasks")

    # 4. Load base config
    base_config = _load_base_config(workspace_root, args.config)

    # 5. Create run directory
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    run_dir = (workspace_root / args.output_root / timestamp).resolve()
    run_dir.mkdir(parents=True, exist_ok=True)
    print(f"[parallel] Run directory: {run_dir}")

    # 6. Setup shard directories, configs, and task files
    shard_results = _setup_shard_dirs(
        run_dir, devices, shards, base_config, args,
    )

    # 7. Build once, install once per device
    if run_supervisor_bridge_setup(shard_results, workspace_root, base_config):
        print("[parallel] Built and installed bridge APK once per device.")
    else:
        print(
            "[parallel] Skipping bridge APK build/install "
            "(runner.perform_bridge_setup=false)"
        )

    # 8. Install signal handlers
    global _active_workers  # noqa: PLW0603
    _install_signal_handlers()

    # 9. Launch workers
    workers = _launch_workers(shard_results, workspace_root)
    _active_workers = workers

    # 10. Wait for all workers
    try:
        _wait_for_workers(workers)
    finally:
        # Ensure all log file handles are closed
        for _, _, log_fh in workers:
            _close_log(log_fh)

    # 11. Write shard manifest
    _write_shard_manifest(run_dir, shard_results)

    # 12. Merge results
    merged = merge_results(shard_results, run_dir)
    summary_payload = {
        "run_timestamp": timestamp,
        "suite_family": args.suite or base_config.get("suite_family", "android_world"),
        "num_task_instances": merged["num_task_instances"],
        "num_attempts": merged["num_attempts"],
        "config": _build_summary_config(base_config, args, devices),
        "metrics": merged["metrics"],
        "parallel": {
            "num_shards": merged["num_shards"],
            "num_devices": merged["num_devices"],
            "shards": merged["shards"],
        },
    }

    summary_path = run_dir / "summary.json"
    summary_path.write_text(
        json.dumps(summary_payload, ensure_ascii=True, indent=2),
        encoding="utf-8",
    )
    print(f"\n[parallel] Wrote summary: {summary_path}")
    print(json.dumps(summary_payload["metrics"], ensure_ascii=True, indent=2))

    # 13. Exit code: 0 if all shards succeeded, 1 otherwise
    any_failed = any(sr.exit_code != 0 for sr in shard_results)
    if any_failed:
        failed = [
            f"shard {sr.shard_index} (exit={sr.exit_code})"
            for sr in shard_results
            if sr.exit_code != 0
        ]
        print(f"[parallel] FAILED shards: {', '.join(failed)}")
        raise SystemExit(1)

    print("[parallel] All shards completed successfully.")


if __name__ == "__main__":
    main()
