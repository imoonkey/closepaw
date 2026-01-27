from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class AgentConfig:
    goal: str
    max_turns: int = 50
    ui_settle_delay_ms: int = 3000
    debug_mode: bool = False
    system_prompt: str | None = None
    model: str = "gpt-5.2"
    llm_backend: str = "openai"
    adb_serial: str | None = None
    enable_screenshot_input: bool = False
    screenshot_max_dimension: int = 1080
    screenshot_jpeg_quality: int = 70
    adb_timeout_s: int = 30
