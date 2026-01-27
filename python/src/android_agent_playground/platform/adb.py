from __future__ import annotations

from dataclasses import dataclass
import re
import subprocess
import time
from typing import Protocol

from android_agent_playground.models import ScreenImage, ScreenImageSource, ScreenSnapshot
from android_agent_playground.perceptor import Perceptor
from android_agent_playground.platform.actions import (
    Click,
    ClickAt,
    LongClick,
    Swipe,
    SystemButton,
    SystemButtonType,
    Type,
    UIAction,
    Wait,
)


class AndroidPlatform(Protocol):
    def capture_screen(self) -> ScreenSnapshot: ...
    def perform_action(self, action: UIAction, snapshot: ScreenSnapshot | None = None) -> "ActionResult": ...
    def has_required_permissions(self) -> bool: ...
    def get_current_package_name(self) -> str | None: ...
    def get_display_info(self) -> "DisplayInfo": ...
    def get_installed_apps(self) -> list["AppInfo"]: ...
    def launch_app(self, package_name: str) -> "ActionResult": ...


@dataclass(frozen=True)
class DisplayInfo:
    width_pixels: int
    height_pixels: int
    density: float


@dataclass(frozen=True)
class AppInfo:
    package_name: str
    label: str
    is_system_app: bool = False


@dataclass(frozen=True)
class ActionResultSuccess:
    message: str


@dataclass(frozen=True)
class ActionResultFailure:
    reason: str
    exception: Exception | None = None


@dataclass(frozen=True)
class ActionResultElementNotFound:
    element_index: int


@dataclass(frozen=True)
class ActionResultCancelled:
    reason: str


ActionResult = ActionResultSuccess | ActionResultFailure | ActionResultElementNotFound | ActionResultCancelled


class AdbClient:
    def __init__(self, serial: str | None = None, timeout_s: int = 30) -> None:
        self._serial = serial
        self._timeout_s = timeout_s

    def shell(self, args: list[str]) -> str:
        return self._run(["shell", *args], text=True)

    def exec_out(self, args: list[str]) -> bytes:
        return self._run_bytes(["exec-out", *args])

    def _run(self, args: list[str], text: bool) -> str:
        cmd = ["adb"]
        if self._serial:
            cmd.extend(["-s", self._serial])
        cmd.extend(args)
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=text,
            timeout=self._timeout_s,
            check=False,
        )
        if result.returncode != 0:
            stderr = result.stderr.strip() if text else result.stderr.decode("utf-8", errors="ignore")
            raise RuntimeError(f"adb command failed: {' '.join(cmd)}\n{stderr}")
        return result.stdout if text else result.stdout.decode("utf-8", errors="ignore")

    def _run_bytes(self, args: list[str]) -> bytes:
        cmd = ["adb"]
        if self._serial:
            cmd.extend(["-s", self._serial])
        cmd.extend(args)
        result = subprocess.run(
            cmd,
            capture_output=True,
            timeout=self._timeout_s,
            check=False,
        )
        if result.returncode != 0:
            stderr = result.stderr.decode("utf-8", errors="ignore").strip()
            raise RuntimeError(f"adb command failed: {' '.join(cmd)}\n{stderr}")
        return result.stdout


class AdbPlatform(AndroidPlatform):
    def __init__(
        self,
        serial: str | None = None,
        enable_screenshot: bool = False,
        screenshot_max_dimension: int = 1080,
        screenshot_jpeg_quality: int = 70,
        adb_timeout_s: int = 30,
    ) -> None:
        self._adb = AdbClient(serial=serial, timeout_s=adb_timeout_s)
        self._enable_screenshot = enable_screenshot
        self._screenshot_max_dimension = screenshot_max_dimension
        self._screenshot_jpeg_quality = screenshot_jpeg_quality

    def capture_screen(self) -> ScreenSnapshot:
        xml = self._dump_uiautomator_xml()
        snapshot = Perceptor.from_uiautomator_xml(xml)
        if self._enable_screenshot:
            snapshot = snapshot.__class__(
                timestamp_ms=snapshot.timestamp_ms,
                elements=snapshot.elements,
                image=self._capture_screenshot(),
            )
        return snapshot

    def perform_action(self, action: UIAction, snapshot: ScreenSnapshot | None = None) -> ActionResult:
        if isinstance(action, Click):
            return self._perform_click(action.element_index, snapshot)
        if isinstance(action, ClickAt):
            return self._tap(action.x, action.y)
        if isinstance(action, LongClick):
            return self._perform_long_click(action.element_index, action.duration_ms, snapshot)
        if isinstance(action, Type):
            return self._perform_type(action, snapshot)
        if isinstance(action, Swipe):
            return self._swipe(action.start_x, action.start_y, action.end_x, action.end_y, action.duration_ms)
        if isinstance(action, SystemButton):
            return self._system_button(action.button)
        if isinstance(action, Wait):
            time.sleep(action.duration_ms / 1000.0)
            return ActionResultSuccess(f"Waited {action.duration_ms}ms")
        return ActionResultFailure("Unsupported action")

    def has_required_permissions(self) -> bool:
        return True

    def get_current_package_name(self) -> str | None:
        return None

    def get_display_info(self) -> DisplayInfo:
        size_out = self._adb.shell(["wm", "size"]).strip()
        density_out = self._adb.shell(["wm", "density"]).strip()
        width, height = self._parse_display_size(size_out)
        density = self._parse_density(density_out)
        return DisplayInfo(width_pixels=width, height_pixels=height, density=density)

    def get_installed_apps(self) -> list[AppInfo]:
        output = self._adb.shell(["pm", "list", "packages", "-3"])
        packages = []
        for line in output.splitlines():
            line = line.strip()
            if line.startswith("package:"):
                pkg = line.split("package:", 1)[1].strip()
                if pkg:
                    packages.append(AppInfo(package_name=pkg, label=pkg))
        return packages

    def launch_app(self, package_name: str) -> ActionResult:
        try:
            self._adb.shell([
                "monkey",
                "-p",
                package_name,
                "-c",
                "android.intent.category.LAUNCHER",
                "1",
            ])
            return ActionResultSuccess(f"Launched {package_name}")
        except Exception as exc:
            return ActionResultFailure(f"Failed to launch {package_name}: {exc}", exc)

    def _dump_uiautomator_xml(self) -> str:
        try:
            raw = self._adb.exec_out(["uiautomator", "dump", "/dev/tty"])
            text = raw.decode("utf-8", errors="ignore")
            return self._extract_xml(text)
        except Exception:
            self._adb.shell(["uiautomator", "dump", "/sdcard/agent_dump.xml"])
            raw = self._adb.exec_out(["cat", "/sdcard/agent_dump.xml"])
            text = raw.decode("utf-8", errors="ignore")
            return self._extract_xml(text)

    def _extract_xml(self, text: str) -> str:
        match = re.search(r"(<hierarchy[\s\S]*</hierarchy>)", text)
        if match:
            return match.group(1)
        return text.strip()

    def _capture_screenshot(self) -> ScreenImage | None:
        try:
            png = self._adb.exec_out(["screencap", "-p"])
            if not png:
                return None
            return ScreenImage(
                width=0,
                height=0,
                mime_type="image/png",
                bytes=png,
                source=ScreenImageSource.UiautomatorScreenshot,
            )
        except Exception:
            return None

    def _perform_click(self, element_index: int, snapshot: ScreenSnapshot | None) -> ActionResult:
        if snapshot is None:
            return ActionResultFailure("Snapshot required for element-based click")
        element = snapshot.elements[element_index] if element_index < len(snapshot.elements) else None
        if element is None:
            return ActionResultElementNotFound(element_index)
        return self._tap(element.center.x, element.center.y)

    def _perform_long_click(self, element_index: int, duration_ms: int, snapshot: ScreenSnapshot | None) -> ActionResult:
        if snapshot is None:
            return ActionResultFailure("Snapshot required for element-based long press")
        element = snapshot.elements[element_index] if element_index < len(snapshot.elements) else None
        if element is None:
            return ActionResultElementNotFound(element_index)
        return self._swipe(element.center.x, element.center.y, element.center.x, element.center.y, duration_ms)

    def _perform_type(self, action: Type, snapshot: ScreenSnapshot | None) -> ActionResult:
        if action.element_index is not None:
            tap_result = self._perform_click(action.element_index, snapshot)
            if isinstance(tap_result, ActionResultFailure):
                return tap_result
            time.sleep(0.1)
        text = self._escape_text(action.text)
        try:
            self._adb.shell(["input", "text", text])
            return ActionResultSuccess(f"Text entered: {action.text}")
        except Exception as exc:
            return ActionResultFailure("Failed to input text", exc)

    def _tap(self, x: int, y: int) -> ActionResult:
        try:
            self._adb.shell(["input", "tap", str(x), str(y)])
            return ActionResultSuccess(f"Tapped at ({x},{y})")
        except Exception as exc:
            return ActionResultFailure("Failed to tap", exc)

    def _swipe(self, start_x: int, start_y: int, end_x: int, end_y: int, duration_ms: int) -> ActionResult:
        try:
            self._adb.shell([
                "input",
                "swipe",
                str(start_x),
                str(start_y),
                str(end_x),
                str(end_y),
                str(duration_ms),
            ])
            return ActionResultSuccess(f"Swiped ({start_x},{start_y}) -> ({end_x},{end_y})")
        except Exception as exc:
            return ActionResultFailure("Failed to swipe", exc)

    def _system_button(self, button: SystemButtonType) -> ActionResult:
        key_map = {
            SystemButtonType.BACK: "KEYCODE_BACK",
            SystemButtonType.HOME: "KEYCODE_HOME",
            SystemButtonType.RECENTS: "KEYCODE_APP_SWITCH",
            SystemButtonType.ENTER: "KEYCODE_ENTER",
        }
        key = key_map.get(button)
        if not key:
            return ActionResultFailure(f"Unsupported system button: {button}")
        try:
            self._adb.shell(["input", "keyevent", key])
            return ActionResultSuccess(f"Pressed {button.value}")
        except Exception as exc:
            return ActionResultFailure("Failed to press system button", exc)

    def _escape_text(self, text: str) -> str:
        return text.replace(" ", "%s")

    def _parse_display_size(self, output: str) -> tuple[int, int]:
        match = re.search(r"(\d+)x(\d+)", output)
        if match:
            return int(match.group(1)), int(match.group(2))
        return 0, 0

    def _parse_density(self, output: str) -> float:
        match = re.search(r"(\d+)", output)
        if match:
            return float(match.group(1))
        return 0.0
