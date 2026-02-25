from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass, replace
from datetime import datetime
import json
import logging
import socket
import os
from pathlib import Path
import shutil
import subprocess
import time
from typing import Any

import yaml

from eval.aw_bridge.native_agent_bridge import BridgeConfig, BridgeOutcome, NativeAgentBridge
from eval.aw_bridge.result_schema import ArtifactPaths, TaskResult, summarize_results
from eval.aw_bridge.task_loader import (
    TaskInstance,
    build_task_instances,
    ensure_android_world_importable,
    load_task_names_from_file,
)
from eval.aw_bridge.trace_parser import empty_trace_result, parse_trace


@dataclass
class RunnerConfig:
    suite_family: str
    output_root: str
    task_random_seed: int
    n_task_combinations: int
    use_identical_params: bool
    skip_unavailable_tasks: bool
    auto_install_missing_task_apps: bool
    retry_infra_failures: int
    adb_serial: str | None
    reference_root: str
    console_port: int
    grpc_port: int
    adb_path: str | None
    perform_emulator_setup: bool
    freeze_datetime: bool
    auto_start_emulator: bool
    emulator_avd_name: str
    emulator_binary_path: str | None
    emulator_boot_timeout_sec: int
    bridge: BridgeConfig
    task_overrides: dict[str, dict[str, Any]]


_PROVIDER_REQUIRED_API_KEY = {
    "OPENAI": "OPENAI_API_KEY",
    "OPENROUTER": "OPENROUTER_API_KEY",
    "NOVITA": "NOVITA_API_KEY",
}

_TASK_REQUIRED_PACKAGES: dict[str, tuple[str, ...]] = {
    "BrowserMultiply": ("com.android.chrome",),
    "ClockTimerEntry": ("com.google.android.deskclock", "com.android.deskclock"),
    "ContactsAddContact": ("com.android.contacts", "com.google.android.contacts"),
    "ExpenseAddSingle": ("com.arduia.expense",),
    "MarkorCreateNote": ("net.gsantner.markor",),
    "RecipeAddSingleRecipe": ("com.flauschcode.broccoli",),
    "SimpleSmsSend": ("com.simplemobiletools.smsmessenger",),
}


def main() -> None:
    args = _parse_args()
    workspace_root = Path(__file__).resolve().parents[2]
    config = _load_config(workspace_root, args)

    # Load API keys from .env file and/or environment
    api_keys = _load_api_keys(workspace_root)
    config.bridge.api_keys = api_keys or None
    _validate_required_api_key(config, api_keys, workspace_root)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    run_dir = (workspace_root / config.output_root / timestamp).resolve()
    artifact_root = run_dir / "artifacts"
    artifact_root.mkdir(parents=True, exist_ok=True)
    runner_log = run_dir / "runner.log"
    _setup_logging(runner_log)

    logging.info("Run directory: %s", run_dir)
    logging.info("Config: %s", _safe_config_for_logging(config))

    ensure_android_world_importable(workspace_root, config.reference_root)
    _run_android_world_connectivity_preflight(config)
    env = _create_env(config)

    all_attempt_results: list[TaskResult] = []
    final_results: list[TaskResult] = []
    per_task_jsonl = run_dir / "per_task.jsonl"

    try:
        selected_tasks = _resolve_selected_tasks(workspace_root, args)
        task_instances = build_task_instances(
            suite_family=config.suite_family,
            n_task_combinations=config.n_task_combinations,
            task_random_seed=config.task_random_seed,
            use_identical_params=config.use_identical_params,
            selected_tasks=selected_tasks,
            env=env,
        )
        logging.info("Loaded %d task instances", len(task_instances))
        try:
            task_instances = _run_preflight_checks(config, task_instances, env)
        except RuntimeError as exc:
            if _should_run_emulator_setup_retry(config, exc):
                logging.warning(
                    "Missing benchmark apps detected; retrying once with perform_emulator_setup=true"
                )
                env.close()
                config.perform_emulator_setup = True
                env = _create_env(config)
                task_instances = build_task_instances(
                    suite_family=config.suite_family,
                    n_task_combinations=config.n_task_combinations,
                    task_random_seed=config.task_random_seed,
                    use_identical_params=config.use_identical_params,
                    selected_tasks=selected_tasks,
                    env=env,
                )
                logging.info(
                    "Reloaded %d task instances after emulator setup",
                    len(task_instances),
                )
                task_instances = _run_preflight_checks(config, task_instances, env)
            else:
                raise

        bridge = NativeAgentBridge(config.bridge)
        for task_idx, task_instance in enumerate(task_instances):
            task_bridge_cfg = _resolve_task_bridge_config(
                config.bridge, task_instance.task_name, config.task_overrides
            )
            task_bridge = (
                bridge if task_bridge_cfg is config.bridge
                else NativeAgentBridge(task_bridge_cfg)
            )
            final_result = _run_one_task_instance(
                bridge=task_bridge,
                suite_family=config.suite_family,
                task_instance=task_instance,
                task_index=task_idx,
                run_prefix=f"aw_{timestamp}",
                artifact_root=artifact_root,
                runner_log=runner_log,
                max_infra_retries=config.retry_infra_failures,
                env=env,
                per_task_jsonl=per_task_jsonl,
                all_attempt_results=all_attempt_results,
            )
            final_results.append(final_result)
    finally:
        env.close()

    summary = summarize_results(final_results)
    safe_config = asdict(config)
    # Redact API keys from persisted config
    if "bridge" in safe_config and "api_keys" in safe_config["bridge"]:
        safe_config["bridge"]["api_keys"] = {
            k: "***" for k in (safe_config["bridge"]["api_keys"] or {})
        }
    summary_payload = {
        "run_timestamp": timestamp,
        "suite_family": config.suite_family,
        "num_task_instances": len(final_results),
        "num_attempts": len(all_attempt_results),
        "config": safe_config,
        "metrics": summary,
    }
    summary_path = run_dir / "summary.json"
    summary_path.write_text(
        json.dumps(summary_payload, ensure_ascii=True, indent=2),
        encoding="utf-8",
    )
    logging.info("Wrote summary: %s", summary_path)
    print(json.dumps(summary_payload["metrics"], ensure_ascii=True, indent=2))


def _run_one_task_instance(
    bridge: NativeAgentBridge,
    suite_family: str,
    task_instance: TaskInstance,
    task_index: int,
    run_prefix: str,
    artifact_root: Path,
    runner_log: Path,
    max_infra_retries: int,
    env: Any,
    per_task_jsonl: Path,
    all_attempt_results: list[TaskResult],
) -> TaskResult:
    attempt = 0
    while True:
        run_id = (
            f"{run_prefix}_{_safe_token(task_instance.task_name)}_"
            f"{task_index}_{attempt}"
        )
        logging.info(
            "[%d] Running task=%s instance=%d attempt=%d run_id=%s",
            task_index,
            task_instance.task_name,
            task_instance.instance_index,
            attempt,
            run_id,
        )

        task = task_instance.task
        artifact_dir = artifact_root / run_id
        trace_dir = artifact_dir / "trace"
        initialized = False
        scripted_score: float | None = None
        scripted_success = False
        task_status: str | None = None
        exception: str | None = None
        bridge_outcome = BridgeOutcome(
            bridge_status="infra_failure",
            agent_completion_reason=None,
            duration_sec=0.0,
            logcat_path=str(artifact_dir / "logcat.log"),
            exception=None,
        )
        trace_parse = empty_trace_result()

        try:
            task.initialize_task(env)
            initialized = True

            bridge_outcome = bridge.run_task(
                goal=task_instance.goal,
                run_id=run_id,
                artifact_dir=artifact_dir,
            )
            trace_pulled = bridge.pull_trace_dir(run_id=run_id, local_trace_dir=trace_dir)
            trace_parse = parse_trace(trace_dir) if trace_pulled else empty_trace_result()

            if trace_parse.answer is not None:
                env.interaction_cache = trace_parse.answer

            if bridge_outcome.bridge_status != "infra_failure":
                scoring_ctx = _capture_scoring_context(bridge, run_id)
                scripted_score = float(task.is_successful(env))
                scoring_ctx["score"] = scripted_score
                scoring_ctx["scoring_duration_ms"] = int(
                    (time.time() - scoring_ctx["scoring_timestamp"]) * 1000
                )
                scripted_success = scripted_score > 0.5
                task_status = "success" if scripted_success else "failure"
                logging.info(
                    "Scoring: run_id=%s score=%.1f a11y=%s fg=%s elements=%d",
                    run_id,
                    scripted_score,
                    scoring_ctx.get("enabled_a11y_services", "?"),
                    scoring_ctx.get("foreground_activity", "?"),
                    scoring_ctx.get("ui_element_count", -1),
                )
                _write_scoring_context(artifact_dir, scoring_ctx)
        except Exception as exc:  # pylint: disable=broad-exception-caught
            exception = str(exc)
            logging.exception(
                "Task execution failed for %s attempt=%d",
                task_instance.task_name,
                attempt,
            )
        finally:
            if initialized:
                try:
                    task.tear_down(env)
                except Exception as teardown_exc:  # pylint: disable=broad-exception-caught
                    if exception:
                        exception = f"{exception}; tear_down={teardown_exc}"
                    else:
                        exception = f"tear_down={teardown_exc}"

            bridge.force_stop()

        result = TaskResult(
            task_name=task_instance.task_name,
            suite_family=suite_family,
            seed=task_instance.seed,
            goal=task_instance.goal,
            run_id=run_id,
            attempt=attempt,
            bridge_status=bridge_outcome.bridge_status,
            agent_completion_reason=(
                trace_parse.completion_reason or bridge_outcome.agent_completion_reason
            ),
            task_status=task_status,
            answer=trace_parse.answer,
            scripted_score=scripted_score,
            scripted_success=scripted_success,
            duration_sec=bridge_outcome.duration_sec,
            turns_executed=trace_parse.turns_executed,
            tool_calls=trace_parse.tool_calls,
            tool_failures=trace_parse.tool_failures,
            artifact_paths=ArtifactPaths(
                trace_dir=str(trace_dir) if trace_dir.exists() else None,
                logcat=bridge_outcome.logcat_path,
                runner_log=str(runner_log),
            ),
            exception=exception or bridge_outcome.exception,
        )

        _append_jsonl(per_task_jsonl, result.to_dict())
        all_attempt_results.append(result)

        should_retry = (
            result.bridge_status == "infra_failure" and attempt < max_infra_retries
        )
        if should_retry:
            attempt += 1
            logging.warning(
                "Infra failure for %s (attempt=%d), retrying...",
                task_instance.task_name,
                attempt,
            )
            continue

        return result


def _create_env(config: RunnerConfig) -> Any:
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


def _resolve_selected_tasks(workspace_root: Path, args: argparse.Namespace) -> list[str] | None:
    if args.tasks:
        return [t.strip() for t in args.tasks.split(",") if t.strip()]
    if args.tasks_file:
        return load_task_names_from_file((workspace_root / args.tasks_file).resolve())
    return None


def _load_config(workspace_root: Path, args: argparse.Namespace) -> RunnerConfig:
    config_path = (workspace_root / args.config).resolve()
    if not config_path.exists():
        raise FileNotFoundError(f"Config not found: {config_path}")
    raw = yaml.safe_load(config_path.read_text(encoding="utf-8")) or {}

    suite_family = args.suite or raw.get("suite_family", "android_world")
    runner_cfg = raw.get("runner", {})
    aw_cfg = raw.get("android_world", {})
    bridge_cfg = raw.get("bridge", {})

    n_task_combinations = (
        args.n_task_combinations
        if args.n_task_combinations is not None
        else int(runner_cfg.get("n_task_combinations", 1))
    )
    task_random_seed = (
        args.task_random_seed
        if args.task_random_seed is not None
        else int(runner_cfg.get("task_random_seed", 30))
    )

    bridge = BridgeConfig(
        package_name=str(bridge_cfg.get("package_name", "com.moonkey.androidagent")),
        activity=str(
            bridge_cfg.get("activity", "com.moonkey.androidagent/.app.MainActivity")
        ),
        llm_backend=str(bridge_cfg.get("llm_backend", "openai")),
        agent_mode=str(bridge_cfg.get("agent_mode", "pro")),
        perception_mode=str(bridge_cfg.get("perception_mode", "accessibility_only")),
        platform_mode=str(
            args.platform_mode or bridge_cfg.get("platform_mode", "accessibility")
        ),
        main_model=str(bridge_cfg.get("main_model", "minimax-m2.5")),
        executor_model=str(bridge_cfg.get("executor_model", "")),
        max_turns=int(bridge_cfg.get("max_turns", 30)),
        auto_start=bool(bridge_cfg.get("auto_start", True)),
        fresh_session=bool(bridge_cfg.get("fresh_session", True)),
        debug_mode=bool(bridge_cfg.get("debug_mode", False)),
        trace_enabled=bool(bridge_cfg.get("trace_enabled", True)),
        max_wait_seconds=int(bridge_cfg.get("max_wait_seconds", 900)),
        poll_interval_seconds=float(bridge_cfg.get("poll_interval_seconds", 1)),
        adb_serial=_nullable_str(args.adb_serial or runner_cfg.get("adb_serial")),
        stop_agent_after_task=bool(runner_cfg.get("stop_agent_after_task", True)),
        adb_command_timeout_sec=int(runner_cfg.get("adb_command_timeout_sec", 60)),
        adb_pull_timeout_sec=int(runner_cfg.get("adb_pull_timeout_sec", 300)),
        shizuku_apk_path=_nullable_str(bridge_cfg.get("shizuku_apk_path")),
        excluded_tools=str(bridge_cfg.get("excluded_tools", "")),
    )

    return RunnerConfig(
        suite_family=suite_family,
        output_root=str(args.output_root or runner_cfg.get("output_root", "eval/results")),
        task_random_seed=task_random_seed,
        n_task_combinations=n_task_combinations,
        use_identical_params=bool(runner_cfg.get("use_identical_params", False)),
        skip_unavailable_tasks=bool(runner_cfg.get("skip_unavailable_tasks", True)),
        auto_install_missing_task_apps=bool(
            runner_cfg.get("auto_install_missing_task_apps", True)
        ),
        retry_infra_failures=int(runner_cfg.get("retry_infra_failures", 1)),
        adb_serial=_nullable_str(args.adb_serial or runner_cfg.get("adb_serial")),
        reference_root=str(aw_cfg.get("reference_root", ".reference/eval/android_world")),
        console_port=int(aw_cfg.get("console_port", 5554)),
        grpc_port=int(aw_cfg.get("grpc_port", 8554)),
        adb_path=_nullable_str(aw_cfg.get("adb_path")),
        perform_emulator_setup=bool(aw_cfg.get("perform_emulator_setup", False)),
        freeze_datetime=bool(aw_cfg.get("freeze_datetime", False)),
        auto_start_emulator=bool(aw_cfg.get("auto_start_emulator", True)),
        emulator_avd_name=str(aw_cfg.get("emulator_avd_name", "AndroidWorldAvd")),
        emulator_binary_path=_nullable_str(aw_cfg.get("emulator_binary_path")),
        emulator_boot_timeout_sec=int(aw_cfg.get("emulator_boot_timeout_sec", 180)),
        bridge=bridge,
        task_overrides=dict(bridge_cfg.get("task_overrides", {})),
    )


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="AndroidWorld bridge runner")
    parser.add_argument("--config", default="eval/config/default.yaml")
    parser.add_argument("--suite", default=None)
    parser.add_argument("--tasks-file", default=None)
    parser.add_argument("--tasks", default=None, help="Comma-separated task names")
    parser.add_argument("--n-task-combinations", type=int, default=None)
    parser.add_argument("--task-random-seed", type=int, default=None)
    parser.add_argument("--output-root", default=None)
    parser.add_argument("--adb-serial", default=None)
    parser.add_argument(
        "--platform-mode",
        default=None,
        choices=["accessibility", "virtual_display"],
        help="Platform mode: accessibility (default) or virtual_display",
    )
    return parser.parse_args()


def _setup_logging(log_path: Path) -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        handlers=[
            logging.FileHandler(log_path, encoding="utf-8"),
            logging.StreamHandler(),
        ],
    )


def _append_jsonl(path: Path, row: dict[str, Any]) -> None:
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(row, ensure_ascii=True))
        stream.write("\n")


def _safe_token(value: str) -> str:
    chars = []
    for ch in value:
        if ch.isalnum() or ch in ("-", "_"):
            chars.append(ch)
        else:
            chars.append("_")
    token = "".join(chars).strip("_")
    return token[:80] if token else "task"


def _nullable_str(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


_API_KEY_NAMES = ("OPENAI_API_KEY", "OPENROUTER_API_KEY", "NOVITA_API_KEY")


def _load_api_keys(workspace_root: Path) -> dict[str, str]:
    """Load API keys from .env file and environment variables."""
    keys: dict[str, str] = {}
    env_file = workspace_root / ".env"
    if env_file.is_file():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                continue
            name, _, value = line.partition("=")
            name = name.strip()
            value = value.strip().strip("\"'")
            if name in _API_KEY_NAMES and value:
                keys[name] = value
    # Environment variables override .env
    for name in _API_KEY_NAMES:
        val = os.environ.get(name)
        if val:
            keys[name] = val
    return keys


def _validate_required_api_key(
    config: RunnerConfig,
    api_keys: dict[str, str],
    workspace_root: Path | None = None,
) -> None:
    """Validate cloud API keys required by selected model(s).

    Semantics:
    - llm_backend == "local": no cloud API key required.
    - Any cloud backend (including "openai" as OpenAI-compatible protocol):
      API key requirement is determined by model provider in llm_models.json.
    """
    backend = config.bridge.llm_backend.strip().lower()
    if backend == "local":
        return

    workspace = workspace_root or Path(__file__).resolve().parents[2]
    required_keys = _resolve_required_api_keys_for_models(config, workspace)
    missing = [name for name in sorted(required_keys) if not api_keys.get(name)]
    if missing:
        models = [config.bridge.main_model]
        if config.bridge.executor_model.strip():
            models.append(config.bridge.executor_model.strip())
        raise RuntimeError(
            "Missing required API key(s) for selected model(s): "
            f"{', '.join(missing)}. Models={models}. "
            "Add keys to .env or environment variables."
        )


def _resolve_required_api_keys_for_models(
    config: RunnerConfig, workspace_root: Path
) -> set[str]:
    model_catalog_path = (
        workspace_root / "app" / "src" / "main" / "assets" / "llm_models.json"
    )
    if not model_catalog_path.is_file():
        raise RuntimeError(f"Model catalog not found: {model_catalog_path}")

    raw = json.loads(model_catalog_path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise RuntimeError(f"Invalid model catalog format: {model_catalog_path}")

    model_names: list[str] = [config.bridge.main_model.strip()]
    executor_model = config.bridge.executor_model.strip()
    if executor_model:
        model_names.append(executor_model)

    required: set[str] = set()
    for model_name in model_names:
        entry = raw.get(model_name)
        if not isinstance(entry, dict):
            raise RuntimeError(
                f"Unknown model key '{model_name}' in {model_catalog_path}"
            )
        provider = str(entry.get("provider", "")).strip().upper()
        env_name = _PROVIDER_REQUIRED_API_KEY.get(provider)
        if not env_name:
            raise RuntimeError(
                f"Unsupported provider '{provider}' for model '{model_name}' "
                f"in {model_catalog_path}"
            )
        required.add(env_name)
    return required


def _build_and_install_bridge(config: RunnerConfig) -> None:
    """Build the agent APK and install it on the target device."""
    workspace_root = Path(__file__).resolve().parents[2]
    gradlew = workspace_root / "gradlew"
    apk_path = workspace_root / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    package = config.bridge.package_name

    logging.info("Building agent APK …")
    result = subprocess.run(
        [str(gradlew), ":app:assembleDebug", "--quiet"],
        cwd=str(workspace_root),
        capture_output=True,
        text=True,
        timeout=300,
    )
    if result.returncode != 0:
        raise RuntimeError(f"Gradle build failed:\n{result.stderr or result.stdout}")
    logging.info("Agent APK built successfully")

    logging.info("Installing agent APK …")
    _run_adb(config, ["install", "-r", "-t", str(apk_path)], check=True, timeout_sec=120)
    logging.info("Agent APK installed")

    # Grant overlay permission
    _run_adb_shell(config, ["appops", "set", package, "SYSTEM_ALERT_WINDOW", "allow"], check=False)
    logging.info("Overlay permission granted")


def _run_preflight_checks(
    config: RunnerConfig,
    task_instances: list[TaskInstance],
    env: Any,
) -> list[TaskInstance]:
    _ensure_adb_device_ready(config)
    _build_and_install_bridge(config)
    if config.auto_install_missing_task_apps:
        _attempt_targeted_task_app_install(config, task_instances, env)
    if config.skip_unavailable_tasks:
        return _filter_unavailable_task_instances(config, task_instances)
    _ensure_task_packages_installed(config, task_instances)
    return task_instances


def _run_android_world_connectivity_preflight(config: RunnerConfig) -> None:
    if config.suite_family != "android_world":
        return

    expected_emulator = f"emulator-{config.console_port}"
    if config.adb_serial:
        if not config.adb_serial.startswith("emulator-"):
            raise RuntimeError(
                "AndroidWorld requires an emulator adb serial, but runner.adb_serial/--adb-serial "
                f"is '{config.adb_serial}'."
            )
        if config.adb_serial != expected_emulator:
            raise RuntimeError(
                "AndroidWorld runner.adb_serial/--adb-serial must match console_port mapping. "
                f"Got adb_serial='{config.adb_serial}' but console_port={config.console_port} "
                f"(expected serial '{expected_emulator}')."
            )
    else:
        config.adb_serial = expected_emulator
    config.bridge.adb_serial = config.adb_serial

    _run_adb_global(config, ["start-server"], check=False, capture_output=True)

    if config.auto_start_emulator:
        if not _is_expected_emulator_online(config, expected_emulator):
            _start_android_world_emulator(config)

    if not _is_expected_emulator_online(config, expected_emulator):
        raise RuntimeError(
            "AndroidWorld emulator not detected. "
            f"Expected adb device '{expected_emulator}' for console_port={config.console_port}. "
            "Start the benchmark emulator before running eval."
        )

    if not _is_local_tcp_port_open(config.grpc_port):
        raise RuntimeError(
            "AndroidWorld gRPC endpoint is not reachable on localhost "
            f"port {config.grpc_port}. "
            "Ensure the emulator is started with the matching gRPC port, or update "
            "android_world.grpc_port in eval/config/default.yaml."
        )

    _wait_for_emulator_stability(config, expected_emulator)


def _ensure_adb_device_ready(config: RunnerConfig) -> None:
    result = _run_adb(config, ["get-state"], check=False, capture_output=True)
    state = (result.stdout or "").strip().lower()
    if result.returncode != 0 or state != "device":
        detail = (result.stderr or result.stdout or "unknown").strip()
        raise RuntimeError(
            "ADB device is not ready. Ensure emulator/device is running and authorized. "
            f"state={state or 'unknown'} detail={detail}"
        )


def _ensure_package_installed(config: RunnerConfig, package: str, label: str) -> None:
    result = _run_adb_shell(config, ["pm", "path", package], check=False, capture_output=True)
    if result.returncode != 0 or "package:" not in (result.stdout or ""):
        detail = (result.stderr or result.stdout or "not installed").strip()
        raise RuntimeError(f"Missing {label}: {package}. adb detail: {detail}")


def _ensure_task_packages_installed(config: RunnerConfig, task_instances: list[TaskInstance]) -> None:
    missing_by_task = _collect_missing_task_packages(config, task_instances)

    if missing_by_task:
        lines = []
        for task_name in sorted(missing_by_task):
            lines.append(f"- {task_name}: one of {', '.join(missing_by_task[task_name])}")
        raise RuntimeError(
            "Missing benchmark app packages required by selected tasks:\n"
            + "\n".join(lines)
            + "\nInstall required benchmark apps before running smoke tests."
        )


def _is_package_installed(config: RunnerConfig, package: str) -> bool:
    result = _run_adb_shell(config, ["pm", "path", package], check=False, capture_output=True)
    return result.returncode == 0 and "package:" in (result.stdout or "")


def _collect_missing_task_packages(
    config: RunnerConfig,
    task_instances: list[TaskInstance],
) -> dict[str, list[str]]:
    missing_by_task: dict[str, list[str]] = {}
    for task in task_instances:
        candidates = _TASK_REQUIRED_PACKAGES.get(task.task_name)
        if not candidates:
            continue
        installed = any(_is_package_installed(config, package) for package in candidates)
        if not installed:
            missing_by_task[task.task_name] = list(candidates)
    return missing_by_task


def _attempt_targeted_task_app_install(
    config: RunnerConfig,
    task_instances: list[TaskInstance],
    env: Any,
) -> None:
    if config.suite_family != "android_world":
        return

    missing_before = _collect_missing_task_packages(config, task_instances)
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
            _fallback_install_apk_candidates(config, app_class)

    missing_after = _collect_missing_task_packages(config, task_instances)
    if missing_after == missing_before:
        logging.warning("Targeted app install did not resolve missing task dependencies.")
    else:
        logging.info("Targeted app install reduced missing task dependencies.")


def _fallback_install_apk_candidates(config: RunnerConfig, app_class: Any) -> None:
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
            result = _run_adb(config, ["install", "-r", path], check=False, capture_output=True)
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


def _filter_unavailable_task_instances(
    config: RunnerConfig,
    task_instances: list[TaskInstance],
) -> list[TaskInstance]:
    available: list[TaskInstance] = []
    dropped: dict[str, list[str]] = {}
    for task in task_instances:
        candidates = _TASK_REQUIRED_PACKAGES.get(task.task_name)
        if not candidates:
            available.append(task)
            continue
        installed = any(_is_package_installed(config, package) for package in candidates)
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
        raise RuntimeError(
            "No runnable tasks remain after filtering unavailable app dependencies."
        )

    return available


def _run_adb(
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


def _run_adb_global(
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


def _run_adb_shell(
    config: RunnerConfig,
    args: list[str],
    check: bool,
    capture_output: bool = False,
    timeout_sec: float | None = None,
) -> subprocess.CompletedProcess[str]:
    return _run_adb(
        config,
        ["shell", *args],
        check=check,
        capture_output=capture_output,
        timeout_sec=timeout_sec,
    )


def _is_local_tcp_port_open(port: int, timeout_sec: float = 0.2) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(timeout_sec)
        return sock.connect_ex(("127.0.0.1", int(port))) == 0


def _is_expected_emulator_online(config: RunnerConfig, expected_serial: str) -> bool:
    devices_out = _run_adb_global(config, ["devices"], check=False, capture_output=True)
    devices_text = (devices_out.stdout or "")
    return any(
        line.startswith(expected_serial + "\t") and "device" in line
        for line in devices_text.splitlines()
    )


def _start_android_world_emulator(config: RunnerConfig) -> None:
    emulator_bin = _resolve_emulator_binary(config)
    if not emulator_bin:
        raise RuntimeError(
            "Android emulator binary not found. Set android_world.emulator_binary_path "
            "or add `emulator` to PATH."
        )

    listed_avds = subprocess.run(
        [emulator_bin, "-list-avds"],
        check=False,
        text=True,
        capture_output=True,
        timeout=15,
    )
    avds = {line.strip() for line in (listed_avds.stdout or "").splitlines() if line.strip()}
    selected_avd = _select_avd_name(config, avds)

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
    _log_cmd = " ".join(cmd)
    logging.info("Auto-starting AndroidWorld emulator: %s", _log_cmd)
    subprocess.Popen(  # pylint: disable=consider-using-with
        cmd,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
    )

    expected_emulator = f"emulator-{config.console_port}"
    deadline = time.time() + max(config.emulator_boot_timeout_sec, 30)
    while time.time() < deadline:
        if _is_expected_emulator_online(config, expected_emulator) and _is_local_tcp_port_open(
            config.grpc_port
        ):
            logging.info(
                "AndroidWorld emulator is ready: serial=%s grpc=%s",
                expected_emulator,
                config.grpc_port,
            )
            return
        time.sleep(2)

    raise RuntimeError(
        "Timed out waiting for auto-started emulator to become ready. "
        f"Expected serial={expected_emulator}, grpc_port={config.grpc_port}, "
        f"timeout={config.emulator_boot_timeout_sec}s."
    )


def _resolve_emulator_binary(config: RunnerConfig) -> str | None:
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


def _wait_for_emulator_stability(config: RunnerConfig, expected_serial: str) -> None:
    timeout_sec = max(config.emulator_boot_timeout_sec, 90)
    _run_adb(
        config,
        ["wait-for-device"],
        check=False,
        capture_output=True,
        timeout_sec=timeout_sec,
    )

    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        if not _is_expected_emulator_online(config, expected_serial):
            time.sleep(2)
            continue

        boot = _run_adb_shell(
            config,
            ["getprop", "sys.boot_completed"],
            check=False,
            capture_output=True,
        )
        whoami = _run_adb_shell(
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

    raise RuntimeError(
        "Emulator did not become stable in time "
        f"(serial={expected_serial}, timeout={timeout_sec}s)."
    )


def _select_avd_name(config: RunnerConfig, avds: set[str]) -> str:
    if config.emulator_avd_name in avds:
        return config.emulator_avd_name

    if not avds:
        raise RuntimeError(
            "No local Android AVD found. Create an AVD (AndroidWorld recommends Pixel 6 API 33) "
            "and set android_world.emulator_avd_name in eval/config/default.yaml."
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


def _capture_scoring_context(
    bridge: NativeAgentBridge,
    run_id: str,
) -> dict[str, Any]:
    """Capture device state at scoring time for diagnostics."""
    ctx: dict[str, Any] = {
        "scoring_timestamp": time.time(),
        "run_id": run_id,
    }
    cfg = bridge._config  # noqa: SLF001  — internal eval code
    serial_args = ["-s", cfg.adb_serial] if cfg.adb_serial else []
    timeout = float(cfg.adb_command_timeout_sec)

    # Foreground activity
    try:
        result = subprocess.run(
            ["adb", *serial_args, "shell", "dumpsys", "activity", "activities"],
            check=False, text=True, capture_output=True, timeout=timeout,
        )
        for line in (result.stdout or "").splitlines():
            line = line.strip()
            if "topResumedActivity=" in line or "mResumedActivity=" in line:
                # Extract ComponentInfo{com.pkg/.Activity} pattern
                start = line.find("{")
                end = line.find("}", start)
                if start >= 0 and end > start:
                    component = line[start + 1 : end]
                    pkg, _, activity = component.partition("/")
                    ctx["foreground_package"] = pkg
                    ctx["foreground_activity"] = activity
                break
    except Exception as exc:
        ctx["foreground_error"] = str(exc)

    # Enabled accessibility services
    try:
        result = subprocess.run(
            ["adb", *serial_args, "shell", "settings", "get", "secure",
             "enabled_accessibility_services"],
            check=False, text=True, capture_output=True, timeout=timeout,
        )
        ctx["enabled_a11y_services"] = (result.stdout or "").strip()
    except Exception as exc:
        ctx["a11y_error"] = str(exc)

    # UI element count via accessibility dump
    try:
        result = subprocess.run(
            ["adb", *serial_args, "shell", "dumpsys", "accessibility"],
            check=False, text=True, capture_output=True, timeout=timeout,
        )
        # Count "nodeId" occurrences as a proxy for visible UI elements
        node_count = (result.stdout or "").count("nodeId")
        ctx["ui_element_count"] = node_count
    except Exception:
        ctx["ui_element_count"] = -1

    return ctx


def _write_scoring_context(artifact_dir: Path, ctx: dict[str, Any]) -> None:
    """Write scoring context JSON to artifact directory."""
    try:
        artifact_dir.mkdir(parents=True, exist_ok=True)
        path = artifact_dir / "scoring_context.json"
        path.write_text(json.dumps(ctx, indent=2), encoding="utf-8")
    except Exception as exc:
        logging.warning("Failed to write scoring_context.json: %s", exc)


def _resolve_task_bridge_config(
    base: BridgeConfig,
    task_name: str,
    overrides: dict[str, dict[str, Any]],
) -> BridgeConfig:
    """Merge per-task overrides into base bridge config.

    Matches by task name prefix; longest prefix wins.
    """
    for prefix, fields in sorted(overrides.items(), key=lambda kv: -len(kv[0])):
        if task_name.startswith(prefix):
            logging.info("Applying task override for %s (prefix=%s): %s", task_name, prefix, fields)
            return replace(base, **fields)
    return base


def _safe_config_for_logging(config: RunnerConfig) -> dict[str, Any]:
    safe = asdict(config)
    bridge = safe.get("bridge")
    if isinstance(bridge, dict) and "api_keys" in bridge:
        keys = bridge.get("api_keys") or {}
        bridge["api_keys"] = {name: "***" for name in keys}
    return safe


def _should_run_emulator_setup_retry(config: RunnerConfig, exc: RuntimeError) -> bool:
    if config.suite_family != "android_world":
        return False
    if config.skip_unavailable_tasks:
        return False
    if config.perform_emulator_setup:
        return False
    return "Missing benchmark app packages required by selected tasks:" in str(exc)


if __name__ == "__main__":
    main()
