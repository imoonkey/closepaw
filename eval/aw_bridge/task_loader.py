from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import sys
from typing import Any


@dataclass
class TaskInstance:
    task_name: str
    instance_index: int
    seed: int | None
    goal: str
    task: Any


def ensure_android_world_importable(workspace_root: Path, reference_root: str) -> None:
    aw_root = (workspace_root / reference_root).resolve()
    if not aw_root.exists():
        raise FileNotFoundError(f"AndroidWorld reference path not found: {aw_root}")
    path_value = str(aw_root)
    if path_value not in sys.path:
        sys.path.insert(0, path_value)


def load_task_names_from_file(path: Path) -> list[str]:
    lines = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        stripped = raw.strip()
        if not stripped or stripped.startswith("#"):
            continue
        lines.append(stripped)
    return lines


def build_task_instances(
    suite_family: str,
    n_task_combinations: int,
    task_random_seed: int,
    use_identical_params: bool,
    selected_tasks: list[str] | None,
    env: Any,
) -> list[TaskInstance]:
    from android_world import registry  # type: ignore
    from android_world import suite_utils  # type: ignore

    task_registry = registry.TaskRegistry()
    family_registry = task_registry.get_registry(family=suite_family)
    suite = suite_utils.create_suite(
        family_registry,
        n_task_combinations=n_task_combinations,
        seed=task_random_seed,
        tasks=selected_tasks,
        use_identical_params=use_identical_params,
        env=env,
    )

    items: list[TaskInstance] = []
    for task_name, instances in suite.items():
        for idx, task in enumerate(instances):
            seed = task.params.get("seed") if hasattr(task, "params") else None
            items.append(
                TaskInstance(
                    task_name=task_name,
                    instance_index=idx,
                    seed=seed,
                    goal=task.goal,
                    task=task,
                )
            )
    return items
