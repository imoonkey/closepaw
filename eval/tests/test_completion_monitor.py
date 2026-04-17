from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from eval.aw_bridge.completion_monitor import (
    COMPLETED_PATTERN,
    ERROR_PATTERN,
    REASON_PATTERN,
    LogcatCompletionMonitor,
    _extract_reason,
    _infer_reason,
)


class ReasonPatternTest(unittest.TestCase):
    def test_matches_colon_format(self) -> None:
        line = 'AgentService: Task completed: task_1, reason: GOAL_ACHIEVED'
        self.assertEqual(_extract_reason(line), "GOAL_ACHIEVED")

    def test_matches_equals_format(self) -> None:
        line = 'reason=GOAL_ACHIEVED'
        self.assertEqual(_extract_reason(line), "GOAL_ACHIEVED")

    def test_matches_colon_with_space(self) -> None:
        line = 'Session completed: sess_1, reason: MAX_TURNS'
        self.assertEqual(_extract_reason(line), "MAX_TURNS")

    def test_matches_mixed_case(self) -> None:
        line = 'reason: GoalAchieved'
        self.assertEqual(_extract_reason(line), "GoalAchieved")

    def test_no_match(self) -> None:
        line = 'some unrelated log line'
        self.assertIsNone(_extract_reason(line))


class CompletedPatternTest(unittest.TestCase):
    def test_task_completed_emitted(self) -> None:
        line = '02-17 12:00:00.000  1234  5678 D AgentSession: Emitted event: TaskCompleted'
        self.assertIsNotNone(COMPLETED_PATTERN.search(line))

    def test_session_completed_service(self) -> None:
        line = '02-17 12:00:00.000  1234  5678 I AgentService: Session completed'
        self.assertIsNotNone(COMPLETED_PATTERN.search(line))

    def test_task_completed_service(self) -> None:
        line = '02-17 12:00:00.000  1234  5678 I AgentService: Task completed'
        self.assertIsNotNone(COMPLETED_PATTERN.search(line))

    def test_session_completed_emitted(self) -> None:
        line = 'AgentSession: Emitted event: SessionCompleted'
        self.assertIsNotNone(COMPLETED_PATTERN.search(line))

    def test_unrelated_line(self) -> None:
        line = 'AgentService: Starting task'
        self.assertIsNone(COMPLETED_PATTERN.search(line))


class ErrorPatternTest(unittest.TestCase):
    def test_session_error_emitted(self) -> None:
        line = 'AgentSession: Emitted event: SessionError'
        self.assertIsNotNone(ERROR_PATTERN.search(line))

    def test_session_error_service(self) -> None:
        line = 'AgentService: Session error'
        self.assertIsNotNone(ERROR_PATTERN.search(line))

    def test_fatal_error(self) -> None:
        line = 'Fatal error in agent run'
        self.assertIsNotNone(ERROR_PATTERN.search(line))

    def test_ask_user_blocks_eval(self) -> None:
        line = (
            'TurnExecutionPhase: Executing tool: ask_user with args: '
            '{"type":"action","message":"Please sign in"}'
        )
        self.assertIsNotNone(ERROR_PATTERN.search(line))

    def test_agent_anr(self) -> None:
        line = "ActivityManager: ANR in ai.closepaw"
        self.assertIsNotNone(ERROR_PATTERN.search(line))


class InferReasonTest(unittest.TestCase):
    def test_infers_ask_user_blocked(self) -> None:
        line = "TurnExecutionPhase: Executing tool: ask_user with args: {...}"
        self.assertEqual(_infer_reason(line), "ASK_USER_BLOCKED")

    def test_infers_agent_anr(self) -> None:
        line = "ActivityManager: ANR in ai.closepaw"
        self.assertEqual(_infer_reason(line), "AGENT_ANR")


class LogcatCompletionMonitorTest(unittest.TestCase):
    def test_detects_completed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            logcat = Path(tmp) / "logcat.log"
            logcat.write_text(
                "02-17 12:00:00.000 I AgentService: Task completed: t1, reason: GOAL_ACHIEVED\n",
                encoding="utf-8",
            )
            monitor = LogcatCompletionMonitor(max_wait_seconds=1, poll_interval_seconds=0.01)
            result = monitor.wait(logcat)
            self.assertEqual(result.bridge_status, "completed")
            self.assertEqual(result.agent_completion_reason, "GOAL_ACHIEVED")

    def test_detects_error(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            logcat = Path(tmp) / "logcat.log"
            logcat.write_text(
                "02-17 12:00:00.000 E AgentService: Session error\n",
                encoding="utf-8",
            )
            monitor = LogcatCompletionMonitor(max_wait_seconds=1, poll_interval_seconds=0.01)
            result = monitor.wait(logcat)
            self.assertEqual(result.bridge_status, "error")

    def test_detects_ask_user_as_error(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            logcat = Path(tmp) / "logcat.log"
            logcat.write_text(
                "TurnExecutionPhase: Executing tool: ask_user with args: {\"type\":\"action\"}\n",
                encoding="utf-8",
            )
            monitor = LogcatCompletionMonitor(max_wait_seconds=1, poll_interval_seconds=0.01)
            result = monitor.wait(logcat)
            self.assertEqual(result.bridge_status, "error")
            self.assertEqual(result.agent_completion_reason, "ASK_USER_BLOCKED")

    def test_detects_agent_anr_as_error(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            logcat = Path(tmp) / "logcat.log"
            logcat.write_text(
                "ActivityManager: ANR in ai.closepaw\n",
                encoding="utf-8",
            )
            monitor = LogcatCompletionMonitor(max_wait_seconds=1, poll_interval_seconds=0.01)
            result = monitor.wait(logcat)
            self.assertEqual(result.bridge_status, "error")
            self.assertEqual(result.agent_completion_reason, "AGENT_ANR")

    def test_timeout_when_no_signal(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            logcat = Path(tmp) / "logcat.log"
            logcat.write_text("some unrelated log\n", encoding="utf-8")
            monitor = LogcatCompletionMonitor(max_wait_seconds=0.1, poll_interval_seconds=0.02)
            result = monitor.wait(logcat)
            self.assertEqual(result.bridge_status, "timeout")
            self.assertIsNone(result.agent_completion_reason)

    def test_reason_from_earlier_line(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            logcat = Path(tmp) / "logcat.log"
            logcat.write_text(
                "AgentService: Task completed: t1, reason: MAX_TURNS\n"
                "AgentSession: Emitted event: SessionCompleted\n",
                encoding="utf-8",
            )
            monitor = LogcatCompletionMonitor(max_wait_seconds=1, poll_interval_seconds=0.01)
            result = monitor.wait(logcat)
            self.assertEqual(result.bridge_status, "completed")
            self.assertEqual(result.agent_completion_reason, "MAX_TURNS")

    def test_ignores_unrelated_reason_lines_before_completion(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            logcat = Path(tmp) / "logcat.log"
            logcat.write_text(
                "AudioService: reason: volume_controller\n"
                "AgentSession: Emitted event: SessionCompleted\n",
                encoding="utf-8",
            )
            monitor = LogcatCompletionMonitor(max_wait_seconds=1, poll_interval_seconds=0.01)
            result = monitor.wait(logcat)
            self.assertEqual(result.bridge_status, "completed")
            self.assertIsNone(result.agent_completion_reason)


if __name__ == "__main__":
    unittest.main()
