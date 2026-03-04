#!/usr/bin/env python3
"""Generate scoreboard from eval results.

Scans eval/results/*/per_task.jsonl, builds a task × run matrix.
Writes:
  doc/autotune/meta/scoreboard.json  (SOT)
  doc/autotune/meta/scoreboard.md   (rendered view)

Usage:
  python scripts/scoreboard.py [--run-id <id>[,<id>...]]
"""

import argparse
import json
import os
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
RESULTS_DIR = REPO_ROOT / "eval" / "results"
OUTPUT_DIR = REPO_ROOT / "doc" / "autotune" / "meta"


def load_runs(run_ids: list[str] | None = None) -> dict:
    """Load per_task.jsonl from each run directory. Returns {run_id: [task_results]}."""
    runs = {}
    if not RESULTS_DIR.exists():
        return runs

    for entry in sorted(RESULTS_DIR.iterdir()):
        if not entry.is_dir() or entry.name.startswith(".") or entry.name == "archive":
            continue
        if run_ids and entry.name not in run_ids:
            continue
        jsonl = entry / "per_task.jsonl"
        if not jsonl.exists():
            continue
        tasks = []
        for line in jsonl.read_text().strip().splitlines():
            if line.strip():
                tasks.append(json.loads(line))
        if tasks:
            runs[entry.name] = tasks
    return runs


def derive_status(scores: list[float]) -> str:
    """Derive task status from score history."""
    if not scores:
        return "new"
    recent = scores[-min(3, len(scores)):]
    successes = sum(1 for s in recent if s >= 1.0)
    if successes == len(recent):
        return "fixed"
    if len(scores) >= 2 and scores[-1] > scores[-2]:
        return "improving"
    if len(scores) >= 2 and scores[-1] < scores[-2]:
        return "regressed"
    if len(recent) >= 3 and successes == 0:
        return "stuck"
    if len(scores) == 1:
        return "new"
    return "stuck" if successes == 0 else "improving"


def build_scoreboard(runs: dict) -> dict:
    """Build scoreboard JSON from run data."""
    tasks: dict[str, dict] = {}
    run_summaries: dict[str, dict] = {}

    sorted_run_ids = sorted(runs.keys())

    for round_num, run_id in enumerate(sorted_run_ids, start=1):
        task_results = runs[run_id]
        passed = 0
        total = 0
        for result in task_results:
            name = result["task_name"]
            score = result.get("scripted_score") or 0.0
            status = result.get("bridge_status", "unknown")
            turns = result.get("turns_executed", 0)

            if name not in tasks:
                tasks[name] = {"runs": {}}
            tasks[name]["runs"][run_id] = {
                "score": score,
                "status": status,
                "turns": turns,
            }
            total += 1
            if score >= 1.0:
                passed += 1

        run_summaries[run_id] = {
            "round": round_num,
            "total": total,
            "passed": passed,
            "rate": round(passed / total, 3) if total > 0 else 0.0,
        }

    # Compute recent_rate and status for each task
    for name, task_data in tasks.items():
        scores_by_run = []
        for run_id in sorted_run_ids:
            if run_id in task_data["runs"]:
                scores_by_run.append(task_data["runs"][run_id]["score"])
        recent_n = min(3, len(scores_by_run))
        recent_scores = scores_by_run[-recent_n:]
        recent_pass = sum(1 for s in recent_scores if s >= 1.0)
        task_data["recent_rate"] = f"{recent_pass}/{recent_n}"
        task_data["status"] = derive_status(scores_by_run)

    return {"tasks": dict(sorted(tasks.items())), "runs": run_summaries}


def render_markdown(scoreboard: dict) -> str:
    """Render scoreboard as markdown table."""
    lines = ["# Scoreboard", ""]

    sorted_run_ids = sorted(scoreboard["runs"].keys())
    run_labels = {}
    for run_id in sorted_run_ids:
        r = scoreboard["runs"][run_id]["round"]
        run_labels[run_id] = f"R{r}"

    # Header
    cols = ["Task"] + [run_labels[r] for r in sorted_run_ids] + ["Recent", "Status"]
    lines.append("| " + " | ".join(cols) + " |")
    lines.append("|" + "|".join(["---"] * len(cols)) + "|")

    # Rows
    for name, task_data in sorted(scoreboard["tasks"].items()):
        row = [name]
        for run_id in sorted_run_ids:
            if run_id in task_data["runs"]:
                score = task_data["runs"][run_id]["score"]
                row.append(f"**{score:.1f}**" if score >= 1.0 else f"{score:.1f}")
            else:
                row.append("")
        row.append(task_data["recent_rate"])
        row.append(task_data["status"])
        lines.append("| " + " | ".join(row) + " |")

    # Run summary
    lines.extend(["", "## Run Summary", ""])
    lines.append("| Run | Date | Tasks | Passed | Rate |")
    lines.append("|-----|------|-------|--------|------|")
    for run_id in sorted_run_ids:
        info = scoreboard["runs"][run_id]
        date = f"{run_id[:4]}-{run_id[4:6]}-{run_id[6:8]}"
        rate_pct = f"{info['rate'] * 100:.1f}%"
        lines.append(
            f"| R{info['round']} ({run_id}) | {date} | {info['total']} | {info['passed']} | {rate_pct} |"
        )

    lines.append("")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="Generate autotune scoreboard")
    parser.add_argument(
        "--run-id",
        help="Only include specific run(s), comma-separated",
    )
    args = parser.parse_args()

    run_ids = args.run_id.split(",") if args.run_id else None
    runs = load_runs(run_ids)

    if not runs:
        print("No eval results found.", file=sys.stderr)
        sys.exit(1)

    scoreboard = build_scoreboard(runs)

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    json_path = OUTPUT_DIR / "scoreboard.json"
    json_path.write_text(json.dumps(scoreboard, indent=2) + "\n")
    print(f"Wrote {json_path.relative_to(REPO_ROOT)}")

    md_path = OUTPUT_DIR / "scoreboard.md"
    md_path.write_text(render_markdown(scoreboard))
    print(f"Wrote {md_path.relative_to(REPO_ROOT)}")


if __name__ == "__main__":
    main()
