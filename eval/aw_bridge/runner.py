from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
from datetime import datetime
import json
import logging
import os
from pathlib import Path
import socket
from typing import Any
from urllib.parse import urlparse

import yaml

from eval.aw_bridge.native_agent_bridge import BridgeConfig, NativeAgentBridge
from eval.aw_bridge.result_schema import TaskResult, summarize_results
from eval.aw_bridge.runner_execution import (
    resolve_task_bridge_config,
    run_one_task_instance,
)
from eval.aw_bridge.runner_preflight import (
    TASK_REQUIRED_PACKAGES,
    create_env,
    resolve_snapshot_policy,
    run_android_world_connectivity_preflight,
    run_preflight_checks,
    should_run_emulator_setup_retry,
)
from eval.aw_bridge.task_loader import (
    TaskInstance,
    build_task_instances,
    ensure_android_world_importable,
    load_task_names_from_file,
)


@dataclass
class RunnerConfig:
    suite_family: str
    output_root: str
    task_random_seed: int
    n_task_combinations: int
    use_identical_params: bool
    skip_unavailable_tasks: bool
    auto_install_missing_task_apps: bool
    perform_bridge_setup: bool
    retry_infra_failures: int
    snapshot_policy: str
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

_API_KEY_NAMES = ("OPENAI_API_KEY", "OPENROUTER_API_KEY", "NOVITA_API_KEY")
_ENV_EXTRAS = ("OPENAI_BASE_URL",)


def main() -> None:
    args = _parse_args()
    workspace_root = Path(__file__).resolve().parents[2]
    config = load_config(workspace_root, args)

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
    run_android_world_connectivity_preflight(config)
    env = create_env(config)

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
            task_instances = run_preflight_checks(config, task_instances, env)
        except Exception as exc:  # pylint: disable=broad-exception-caught
            if should_run_emulator_setup_retry(config, exc):
                logging.warning(
                    "Preflight failed with recoverable setup issue; retrying once with "
                    "perform_emulator_setup=true"
                )
                env.close()
                config.perform_emulator_setup = True
                env = create_env(config)
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
                task_instances = run_preflight_checks(config, task_instances, env)
            else:
                raise

        bridge = NativeAgentBridge(config.bridge)
        for task_idx, task_instance in enumerate(task_instances):
            task_bridge_cfg = resolve_task_bridge_config(
                config.bridge, task_instance.task_name, config.task_overrides
            )
            task_bridge = bridge if task_bridge_cfg is config.bridge else NativeAgentBridge(
                task_bridge_cfg
            )
            final_result = run_one_task_instance(
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


def _resolve_selected_tasks(workspace_root: Path, args: argparse.Namespace) -> list[str] | None:
    if args.tasks:
        return [t.strip() for t in args.tasks.split(",") if t.strip()]
    if args.tasks_file:
        return load_task_names_from_file((workspace_root / args.tasks_file).resolve())
    return None


def load_config(workspace_root: Path, args: argparse.Namespace) -> RunnerConfig:
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
    snapshot_policy = resolve_snapshot_policy(
        args.snapshot_policy or runner_cfg.get("snapshot_policy", "auto_repair")
    ).value

    bridge = BridgeConfig(
        package_name=str(bridge_cfg.get("package_name", "com.moonkey.androidagent")),
        activity=str(bridge_cfg.get("activity", "com.moonkey.androidagent/.app.MainActivity")),
        llm_backend=str(bridge_cfg.get("llm_backend", "openai")),
        agent_mode=str(bridge_cfg.get("agent_mode", "pro")),
        perception_mode=str(bridge_cfg.get("perception_mode", "accessibility_only")),
        platform_mode=str(args.platform_mode or bridge_cfg.get("platform_mode", "accessibility")),
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
        auto_install_missing_task_apps=bool(runner_cfg.get("auto_install_missing_task_apps", True)),
        perform_bridge_setup=bool(runner_cfg.get("perform_bridge_setup", True)),
        retry_infra_failures=int(runner_cfg.get("retry_infra_failures", 1)),
        snapshot_policy=snapshot_policy,
        adb_serial=_nullable_str(args.adb_serial or runner_cfg.get("adb_serial")),
        reference_root=str(aw_cfg.get("reference_root", ".reference/eval/android_world")),
        console_port=int(aw_cfg.get("console_port", 5554)),
        grpc_port=int(aw_cfg.get("grpc_port", 8554)),
        adb_path=_nullable_path_str(aw_cfg.get("adb_path")),
        perform_emulator_setup=bool(aw_cfg.get("perform_emulator_setup", False)),
        freeze_datetime=bool(aw_cfg.get("freeze_datetime", False)),
        auto_start_emulator=bool(aw_cfg.get("auto_start_emulator", True)),
        emulator_avd_name=str(aw_cfg.get("emulator_avd_name", "AndroidWorldAvd")),
        emulator_binary_path=_nullable_path_str(aw_cfg.get("emulator_binary_path")),
        emulator_boot_timeout_sec=int(aw_cfg.get("emulator_boot_timeout_sec", 180)),
        bridge=bridge,
        task_overrides=dict(bridge_cfg.get("task_overrides", {})),
    )


def load_config_from_path(workspace_root: Path, config_path: str | Path) -> RunnerConfig:
    return load_config(
        workspace_root,
        argparse.Namespace(
            config=str(config_path),
            suite=None,
            tasks=None,
            tasks_file=None,
            n_task_combinations=None,
            task_random_seed=None,
            output_root=None,
            adb_serial=None,
            snapshot_policy=None,
            platform_mode=None,
        ),
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
        "--snapshot-policy",
        default=None,
        choices=["strict", "auto_repair", "best_effort", "off"],
    )
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


def _nullable_str(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _nullable_path_str(value: Any) -> str | None:
    text = _nullable_str(value)
    if text is None:
        return None
    return os.path.expanduser(os.path.expandvars(text))


def _load_api_keys(workspace_root: Path) -> dict[str, str]:
    """Load API keys and env extras (e.g. OPENAI_BASE_URL) from .env and environment."""
    _ALL_ENV_NAMES = _API_KEY_NAMES + _ENV_EXTRAS
    keys: dict[str, str] = {}
    env_file = workspace_root / ".env"
    if env_file.is_file():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            name, _, value = line.partition("=")
            name = name.strip()
            value = value.strip().strip("\"'")
            if name in _ALL_ENV_NAMES and value:
                keys[name] = value
    for name in _ALL_ENV_NAMES:
        val = os.environ.get(name)
        if val:
            keys[name] = val
    return keys


def _validate_required_api_key(
    config: RunnerConfig,
    api_keys: dict[str, str],
    workspace_root: Path | None = None,
) -> None:
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
    _validate_openai_base_url(api_keys, required_keys)


def _validate_openai_base_url(api_keys: dict[str, str], required_keys: set[str]) -> None:
    if "OPENAI_API_KEY" not in required_keys:
        return

    base_url = api_keys.get("OPENAI_BASE_URL")
    if not base_url:
        return

    parsed = urlparse(base_url)
    if not parsed.scheme or not parsed.hostname:
        raise RuntimeError(f"Invalid OPENAI_BASE_URL: {base_url}")

    host = parsed.hostname
    if host in {"localhost", "127.0.0.1", "::1", "10.0.2.2"}:
        host = "127.0.0.1"
    port = parsed.port or (443 if parsed.scheme == "https" else 80)

    try:
        with socket.create_connection((host, port), timeout=2.0):
            return
    except OSError as exc:
        hint = ""
        if port == 18080 and host == "127.0.0.1":
            hint = " Start the local proxy or establish the SSH tunnel before running eval."
        raise RuntimeError(
            "OPENAI_BASE_URL is configured but not reachable from the eval host: "
            f"{base_url} ({host}:{port}).{hint}"
        ) from exc


def _resolve_required_api_keys_for_models(config: RunnerConfig, workspace_root: Path) -> set[str]:
    model_catalog_path = workspace_root / "app" / "src" / "main" / "assets" / "llm_models.json"
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
            raise RuntimeError(f"Unknown model key '{model_name}' in {model_catalog_path}")
        provider = str(entry.get("provider", "")).strip().upper()
        env_name = _PROVIDER_REQUIRED_API_KEY.get(provider)
        if not env_name:
            raise RuntimeError(
                f"Unsupported provider '{provider}' for model '{model_name}' "
                f"in {model_catalog_path}"
            )
        required.add(env_name)
    return required


def _safe_config_for_logging(config: RunnerConfig) -> dict[str, Any]:
    safe = asdict(config)
    bridge = safe.get("bridge")
    if isinstance(bridge, dict) and "api_keys" in bridge:
        keys = bridge.get("api_keys") or {}
        bridge["api_keys"] = {name: "***" for name in keys}
    return safe


if __name__ == "__main__":
    main()
