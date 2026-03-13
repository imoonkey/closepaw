from __future__ import annotations

import unittest
from unittest import mock

from eval.aw_bridge.native_agent_bridge import BridgeConfig
from eval.aw_bridge.runner import RunnerConfig
from eval.aw_bridge.runner_preflight import (
    PreflightError,
    PreflightErrorCode,
    SnapshotPolicy,
    resolve_snapshot_policy,
    run_preflight_checks,
    should_run_emulator_setup_retry,
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
        shizuku_apk_path=None,
        excluded_tools="",
        clear_memory_before_task=True,
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
        perform_bridge_setup=True,
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
        self.assertTrue(should_run_emulator_setup_retry(config, err))

    def test_no_retry_for_non_preflight_error(self) -> None:
        config = _runner_config()
        self.assertFalse(should_run_emulator_setup_retry(config, RuntimeError("x")))


class RunnerPreflightBridgeSetupFlagTest(unittest.TestCase):
    def test_skips_bridge_setup_when_disabled(self) -> None:
        config = _runner_config(
            skip_unavailable_tasks=True,
            auto_install_missing_task_apps=False,
            perform_bridge_setup=False,
        )
        task_instances = [object()]

        with mock.patch("eval.aw_bridge.runner_preflight.ensure_adb_device_ready"), mock.patch(
            "eval.aw_bridge.runner_preflight.ensure_task_app_snapshots"
        ), mock.patch(
            "eval.aw_bridge.runner_preflight.filter_unavailable_task_instances",
            return_value=task_instances,
        ), mock.patch(
            "eval.aw_bridge.runner_preflight.build_and_install_bridge"
        ) as build_mock:
            returned = run_preflight_checks(config, task_instances, env=object())

        self.assertEqual(returned, task_instances)
        build_mock.assert_not_called()

    def test_runs_bridge_setup_when_enabled(self) -> None:
        config = _runner_config(
            skip_unavailable_tasks=True,
            auto_install_missing_task_apps=False,
            perform_bridge_setup=True,
        )
        task_instances = [object()]

        with mock.patch("eval.aw_bridge.runner_preflight.ensure_adb_device_ready"), mock.patch(
            "eval.aw_bridge.runner_preflight.ensure_task_app_snapshots"
        ), mock.patch(
            "eval.aw_bridge.runner_preflight.filter_unavailable_task_instances",
            return_value=task_instances,
        ), mock.patch(
            "eval.aw_bridge.runner_preflight.build_and_install_bridge"
        ) as build_mock:
            returned = run_preflight_checks(config, task_instances, env=object())

        self.assertEqual(returned, task_instances)
        build_mock.assert_called_once_with(config)


if __name__ == "__main__":
    unittest.main()
