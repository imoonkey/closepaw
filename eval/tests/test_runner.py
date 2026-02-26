from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from eval.aw_bridge.native_agent_bridge import BridgeConfig
from eval.aw_bridge.runner import (
    RunnerConfig,
    _validate_required_api_key,
)
from eval.aw_bridge.runner_preflight import (
    TASK_REQUIRED_PACKAGES,
    run_adb,
    run_android_world_connectivity_preflight,
    wait_for_emulator_stability,
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
    )


def _runner_config(**overrides: object) -> RunnerConfig:
    config = RunnerConfig(
        suite_family="android_world",
        output_root="eval/results",
        task_random_seed=30,
        n_task_combinations=1,
        use_identical_params=False,
        skip_unavailable_tasks=True,
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
    if "adb_serial" in overrides:
        config.bridge.adb_serial = config.adb_serial
    return config


class RunnerAdbTest(unittest.TestCase):
    def test_run_adb_uses_default_timeout(self) -> None:
        config = _runner_config(adb_serial="emulator-5554")
        completed = subprocess.CompletedProcess(args=["adb"], returncode=0, stdout="", stderr="")
        with mock.patch("eval.aw_bridge.runner_preflight.subprocess.run", return_value=completed) as run_mock:
            run_adb(config, ["devices"], check=False, capture_output=True)

        self.assertEqual(run_mock.call_args[1]["timeout"], 60.0)

    def test_run_adb_honors_timeout_override(self) -> None:
        config = _runner_config(adb_serial="emulator-5554")
        completed = subprocess.CompletedProcess(args=["adb"], returncode=0, stdout="", stderr="")
        with mock.patch("eval.aw_bridge.runner_preflight.subprocess.run", return_value=completed) as run_mock:
            run_adb(
                config,
                ["wait-for-device"],
                check=False,
                capture_output=True,
                timeout_sec=222,
            )

        self.assertEqual(run_mock.call_args[1]["timeout"], 222.0)


class RunnerConnectivityPreflightTest(unittest.TestCase):
    def test_rejects_serial_console_port_mismatch(self) -> None:
        config = _runner_config(adb_serial="emulator-5556", console_port=5554, auto_start_emulator=False)
        completed = subprocess.CompletedProcess(args=["adb"], returncode=0, stdout="", stderr="")
        with mock.patch("eval.aw_bridge.runner_preflight.run_adb_global", return_value=completed), mock.patch(
            "eval.aw_bridge.runner_preflight.is_expected_emulator_online", return_value=True
        ), mock.patch(
            "eval.aw_bridge.runner_preflight.is_local_tcp_port_open", return_value=True
        ), mock.patch(
            "eval.aw_bridge.runner_preflight.wait_for_emulator_stability"
        ) as wait_mock:
            with self.assertRaisesRegex(RuntimeError, "must match console_port mapping"):
                run_android_world_connectivity_preflight(config)

        wait_mock.assert_not_called()


class RunnerEmulatorStabilityTest(unittest.TestCase):
    def test_wait_for_device_uses_boot_timeout(self) -> None:
        config = _runner_config(adb_serial="emulator-5554", emulator_boot_timeout_sec=180)
        ok = subprocess.CompletedProcess(args=["adb"], returncode=0, stdout="ok", stderr="")
        boot_done = subprocess.CompletedProcess(args=["adb"], returncode=0, stdout="1\n", stderr="")
        whoami = subprocess.CompletedProcess(args=["adb"], returncode=0, stdout="shell\n", stderr="")

        with mock.patch("eval.aw_bridge.runner_preflight.run_adb", return_value=ok) as run_adb_mock, mock.patch(
            "eval.aw_bridge.runner_preflight.is_expected_emulator_online", return_value=True
        ), mock.patch(
            "eval.aw_bridge.runner_preflight.run_adb_shell", side_effect=[boot_done, whoami]
        ), mock.patch("eval.aw_bridge.runner_preflight.time.sleep"):
            wait_for_emulator_stability(config, "emulator-5554")

        first_call = run_adb_mock.call_args_list[0]
        self.assertEqual(first_call[1]["timeout_sec"], 180)


class RunnerApiKeyValidationTest(unittest.TestCase):
    def _workspace_with_catalog(self, catalog: dict[str, dict[str, str]]) -> Path:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        catalog_path = root / "app" / "src" / "main" / "assets"
        catalog_path.mkdir(parents=True, exist_ok=True)
        (catalog_path / "llm_models.json").write_text(
            json.dumps(catalog, ensure_ascii=True, indent=2),
            encoding="utf-8",
        )
        return root

    def test_openai_protocol_uses_model_provider_key(self) -> None:
        config = _runner_config()
        config.bridge.llm_backend = "openai"
        config.bridge.main_model = "glm-5"
        workspace = self._workspace_with_catalog(
            {
                "glm-5": {"provider": "OPENROUTER"},
            }
        )

        _validate_required_api_key(
            config,
            {"OPENROUTER_API_KEY": "ok"},
            workspace_root=workspace,
        )

    def test_missing_provider_key_raises(self) -> None:
        config = _runner_config()
        config.bridge.llm_backend = "openai"
        config.bridge.main_model = "glm-5"
        workspace = self._workspace_with_catalog(
            {
                "glm-5": {"provider": "OPENROUTER"},
            }
        )

        with self.assertRaisesRegex(RuntimeError, "OPENROUTER_API_KEY"):
            _validate_required_api_key(config, {}, workspace_root=workspace)

    def test_local_backend_skips_cloud_key_validation(self) -> None:
        config = _runner_config()
        config.bridge.llm_backend = "local"
        workspace = self._workspace_with_catalog({})

        _validate_required_api_key(config, {}, workspace_root=workspace)

    def test_mixed_main_and_executor_require_both_keys(self) -> None:
        config = _runner_config()
        config.bridge.llm_backend = "openai"
        config.bridge.main_model = "glm-5"
        config.bridge.executor_model = "gpt-5.2"
        workspace = self._workspace_with_catalog(
            {
                "glm-5": {"provider": "OPENROUTER"},
                "gpt-5.2": {"provider": "OPENAI"},
            }
        )

        with self.assertRaisesRegex(RuntimeError, "OPENAI_API_KEY"):
            _validate_required_api_key(
                config,
                {"OPENROUTER_API_KEY": "ok"},
                workspace_root=workspace,
            )


class RunnerTaskPackageMapTest(unittest.TestCase):
    def test_includes_recipe_and_sms_requirements(self) -> None:
        self.assertEqual(
            TASK_REQUIRED_PACKAGES.get("RecipeAddSingleRecipe"),
            ("com.flauschcode.broccoli",),
        )
        self.assertEqual(
            TASK_REQUIRED_PACKAGES.get("SimpleSmsSend"),
            ("com.simplemobiletools.smsmessenger",),
        )


if __name__ == "__main__":
    unittest.main()
