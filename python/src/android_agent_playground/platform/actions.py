from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class SystemButtonType(str, Enum):
    BACK = "back"
    HOME = "home"
    RECENTS = "recents"
    ENTER = "enter"


@dataclass(frozen=True)
class Click:
    element_index: int


@dataclass(frozen=True)
class ClickAt:
    x: int
    y: int


@dataclass(frozen=True)
class LongClick:
    element_index: int
    duration_ms: int = 1000


@dataclass(frozen=True)
class Type:
    text: str
    element_index: int | None = None


@dataclass(frozen=True)
class Swipe:
    start_x: int
    start_y: int
    end_x: int
    end_y: int
    duration_ms: int = 300


@dataclass(frozen=True)
class SystemButton:
    button: SystemButtonType


@dataclass(frozen=True)
class Wait:
    duration_ms: int


UIAction = Click | ClickAt | LongClick | Type | Swipe | SystemButton | Wait
