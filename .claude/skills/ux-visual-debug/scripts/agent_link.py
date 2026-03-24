#!/usr/bin/env python3
from __future__ import annotations

import os
import re
import signal
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional

ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")


def find_project_root(start: Path) -> Path:
    for candidate in [start] + list(start.parents):
        if (candidate / "scripts" / "debug-run.sh").exists() and (candidate / "scripts" / "setup.sh").exists():
            return candidate
    raise RuntimeError("Cannot locate project root containing scripts/debug-run.sh and scripts/setup.sh")


def list_debug_run_dirs(project_root: Path) -> List[Path]:
    debug_root = project_root / "debug-output"
    if not debug_root.exists():
        return []
    return sorted([p.resolve() for p in debug_root.glob("run_*") if p.is_dir()], key=lambda p: p.stat().st_mtime)


@dataclass
class AgentLinkState:
    enabled: bool
    mode: str
    goal: str
    setup_ran: bool
    setup_exit: Optional[int]
    debug_run_started: bool
    debug_run_exit: Optional[int]
    debug_run_log: str
    debug_output_dir: str
    status: str
    error: str


class AgentLink:
    def __init__(
        self,
        *,
        project_root: Path,
        run_dir: Path,
        serial: Optional[str],
        goal: str,
        mode: str,
        run_setup: bool,
        debug_args: List[str],
        join_timeout_sec: int,
        start_delay_ms: int,
    ):
        self.project_root = project_root
        self.serial = serial
        self.goal = goal.strip()
        self.mode = mode
        self.run_setup = run_setup
        self.debug_args = debug_args
        self.join_timeout_sec = max(0, join_timeout_sec)
        self.start_delay_ms = max(0, start_delay_ms)

        self.debug_script = project_root / "scripts" / "debug-run.sh"
        self.setup_script = project_root / "scripts" / "setup.sh"
        self.log_path = run_dir / "agent_debug_run.log"
        self.setup_log_path = run_dir / "agent_setup.log"

        self.proc: Optional[subprocess.Popen] = None
        self.before_debug_dirs: List[Path] = []

        self.state = AgentLinkState(
            enabled=bool(self.goal),
            mode=self.mode if self.goal else "none",
            goal=self.goal,
            setup_ran=False,
            setup_exit=None,
            debug_run_started=False,
            debug_run_exit=None,
            debug_run_log=str(self.log_path),
            debug_output_dir="",
            status="DISABLED" if not self.goal else "PENDING",
            error="",
        )

    def _env(self) -> Dict[str, str]:
        env = os.environ.copy()
        if self.serial:
            env["ANDROID_SERIAL"] = self.serial
        return env

    def _scan_log_for_debug_dir(self) -> str:
        if not self.log_path.exists():
            return ""
        lines = self.log_path.read_text(encoding="utf-8", errors="ignore").splitlines()
        for raw_line in reversed(lines):
            line = ANSI_RE.sub("", raw_line)
            match = re.search(r"Debug output(?: saved to)?:\s*(\S+)", line)
            if match:
                return match.group(1)
        return ""

    def _discover_debug_dir(self) -> str:
        after = list_debug_run_dirs(self.project_root)
        before_set = {str(p) for p in self.before_debug_dirs}
        created = [str(p) for p in after if str(p) not in before_set]
        if created:
            return created[-1]
        return self._scan_log_for_debug_dir()

    def _run_setup_if_needed(self) -> None:
        if not self.run_setup:
            return
        self.state.setup_ran = True
        with self.setup_log_path.open("w", encoding="utf-8") as fh:
            result = subprocess.run(
                ["bash", str(self.setup_script)],
                cwd=str(self.project_root),
                env=self._env(),
                stdout=fh,
                stderr=subprocess.STDOUT,
                text=True,
            )
        self.state.setup_exit = result.returncode
        if result.returncode != 0:
            raise RuntimeError(f"setup.sh failed (exit {result.returncode}). See {self.setup_log_path}")

    def _start_debug_run(self) -> None:
        self.before_debug_dirs = list_debug_run_dirs(self.project_root)
        cmd = ["bash", str(self.debug_script)] + self.debug_args + [self.goal]
        log_fh = self.log_path.open("w", encoding="utf-8")
        self.proc = subprocess.Popen(
            cmd,
            cwd=str(self.project_root),
            env=self._env(),
            stdout=log_fh,
            stderr=subprocess.STDOUT,
            text=True,
        )
        self.state.debug_run_started = True

    def start_before_scenario(self) -> None:
        if not self.state.enabled:
            return

        self._run_setup_if_needed()
        self._start_debug_run()

        if self.mode == "serial":
            self._finish_serial()
            return

        if self.start_delay_ms > 0:
            time.sleep(self.start_delay_ms / 1000.0)
        self.state.status = "RUNNING"

    def _finish_serial(self) -> None:
        if not self.proc:
            self.state.status = "ERROR"
            self.state.error = "debug-run process not started"
            return
        self.state.debug_run_exit = self.proc.wait()
        self.state.debug_output_dir = self._discover_debug_dir()
        if self.state.debug_run_exit == 0:
            self.state.status = "PASS"
        else:
            self.state.status = "FAIL"
            self.state.error = f"debug-run.sh failed with exit {self.state.debug_run_exit}"

    def finish_after_scenario(self) -> None:
        if not self.state.enabled or self.mode != "parallel":
            return

        if not self.proc:
            self.state.status = "ERROR"
            self.state.error = "parallel mode enabled but debug-run process was not started"
            return

        try:
            self.state.debug_run_exit = self.proc.wait(timeout=self.join_timeout_sec)
        except subprocess.TimeoutExpired:
            self.proc.send_signal(signal.SIGINT)
            self._wait_with_escalation()

        self.state.debug_output_dir = self._discover_debug_dir()
        if self.state.debug_run_exit == 0:
            self.state.status = "PASS"
        elif self.state.debug_run_exit is None:
            self.state.status = "ERROR"
            self.state.error = "debug-run exit code unavailable"
        else:
            self.state.status = "FAIL"
            self.state.error = f"debug-run exited with code {self.state.debug_run_exit}"

    def _wait_with_escalation(self) -> None:
        if not self.proc:
            return
        try:
            self.state.debug_run_exit = self.proc.wait(timeout=10)
            return
        except subprocess.TimeoutExpired:
            pass

        self.proc.terminate()
        try:
            self.state.debug_run_exit = self.proc.wait(timeout=5)
            return
        except subprocess.TimeoutExpired:
            pass

        self.proc.kill()
        self.state.debug_run_exit = self.proc.wait(timeout=2)
