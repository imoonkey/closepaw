from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description="Compare two eval run summaries")
    parser.add_argument("--base", required=True, help="Path to base run directory")
    parser.add_argument("--new", required=True, help="Path to new run directory")
    args = parser.parse_args()

    base_summary = _load_summary(Path(args.base).resolve())
    new_summary = _load_summary(Path(args.new).resolve())

    base_metrics = base_summary.get("metrics", {})
    new_metrics = new_summary.get("metrics", {})

    payload = {
        "base_run": str(Path(args.base).resolve()),
        "new_run": str(Path(args.new).resolve()),
        "deltas": {
            "scripted_success_rate": _delta(
                base_metrics.get("scripted_success_rate"),
                new_metrics.get("scripted_success_rate"),
            ),
            "timeout_rate": _delta(
                base_metrics.get("timeout_rate"),
                new_metrics.get("timeout_rate"),
            ),
            "infra_failure_rate": _delta(
                base_metrics.get("infra_failure_rate"),
                new_metrics.get("infra_failure_rate"),
            ),
            "error_rate": _delta(
                base_metrics.get("error_rate"),
                new_metrics.get("error_rate"),
            ),
            "duration_p50_sec": _delta(
                base_metrics.get("duration_p50_sec"),
                new_metrics.get("duration_p50_sec"),
            ),
            "duration_p90_sec": _delta(
                base_metrics.get("duration_p90_sec"),
                new_metrics.get("duration_p90_sec"),
            ),
            "goal_claim_precision": _delta(
                base_metrics.get("goal_claim_precision"),
                new_metrics.get("goal_claim_precision"),
            ),
            "tool_failure_rate": _delta(
                base_metrics.get("tool_failure_rate"),
                new_metrics.get("tool_failure_rate"),
            ),
        },
    }
    print(json.dumps(payload, ensure_ascii=True, indent=2))


def _load_summary(run_dir: Path) -> dict:
    summary_path = run_dir / "summary.json"
    if not summary_path.exists():
        raise FileNotFoundError(f"Missing summary.json: {summary_path}")
    return json.loads(summary_path.read_text(encoding="utf-8", errors="replace"))


def _delta(base_value: float | None, new_value: float | None) -> float | None:
    if base_value is None or new_value is None:
        return None
    return float(new_value) - float(base_value)


if __name__ == "__main__":
    main()
