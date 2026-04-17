from __future__ import annotations

import subprocess
import unittest
from unittest import mock

from eval.aw_bridge.native_agent_bridge import BridgeConfig, NativeAgentBridge


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


class NativeAgentBridgeAccessibilityTest(unittest.TestCase):
    def test_clear_long_term_memory_uses_run_as(self) -> None:
        bridge = NativeAgentBridge(_bridge_config())
        ok = subprocess.CompletedProcess(args=["adb"], returncode=0, stdout="", stderr="")

        with mock.patch.object(bridge, "_run_adb_shell", return_value=ok) as shell_mock:
            bridge._clear_long_term_memory()

        shell_mock.assert_called_once_with(
            ["run-as", "ai.closepaw", "rm", "-rf", "files/memory"],
            check=False,
            capture_output=True,
            timeout_sec=60,
        )

    def test_start_agent_clears_memory_and_forwards_excluded_tools(self) -> None:
        cfg = _bridge_config()
        cfg.excluded_tools = "ask_user,remember_experience"
        bridge = NativeAgentBridge(cfg)
        ok = subprocess.CompletedProcess(args=["adb"], returncode=0, stdout="", stderr="")

        with mock.patch.object(bridge, "force_stop"), mock.patch.object(
            bridge, "_clear_long_term_memory"
        ) as clear_mock, mock.patch.object(
            bridge, "_ensure_shizuku"
        ), mock.patch.object(
            bridge, "_ensure_accessibility_service"
        ), mock.patch.object(
            bridge, "_run_adb_shell", return_value=ok
        ) as shell_mock:
            bridge._start_agent(goal="Open Settings", run_id="run-1")

        clear_mock.assert_called_once_with()
        start_args = shell_mock.call_args_list[-1][0][0]
        self.assertIn("excluded_tools", start_args)
        self.assertIn("ask_user,remember_experience", start_args)

    def test_start_agent_skips_memory_clear_when_disabled(self) -> None:
        cfg = _bridge_config()
        cfg.clear_memory_before_task = False
        bridge = NativeAgentBridge(cfg)
        ok = subprocess.CompletedProcess(args=["adb"], returncode=0, stdout="", stderr="")

        with mock.patch.object(bridge, "force_stop"), mock.patch.object(
            bridge, "_clear_long_term_memory"
        ) as clear_mock, mock.patch.object(
            bridge, "_ensure_shizuku"
        ), mock.patch.object(
            bridge, "_ensure_accessibility_service"
        ), mock.patch.object(
            bridge, "_run_adb_shell", return_value=ok
        ):
            bridge._start_agent(goal="Open Settings", run_id="run-1")

        clear_mock.assert_not_called()

    def test_bound_service_parser_detects_service(self) -> None:
        dumpsys_text = """
            User state[0]
              Bound services:
                Service[label=ClosePaw, componentName=ai.closepaw/ai.closepaw.app.AgentService]
              Enabled services:
                ai.closepaw/ai.closepaw.app.AgentService
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
                ai.closepaw/ai.closepaw.app.AgentService
        """
        self.assertFalse(
            NativeAgentBridge._is_service_in_bound_accessibility_services(dumpsys_text)
        )

    def test_bound_service_parser_detects_mBoundServices_variant(self) -> None:
        dumpsys_text = """
            User state[0]
              mBoundServices:
                Service[label=ClosePaw, componentName=ai.closepaw/ai.closepaw.app.AgentService]
              mEnabledServices:
                ai.closepaw/ai.closepaw.app.AgentService
        """
        self.assertTrue(
            NativeAgentBridge._is_service_in_bound_accessibility_services(dumpsys_text)
        )

    def test_bound_service_parser_detects_label_only_format(self) -> None:
        """Real-world format: Bound services uses label= without componentName."""
        dumpsys_text = """
     Bound services:{Service[label=com.google.androidenv.accessibilityforwarder.Acce\u2026, feedbackType[FEEDBACK_GENERIC], capabilities=1, eventTypes=TYPES_ALL_MASK, notificationTimeout=0, requestA11yBtn=false],
                     Service[label=ClosePaw, feedbackType[FEEDBACK_GENERIC], capabilities=161, eventTypes=[TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED], notificationTimeout=100, requestA11yBtn=false]}
     Enabled services:{{com.google.androidenv.accessibilityforwarder/com.google.androidenv.accessibilityforwarder.AccessibilityForwarder}, {ai.closepaw/ai.closepaw.app.AgentService}}
        """
        self.assertTrue(
            NativeAgentBridge._is_service_in_bound_accessibility_services(dumpsys_text)
        )

    def test_bound_service_parser_rejects_label_only_when_not_bound(self) -> None:
        """Label-only format but our service is NOT in the bound list."""
        dumpsys_text = """
     Bound services:{Service[label=com.google.androidenv.accessibilityforwarder.Acce\u2026, feedbackType[FEEDBACK_GENERIC], capabilities=1]}
     Enabled services:{{ai.closepaw/ai.closepaw.app.AgentService}}
        """
        self.assertFalse(
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

    def test_ensure_accessibility_starts_app_before_enabling_a11y(self) -> None:
        """Verify the app is launched (clearing force-stopped state) before a11y setup."""
        bridge = NativeAgentBridge(_bridge_config())
        ok = subprocess.CompletedProcess(args=["adb"], returncode=0, stdout="", stderr="")
        call_order: list[str] = []

        def tracking_run(cmd, **kwargs):
            if isinstance(cmd, list):
                if cmd[:2] == ["am", "start"]:
                    call_order.append("am_start")
                elif cmd[:2] == ["appops", "set"]:
                    call_order.append("appops")
            return ok

        def tracking_enable():
            call_order.append("enable_a11y")

        with mock.patch.object(bridge, "_run_adb_shell", side_effect=tracking_run), \
             mock.patch.object(
                 bridge, "_enable_accessibility_service_settings", side_effect=tracking_enable,
             ), \
             mock.patch.object(
                 bridge, "_wait_for_accessibility_service_ready", return_value=(True, "ready"),
             ), \
             mock.patch("eval.aw_bridge.native_agent_bridge.time.sleep"):
            bridge._ensure_accessibility_service()

        self.assertEqual(
            call_order,
            ["appops", "am_start", "enable_a11y"],
            "App must be started after overlay grant but before enabling a11y service",
        )


if __name__ == "__main__":
    unittest.main()
