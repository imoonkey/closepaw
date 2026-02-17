from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import shlex
import subprocess
import time

from eval.aw_bridge.completion_monitor import LogcatCompletionMonitor


@dataclass
class BridgeConfig:
    package_name: str
    activity: str
    llm_backend: str
    agent_mode: str
    perception_mode: str
    platform_mode: str
    main_model: str
    executor_model: str
    auto_start: bool
    fresh_session: bool
    debug_mode: bool
    trace_enabled: bool
    max_wait_seconds: int
    poll_interval_seconds: float
    adb_serial: str | None
    stop_agent_after_task: bool
    adb_command_timeout_sec: int
    adb_pull_timeout_sec: int


@dataclass
class BridgeOutcome:
    bridge_status: str
    agent_completion_reason: str | None
    duration_sec: float
    logcat_path: str
    exception: str | None = None


class NativeAgentBridge:
    def __init__(self, config: BridgeConfig) -> None:
        self._config = config

    def run_task(self, goal: str, run_id: str, artifact_dir: Path) -> BridgeOutcome:
        artifact_dir.mkdir(parents=True, exist_ok=True)
        logcat_path = artifact_dir / "logcat.log"
        started = time.monotonic()

        logcat_proc: subprocess.Popen[str] | None = None
        logcat_file = None
        try:
            self._run_adb(
                ["logcat", "-c"],
                check=False,
                timeout_sec=self._config.adb_command_timeout_sec,
            )
            logcat_file = logcat_path.open("w", encoding="utf-8", buffering=1)
            logcat_proc = subprocess.Popen(
                self._adb_command(["logcat", "-v", "threadtime"]),
                stdout=logcat_file,
                stderr=subprocess.STDOUT,
                text=True,
            )

            self._clear_device_trace(run_id)
            self._start_agent(goal=goal, run_id=run_id)

            monitor = LogcatCompletionMonitor(
                max_wait_seconds=self._config.max_wait_seconds,
                poll_interval_seconds=self._config.poll_interval_seconds,
            )
            monitor_result = monitor.wait(logcat_path)

            if self._config.stop_agent_after_task:
                self.stop_agent()
                self.force_stop()

            return BridgeOutcome(
                bridge_status=monitor_result.bridge_status,
                agent_completion_reason=monitor_result.agent_completion_reason,
                duration_sec=time.monotonic() - started,
                logcat_path=str(logcat_path),
            )
        except Exception as exc:  # pylint: disable=broad-exception-caught
            return BridgeOutcome(
                bridge_status="infra_failure",
                agent_completion_reason=None,
                duration_sec=time.monotonic() - started,
                logcat_path=str(logcat_path),
                exception=str(exc),
            )
        finally:
            if logcat_proc is not None:
                _stop_process(logcat_proc)
            if logcat_file is not None:
                logcat_file.close()

    def pull_trace_dir(self, run_id: str, local_trace_dir: Path) -> bool:
        device_trace_dir = self._device_trace_dir(run_id)
        exists = self._run_adb_shell(
            ["ls", device_trace_dir],
            check=False,
            capture_output=True,
            timeout_sec=self._config.adb_command_timeout_sec,
        )
        if exists.returncode != 0:
            return False

        local_trace_dir.mkdir(parents=True, exist_ok=True)
        result = self._run_adb(
            ["pull", f"{device_trace_dir}/.", str(local_trace_dir)],
            check=False,
            capture_output=True,
            timeout_sec=self._config.adb_pull_timeout_sec,
        )
        return result.returncode == 0

    def stop_agent(self) -> None:
        self._run_adb_shell(
            [
                "am",
                "broadcast",
                "-a",
                f"{self._config.package_name}.STOP_AGENT",
                "-p",
                self._config.package_name,
            ],
            check=False,
            timeout_sec=self._config.adb_command_timeout_sec,
        )

    def force_stop(self) -> None:
        self._run_adb_shell(
            ["am", "force-stop", self._config.package_name],
            check=False,
            timeout_sec=self._config.adb_command_timeout_sec,
        )

    def _start_agent(self, goal: str, run_id: str) -> None:
        extras = [
            "--es",
            "goal",
            goal,
            "--es",
            "llm_backend",
            self._config.llm_backend,
            "--es",
            "agent_mode",
            self._config.agent_mode,
            "--es",
            "perception_mode",
            self._config.perception_mode,
            "--es",
            "platform_mode",
            self._config.platform_mode,
            "--ez",
            "auto_start",
            _bool_arg(self._config.auto_start),
            "--ez",
            "fresh_session",
            _bool_arg(self._config.fresh_session),
            "--ez",
            "debug_mode",
            _bool_arg(self._config.debug_mode),
            "--ez",
            "trace_enabled",
            _bool_arg(self._config.trace_enabled),
            "--es",
            "trace_run_id",
            run_id,
            "--es",
            "main_model",
            self._config.main_model,
        ]
        if self._config.executor_model:
            extras.extend(["--es", "executor_model", self._config.executor_model])

        self._run_adb_shell(
            ["input", "keyevent", "KEYCODE_HOME"],
            check=False,
            timeout_sec=self._config.adb_command_timeout_sec,
        )
        self._run_adb_shell(
            [
                "am",
                "start",
                "-n",
                self._config.activity,
                "--activity-clear-top",
                "--activity-single-top",
                *extras,
            ],
            check=True,
            timeout_sec=self._config.adb_command_timeout_sec,
        )

    def _clear_device_trace(self, run_id: str) -> None:
        self._run_adb_shell(
            ["rm", "-rf", self._device_trace_dir(run_id)],
            check=False,
            timeout_sec=self._config.adb_command_timeout_sec,
        )

    def _device_trace_dir(self, run_id: str) -> str:
        return (
            f"/sdcard/Android/data/{self._config.package_name}/files/"
            f"inspection-trace/{run_id}"
        )

    def _adb_command(self, args: list[str]) -> list[str]:
        if self._config.adb_serial:
            return ["adb", "-s", self._config.adb_serial, *args]
        return ["adb", *args]

    def _adb_shell_command(self, args: list[str]) -> list[str]:
        """Build an adb shell command with proper escaping.

        adb shell concatenates remaining args and passes them through the
        device shell, so each argument must be individually quoted.
        """
        escaped = " ".join(shlex.quote(a) for a in args)
        if self._config.adb_serial:
            return ["adb", "-s", self._config.adb_serial, "shell", escaped]
        return ["adb", "shell", escaped]

    def _run_adb(
        self,
        args: list[str],
        check: bool,
        capture_output: bool = False,
        timeout_sec: int | None = None,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            self._adb_command(args),
            check=check,
            text=True,
            capture_output=capture_output,
            timeout=timeout_sec,
        )

    def _run_adb_shell(
        self,
        args: list[str],
        check: bool,
        capture_output: bool = False,
        timeout_sec: int | None = None,
    ) -> subprocess.CompletedProcess[str]:
        """Run an adb shell command with properly escaped arguments."""
        return subprocess.run(
            self._adb_shell_command(args),
            check=check,
            text=True,
            capture_output=capture_output,
            timeout=timeout_sec,
        )


def _bool_arg(value: bool) -> str:
    return "true" if value else "false"


def _stop_process(proc: subprocess.Popen[str]) -> None:
    if proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=3)
    except subprocess.TimeoutExpired:
        proc.kill()
