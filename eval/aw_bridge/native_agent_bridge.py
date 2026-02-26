from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import logging
import shlex
import subprocess
import time

from eval.aw_bridge.completion_monitor import LogcatCompletionMonitor

_log = logging.getLogger(__name__)


_SHIZUKU_PKG = "moe.shizuku.privileged.api"
_SHIZUKU_SERVER_PROCESS = "shizuku_server"
_SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"


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
    max_turns: int
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
    api_keys: dict[str, str] | None = None
    shizuku_apk_path: str | None = None
    excluded_tools: str = ""  # comma-separated tool names to remove from agent


@dataclass
class BridgeOutcome:
    bridge_status: str
    agent_completion_reason: str | None
    duration_sec: float
    logcat_path: str
    exception: str | None = None


class NativeAgentBridge:
    _A11Y_SERVICE = "com.moonkey.androidagent/com.moonkey.androidagent.app.AgentService"

    def __init__(self, config: BridgeConfig) -> None:
        self._config = config
        self._adb_shell_uid_cache: int | None = None

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
        self.force_stop()
        self._ensure_shizuku()
        self._ensure_accessibility_service()

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
            "--ei",
            "max_turns",
            str(self._config.max_turns),
        ]
        if self._config.executor_model:
            extras.extend(["--es", "executor_model", self._config.executor_model])
        if self._config.excluded_tools:
            extras.extend(["--es", "excluded_tools", self._config.excluded_tools])
        if self._config.api_keys:
            _KEY_MAP = {
                "OPENAI_API_KEY": "api_key",
                "OPENROUTER_API_KEY": "openrouter_api_key",
                "NOVITA_API_KEY": "novita_api_key",
            }
            for env_name, extra_name in _KEY_MAP.items():
                val = self._config.api_keys.get(env_name)
                if val:
                    extras.extend(["--es", extra_name, val])

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

    def _ensure_accessibility_service(self) -> None:
        """Ensure our accessibility service is enabled and bound.

        force-stop removes the service from the enabled list, so we
        must re-add it before each task launch.  Also grants overlay
        permission which the agent checks before starting a session.

        After enabling, polls ``dumpsys accessibility`` until the service
        appears in the *Bound services* list so the activity won't race
        ahead before ``AgentService.instance`` is set.
        """
        # Grant overlay (draw-over-other-apps) permission
        self._run_adb_shell(
            ["appops", "set", self._config.package_name,
             "SYSTEM_ALERT_WINDOW", "allow"],
            check=False,
            timeout_sec=self._config.adb_command_timeout_sec,
        )

        result = self._run_adb_shell(
            ["settings", "get", "secure", "enabled_accessibility_services"],
            check=False,
            capture_output=True,
            timeout_sec=self._config.adb_command_timeout_sec,
        )
        current = (result.stdout or "").strip()

        # Keep existing a11y services (including AndroidWorld's AccessibilityForwarder
        # needed for scoring) and ensure AgentService is also enabled.
        if current and current != "null":
            parts = [p for p in current.split(":") if p]
            if self._A11Y_SERVICE not in parts:
                parts.append(self._A11Y_SERVICE)
                new_value = ":".join(parts)
                _log.info("Enabling accessibility service (preserving existing): %s", new_value)
                self._run_adb_shell(
                    ["settings", "put", "secure",
                     "enabled_accessibility_services", new_value],
                    check=False,
                    timeout_sec=self._config.adb_command_timeout_sec,
                )
                self._run_adb_shell(
                    ["settings", "put", "secure", "accessibility_enabled", "1"],
                    check=False,
                    timeout_sec=self._config.adb_command_timeout_sec,
                )
        else:
            _log.info("Enabling accessibility service: %s", self._A11Y_SERVICE)
            self._run_adb_shell(
                ["settings", "put", "secure",
                 "enabled_accessibility_services", self._A11Y_SERVICE],
                check=False,
                timeout_sec=self._config.adb_command_timeout_sec,
            )
            self._run_adb_shell(
                ["settings", "put", "secure", "accessibility_enabled", "1"],
                check=False,
                timeout_sec=self._config.adb_command_timeout_sec,
            )

        # Poll until the service is actually bound (not just enabled).
        # On an emulator under load (AndroidWorld gRPC traffic), binding
        # can take up to ~6 seconds.  Use a generous fixed sleep because
        # subprocess-based polling (dumpsys) is unreliable when the gRPC
        # runtime interferes with fork() handlers.
        _log.info("Waiting for accessibility service to bind …")
        time.sleep(self._SERVICE_BIND_WAIT_SEC)
        _log.info("Accessibility service wait complete")

    _SERVICE_BIND_WAIT_SEC = 8
    _SHIZUKU_START_TIMEOUT_SEC = 15

    # ── Shizuku lifecycle ────────────────────────────────────────

    def _ensure_shizuku(self) -> None:
        """Install Shizuku and start its server for virtual-display mode.

        Skips if platform_mode is not ``virtual_display``.  Assumes the
        user has already granted Shizuku permission to the agent app at
        least once (the grant persists across emulator reboots as long as
        the userdata partition is not wiped).
        """
        if self._config.platform_mode != "virtual_display":
            return

        if not self._is_package_installed(_SHIZUKU_PKG):
            self._install_shizuku()

        self._grant_shizuku_permission()

        if self._is_shizuku_server_running():
            server_uid = self._get_shizuku_server_uid()
            if server_uid == 2000:
                _log.info("Shizuku server already running (uid=2000)")
                return

            adb_shell_uid = self._get_adb_shell_uid()
            if adb_shell_uid == 0:
                _log.info(
                    "Shizuku server already running with uid=%s; restarting as uid 2000",
                    "unknown" if server_uid is None else str(server_uid),
                )
                self._stop_shizuku_server()
            else:
                _log.info(
                    "Shizuku server already running%s",
                    "" if server_uid is None else f" (uid={server_uid})",
                )
                return

        self._start_shizuku_server()

    def _grant_shizuku_permission(self) -> None:
        """Best-effort grant for Shizuku API permission.

        Some setups require manual approval in Shizuku app.  On emulators and
        many userdebug images, `pm grant` works and removes that manual step.
        """
        result = self._run_adb_shell(
            ["pm", "grant", self._config.package_name, _SHIZUKU_PERMISSION],
            check=False,
            capture_output=True,
            timeout_sec=self._config.adb_command_timeout_sec,
        )
        if result.returncode == 0:
            _log.info("Granted Shizuku permission: %s", _SHIZUKU_PERMISSION)
            return

        stderr = (result.stderr or "").strip()
        _log.warning(
            "Failed to grant Shizuku permission (%s). "
            "Manual approval in Shizuku app may be required. stderr=%s",
            _SHIZUKU_PERMISSION,
            stderr,
        )

    def _is_package_installed(self, package: str) -> bool:
        result = self._run_adb_shell(
            ["pm", "list", "packages", package],
            check=False,
            capture_output=True,
            timeout_sec=self._config.adb_command_timeout_sec,
        )
        return f"package:{package}" in (result.stdout or "")

    def _install_shizuku(self) -> None:
        apk_path = self._config.shizuku_apk_path
        if not apk_path:
            raise RuntimeError(
                "Shizuku is not installed on the device and no "
                "shizuku_apk_path is configured.  Either install Shizuku "
                "manually or set bridge.shizuku_apk_path in the eval config."
            )
        resolved = Path(apk_path).resolve()
        if not resolved.is_file():
            raise RuntimeError(f"Shizuku APK not found at {resolved}")

        _log.info("Installing Shizuku from %s …", resolved)
        self._run_adb(
            ["install", "-r", "-t", str(resolved)],
            check=True,
            timeout_sec=120,
        )
        _log.info("Shizuku installed")

    def _is_shizuku_server_running(self) -> bool:
        result = self._run_adb_shell(
            ["pidof", _SHIZUKU_SERVER_PROCESS],
            check=False,
            capture_output=True,
            timeout_sec=self._config.adb_command_timeout_sec,
        )
        return bool((result.stdout or "").strip())

    def _stop_shizuku_server(self) -> None:
        self._run_adb_shell(
            [
                "sh",
                "-c",
                f"kill -9 $(pidof {_SHIZUKU_SERVER_PROCESS}) 2>/dev/null || true",
            ],
            check=False,
            timeout_sec=self._config.adb_command_timeout_sec,
        )

    def _get_shizuku_server_uid(self) -> int | None:
        pid_raw = self._get_shizuku_pid().strip()
        if not pid_raw:
            return None

        pid = pid_raw.split()[0]
        result = self._run_adb_shell(
            ["cat", f"/proc/{pid}/status"],
            check=False,
            capture_output=True,
            timeout_sec=self._config.adb_command_timeout_sec,
        )
        if result.returncode != 0:
            return None

        for line in (result.stdout or "").splitlines():
            if not line.startswith("Uid:"):
                continue
            parts = line.split()
            if len(parts) < 2:
                return None
            try:
                return int(parts[1])
            except ValueError:
                return None
        return None

    def _start_shizuku_server(self) -> None:
        """Start the Shizuku server.

        Primary path uses the native launcher library shipped inside the
        installed APK (`lib/<abi>/libshizuku.so`).  This mirrors the modern
        "Start via ADB" flow and avoids scoped-storage access to start scripts.
        Falls back to the historical app_process entrypoint for compatibility.
        """
        device_apk = self._resolve_shizuku_apk_path()
        _log.info("Starting Shizuku server (APK=%s) …", device_apk)

        apk_dir = device_apk.rsplit("/base.apk", 1)[0]
        native_started = self._start_shizuku_server_via_native_lib(apk_dir)
        if not native_started:
            _log.warning(
                "Native Shizuku launcher not found under %s/lib/*/libshizuku.so; "
                "falling back to app_process entrypoint",
                apk_dir,
            )
            # Backward-compatibility fallback for older builds.
            start_cmd = (
                f"(CLASSPATH={shlex.quote(device_apk)} "
                f"/system/bin/app_process -Djava.class.path={shlex.quote(device_apk)} "
                f"/system/bin --nice-name={_SHIZUKU_SERVER_PROCESS} "
                f"moe.shizuku.server.ShizukuService &)"
            )
            self._run_shizuku_start_script(
                start_cmd,
                timeout_sec=10,
            )

        # Poll until the server process appears.
        deadline = time.monotonic() + self._SHIZUKU_START_TIMEOUT_SEC
        while time.monotonic() < deadline:
            if self._is_shizuku_server_running():
                _log.info("Shizuku server started (pid=%s)",
                          self._get_shizuku_pid())
                return
            time.sleep(1)

        raise RuntimeError(
            f"Shizuku server did not start within "
            f"{self._SHIZUKU_START_TIMEOUT_SEC}s"
        )

    def _resolve_shizuku_apk_path(self) -> str:
        result = self._run_adb_shell(
            ["pm", "path", _SHIZUKU_PKG],
            check=False,
            capture_output=True,
            timeout_sec=self._config.adb_command_timeout_sec,
        )
        apk_line = (result.stdout or "").strip()
        if not apk_line.startswith("package:"):
            raise RuntimeError(f"Cannot resolve Shizuku APK on device: {apk_line}")
        return apk_line.split("package:", 1)[1]

    def _start_shizuku_server_via_native_lib(self, apk_dir: str) -> bool:
        # Try common ABI folders used by package manager extraction.
        script = (
            f"APK_DIR={shlex.quote(apk_dir)}; "
            "for abi in arm64 arm x86_64 x86; do "
            "  LIB=\"$APK_DIR/lib/$abi/libshizuku.so\"; "
            "  if [ -f \"$LIB\" ]; then "
            "    \"$LIB\" >/dev/null 2>&1 & "
            "    exit 0; "
            "  fi; "
            "done; "
            "exit 1"
        )
        result = self._run_shizuku_start_script(
            script,
            timeout_sec=self._config.adb_command_timeout_sec,
        )
        return result.returncode == 0

    def _run_shizuku_start_script(
        self,
        script: str,
        timeout_sec: int | None,
    ) -> subprocess.CompletedProcess[str]:
        uid = self._get_adb_shell_uid()
        if uid == 0:
            # Some environments expose adb shell as root; start Shizuku as
            # uid 2000 (shell) to avoid packageName/uid permission failures.
            for su_args in (
                ["su", "2000", "sh", "-c", script],
                ["su", "shell", "sh", "-c", script],
            ):
                result = self._run_adb_shell(
                    su_args,
                    check=False,
                    capture_output=True,
                    timeout_sec=timeout_sec,
                )
                if result.returncode == 0:
                    return result
            _log.warning(
                "adb shell is running as root, but could not switch to uid 2000 "
                "for Shizuku start; retrying without uid switch"
            )

        return self._run_adb_shell(
            ["sh", "-c", script],
            check=False,
            capture_output=True,
            timeout_sec=timeout_sec,
        )

    def _get_adb_shell_uid(self) -> int | None:
        if self._adb_shell_uid_cache is not None:
            return self._adb_shell_uid_cache

        result = self._run_adb_shell(
            ["id", "-u"],
            check=False,
            capture_output=True,
            timeout_sec=self._config.adb_command_timeout_sec,
        )
        raw = (result.stdout or "").strip()
        try:
            uid = int(raw)
        except ValueError:
            _log.warning("Unable to parse adb shell uid from %r", raw)
            return None

        self._adb_shell_uid_cache = uid
        return uid

    def _get_shizuku_pid(self) -> str:
        result = self._run_adb_shell(
            ["pidof", _SHIZUKU_SERVER_PROCESS],
            check=False,
            capture_output=True,
            timeout_sec=self._config.adb_command_timeout_sec,
        )
        return (result.stdout or "").strip()

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
