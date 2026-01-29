from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import base64


@dataclass(frozen=True)
class Bounds:
    left: int
    top: int
    right: int
    bottom: int

    @property
    def width(self) -> int:
        return self.right - self.left

    @property
    def height(self) -> int:
        return self.bottom - self.top

    @property
    def center_x(self) -> int:
        return (self.left + self.right) // 2

    @property
    def center_y(self) -> int:
        return (self.top + self.bottom) // 2


@dataclass(frozen=True)
class Point:
    x: int
    y: int


class ScreenImageSource(str, Enum):
    UiautomatorScreenshot = "uiautomator_screenshot"


@dataclass(frozen=True)
class ScreenImage:
    width: int
    height: int
    mime_type: str
    bytes: bytes
    source: ScreenImageSource

    def to_data_url(self) -> str:
        encoded = base64.b64encode(self.bytes).decode("ascii")
        return f"data:{self.mime_type};base64,{encoded}"


@dataclass(frozen=True)
class PerceptionElement:
    index: int
    text: str
    resource_id: str
    class_name: str
    description: str
    is_clickable: bool
    is_editable: bool
    is_scrollable: bool
    bounds: Bounds
    center: Point


@dataclass(frozen=True)
class ScreenSnapshot:
    timestamp_ms: int
    elements: list[PerceptionElement]
    image: ScreenImage | None = None
