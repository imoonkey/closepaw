from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import logging
from pathlib import Path
import shutil
import socket
import subprocess
import time
from typing import Any, TYPE_CHECKING

from eval.aw_bridge.task_loader import TaskInstance

if TYPE_CHECKING:
    from eval.aw_bridge.runner import RunnerConfig


class SnapshotPolicy(str, Enum):
    STRICT = "strict"
    AUTO_REPAIR = "auto_repair"
    BEST_EFFORT = "best_effort"
    OFF = "off"


class PreflightErrorCode(str, Enum):
    MISSING_TASK_PACKAGES = "missing_task_packages"
    SNAPSHOTS_MISSING = "snapshots_missing"
    PRECHECK_FAILED = "precheck_failed"


class PreflightError(RuntimeError):
    def __init__(self, code: PreflightErrorCode, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class SnapshotCheckReport:
    policy: str
    app_names: list[str]
    missing_before: dict[str, str]
    repaired_apps: list[str]
    unresolved: dict[str, str]
    skipped: bool = False

    def to_dict(self) -> dict[str, Any]:
        return {
            "policy": self.policy,
            "app_names": self.app_names,
            "missing_before": self.missing_before,
            "repaired_apps": self.repaired_apps,
            "unresolved": self.unresolved,
            "skipped": self.skipped,
        }


TASK_REQUIRED_PACKAGES: dict[str, tuple[str, ...]] = {
    "BrowserMultiply": ("com.android.chrome",),
    "ClockTimerEntry": ("com.google.android.deskclock", "com.android.deskclock"),
    "ContactsAddContact": ("com.android.contacts", "com.google.android.contacts"),
    "ExpenseAddSingle": ("com.arduia.expense",),
    "MarkorCreateNote": ("net.gsantner.markor",),
    "RecipeAddSingleRecipe": ("com.flauschcode.broccoli",),
    "SimpleSmsSend": ("com.simplemobiletools.smsmessenger",),
}


def resolve_snapshot_policy(raw: str | None) -> SnapshotPolicy:
    if not raw:
        return SnapshotPolicy.AUTO_REPAIR
    normalized = raw.strip().lower()
    for candidate in SnapshotPolicy:
        if candidate.value == normalized:
            return candidate
    valid = ", ".join(policy.value for policy in SnapshotPolicy)
    raise ValueError(f"Invalid snapshot_policy='{raw}'. Expected one of: {valid}")


def create_env(config: RunnerConfig) -> Any:
    from android_world.env import env_launcher  # type: ignore

    kwargs: dict[str, Any] = {
        "console_port": config.console_port,
        "emulator_setup": config.perform_emulator_setup,
        "freeze_datetime": config.freeze_datetime,
        "grpc_port": config.grpc_port,
    }
    if config.adb_path:
        kwargs["adb_path"] = config.adb_path
    return env_launcher.load_and_setup_env(**kwargs)


def run_preflight_checks(
    config: RunnerConfig,
    task_instances: list[TaskInstance],
    env: Any,
) -> list[TaskInstance]:
    ensure_adb_device_ready(config)

    # Correctness-first order: snapshot baseline before package filtering.
    ensure_task_app_snapshots(config, task_instances, env)

    if config.auto_install_missing_task_apps:
        attempt_targeted_task_app_install(config, task_instances, env)

    if config.skip_unavailable_tasks:
        task_instances = filter_unavailable_task_instances(config, task_instances)
    else:
        ensure_task_packages_installed(config, task_instances)

    # Bridge setup after benchmark app setup preflight.
    build_and_install_bridge(config)
    return task_instances


def collect_required_app_names(task_instances: list[TaskInstance]) -> set[str]:
    app_names: set[str] = set()
    for task_instance in task_instances:
        task = getattr(task_instance, "task", None)
        if task is None:
            continue
        raw_names = getattr(task, "app_names", ()) or ()
        for app_name in raw_names:
            normalized = str(app_name or "").strip().lower()
            if normalized and normalized != "clipper":
                app_names.add(normalized)
    return app_names


def ensure_task_app_snapshots(
    config: RunnerConfig,
    task_instances: list[TaskInstance],
    env: Any,
    snapshot_policy: SnapshotPolicy | None = None,
) -> SnapshotCheckReport:
    app_names = sorted(collect_required_app_names(task_instances))
    return ensure_app_snapshots(config, app_names, env, snapshot_policy=snapshot_policy)


def ensure_app_snapshots(
    config: RunnerConfig,
    app_names: list[str],
    env: Any,
    snapshot_policy: SnapshotPolicy | None = None,
) -> SnapshotCheckReport:
    """Ensure AndroidWorld snapshots exist for the provided logical app names."""
    policy = snapshot_policy or resolve_snapshot_policy(config.snapshot_policy)
    report = SnapshotCheckReport(
        policy=policy.value,
        app_names=app_names,
        missing_before={},
        repaired_apps=[],
        unresolved={},
        skipped=False,
    )

    if config.suite_family != "android_world" or not app_names:
        return report
    if policy is SnapshotPolicy.OFF:
        logging.warning("Snapshot preflight is disabled (snapshot_policy=off).")
        return SnapshotCheckReport(
            policy=policy.value,
            app_names=app_names,
            missing_before={},
            repaired_apps=[],
            unresolved={},
            skipped=True,
        )

    try:
        from android_world.env.setup_device import setup as aw_setup  # type: ignore
        from android_world.utils import app_snapshot  # type: ignore
    except Exception as exc:  # pylint: disable=broad-exception-caught
        message = (
            "Unable to import AndroidWorld snapshot utilities; cannot verify app snapshots: "
            f"{exc}"
        )
        if policy in (SnapshotPolicy.STRICT, SnapshotPolicy.AUTO_REPAIR):
            raise PreflightError(PreflightErrorCode.SNAPSHOTS_MISSING, message) from exc
        logging.warning("%s", message)
        return report

    missing_before = _probe_missing_snapshots(app_names, env, app_snapshot)
    if not missing_before:
        logging.info(
            "AndroidWorld snapshots verified for %d app(s) used by selected tasks.",
            len(app_names),
        )
        return report

    report = SnapshotCheckReport(
        policy=policy.value,
        app_names=app_names,
        missing_before=missing_before,
        repaired_apps=[],
        unresolved={},
        skipped=False,
    )

    if policy is SnapshotPolicy.STRICT:
        detail = _snapshot_error_detail(missing_before)
        raise PreflightError(
            PreflightErrorCode.SNAPSHOTS_MISSING,
            "AndroidWorld snapshots are missing for task apps (strict mode). "
            f"Details: {detail}",
        )

    logging.info(
        "Generating missing AndroidWorld snapshots for %d app(s): %s",
        len(missing_before),
        ", ".join(sorted(missing_before)),
    )
    repaired: list[str] = []
    for app_name in sorted(missing_before):
        app_class = aw_setup.get_app_mapping(app_name)
        if app_class is None:
            logging.warning(
                "No AndroidWorld app mapping found for '%s'; cannot generate snapshot.",
                app_name,
            )
            continue
        try:
            aw_setup.maybe_install_app(app_class, env)
            aw_setup.setup_app(app_class, env)
            repaired.append(app_name)
        except Exception as exc:  # pylint: disable=broad-exception-caught
            logging.warning("Failed to generate snapshot for app '%s': %s", app_name, exc)

    unresolved = _probe_missing_snapshots(sorted(missing_before), env, app_snapshot)
    report = SnapshotCheckReport(
        policy=policy.value,
        app_names=app_names,
        missing_before=missing_before,
        repaired_apps=repaired,
        unresolved=unresolved,
        skipped=False,
    )
    if not unresolved:
        return report

    detail = _snapshot_error_detail(unresolved)
    if policy is SnapshotPolicy.AUTO_REPAIR:
        raise PreflightError(
            PreflightErrorCode.SNAPSHOTS_MISSING,
            "AndroidWorld snapshots are missing for task apps after attempted setup. "
            f"Details: {detail}. "
            "Run baseline prep with wipe-data + setup, then rerun eval.",
        )

    logging.warning(
        "Snapshot unresolved for %d app(s) in best_effort mode; continuing. Details: %s",
        len(unresolved),
        detail,
    )
    return report


def run_android_world_connectivity_preflight(config: RunnerConfig) -> None:
    if config.suite_family != "android_world":
        return

    expected_emulator = f"emulator-{config.console_port}"
    if config.adb_serial:
        if not config.adb_serial.startswith("emulator-"):
            raise PreflightError(
                PreflightErrorCode.PRECHECK_FAILED,
                "AndroidWorld requires an emulator adb serial, but runner.adb_serial/--adb-serial "
                f"is '{config.adb_serial}'.",
            )
        if config.adb_serial != expected_emulator:
            raise PreflightError(
                PreflightErrorCode.PRECHECK_FAILED,
                "AndroidWorld runner.adb_serial/--adb-serial must match console_port mapping. "
                f"Got adb_serial='{config.adb_serial}' but console_port={config.console_port} "
                f"(expected serial '{expected_emulator}').",
            )
    else:
        config.adb_serial = expected_emulator
    config.bridge.adb_serial = config.adb_serial

    run_adb_global(config, ["start-server"], check=False, capture_output=True)

    if config.auto_start_emulator:
        if not is_expected_emulator_online(config, expected_emulator):
            start_android_world_emulator(config)

    if not is_expected_emulator_online(config, expected_emulator):
        raise PreflightError(
            PreflightErrorCode.PRECHECK_FAILED,
            "AndroidWorld emulator not detected. "
            f"Expected adb device '{expected_emulator}' for console_port={config.console_port}. "
            "Start the benchmark emulator before running eval.",
        )

    if not is_local_tcp_port_open(config.grpc_port):
        raise PreflightError(
            PreflightErrorCode.PRECHECK_FAILED,
            "AndroidWorld gRPC endpoint is not reachable on localhost "
            f"port {config.grpc_port}. "
            "Ensure the emulator is started with the matching gRPC port, or update "
            "android_world.grpc_port in eval/config/default.yaml.",
        )

    wait_for_emulator_stability(config, expected_emulator)


def ensure_adb_device_ready(config: RunnerConfig) -> None:
    result = run_adb(config, ["get-state"], check=False, capture_output=True)
    state = (result.stdout or "").strip().lower()
    if result.returncode != 0 or state != "device":
        detail = (result.stderr or result.stdout or "unknown").strip()
        raise PreflightError(
            PreflightErrorCode.PRECHECK_FAILED,
            "ADB device is not ready. Ensure emulator/device is running and authorized. "
            f"state={state or 'unknown'} detail={detail}",
        )


def ensure_task_packages_installed(config: RunnerConfig, task_instances: list[TaskInstance]) -> None:
    missing_by_task = collect_missing_task_packages(config, task_instances)
    if missing_by_task:
        lines = []
        for task_name in sorted(missing_by_task):
            lines.append(f"- {task_name}: one of {', '.join(missing_by_task[task_name])}")
        raise PreflightError(
            PreflightErrorCode.MISSING_TASK_PACKAGES,
            "Missing benchmark app packages required by selected tasks:\n"
            + "\n".join(lines)
            + "\nInstall required benchmark apps before running smoke tests.",
        )


def is_package_installed(config: RunnerConfig, package: str) -> bool:
    result = run_adb_shell(config, ["pm", "path", package], check=False, capture_output=True)
    return result.returncode == 0 and "package:" in (result.stdout or "")


def collect_missing_task_packages(
    config: RunnerConfig,
    task_instances: list[TaskInstance],
) -> dict[str, list[str]]:
    missing_by_task: dict[str, list[str]] = {}
    for task in task_instances:
        candidates = TASK_REQUIRED_PACKAGES.get(task.task_name)
        if not candidates:
            continue
        installed = any(is_package_installed(config, package) for package in candidates)
        if not installed:
            missing_by_task[task.task_name] = list(candidates)
    return missing_by_task


def attempt_targeted_task_app_install(
    config: RunnerConfig,
    task_instances: list[TaskInstance],
    env: Any,
) -> None:
    if config.suite_family != "android_world":
        return

    missing_before = collect_missing_task_packages(config, task_instances)
    if not missing_before:
        return

    try:
        from android_world.env.setup_device import setup as aw_setup  # type: ignore
    except Exception as exc:  # pylint: disable=broad-exception-caught
        logging.warning("Unable to import AndroidWorld setup module for targeted app install: %s", exc)
        return

    app_list = aw_setup.get_app_list_to_setup([task.task_name for task in task_instances]) or ()
    if not app_list:
        return

    logging.info(
        "Attempting targeted app install/setup for missing task dependencies (%d apps)",
        len(app_list),
    )
    for app_class in app_list:
        try:
            aw_setup.maybe_install_app(app_class, env)
            aw_setup.setup_app(app_class, env)
        except Exception as exc:  # pylint: disable=broad-exception-caught
            logging.warning(
                "Targeted setup failed for app '%s': %s",
                getattr(app_class, "app_name", str(app_class)),
                exc,
            )
            fallback_install_apk_candidates(config, app_class)

    missing_after = collect_missing_task_packages(config, task_instances)
    if missing_after == missing_before:
        logging.warning("Targeted app install did not resolve missing task dependencies.")
    else:
        logging.info("Targeted app install reduced missing task dependencies.")


def fallback_install_apk_candidates(config: RunnerConfig, app_class: Any) -> None:
    apk_names = tuple(getattr(app_class, "apk_names", ()) or ())
    if not apk_names:
        return

    try:
        from android_world.env.setup_device import apps as aw_apps  # type: ignore
    except Exception as exc:  # pylint: disable=broad-exception-caught
        logging.warning("Cannot import AndroidWorld app downloader for fallback install: %s", exc)
        return

    app_name = getattr(app_class, "app_name", str(app_class))
    for apk_name in apk_names:
        try:
            path = aw_apps.download_app_data(apk_name)
            result = run_adb(config, ["install", "-r", path], check=False, capture_output=True)
            if result.returncode == 0:
                logging.info("Fallback APK install succeeded for %s via %s", app_name, apk_name)
                return
            detail = (result.stderr or result.stdout or "").strip()
            logging.warning(
                "Fallback APK install failed for %s via %s: %s",
                app_name,
                apk_name,
                detail,
            )
        except Exception as exc:  # pylint: disable=broad-exception-caught
            logging.warning(
                "Fallback APK install exception for %s via %s: %s",
                app_name,
                apk_name,
                exc,
            )


def filter_unavailable_task_instances(
    config: RunnerConfig,
    task_instances: list[TaskInstance],
) -> list[TaskInstance]:
    available: list[TaskInstance] = []
    dropped: dict[str, list[str]] = {}
    for task in task_instances:
        candidates = TASK_REQUIRED_PACKAGES.get(task.task_name)
        if not candidates:
            available.append(task)
            continue
        installed = any(is_package_installed(config, package) for package in candidates)
        if installed:
            available.append(task)
        else:
            dropped[task.task_name] = list(candidates)

    if dropped:
        logging.warning(
            "Skipping %d task(s) due to missing app packages on device.",
            len(dropped),
        )
        for task_name in sorted(dropped):
            logging.warning(
                "  - %s: requires one of [%s]",
                task_name,
                ", ".join(dropped[task_name]),
            )

    if not available:
        raise PreflightError(
            PreflightErrorCode.MISSING_TASK_PACKAGES,
            "No runnable tasks remain after filtering unavailable app dependencies.",
        )
    return available


def run_adb(
    config: RunnerConfig,
    args: list[str],
    check: bool,
    capture_output: bool = False,
    timeout_sec: float | None = None,
) -> subprocess.CompletedProcess[str]:
    cmd = ["adb"]
    if config.adb_serial:
        cmd.extend(["-s", config.adb_serial])
    cmd.extend(args)
    timeout = (
        float(timeout_sec)
        if timeout_sec is not None
        else float(config.bridge.adb_command_timeout_sec)
    )
    return subprocess.run(
        cmd,
        check=check,
        text=True,
        capture_output=capture_output,
        timeout=timeout,
    )


def run_adb_global(
    config: RunnerConfig,
    args: list[str],
    check: bool,
    capture_output: bool = False,
    timeout_sec: float | None = None,
) -> subprocess.CompletedProcess[str]:
    timeout = (
        float(timeout_sec)
        if timeout_sec is not None
        else float(config.bridge.adb_command_timeout_sec)
    )
    return subprocess.run(
        ["adb", *args],
        check=check,
        text=True,
        capture_output=capture_output,
        timeout=timeout,
    )


def run_adb_shell(
    config: RunnerConfig,
    args: list[str],
    check: bool,
    capture_output: bool = False,
    timeout_sec: float | None = None,
) -> subprocess.CompletedProcess[str]:
    return run_adb(
        config,
        ["shell", *args],
        check=check,
        capture_output=capture_output,
        timeout_sec=timeout_sec,
    )


def is_local_tcp_port_open(port: int, timeout_sec: float = 0.2) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(timeout_sec)
        return sock.connect_ex(("127.0.0.1", int(port))) == 0


def is_expected_emulator_online(config: RunnerConfig, expected_serial: str) -> bool:
    devices_out = run_adb_global(config, ["devices"], check=False, capture_output=True)
    devices_text = (devices_out.stdout or "")
    return any(
        line.startswith(expected_serial + "\t") and "device" in line
        for line in devices_text.splitlines()
    )


def start_android_world_emulator(config: RunnerConfig) -> None:
    emulator_bin = resolve_emulator_binary(config)
    if not emulator_bin:
        raise PreflightError(
            PreflightErrorCode.PRECHECK_FAILED,
            "Android emulator binary not found. Set android_world.emulator_binary_path "
            "or add `emulator` to PATH.",
        )

    listed_avds = subprocess.run(
        [emulator_bin, "-list-avds"],
        check=False,
        text=True,
        capture_output=True,
        timeout=15,
    )
    avds = {line.strip() for line in (listed_avds.stdout or "").splitlines() if line.strip()}
    selected_avd = select_avd_name(config, avds)

    cmd = [
        emulator_bin,
        "-avd",
        selected_avd,
        "-port",
        str(config.console_port),
        "-grpc",
        str(config.grpc_port),
        "-no-snapshot",
        "-no-boot-anim",
    ]
    logging.info("Auto-starting AndroidWorld emulator: %s", " ".join(cmd))
    subprocess.Popen(  # pylint: disable=consider-using-with
        cmd,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
    )

    expected_emulator = f"emulator-{config.console_port}"
    deadline = time.time() + max(config.emulator_boot_timeout_sec, 30)
    while time.time() < deadline:
        if is_expected_emulator_online(config, expected_emulator) and is_local_tcp_port_open(
            config.grpc_port
        ):
            logging.info(
                "AndroidWorld emulator is ready: serial=%s grpc=%s",
                expected_emulator,
                config.grpc_port,
            )
            return
        time.sleep(2)

    raise PreflightError(
        PreflightErrorCode.PRECHECK_FAILED,
        "Timed out waiting for auto-started emulator to become ready. "
        f"Expected serial={expected_emulator}, grpc_port={config.grpc_port}, "
        f"timeout={config.emulator_boot_timeout_sec}s.",
    )


def resolve_emulator_binary(config: RunnerConfig) -> str | None:
    if config.emulator_binary_path:
        return config.emulator_binary_path

    from_path = shutil.which("emulator")
    if from_path:
        return from_path

    home = Path.home()
    candidates = [
        home / "Library/Android/sdk/emulator/emulator",
        home / "Android/Sdk/emulator/emulator",
    ]
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    return None


def wait_for_emulator_stability(config: RunnerConfig, expected_serial: str) -> None:
    timeout_sec = max(config.emulator_boot_timeout_sec, 90)
    run_adb(
        config,
        ["wait-for-device"],
        check=False,
        capture_output=True,
        timeout_sec=timeout_sec,
    )

    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        if not is_expected_emulator_online(config, expected_serial):
            time.sleep(2)
            continue

        boot = run_adb_shell(
            config,
            ["getprop", "sys.boot_completed"],
            check=False,
            capture_output=True,
        )
        whoami = run_adb_shell(
            config,
            ["whoami"],
            check=False,
            capture_output=True,
        )
        boot_ok = (boot.stdout or "").strip() == "1"
        shell_ok = whoami.returncode == 0 and bool((whoami.stdout or "").strip())
        if boot_ok and shell_ok:
            return
        time.sleep(2)

    raise PreflightError(
        PreflightErrorCode.PRECHECK_FAILED,
        "Emulator did not become stable in time "
        f"(serial={expected_serial}, timeout={timeout_sec}s).",
    )


def select_avd_name(config: RunnerConfig, avds: set[str]) -> str:
    if config.emulator_avd_name in avds:
        return config.emulator_avd_name

    if not avds:
        raise PreflightError(
            PreflightErrorCode.PRECHECK_FAILED,
            "No local Android AVD found. Create an AVD (AndroidWorld recommends Pixel 6 API 33) "
            "and set android_world.emulator_avd_name in eval/config/default.yaml.",
        )

    if len(avds) == 1:
        fallback = next(iter(avds))
        logging.warning(
            "Configured AVD '%s' not found; falling back to only available AVD '%s'.",
            config.emulator_avd_name,
            fallback,
        )
        return fallback

    preferred = sorted(
        avds,
        key=lambda name: (
            0 if "androidworld" in name.lower() else 1,
            0 if name.lower().startswith("pixel") else 1,
            name,
        ),
    )[0]
    logging.warning(
        "Configured AVD '%s' not found; falling back to '%s' from available AVDs: %s",
        config.emulator_avd_name,
        preferred,
        sorted(avds),
    )
    return preferred


def build_and_install_bridge(config: RunnerConfig) -> None:
    workspace_root = Path(__file__).resolve().parents[2]
    gradlew = workspace_root / "gradlew"
    apk_path = workspace_root / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    package = config.bridge.package_name

    logging.info("Building agent APK ...")
    result = subprocess.run(
        [str(gradlew), ":app:assembleDebug", "--quiet"],
        cwd=str(workspace_root),
        capture_output=True,
        text=True,
        timeout=300,
    )
    if result.returncode != 0:
        raise PreflightError(
            PreflightErrorCode.PRECHECK_FAILED,
            f"Gradle build failed:\n{result.stderr or result.stdout}",
        )
    logging.info("Agent APK built successfully")

    logging.info("Installing agent APK ...")
    run_adb(config, ["install", "-r", "-t", str(apk_path)], check=True, timeout_sec=120)
    logging.info("Agent APK installed")

    run_adb_shell(config, ["appops", "set", package, "SYSTEM_ALERT_WINDOW", "allow"], check=False)
    logging.info("Overlay permission granted")


def should_run_emulator_setup_retry(config: RunnerConfig, exc: Exception) -> bool:
    if config.suite_family != "android_world":
        return False
    if config.skip_unavailable_tasks:
        return False
    if config.perform_emulator_setup:
        return False
    if not isinstance(exc, PreflightError):
        return False
    return exc.code in (
        PreflightErrorCode.MISSING_TASK_PACKAGES,
        PreflightErrorCode.SNAPSHOTS_MISSING,
    )


def _probe_missing_snapshots(
    app_names: list[str],
    env: Any,
    app_snapshot_module: Any,
) -> dict[str, str]:
    missing: dict[str, str] = {}
    for app_name in app_names:
        try:
            app_snapshot_module.restore_snapshot(app_name, env.controller)
        except RuntimeError as exc:
            missing[app_name] = str(exc)
    return missing


def _snapshot_error_detail(errors: dict[str, str]) -> str:
    return "; ".join(f"{name}: {reason}" for name, reason in sorted(errors.items()))
