#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

from agent_link import AgentLink, AgentLinkState, find_project_root
def slugify(value: str) -> str:
    slug = re.sub(r"[^a-zA-Z0-9]+", "-", value.strip().lower())
    slug = re.sub(r"-+", "-", slug).strip("-")
    return slug or "scenario"


def parse_bounds(raw: str) -> Optional[tuple[int, int, int, int]]:
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", raw or "")
    if not match:
        return None
    x1, y1, x2, y2 = (int(match.group(i)) for i in range(1, 5))
    return x1, y1, x2, y2


def center_of_bounds(bounds: tuple[int, int, int, int]) -> tuple[int, int]:
    x1, y1, x2, y2 = bounds
    return ((x1 + x2) // 2, (y1 + y2) // 2)


@dataclass
class StepResult:
    index: int
    name: str
    action: str
    status: str
    error: str
    started_at: str
    ended_at: str
    artifacts: Dict[str, str]


class UXRunner:
    def __init__(self, scenario: Dict[str, Any], out_root: Path, serial: Optional[str]):
        self.scenario = scenario
        self.serial = serial
        self.package = scenario.get("package", "")
        self.launch_activity = scenario.get("launch_activity", "")
        self.name = scenario.get("name", "ux-qa")

        run_name = f"run_{datetime.now().strftime('%Y%m%d_%H%M%S')}_{slugify(self.name)}"
        self.run_dir = out_root / run_name
        self.run_dir.mkdir(parents=True, exist_ok=True)

        self.steps: List[StepResult] = []
        self.start_time = datetime.now()

    @property
    def adb(self) -> List[str]:
        base = ["adb"]
        if self.serial:
            base.extend(["-s", self.serial])
        return base

    def _run(self, cmd: List[str], check: bool = True, text: bool = True) -> subprocess.CompletedProcess:
        proc = subprocess.run(cmd, capture_output=True, text=text)
        if check and proc.returncode != 0:
            stderr = (proc.stderr or "").strip()
            stdout = (proc.stdout or "").strip()
            detail = stderr or stdout or "unknown error"
            raise RuntimeError(f"Command failed: {' '.join(cmd)} :: {detail}")
        return proc

    def shell(self, *parts: str, check: bool = True) -> str:
        return (self._run(self.adb + ["shell", *parts], check=check).stdout or "").strip()

    def ensure_prerequisites(self) -> None:
        if shutil.which("adb") is None:
            raise RuntimeError("adb not found in PATH")
        state = self._run(self.adb + ["get-state"], check=False).stdout.strip()
        if state != "device":
            raise RuntimeError("No connected device in 'device' state. Run `adb devices`.")

    def current_serial(self) -> str:
        out = self._run(self.adb + ["get-serialno"], check=False).stdout.strip()
        return out or (self.serial or "unknown")

    def dump_ui(self, label: str) -> Path:
        remote = "/sdcard/ux_dump.xml"
        self.shell("uiautomator", "dump", "--compressed", remote)
        local = self.run_dir / f"{label}.xml"
        self._run(self.adb + ["pull", remote, str(local)])
        return local

    def screenshot(self, label: str) -> Path:
        local = self.run_dir / f"{label}.png"
        with local.open("wb") as fh:
            proc = subprocess.run(self.adb + ["exec-out", "screencap", "-p"], stdout=fh, stderr=subprocess.PIPE)
        if proc.returncode != 0:
            raise RuntimeError((proc.stderr or b"screencap failed").decode("utf-8", errors="ignore"))
        return local

    def visible_strings(self, xml_path: Path) -> List[str]:
        root = ET.parse(xml_path).getroot()
        seen = set()
        ordered: List[str] = []
        for node in root.iter("node"):
            for key in ("text", "content-desc", "resource-id"):
                value = (node.attrib.get(key) or "").strip()
                if value and value not in seen:
                    seen.add(value)
                    ordered.append(value)
        return ordered

    def capture_snapshot(self, prefix: str) -> Dict[str, str]:
        png = self.screenshot(prefix)
        xml = self.dump_ui(prefix)
        visible = self.visible_strings(xml)
        visible_file = self.run_dir / f"{prefix}_visible.txt"
        visible_file.write_text("\n".join(visible[:300]) + ("\n" if visible else ""), encoding="utf-8")
        return {"screenshot": str(png), "ui_xml": str(xml), "visible_text": str(visible_file)}

    def _find_nodes(self, xml_path: Path) -> List[Dict[str, str]]:
        return [node.attrib for node in ET.parse(xml_path).getroot().iter("node")]

    def _lookup_and_tap(
        self, mode: str, value: str, occurrence: int,
        contains: bool = False, retries: int = 1, retry_interval_ms: int = 800,
    ) -> None:
        if not value:
            raise RuntimeError(f"{mode} selector cannot be empty")

        last_error: Optional[RuntimeError] = None
        for attempt in range(max(1, retries)):
            try:
                nodes = self._find_nodes(self.dump_ui("lookup"))
                matches: List[Dict[str, str]] = []
                for node in nodes:
                    text_val = (node.get("text") or "").strip()
                    desc_val = (node.get("content-desc") or "").strip()
                    rid_val = (node.get("resource-id") or "").strip()

                    if mode == "text":
                        pool = [text_val, desc_val]
                        ok = any((value in item if contains else value == item) for item in pool if item)
                    elif mode == "resource_id":
                        ok = value == rid_val
                    elif mode == "desc":
                        ok = (value in desc_val) if contains else (value == desc_val)
                    else:
                        raise RuntimeError(f"Unsupported selector mode: {mode}")

                    if ok:
                        matches.append(node)

                if not matches:
                    raise RuntimeError(f"No node matched {mode}='{value}'")

                idx = occurrence - 1
                if idx < 0 or idx >= len(matches):
                    raise RuntimeError(f"Matched {len(matches)} node(s), occurrence {occurrence} out of range")

                bounds = parse_bounds(matches[idx].get("bounds", ""))
                if not bounds:
                    raise RuntimeError("Matched node missing valid bounds")

                x, y = center_of_bounds(bounds)
                self.shell("input", "tap", str(x), str(y))
                return
            except RuntimeError as exc:
                last_error = exc
                if attempt < retries - 1:
                    time.sleep(retry_interval_ms / 1000.0)

        raise last_error  # type: ignore[misc]

    def _has_text(self, target: str) -> bool:
        if not target:
            return False
        return any(target in item for item in self.visible_strings(self.dump_ui("assert")))

    def _has_desc(self, target: str) -> bool:
        if not target:
            return False
        xml_path = self.dump_ui("assert")
        for node in ET.parse(xml_path).getroot().iter("node"):
            desc = (node.attrib.get("content-desc") or "").strip()
            if desc and target in desc:
                return True
        return False

    def _poll_until(self, check_fn, target: str, timeout_ms: int, interval_ms: int, expect: bool) -> None:
        deadline = time.time() + timeout_ms / 1000.0
        while time.time() < deadline:
            found = check_fn(target)
            if found == expect:
                return
            time.sleep(interval_ms / 1000.0)
        verb = "appear" if expect else "disappear"
        raise RuntimeError(f"Timed out waiting for '{target}' to {verb} ({timeout_ms}ms)")

    def execute_step(self, index: int, step: Dict[str, Any]) -> bool:
        action = (step.get("action") or "").strip()
        name = (step.get("name") or action or f"step-{index}").strip()
        continue_on_fail = bool(step.get("continue_on_fail", False))

        started = datetime.now()
        status = "PASS"
        error = ""

        try:
            if action == "force_stop":
                package = step.get("package") or self.package
                if not package:
                    raise RuntimeError("force_stop requires package")
                self.shell("am", "force-stop", package)
            elif action == "start_app":
                package = step.get("package") or self.package
                activity = step.get("activity") or self.launch_activity
                if not package:
                    raise RuntimeError("start_app requires package")
                if activity:
                    try:
                        self.shell("am", "start", "-n", f"{package}/{activity}")
                    except RuntimeError:
                        if step.get("fallback_to_monkey", True):
                            self.shell("monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1")
                        else:
                            raise
                else:
                    self.shell("monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1")
            elif action == "wait":
                time.sleep(max(0, int(step.get("ms", 800))) / 1000.0)
            elif action == "tap_text":
                self._lookup_and_tap(
                    "text", str(step.get("text") or ""), int(step.get("occurrence", 1)),
                    retries=int(step.get("retries", 1)),
                    retry_interval_ms=int(step.get("retry_interval_ms", 800)),
                )
            elif action == "tap_contains_text":
                self._lookup_and_tap(
                    "text", str(step.get("text") or ""), int(step.get("occurrence", 1)), True,
                    retries=int(step.get("retries", 1)),
                    retry_interval_ms=int(step.get("retry_interval_ms", 800)),
                )
            elif action == "tap_resource_id":
                self._lookup_and_tap(
                    "resource_id", str(step.get("resource_id") or ""), int(step.get("occurrence", 1)),
                    retries=int(step.get("retries", 1)),
                    retry_interval_ms=int(step.get("retry_interval_ms", 800)),
                )
            elif action == "tap_desc":
                self._lookup_and_tap(
                    "desc", str(step.get("desc") or ""), int(step.get("occurrence", 1)),
                    bool(step.get("contains", False)),
                    retries=int(step.get("retries", 1)),
                    retry_interval_ms=int(step.get("retry_interval_ms", 800)),
                )
            elif action == "tap_xy":
                self.shell("input", "tap", str(int(step.get("x"))), str(int(step.get("y"))))
            elif action == "type":
                if step.get("clear", False):
                    for _ in range(int(step.get("clear_count", 40))):
                        self.shell("input", "keyevent", "KEYCODE_DEL")
                encoded = str(step.get("text") if step.get("text") is not None else "").replace(" ", "%s").replace("\n", "%s")
                self.shell("input", "text", encoded)
                if step.get("submit", False):
                    self.shell("input", "keyevent", "KEYCODE_ENTER")
            elif action == "keyevent":
                key = step.get("key")
                if not key:
                    raise RuntimeError("keyevent requires key")
                self.shell("input", "keyevent", str(key))
            elif action == "back":
                self.shell("input", "keyevent", "KEYCODE_BACK")
            elif action == "home":
                self.shell("input", "keyevent", "KEYCODE_HOME")
            elif action == "swipe":
                self.shell(
                    "input",
                    "swipe",
                    str(int(step.get("x1"))),
                    str(int(step.get("y1"))),
                    str(int(step.get("x2"))),
                    str(int(step.get("y2"))),
                    str(int(step.get("duration_ms", 300))),
                )
            elif action == "assert_text":
                target = str(step.get("text") or "")
                if not self._has_text(target):
                    raise RuntimeError(f"Expected text not found: {target}")
            elif action == "assert_not_text":
                target = str(step.get("text") or "")
                if target and self._has_text(target):
                    raise RuntimeError(f"Unexpected text visible: {target}")
            elif action in {"screenshot", "dump_ui", "note"}:
                pass
            elif action == "wait_for_text":
                target = str(step.get("text") or "")
                self._poll_until(
                    self._has_text, target,
                    timeout_ms=int(step.get("timeout_ms", 10000)),
                    interval_ms=int(step.get("interval_ms", 500)),
                    expect=True,
                )
            elif action == "wait_for_not_text":
                target = str(step.get("text") or "")
                self._poll_until(
                    self._has_text, target,
                    timeout_ms=int(step.get("timeout_ms", 10000)),
                    interval_ms=int(step.get("interval_ms", 500)),
                    expect=False,
                )
            elif action == "wait_for_desc":
                target = str(step.get("desc") or "")
                self._poll_until(
                    self._has_desc, target,
                    timeout_ms=int(step.get("timeout_ms", 10000)),
                    interval_ms=int(step.get("interval_ms", 500)),
                    expect=True,
                )
            else:
                raise RuntimeError(f"Unsupported action: {action}")
        except Exception as exc:  # noqa: BLE001
            status = "FAIL"
            error = str(exc)

        ended = datetime.now()
        prefix = f"step_{index:03d}_{slugify(name)}"
        artifacts = self.capture_snapshot(prefix)
        self.steps.append(
            StepResult(
                index=index,
                name=name,
                action=action,
                status=status,
                error=error,
                started_at=started.isoformat(timespec="seconds"),
                ended_at=ended.isoformat(timespec="seconds"),
                artifacts=artifacts,
            )
        )
        return status == "PASS" or continue_on_fail

    def write_summary(self, agent_state: AgentLinkState) -> Dict[str, Any]:
        failed = [s for s in self.steps if s.status != "PASS"]
        summary = {
            "scenario": self.name,
            "package": self.package,
            "device_serial": self.current_serial(),
            "started_at": self.start_time.isoformat(timespec="seconds"),
            "finished_at": datetime.now().isoformat(timespec="seconds"),
            "step_count": len(self.steps),
            "failed_count": len(failed),
            "status": "PASS" if not failed else "FAIL",
            "agent_link": asdict(agent_state),
            "steps": [asdict(s) for s in self.steps],
        }

        (self.run_dir / "run_summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

        lines = [
            "# UX QA Report",
            "",
            f"- Scenario: `{self.name}`",
            f"- Package: `{self.package or 'N/A'}`",
            f"- Device: `{summary['device_serial']}`",
            f"- Start: `{summary['started_at']}`",
            f"- Finish: `{summary['finished_at']}`",
            f"- Result: **{summary['status']}**",
            f"- Failed steps: `{summary['failed_count']}/{summary['step_count']}`",
            "",
            "## Agent Linkage",
            "",
            f"- Enabled: `{agent_state.enabled}`",
            f"- Mode: `{agent_state.mode}`",
            f"- Goal: `{agent_state.goal or 'N/A'}`",
            f"- Setup: `{agent_state.setup_ran}` (exit: `{agent_state.setup_exit}`)",
            f"- debug-run status: `{agent_state.status}` (exit: `{agent_state.debug_run_exit}`)",
            f"- debug-run log: `{agent_state.debug_run_log}`",
            f"- debug-run output: `{agent_state.debug_output_dir or 'N/A'}`",
        ]
        if agent_state.error:
            lines.append(f"- debug-run error: `{agent_state.error}`")

        lines.extend(["", "## Failed Steps", ""])
        if failed:
            for s in failed:
                lines.extend(
                    [
                        f"- Step {s.index} `{s.name}` ({s.action})",
                        f"  - Error: {s.error}",
                        f"  - Screenshot: `{s.artifacts['screenshot']}`",
                        f"  - UI XML: `{s.artifacts['ui_xml']}`",
                    ]
                )
        else:
            lines.append("No failed steps.")

        lines.extend(["", "## Step Table", "", "| # | Name | Action | Status |", "|---|---|---|---|"])
        for s in self.steps:
            lines.append(f"| {s.index} | {s.name} | {s.action} | {s.status} |")

        (self.run_dir / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
        return summary


def load_scenario(path: Path) -> Dict[str, Any]:
    if not path.exists():
        raise RuntimeError(f"Scenario not found: {path}")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"Invalid scenario JSON: {path} :: {exc}") from exc
    if not isinstance(data.get("steps"), list) or not data["steps"]:
        raise RuntimeError("Scenario must contain non-empty `steps` array")
    return data

def run_ux_qa(args: Any) -> int:
    runner: Optional[UXRunner] = None
    agent_link: Optional[AgentLink] = None

    try:
        scenario = load_scenario(Path(args.scenario).resolve())
        out_root = Path(args.out_root).resolve()

        runner = UXRunner(scenario, out_root, serial=args.serial)
        runner.ensure_prerequisites()

        project_root = find_project_root(Path(__file__).resolve().parent)
        agent_link = AgentLink(
            project_root=project_root,
            run_dir=runner.run_dir,
            serial=args.serial,
            goal=args.agent_goal,
            mode=args.agent_link_mode,
            run_setup=args.agent_setup,
            debug_args=args.agent_debug_arg,
            join_timeout_sec=args.agent_join_timeout_sec,
            start_delay_ms=args.agent_start_delay_ms,
        )

        agent_link.start_before_scenario()
        runner.capture_snapshot("step_000_initial")

        for idx, step in enumerate(scenario["steps"], start=1):
            if not isinstance(step, dict):
                raise RuntimeError(f"Step {idx} is not an object")
            should_continue = runner.execute_step(idx, step)
            if not should_continue:
                break

        agent_link.finish_after_scenario()
        summary = runner.write_summary(agent_link.state)

        print(f"[ux-qa] Output: {runner.run_dir}")
        print(f"[ux-qa] Result: {summary['status']} ({summary['failed_count']} failed / {summary['step_count']} steps)")
        if summary["agent_link"]["enabled"]:
            print(f"[ux-qa] Agent link: {summary['agent_link']['status']} -> {summary['agent_link']['debug_output_dir'] or 'N/A'}")

        return 0 if summary["status"] == "PASS" else 2
    except Exception as exc:  # noqa: BLE001
        if agent_link:
            try:
                agent_link.finish_after_scenario()
            except Exception:
                pass
        print(f"[ux-qa] ERROR: {exc}", file=sys.stderr)
        return 1
