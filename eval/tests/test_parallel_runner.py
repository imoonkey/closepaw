"""Unit tests for parallel_runner.py.

Tests cover pure logic functions only (no subprocess or real filesystem side
effects beyond pytest's ``tmp_path`` fixture).
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from unittest import mock

import pytest

from eval.aw_bridge.parallel_runner import (
    _load_base_config,
    _build_summary_config,
    _build_and_install_bridge_once_per_device,
    DeviceSpec,
    ShardResult,
    create_worker_config,
    filter_active_device_shards,
    merge_results,
    parse_device_spec,
    resolve_task_list,
    run_supervisor_bridge_setup,
    shard_tasks,
    should_perform_bridge_setup,
    task_result_from_dict,
    validate_device_specs,
)


# ---------------------------------------------------------------------------
# parse_device_spec
# ---------------------------------------------------------------------------

class TestParseDeviceSpec:
    def test_valid_spec(self) -> None:
        spec = parse_device_spec("emulator-5554:5554:8554")
        assert spec == DeviceSpec("emulator-5554", 5554, 8554)

    def test_valid_spec_with_whitespace(self) -> None:
        spec = parse_device_spec("  emulator-5554:5554:8554  ")
        assert spec == DeviceSpec("emulator-5554", 5554, 8554)

    def test_invalid_too_few_parts(self) -> None:
        with pytest.raises(ValueError, match="Invalid --device format"):
            parse_device_spec("emulator-5554:5554")

    def test_invalid_too_many_parts(self) -> None:
        with pytest.raises(ValueError, match="Invalid --device format"):
            parse_device_spec("emulator-5554:5554:8554:extra")

    def test_non_integer_port(self) -> None:
        with pytest.raises(ValueError, match="Non-integer port"):
            parse_device_spec("emulator-5554:abc:8554")

    def test_empty_serial(self) -> None:
        with pytest.raises(ValueError, match="Empty serial"):
            parse_device_spec(":5554:8554")


# ---------------------------------------------------------------------------
# validate_device_specs
# ---------------------------------------------------------------------------

class TestValidateDeviceSpecs:
    def test_valid(self) -> None:
        specs = [
            DeviceSpec("emulator-5554", 5554, 8554),
            DeviceSpec("emulator-5556", 5556, 8556),
        ]
        validate_device_specs(specs)  # should not raise

    def test_single_device(self) -> None:
        validate_device_specs([DeviceSpec("emulator-5554", 5554, 8554)])

    def test_empty_raises(self) -> None:
        with pytest.raises(ValueError, match="At least one"):
            validate_device_specs([])

    def test_duplicate_serial(self) -> None:
        specs = [
            DeviceSpec("emulator-5554", 5554, 8554),
            DeviceSpec("emulator-5554", 5556, 8556),
        ]
        with pytest.raises(ValueError, match="Duplicate device serial"):
            validate_device_specs(specs)

    def test_duplicate_console_port(self) -> None:
        specs = [
            DeviceSpec("emulator-5554", 5554, 8554),
            DeviceSpec("emulator-5556", 5554, 8556),
        ]
        with pytest.raises(ValueError, match="Duplicate console port"):
            validate_device_specs(specs)

    def test_duplicate_grpc_port(self) -> None:
        specs = [
            DeviceSpec("emulator-5554", 5554, 8554),
            DeviceSpec("emulator-5556", 5556, 8554),
        ]
        with pytest.raises(ValueError, match="Duplicate gRPC port"):
            validate_device_specs(specs)


# ---------------------------------------------------------------------------
# shard_tasks
# ---------------------------------------------------------------------------

class TestShardTasks:
    def test_round_robin_two_shards(self) -> None:
        tasks = ["t0", "t1", "t2", "t3", "t4"]
        result = shard_tasks(tasks, 2)
        assert result == [["t0", "t2", "t4"], ["t1", "t3"]]

    def test_round_robin_three_shards(self) -> None:
        tasks = ["a", "b", "c", "d"]
        result = shard_tasks(tasks, 3)
        assert result == [["a", "d"], ["b"], ["c"]]

    def test_single_shard(self) -> None:
        tasks = ["t0", "t1", "t2"]
        result = shard_tasks(tasks, 1)
        assert result == [["t0", "t1", "t2"]]

    def test_more_shards_than_tasks(self) -> None:
        tasks = ["t0"]
        result = shard_tasks(tasks, 3)
        assert result == [["t0"], [], []]

    def test_empty_tasks(self) -> None:
        result = shard_tasks([], 2)
        assert result == [[], []]

    def test_deterministic(self) -> None:
        tasks = ["a", "b", "c", "d"]
        r1 = shard_tasks(tasks, 3)
        r2 = shard_tasks(tasks, 3)
        assert r1 == r2

    def test_equal_split(self) -> None:
        tasks = ["t0", "t1", "t2", "t3"]
        result = shard_tasks(tasks, 2)
        assert result == [["t0", "t2"], ["t1", "t3"]]


class TestFilterActiveDeviceShards:
    def test_filters_empty_shards(self) -> None:
        devices = [
            DeviceSpec("emulator-5554", 5554, 8554),
            DeviceSpec("emulator-5556", 5556, 8556),
            DeviceSpec("emulator-5558", 5558, 8558),
        ]
        active_devices, active_shards = filter_active_device_shards(
            devices,
            [["T0"], [], ["T2"]],
        )
        assert active_devices == [devices[0], devices[2]]
        assert active_shards == [["T0"], ["T2"]]

    def test_raises_when_all_shards_empty(self) -> None:
        devices = [DeviceSpec("emulator-5554", 5554, 8554)]
        with pytest.raises(ValueError, match="No task shards contain runnable tasks"):
            filter_active_device_shards(devices, [[]])


# ---------------------------------------------------------------------------
# create_worker_config
# ---------------------------------------------------------------------------

class TestCreateWorkerConfig:
    @staticmethod
    def _base_config() -> dict:
        return {
            "suite_family": "android_world",
            "runner": {
                "output_root": "eval/results",
                "adb_serial": None,
                "perform_bridge_setup": True,
            },
            "android_world": {
                "console_port": 5554,
                "grpc_port": 8554,
                "auto_start_emulator": True,
            },
            "bridge": {"llm_backend": "openai"},
        }

    @staticmethod
    def _default_args(**overrides: object) -> argparse.Namespace:
        defaults = {
            "suite": None,
            "n_task_combinations": None,
            "task_random_seed": None,
            "output_root": "eval/results",
        }
        defaults.update(overrides)
        return argparse.Namespace(**defaults)

    def test_overrides_device_fields(self) -> None:
        base = self._base_config()
        device = DeviceSpec("emulator-5556", 5556, 8556)
        cfg = create_worker_config(base, device, "shards/s0/run", self._default_args())
        assert cfg["runner"]["adb_serial"] == "emulator-5556"
        assert cfg["runner"]["output_root"] == "shards/s0/run"
        assert cfg["runner"]["perform_bridge_setup"] is False
        assert cfg["android_world"]["console_port"] == 5556
        assert cfg["android_world"]["grpc_port"] == 8556
        assert cfg["android_world"]["auto_start_emulator"] is False

    def test_does_not_mutate_base(self) -> None:
        base = self._base_config()
        device = DeviceSpec("emulator-5556", 5556, 8556)
        create_worker_config(base, device, "shards/s0/run", self._default_args())
        assert base["runner"]["adb_serial"] is None
        assert base["runner"]["perform_bridge_setup"] is True
        assert base["android_world"]["console_port"] == 5554
        assert base["android_world"]["auto_start_emulator"] is True

    def test_forwards_suite_override(self) -> None:
        base = self._base_config()
        device = DeviceSpec("emulator-5554", 5554, 8554)
        args = self._default_args(suite="custom_suite")
        cfg = create_worker_config(base, device, "out", args)
        assert cfg["suite_family"] == "custom_suite"

    def test_forwards_n_task_combinations(self) -> None:
        base = self._base_config()
        device = DeviceSpec("emulator-5554", 5554, 8554)
        args = self._default_args(n_task_combinations=3)
        cfg = create_worker_config(base, device, "out", args)
        assert cfg["runner"]["n_task_combinations"] == 3

    def test_forwards_task_random_seed(self) -> None:
        base = self._base_config()
        device = DeviceSpec("emulator-5554", 5554, 8554)
        args = self._default_args(task_random_seed=42)
        cfg = create_worker_config(base, device, "out", args)
        assert cfg["runner"]["task_random_seed"] == 42

    def test_no_suite_if_not_specified(self) -> None:
        base = self._base_config()
        device = DeviceSpec("emulator-5554", 5554, 8554)
        cfg = create_worker_config(base, device, "out", self._default_args())
        # Should keep original suite_family, not overwrite
        assert cfg["suite_family"] == "android_world"

    def test_creates_missing_sections(self) -> None:
        base = {"suite_family": "android_world"}
        device = DeviceSpec("emulator-5554", 5554, 8554)
        cfg = create_worker_config(base, device, "out", self._default_args())
        assert cfg["runner"]["adb_serial"] == "emulator-5554"
        assert cfg["runner"]["perform_bridge_setup"] is False
        assert cfg["android_world"]["console_port"] == 5554
        assert cfg["android_world"]["auto_start_emulator"] is False


class TestLoadBaseConfig:
    def test_overlay_config_deep_merges_default_yaml(self, tmp_path: Path) -> None:
        config_dir = tmp_path / "eval" / "config"
        config_dir.mkdir(parents=True, exist_ok=True)
        (config_dir / "default.yaml").write_text(
            (
                "runner:\n"
                "  perform_bridge_setup: true\n"
                "android_world:\n"
                "  adb_path: /usr/local/bin/adb\n"
                "bridge:\n"
                "  task_overrides:\n"
                "    BrowserDraw:\n"
                "      perception_mode: hybrid\n"
            ),
            encoding="utf-8",
        )
        (config_dir / "remote.yaml").write_text(
            (
                "android_world:\n"
                "  adb_path: ~/android-sdk/platform-tools/adb\n"
                "bridge:\n"
                "  task_overrides:\n"
                "    BrowserDraw:\n"
                "      max_turns: 60\n"
            ),
            encoding="utf-8",
        )

        cfg = _load_base_config(tmp_path, "eval/config/remote.yaml")

        assert cfg["runner"]["perform_bridge_setup"] is True
        assert cfg["android_world"]["adb_path"] == "~/android-sdk/platform-tools/adb"
        assert cfg["bridge"]["task_overrides"]["BrowserDraw"] == {
            "perception_mode": "hybrid",
            "max_turns": 60,
        }


# ---------------------------------------------------------------------------
# resolve_task_list
# ---------------------------------------------------------------------------

class TestResolveTaskList:
    def test_from_tasks_arg(self, tmp_path: Path) -> None:
        args = argparse.Namespace(tasks="Task1,Task2,Task3", tasks_file=None)
        result = resolve_task_list(tmp_path, args)
        assert result == ["Task1", "Task2", "Task3"]

    def test_from_tasks_arg_strips_whitespace(self, tmp_path: Path) -> None:
        args = argparse.Namespace(tasks=" Task1 , Task2 ", tasks_file=None)
        result = resolve_task_list(tmp_path, args)
        assert result == ["Task1", "Task2"]

    def test_from_tasks_file(self, tmp_path: Path) -> None:
        tasks_file = tmp_path / "tasks.txt"
        tasks_file.write_text("TaskA\nTaskB\n# comment\n\nTaskC\n")
        args = argparse.Namespace(tasks=None, tasks_file=str(tasks_file))
        result = resolve_task_list(tmp_path, args)
        assert result == ["TaskA", "TaskB", "TaskC"]

    def test_neither_raises(self, tmp_path: Path) -> None:
        args = argparse.Namespace(tasks=None, tasks_file=None)
        with pytest.raises(ValueError, match="Either --tasks-file or --tasks"):
            resolve_task_list(tmp_path, args)

    def test_missing_file_raises(self, tmp_path: Path) -> None:
        args = argparse.Namespace(tasks=None, tasks_file="nonexistent.txt")
        with pytest.raises(FileNotFoundError, match="Tasks file not found"):
            resolve_task_list(tmp_path, args)

    def test_tasks_takes_precedence(self, tmp_path: Path) -> None:
        # When both are provided, --tasks wins
        tasks_file = tmp_path / "tasks.txt"
        tasks_file.write_text("TaskFromFile\n")
        args = argparse.Namespace(tasks="TaskFromArg", tasks_file=str(tasks_file))
        result = resolve_task_list(tmp_path, args)
        assert result == ["TaskFromArg"]


# ---------------------------------------------------------------------------
# task_result_from_dict
# ---------------------------------------------------------------------------

class TestTaskResultFromDict:
    @staticmethod
    def _sample_row(**overrides: object) -> dict:
        base: dict = {
            "task_name": "BrowserMultiply",
            "suite_family": "android_world",
            "seed": 30,
            "goal": "Multiply 3 by 7",
            "run_id": "aw_test_0_0",
            "attempt": 0,
            "bridge_status": "completed",
            "agent_completion_reason": "GoalAchieved",
            "task_status": "success",
            "answer": "21",
            "scripted_score": 1.0,
            "scripted_success": True,
            "duration_sec": 42.5,
            "turns_executed": 5,
            "tool_calls": 10,
            "tool_failures": 1,
            "artifact_paths": {
                "trace_dir": "/tmp/trace",
                "logcat": "/tmp/logcat.log",
                "runner_log": "/tmp/runner.log",
            },
            "exception": None,
        }
        base.update(overrides)
        return base

    def test_basic_deserialization(self) -> None:
        row = self._sample_row()
        result = task_result_from_dict(row)
        assert result.task_name == "BrowserMultiply"
        assert result.scripted_success is True
        assert result.duration_sec == 42.5
        assert result.tool_calls == 10
        assert result.artifact_paths.trace_dir == "/tmp/trace"

    def test_missing_optional_fields(self) -> None:
        row = self._sample_row()
        del row["agent_completion_reason"]
        del row["task_status"]
        del row["answer"]
        del row["scripted_score"]
        del row["exception"]
        result = task_result_from_dict(row)
        assert result.agent_completion_reason is None
        assert result.task_status is None
        assert result.answer is None

    def test_missing_artifact_paths(self) -> None:
        row = self._sample_row(artifact_paths=None)
        result = task_result_from_dict(row)
        assert result.artifact_paths.trace_dir is None
        assert result.artifact_paths.logcat is None


# ---------------------------------------------------------------------------
# merge_results
# ---------------------------------------------------------------------------

def _make_shard_result(
    tmp_path: Path,
    shard_index: int,
    device: DeviceSpec,
    tasks: list[str],
    rows: list[dict] | None = None,
    exit_code: int = 0,
) -> ShardResult:
    """Create a ShardResult with optional per_task.jsonl data."""
    shard_dir = tmp_path / f"shards/shard_{shard_index:02d}"
    output_root = shard_dir / "run"
    run_dir = output_root / "20260218_120000"
    run_dir.mkdir(parents=True, exist_ok=True)

    if rows is not None:
        per_task = run_dir / "per_task.jsonl"
        with per_task.open("w", encoding="utf-8") as f:
            for row in rows:
                f.write(json.dumps(row) + "\n")

    return ShardResult(
        shard_index=shard_index,
        device=device,
        tasks=tasks,
        shard_dir=shard_dir,
        output_root=output_root,
        config_path=shard_dir / "worker_config.yaml",
        tasks_path=shard_dir / "tasks.txt",
        stdout_log=shard_dir / "runner_stdout.log",
        exit_code=exit_code,
        start_time=1000.0,
        end_time=1100.0,
    )


def _sample_jsonl_row(
    task_name: str = "TestTask",
    attempt: int = 0,
    scripted_success: bool = True,
    duration_sec: float = 60.0,
    bridge_status: str = "completed",
    seed: int | None = 30,
) -> dict:
    return {
        "task_name": task_name,
        "suite_family": "android_world",
        "seed": seed,
        "goal": f"Do {task_name}",
        "run_id": f"aw_test_{task_name}_{attempt}",
        "attempt": attempt,
        "bridge_status": bridge_status,
        "agent_completion_reason": "GoalAchieved" if scripted_success else "GoalNotAchieved",
        "task_status": "success" if scripted_success else "failure",
        "answer": "done",
        "scripted_score": 1.0 if scripted_success else 0.0,
        "scripted_success": scripted_success,
        "duration_sec": duration_sec,
        "turns_executed": 3,
        "tool_calls": 5,
        "tool_failures": 0,
        "artifact_paths": {"trace_dir": None, "logcat": None, "runner_log": None},
        "exception": None,
    }


class TestMergeResults:
    def test_two_shards_concatenated(self, tmp_path: Path) -> None:
        d0 = DeviceSpec("emulator-5554", 5554, 8554)
        d1 = DeviceSpec("emulator-5556", 5556, 8556)
        sr0 = _make_shard_result(tmp_path, 0, d0, ["T0", "T2"], [
            _sample_jsonl_row("T0", scripted_success=True),
            _sample_jsonl_row("T2", scripted_success=False),
        ])
        sr1 = _make_shard_result(tmp_path, 1, d1, ["T1"], [
            _sample_jsonl_row("T1", scripted_success=True),
        ])
        result = merge_results([sr0, sr1], tmp_path)
        assert result["num_shards"] == 2
        assert result["num_task_instances"] == 3
        assert result["num_attempts"] == 3
        assert result["metrics"]["num_results"] == 3
        # 2 successes out of 3
        assert abs(result["metrics"]["scripted_success_rate"] - 2 / 3) < 0.01

    def test_final_attempt_selection(self, tmp_path: Path) -> None:
        """When a task has multiple attempts, use the latest."""
        d0 = DeviceSpec("emulator-5554", 5554, 8554)
        sr0 = _make_shard_result(tmp_path, 0, d0, ["T0"], [
            _sample_jsonl_row("T0", attempt=0, scripted_success=False),
            _sample_jsonl_row("T0", attempt=1, scripted_success=True),
        ])
        result = merge_results([sr0], tmp_path)
        assert result["num_task_instances"] == 1  # deduped by (task_name, seed)
        assert result["num_attempts"] == 2
        assert result["metrics"]["scripted_success_rate"] == 1.0

    def test_empty_shards(self, tmp_path: Path) -> None:
        d0 = DeviceSpec("emulator-5554", 5554, 8554)
        sr0 = _make_shard_result(tmp_path, 0, d0, [], rows=[])
        result = merge_results([sr0], tmp_path)
        assert result["num_task_instances"] == 0
        assert result["metrics"]["num_results"] == 0

    def test_missing_output_dir(self, tmp_path: Path) -> None:
        """Shard whose runner never created output should not crash merge."""
        d0 = DeviceSpec("emulator-5554", 5554, 8554)
        sr0 = ShardResult(
            shard_index=0,
            device=d0,
            tasks=["T0"],
            shard_dir=tmp_path / "shards/shard_00",
            output_root=tmp_path / "shards/shard_00/run_nonexistent",
            config_path=tmp_path / "shards/shard_00/worker_config.yaml",
            tasks_path=tmp_path / "shards/shard_00/tasks.txt",
            stdout_log=tmp_path / "shards/shard_00/runner_stdout.log",
            exit_code=1,
            start_time=1000.0,
            end_time=1010.0,
        )
        result = merge_results([sr0], tmp_path)
        assert result["num_task_instances"] == 0
        assert result["shards"][0]["per_task_count"] == 0
        assert result["shards"][0]["run_dir"] is None

    def test_merged_jsonl_written(self, tmp_path: Path) -> None:
        d0 = DeviceSpec("emulator-5554", 5554, 8554)
        sr0 = _make_shard_result(tmp_path, 0, d0, ["T0"], [
            _sample_jsonl_row("T0"),
        ])
        merge_results([sr0], tmp_path)
        merged = tmp_path / "per_task.jsonl"
        assert merged.exists()
        lines = merged.read_text().strip().splitlines()
        assert len(lines) == 1
        row = json.loads(lines[0])
        assert row["task_name"] == "T0"

    def test_shard_timing(self, tmp_path: Path) -> None:
        d0 = DeviceSpec("emulator-5554", 5554, 8554)
        sr0 = _make_shard_result(tmp_path, 0, d0, ["T0"], [
            _sample_jsonl_row("T0"),
        ])
        result = merge_results([sr0], tmp_path)
        assert result["shards"][0]["duration_sec"] == 100.0  # 1100 - 1000


class TestBuildAndInstallBridgeOncePerDevice:
    def test_builds_once_and_installs_for_each_worker(self, tmp_path: Path) -> None:
        d0 = DeviceSpec("emulator-5554", 5554, 8554)
        d1 = DeviceSpec("emulator-5556", 5556, 8556)
        sr0 = _make_shard_result(tmp_path, 0, d0, ["T0"], rows=[])
        sr1 = _make_shard_result(tmp_path, 1, d1, ["T1"], rows=[])
        apk_path = tmp_path / "app-debug.apk"
        cfg0 = object()
        cfg1 = object()

        with mock.patch(
            "eval.aw_bridge.parallel_runner.build_bridge_apk",
            return_value=apk_path,
        ) as build_mock, mock.patch(
            "eval.aw_bridge.parallel_runner.load_config_from_path",
            side_effect=[cfg0, cfg1],
        ) as load_mock, mock.patch(
            "eval.aw_bridge.parallel_runner.install_bridge_apk",
        ) as install_mock:
            _build_and_install_bridge_once_per_device([sr0, sr1], tmp_path)

        build_mock.assert_called_once_with(tmp_path)
        assert load_mock.call_args_list == [
            mock.call(tmp_path, sr0.config_path),
            mock.call(tmp_path, sr1.config_path),
        ]
        assert install_mock.call_args_list == [
            mock.call(cfg0, apk_path),
            mock.call(cfg1, apk_path),
        ]


class TestRunSupervisorBridgeSetup:
    def test_runs_when_enabled(self, tmp_path: Path) -> None:
        base_config = {"runner": {"perform_bridge_setup": True}}

        with mock.patch(
            "eval.aw_bridge.parallel_runner._build_and_install_bridge_once_per_device"
        ) as install_mock:
            performed = run_supervisor_bridge_setup([], tmp_path, base_config)

        assert performed is True
        install_mock.assert_called_once_with([], tmp_path)

    def test_skips_when_disabled(self, tmp_path: Path) -> None:
        base_config = {"runner": {"perform_bridge_setup": False}}

        with mock.patch(
            "eval.aw_bridge.parallel_runner._build_and_install_bridge_once_per_device"
        ) as install_mock:
            performed = run_supervisor_bridge_setup([], tmp_path, base_config)

        assert performed is False
        install_mock.assert_not_called()

    def test_defaults_to_enabled_when_flag_missing(self, tmp_path: Path) -> None:
        with mock.patch(
            "eval.aw_bridge.parallel_runner._build_and_install_bridge_once_per_device"
        ) as install_mock:
            performed = run_supervisor_bridge_setup([], tmp_path, {})

        assert performed is True
        install_mock.assert_called_once_with([], tmp_path)


class TestBuildSummaryConfig:
    @staticmethod
    def _args(**overrides: object) -> argparse.Namespace:
        defaults = {
            "suite": None,
            "output_root": "eval/results",
            "n_task_combinations": None,
            "task_random_seed": None,
        }
        defaults.update(overrides)
        return argparse.Namespace(**defaults)

    def test_applies_cli_overrides(self) -> None:
        base_config = {
            "suite_family": "android_world",
            "runner": {
                "output_root": "eval/custom",
                "perform_bridge_setup": True,
                "n_task_combinations": 1,
                "task_random_seed": 30,
            },
        }
        args = self._args(
            suite="custom_suite",
            output_root="eval/results",
            n_task_combinations=5,
            task_random_seed=99,
        )

        summary_cfg = _build_summary_config(
            base_config,
            args,
            [DeviceSpec("emulator-5554", 5554, 8554)],
        )

        assert summary_cfg["suite_family"] == "custom_suite"
        assert summary_cfg["runner"]["output_root"] == "eval/results"
        assert summary_cfg["runner"]["n_task_combinations"] == 5
        assert summary_cfg["runner"]["task_random_seed"] == 99
        assert summary_cfg["runner"]["perform_bridge_setup"] is True
        assert summary_cfg["parallel"]["worker_perform_bridge_setup"] is False
        assert summary_cfg["parallel"]["worker_auto_start_emulator"] is False

    def test_preserves_disabled_bridge_setup(self) -> None:
        base_config = {"runner": {"perform_bridge_setup": False}}

        summary_cfg = _build_summary_config(
            base_config,
            self._args(),
            [DeviceSpec("emulator-5554", 5554, 8554)],
        )

        assert should_perform_bridge_setup(base_config) is False
        assert summary_cfg["runner"]["perform_bridge_setup"] is False
