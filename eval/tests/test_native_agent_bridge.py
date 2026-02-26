from __future__ import annotations

import subprocess
import unittest
from unittest import mock

from eval.aw_bridge.native_agent_bridge import BridgeConfig, NativeAgentBridge


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


class NativeAgentBridgeAccessibilityTest(unittest.TestCase):
    def test_bound_service_parser_detects_service(self) -> None:
        dumpsys_text = """
            User state[0]
              Bound services:
                Service[label=Android Agent, componentName=com.moonkey.androidagent/com.moonkey.androidagent.app.AgentService]
              Enabled services:
                com.moonkey.androidagent/com.moonkey.androidagent.app.AgentService
        """
        self.assertTrue(
            NativeAgentBridge._is_service_in_bound_accessibility_services(dumpsys_text)
        )

    def test_bound_service_parser_rejects_enabled_only(self) -> None:
        dumpsys_text = """
            User state[0]
              Bound services:
                none
              Enabled services:
                com.moonkey.androidagent/com.moonkey.androidagent.app.AgentService
        """
        self.assertFalse(
            NativeAgentBridge._is_service_in_bound_accessibility_services(dumpsys_text)
        )

    def test_bound_service_parser_detects_mBoundServices_variant(self) -> None:
        dumpsys_text = """
            User state[0]
              mBoundServices:
                Service[label=Android Agent, componentName=com.moonkey.androidagent/com.moonkey.androidagent.app.AgentService]
              mEnabledServices:
                com.moonkey.androidagent/com.moonkey.androidagent.app.AgentService
        """
        self.assertTrue(
            NativeAgentBridge._is_service_in_bound_accessibility_services(dumpsys_text)
        )

    def test_ensure_accessibility_service_retries_then_succeeds(self) -> None:
        bridge = NativeAgentBridge(_bridge_config())
        ok = subprocess.CompletedProcess(args=["adb"], returncode=0, stdout="", stderr="")

        with mock.patch.object(bridge, "_run_adb_shell", return_value=ok), mock.patch.object(
            bridge,
            "_enable_accessibility_service_settings",
        ) as enable_mock, mock.patch.object(
            bridge,
            "_wait_for_accessibility_service_ready",
            side_effect=[(False, "not bound"), (True, "ready")],
        ) as wait_mock, mock.patch("eval.aw_bridge.native_agent_bridge.time.sleep"):
            bridge._ensure_accessibility_service()

        self.assertEqual(enable_mock.call_count, 2)
        self.assertEqual(wait_mock.call_count, 2)

    def test_ensure_accessibility_service_raises_after_retry_budget(self) -> None:
        bridge = NativeAgentBridge(_bridge_config())
        ok = subprocess.CompletedProcess(args=["adb"], returncode=0, stdout="", stderr="")

        with mock.patch.object(bridge, "_run_adb_shell", return_value=ok), mock.patch.object(
            bridge,
            "_enable_accessibility_service_settings",
        ), mock.patch.object(
            bridge,
            "_wait_for_accessibility_service_ready",
            return_value=(False, "still not bound"),
        ), mock.patch("eval.aw_bridge.native_agent_bridge.time.sleep"):
            with self.assertRaisesRegex(RuntimeError, "did not become ready"):
                bridge._ensure_accessibility_service()


if __name__ == "__main__":
    unittest.main()
