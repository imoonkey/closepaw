#!/usr/bin/env python3
"""Validate AW subset groups against fullset and against each other.

Checks:
1) Every task in each subset group exists in aw_fullset.txt.
2) Subset groups have no overlaps with each other.

Usage:
  python3 scripts/check_aw_subsets.py
  python3 scripts/check_aw_subsets.py \
    --fullset eval/config/aw_fullset.txt \
    --groups eval/config/aw_subset_group_1.txt eval/config/aw_subset_group_2.txt
"""

from __future__ import annotations

import argparse
from itertools import combinations
from pathlib import Path
import sys


def read_tasks(path: Path) -> list[str]:
    tasks: list[str] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        tasks.append(line)
    return tasks


def find_duplicates(items: list[str]) -> list[str]:
    seen: set[str] = set()
    dupes: set[str] = set()
    for item in items:
        if item in seen:
            dupes.add(item)
        else:
            seen.add(item)
    return sorted(dupes)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--fullset",
        type=Path,
        default=Path("eval/config/aw_fullset.txt"),
        help="Path to aw fullset list",
    )
    parser.add_argument(
        "--groups",
        type=Path,
        nargs="+",
        default=[
            Path("eval/config/aw_subset_group_1.txt"),
            Path("eval/config/aw_subset_group_2.txt"),
            Path("eval/config/aw_subset_group_3.txt"),
            Path("eval/config/aw_subset_group_4.txt"),
        ],
        help="Subset group files to validate",
    )
    args = parser.parse_args()

    missing_files = [path for path in [args.fullset, *args.groups] if not path.exists()]
    if missing_files:
        print("ERROR: missing files:")
        for path in missing_files:
            print(f"  - {path}")
        return 2

    fullset_tasks = read_tasks(args.fullset)
    fullset_set = set(fullset_tasks)
    fullset_dupes = find_duplicates(fullset_tasks)

    group_tasks = {path: read_tasks(path) for path in args.groups}
    group_sets = {path: set(tasks) for path, tasks in group_tasks.items()}

    ok = True

    print("== Summary ==")
    print(f"fullset: {args.fullset} ({len(fullset_tasks)} tasks)")
    for path in args.groups:
        print(f"group:   {path} ({len(group_tasks[path])} tasks)")

    print("\n== Check 1: subset tasks are in fullset ==")
    if fullset_dupes:
        ok = False
        print(f"FAIL: fullset has duplicate tasks ({len(fullset_dupes)}):")
        for task in fullset_dupes:
            print(f"  - {task}")
    else:
        print("OK: fullset has no duplicate tasks")

    for path in args.groups:
        tasks = group_tasks[path]
        dupes = find_duplicates(tasks)
        if dupes:
            ok = False
            print(f"FAIL: {path} has duplicate tasks ({len(dupes)}):")
            for task in dupes:
                print(f"  - {task}")

        missing = sorted(task for task in group_sets[path] if task not in fullset_set)
        if missing:
            ok = False
            print(f"FAIL: {path} has {len(missing)} task(s) not in fullset:")
            for task in missing:
                print(f"  - {task}")
        else:
            print(f"OK: {path} is a subset of fullset")

    print("\n== Check 2: no overlap between groups ==")
    overlap_found = False
    for left, right in combinations(args.groups, 2):
        overlap = sorted(group_sets[left] & group_sets[right])
        if overlap:
            ok = False
            overlap_found = True
            print(f"FAIL: overlap between {left} and {right} ({len(overlap)}):")
            for task in overlap:
                print(f"  - {task}")
    if not overlap_found:
        print("OK: no overlaps found across groups")

    if ok:
        print("\nPASS: all checks passed")
        return 0

    print("\nFAIL: one or more checks failed")
    return 1


if __name__ == "__main__":
    sys.exit(main())
