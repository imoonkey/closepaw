#!/usr/bin/env python3
from __future__ import annotations

import argparse
import sys

from ux_runner_core import UXRunner, run_ux_qa


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run Android UX QA scenarios over ADB")

    # -- Capture mode (AI-interactive) --
    parser.add_argument(
        "--capture",
        metavar="PREFIX",
        default=None,
        help="Quick-capture mode: take screenshot + UI dump + visible text. "
        "Output files: PREFIX.png, PREFIX.xml, PREFIX_visible.txt",
    )
    parser.add_argument("--serial", default=None, help="ADB device serial")

    # -- Scenario mode --
    parser.add_argument("--scenario", default=None, help="Path to scenario JSON")
    parser.add_argument("--out-root", default="debug-output/ux-qa", help="Root output folder")

    parser.add_argument("--agent-goal", default="", help="If set, run scripts/debug-run.sh with this agent goal")
    parser.add_argument(
        "--agent-link-mode",
        choices=["parallel", "serial"],
        default="parallel",
        help="Run debug-run in parallel with UX steps, or serially before UX steps",
    )
    parser.add_argument("--agent-setup", action="store_true", help="Run scripts/setup.sh before linked debug-run")
    parser.add_argument(
        "--agent-debug-arg",
        action="append",
        default=[],
        help="Extra argument passed to scripts/debug-run.sh (repeatable)",
    )
    parser.add_argument(
        "--agent-join-timeout-sec",
        type=int,
        default=30,
        help="Parallel mode: max seconds to wait for debug-run completion after UX steps",
    )
    parser.add_argument(
        "--agent-start-delay-ms",
        type=int,
        default=1200,
        help="Parallel mode: delay before UX steps after debug-run starts",
    )
    return parser.parse_args()


def run_capture(prefix: str, serial: str | None) -> int:
    """Quick-capture: screenshot + UI dump + visible text."""
    from pathlib import Path

    out_dir = Path(prefix).parent
    out_dir.mkdir(parents=True, exist_ok=True)
    label = Path(prefix).name

    dummy_scenario: dict = {"name": "capture", "package": "", "steps": []}
    runner = UXRunner(dummy_scenario, out_dir, serial=serial)
    runner.ensure_prerequisites()

    artifacts = runner.capture_snapshot(label)
    print(f"[capture] Screenshot: {artifacts['screenshot']}")
    print(f"[capture] UI XML:     {artifacts['ui_xml']}")
    print(f"[capture] Visible:    {artifacts['visible_text']}")
    return 0


def main() -> int:
    args = parse_args()

    if args.capture:
        return run_capture(args.capture, args.serial)

    if not args.scenario:
        print("Error: --scenario or --capture is required", file=sys.stderr)
        return 1

    return run_ux_qa(args)


if __name__ == "__main__":
    raise SystemExit(main())
