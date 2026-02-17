from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re
import time


COMPLETED_PATTERN = re.compile(
    r"(AgentSession: Emitted event: TaskCompleted|"
    r"AgentService: Received event: TaskCompleted|"
    r"AgentSession: Emitted event: SessionCompleted|"
    r"AgentService: Session completed|"
    r"AgentService: Task completed)"
)
ERROR_PATTERN = re.compile(
    r"(AgentSession: Emitted event: SessionError|"
    r"AgentService: Session error|"
    r"Fatal error)"
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
                        reason = reason or _extract_reason(line)
                        if COMPLETED_PATTERN.search(line):
                            return MonitorResult(
                                bridge_status="completed",
                                agent_completion_reason=reason,
                                matched_line=line.strip(),
                            )
                        if ERROR_PATTERN.search(line):
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
