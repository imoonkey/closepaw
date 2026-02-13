#!/usr/bin/env python3
from __future__ import annotations

import argparse

from ux_runner_core import run_ux_qa


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run Android UX QA scenarios over ADB")
    parser.add_argument("--scenario", required=True, help="Path to scenario JSON")
    parser.add_argument("--out-root", default="debug-output/ux-qa", help="Root output folder")
    parser.add_argument("--serial", default=None, help="ADB device serial")

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


def main() -> int:
    return run_ux_qa(parse_args())


if __name__ == "__main__":
    raise SystemExit(main())
