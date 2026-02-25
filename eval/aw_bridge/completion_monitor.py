from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re
import time


COMPLETED_PATTERN = re.compile(
    r"(AgentSession: Emitted event: TaskCompleted|"
    r"AgentService: Received event: TaskCompleted|"
    # NOTE: "AgentSession: Emitted event: SessionCompleted" is intentionally
    # excluded — that log line does NOT include the completion reason, so the
    # USER_STOPPED filter below cannot distinguish teardown events from real
    # completions.  "AgentService: Session completed" (below) carries the
    # reason field and fires immediately after.
    r"AgentService: Session completed|"
    r"AgentService: Task completed)"
)
# Sessions killed by fresh_session=true or stop_agent emit "Session completed"
# with reason USER_STOPPED.  These must NOT count as task completions — they
# are teardown signals for an earlier (often auto-created) session, not the
# result of the agent finishing the goal.
_USER_STOPPED_PATTERN = re.compile(r"reason[=:]\s*USER_STOPPED")
ERROR_PATTERN = re.compile(
    r"(AgentSession: Emitted event: SessionError|"
    r"AgentService: Session error|"
    r"Fatal error|"
    r"TurnExecutionPhase: Executing tool: ask_user|"
    r"ANR in com\.moonkey\.androidagent|"
    r"Timeout executing service: ServiceRecord\{[^}]*com\.moonkey\.androidagent/.app.AgentService)"
)
REASON_PATTERN = re.compile(r"reason[=:]\s*([A-Za-z_]+)")


@dataclass
class MonitorResult:
    bridge_status: str
    agent_completion_reason: str | None
    matched_line: str | None


class LogcatCompletionMonitor:
    def __init__(self, max_wait_seconds: int, poll_interval_seconds: float) -> None:
        self._max_wait_seconds = max_wait_seconds
        self._poll_interval_seconds = poll_interval_seconds

    def wait(self, logcat_path: Path) -> MonitorResult:
        started_at = time.monotonic()
        reason: str | None = None
        cursor = 0

        while True:
            if logcat_path.exists():
                with logcat_path.open("r", encoding="utf-8", errors="replace") as stream:
                    stream.seek(cursor)
                    for line in stream:
                        if COMPLETED_PATTERN.search(line):
                            if _USER_STOPPED_PATTERN.search(line):
                                continue
                            reason = reason or _extract_reason(line)
                            return MonitorResult(
                                bridge_status="completed",
                                agent_completion_reason=reason,
                                matched_line=line.strip(),
                            )
                        if ERROR_PATTERN.search(line):
                            reason = reason or _extract_reason(line) or _infer_reason(line)
                            return MonitorResult(
                                bridge_status="error",
                                agent_completion_reason=reason,
                                matched_line=line.strip(),
                            )
                    cursor = stream.tell()

            if (time.monotonic() - started_at) >= self._max_wait_seconds:
                return MonitorResult(
                    bridge_status="timeout",
                    agent_completion_reason=reason,
                    matched_line=None,
                )

            time.sleep(self._poll_interval_seconds)


def _extract_reason(line: str) -> str | None:
    match = REASON_PATTERN.search(line)
    if not match:
        return None
    return match.group(1)


def _infer_reason(line: str) -> str | None:
    if "Executing tool: ask_user" in line:
        return "ASK_USER_BLOCKED"
    if "ANR in com.moonkey.androidagent" in line:
        return "AGENT_ANR"
    if (
        "Timeout executing service: ServiceRecord" in line
        and "com.moonkey.androidagent/.app.AgentService" in line
    ):
        return "AGENT_ANR"
    return None
