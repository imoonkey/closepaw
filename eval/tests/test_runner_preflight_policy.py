from __future__ import annotations

import unittest

from eval.aw_bridge.native_agent_bridge import BridgeConfig
from eval.aw_bridge.runner import RunnerConfig, _should_run_emulator_setup_retry
from eval.aw_bridge.runner_preflight import (
    PreflightError,
    PreflightErrorCode,
    SnapshotPolicy,
    resolve_snapshot_policy,
)


def _bridge_config() -> BridgeConfig:
    return BridgeConfig(
        package_name="com.moonkey.androidagent",
        activity="com.moonkey.androidagent/.app.MainActivity",
        llm_backend="openai",
        agent_mode="basic",
        perception_mode="accessibility_only",
        platform_mode="accessibility",
        main_model="minimax-m2.5",
        executor_model="",
        max_turns=30,
        auto_start=True,
        fresh_session=True,
        debug_mode=False,
        trace_enabled=True,
        max_wait_seconds=900,
        poll_interval_seconds=1.0,
        adb_serial=None,
        stop_agent_after_task=True,
        adb_command_timeout_sec=60,
        adb_pull_timeout_sec=300,
        api_keys=None,
    )


def _runner_config(**overrides: object) -> RunnerConfig:
    config = RunnerConfig(
        suite_family="android_world",
        output_root="eval/results",
        task_random_seed=30,
        n_task_combinations=1,
        use_identical_params=False,
        skip_unavailable_tasks=False,
        auto_install_missing_task_apps=False,
        retry_infra_failures=1,
        snapshot_policy="auto_repair",
        adb_serial=None,
        reference_root=".reference/eval/android_world",
        console_port=5554,
        grpc_port=8554,
        adb_path=None,
        perform_emulator_setup=False,
        freeze_datetime=False,
        auto_start_emulator=False,
        emulator_avd_name="AndroidWorldAvd",
        emulator_binary_path=None,
        emulator_boot_timeout_sec=180,
        bridge=_bridge_config(),
        task_overrides={},
    )
    for name, value in overrides.items():
        setattr(config, name, value)
    return config


class RunnerSnapshotPolicyTest(unittest.TestCase):
    def test_resolve_snapshot_policy_default(self) -> None:
        self.assertEqual(resolve_snapshot_policy(None), SnapshotPolicy.AUTO_REPAIR)

    def test_resolve_snapshot_policy_invalid(self) -> None:
        with self.assertRaisesRegex(ValueError, "Invalid snapshot_policy"):
            resolve_snapshot_policy("bad-value")


class RunnerTypedRetryTest(unittest.TestCase):
    def test_retry_for_missing_task_packages(self) -> None:
        config = _runner_config()
        err = PreflightError(
            PreflightErrorCode.MISSING_TASK_PACKAGES,
            "missing packages",
        )
        self.assertTrue(_should_run_emulator_setup_retry(config, err))

    def test_no_retry_for_non_preflight_error(self) -> None:
        config = _runner_config()
        self.assertFalse(_should_run_emulator_setup_retry(config, RuntimeError("x")))


if __name__ == "__main__":
    unittest.main()

