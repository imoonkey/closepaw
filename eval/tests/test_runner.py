from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock
import argparse

from eval.aw_bridge.native_agent_bridge import BridgeConfig
from eval.aw_bridge.runner import (
    RunnerConfig,
    _validate_required_api_key,
    load_config,
    load_config_from_path,
)
from eval.aw_bridge.runner_preflight import (
    TASK_REQUIRED_PACKAGES,
    run_adb,
    run_adb_global,
    run_android_world_connectivity_preflight,
    wait_for_emulator_stability,
)


def _bridge_config() -> BridgeConfig:
    return BridgeConfig(
        package_name="ai.closepaw",
        activity="ai.closepaw/.app.MainActivity",
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
        skip_unavailable_tasks=True,
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
    if "adb_serial" in overrides:
        config.bridge.adb_serial = config.adb_serial
    return config


class RunnerAdbTest(unittest.TestCase):
    def test_load_default_config_disables_memory_for_eval(self) -> None:
        workspace_root = Path(__file__).resolve().parents[2]
        config = load_config_from_path(workspace_root, "eval/config/default.yaml")

        self.assertIn("ask_user", config.bridge.excluded_tools)
        self.assertIn("remember_experience", config.bridge.excluded_tools)
        self.assertTrue(config.bridge.clear_memory_before_task)

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

    def test_run_adb_uses_configured_binary(self) -> None:
        config = _runner_config(adb_serial="emulator-5554", adb_path="/opt/android/platform-tools/adb")
        completed = subprocess.CompletedProcess(args=["adb"], returncode=0, stdout="", stderr="")
        with mock.patch("eval.aw_bridge.runner_preflight.subprocess.run", return_value=completed) as run_mock:
            run_adb(config, ["devices"], check=False, capture_output=True)

        self.assertEqual(
            run_mock.call_args[0][0],
            ["/opt/android/platform-tools/adb", "-s", "emulator-5554", "devices"],
        )

    def test_run_adb_global_uses_configured_binary(self) -> None:
        config = _runner_config(adb_path="/opt/android/platform-tools/adb")
        completed = subprocess.CompletedProcess(args=["adb"], returncode=0, stdout="", stderr="")
        with mock.patch("eval.aw_bridge.runner_preflight.subprocess.run", return_value=completed) as run_mock:
            run_adb_global(config, ["start-server"], check=False, capture_output=True)

        self.assertEqual(
            run_mock.call_args[0][0],
            ["/opt/android/platform-tools/adb", "start-server"],
        )


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

        with mock.patch("eval.aw_bridge.runner.socket.create_connection"):
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

    def test_openai_base_url_must_be_reachable_for_openai_provider(self) -> None:
        config = _runner_config()
        config.bridge.llm_backend = "openai"
        config.bridge.main_model = "gpt-5.4"
        workspace = self._workspace_with_catalog({"gpt-5.4": {"provider": "OPENAI"}})

        with mock.patch(
            "eval.aw_bridge.runner.socket.create_connection",
            side_effect=ConnectionRefusedError("refused"),
        ):
            with self.assertRaisesRegex(
                RuntimeError,
                "OPENAI_BASE_URL is configured but not reachable",
            ):
                _validate_required_api_key(
                    config,
                    {
                        "OPENAI_API_KEY": "ok",
                        "OPENAI_BASE_URL": "http://localhost:18080/v1",
                    },
                    workspace_root=workspace,
                )

    def test_openai_base_url_accepts_emulator_alias(self) -> None:
        config = _runner_config()
        config.bridge.llm_backend = "openai"
        config.bridge.main_model = "gpt-5.4"
        workspace = self._workspace_with_catalog({"gpt-5.4": {"provider": "OPENAI"}})

        mocked_socket = mock.MagicMock()
        mocked_socket.__enter__.return_value = mocked_socket
        mocked_socket.__exit__.return_value = False
        with mock.patch(
            "eval.aw_bridge.runner.socket.create_connection",
            return_value=mocked_socket,
        ) as connect_mock:
            _validate_required_api_key(
                config,
                {
                    "OPENAI_API_KEY": "ok",
                    "OPENAI_BASE_URL": "http://10.0.2.2:18080/v1",
                },
                workspace_root=workspace,
            )

        connect_mock.assert_called_once_with(("127.0.0.1", 18080), timeout=2.0)

    def test_other_custom_requires_full_trio(self) -> None:
        config = _runner_config()
        config.bridge.llm_backend = "openai"
        config.bridge.main_model = "other-custom"
        # llm_models.json does NOT list other-custom — synth entry only.
        workspace = self._workspace_with_catalog({})

        # Missing all three.
        with self.assertRaisesRegex(
            RuntimeError, "OTHER_API_KEY.*OTHER_BASE_URL.*OTHER_MODEL_ID"
        ):
            _validate_required_api_key(config, {}, workspace_root=workspace)

        # Missing base url + model id.
        with self.assertRaisesRegex(RuntimeError, "OTHER_BASE_URL"):
            _validate_required_api_key(
                config, {"OTHER_API_KEY": "key"}, workspace_root=workspace
            )

        # All three present → passes.
        _validate_required_api_key(
            config,
            {
                "OTHER_API_KEY": "key",
                "OTHER_BASE_URL": "https://example.com/v1",
                "OTHER_MODEL_ID": "vendor/model",
            },
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


class RunnerConfigLoadingTest(unittest.TestCase):
    def _write_default_config(
        self,
        root: Path,
        perform_bridge_setup: str | None = None,
        adb_path: str | None = None,
    ) -> Path:
        config_dir = root / "eval" / "config"
        config_dir.mkdir(parents=True, exist_ok=True)
        perform_bridge_line = (
            f"  perform_bridge_setup: {perform_bridge_setup}\n"
            if perform_bridge_setup is not None
            else ""
        )
        adb_path_line = f"  adb_path: {adb_path}\n" if adb_path is not None else ""
        config_path = config_dir / "default.yaml"
        config_path.write_text(
            (
                "suite_family: android_world\n"
                "runner:\n"
                "  output_root: eval/results\n"
                "  task_random_seed: 30\n"
                "  n_task_combinations: 1\n"
                "  use_identical_params: false\n"
                "  skip_unavailable_tasks: true\n"
                "  auto_install_missing_task_apps: true\n"
                f"{perform_bridge_line}"
                "android_world:\n"
                "  console_port: 5554\n"
                "  grpc_port: 8554\n"
                f"{adb_path_line}"
                "  auto_start_emulator: false\n"
                "bridge:\n"
                "  llm_backend: openai\n"
                "  package_name: ai.closepaw\n"
                "  activity: ai.closepaw/.app.MainActivity\n"
                "  agent_mode: basic\n"
                "  perception_mode: accessibility_only\n"
                "  platform_mode: accessibility\n"
                "  main_model: minimax-m2.5\n"
                "  executor_model: \"\"\n"
                "  max_turns: 30\n"
                "  auto_start: true\n"
                "  fresh_session: true\n"
                "  debug_mode: false\n"
                "  trace_enabled: true\n"
                "  max_wait_seconds: 900\n"
                "  poll_interval_seconds: 1\n"
                "  task_overrides:\n"
                "    BrowserDraw:\n"
                "      perception_mode: hybrid\n"
            ),
            encoding="utf-8",
        )
        return config_path

    def _write_overlay_config(self, root: Path, body: str = "") -> Path:
        config_dir = root / "eval" / "config"
        config_dir.mkdir(parents=True, exist_ok=True)
        config_path = config_dir / "test.yaml"
        config_path.write_text(body, encoding="utf-8")
        return config_path

    def _args_for(self, config_path: Path) -> argparse.Namespace:
        return argparse.Namespace(
            config=str(config_path),
            suite=None,
            tasks=None,
            tasks_file=None,
            n_task_combinations=None,
            task_random_seed=None,
            output_root=None,
            adb_serial=None,
            snapshot_policy=None,
            platform_mode=None,
        )

    def test_perform_bridge_setup_defaults_true(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write_default_config(root)
            config_path = self._write_overlay_config(root)
            config = load_config(root, self._args_for(config_path))
        self.assertTrue(config.perform_bridge_setup)

    def test_perform_bridge_setup_can_be_disabled(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write_default_config(root)
            config_path = self._write_overlay_config(
                root,
                "runner:\n"
                "  perform_bridge_setup: false\n",
            )
            config = load_config(root, self._args_for(config_path))
        self.assertFalse(config.perform_bridge_setup)

    def test_load_config_from_path_uses_same_defaults(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write_default_config(root)
            config_path = self._write_overlay_config(root)
            config = load_config_from_path(root, config_path)
        self.assertTrue(config.perform_bridge_setup)

    def test_load_config_expands_adb_path(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with mock.patch.dict("os.environ", {"HOME": "/tmp/remote-home"}, clear=False):
                self._write_default_config(root)
                config_path = self._write_overlay_config(
                    root,
                    "android_world:\n"
                    "  adb_path: ~/android-sdk/platform-tools/adb\n",
                )
                config = load_config(root, self._args_for(config_path))
        self.assertEqual(config.adb_path, "/tmp/remote-home/android-sdk/platform-tools/adb")

    def test_overlay_config_deep_merges_default_yaml(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._write_default_config(root, perform_bridge_setup="true", adb_path="/usr/local/bin/adb")
            config_path = self._write_overlay_config(
                root,
                (
                    "runner:\n"
                    "  perform_bridge_setup: false\n"
                    "bridge:\n"
                    "  main_model: gpt-5.4\n"
                    "  task_overrides:\n"
                    "    BrowserDraw:\n"
                    "      max_turns: 60\n"
                    "    BrowserMaze:\n"
                    "      perception_mode: hybrid\n"
                ),
            )
            config = load_config(root, self._args_for(config_path))

        self.assertFalse(config.perform_bridge_setup)
        self.assertEqual(config.adb_path, "/usr/local/bin/adb")
        self.assertEqual(config.bridge.main_model, "gpt-5.4")
        self.assertEqual(
            config.task_overrides["BrowserDraw"],
            {"perception_mode": "hybrid", "max_turns": 60},
        )
        self.assertEqual(
            config.task_overrides["BrowserMaze"],
            {"perception_mode": "hybrid"},
        )


if __name__ == "__main__":
    unittest.main()
