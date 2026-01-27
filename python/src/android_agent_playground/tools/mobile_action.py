from __future__ import annotations

from dataclasses import dataclass
import time
from typing import Protocol

from android_agent_playground.perceptor import Perceptor
from android_agent_playground.platform import (
    ActionResultCancelled,
    ActionResultElementNotFound,
    ActionResultFailure,
    ActionResultSuccess,
    AndroidPlatform,
    Click,
    LongClick,
    Swipe,
    SystemButton,
    SystemButtonType,
    Type,
    Wait,
)
from android_agent_playground.tools.registry import (
    ToolExecutionCancelled,
    ToolExecutionFailure,
    ToolExecutionResult,
    ToolExecutionSuccess,
    ToolInvocation,
    ToolObservationScreenState,
    ToolSpec,
    ToolExecutionContext,
    ValidationResult,
)


class ActionHandler(Protocol):
    action_name: str

    def validate(self, params: dict) -> ValidationResult: ...
    def create_invocation(self, params: dict) -> ToolInvocation: ...


@dataclass(frozen=True)
class UIActionInvocation:
    tool_name: str
    params: dict
    description: str
    ui_action: object

    def get_description(self) -> str:
        return self.description

    def execute(self, context: ToolExecutionContext) -> ToolExecutionResult:
        if context.is_cancelled():
            return ToolExecutionCancelled("Cancelled before execution")

        result = context.platform.perform_action(self.ui_action, context.current_snapshot)
        if isinstance(result, ActionResultSuccess):
            observation = self._capture_post_action_observation(context.platform)
            return ToolExecutionSuccess(output=result.message, observation=observation)
        if isinstance(result, ActionResultElementNotFound):
            return ToolExecutionFailure(f"Element not found: index {result.element_index}")
        if isinstance(result, ActionResultCancelled):
            return ToolExecutionCancelled(result.reason)
        if isinstance(result, ActionResultFailure):
            return ToolExecutionFailure(result.reason, result.exception)
        return ToolExecutionFailure("Unknown action result")

    def _capture_post_action_observation(self, platform: AndroidPlatform) -> ToolObservationScreenState | None:
        try:
            time.sleep(0.3)
            snapshot = platform.capture_screen()
            tree = Perceptor.to_prompt_json(snapshot)
            return ToolObservationScreenState(
                accessibility_tree=tree,
                element_count=len(snapshot.elements),
                snapshot=snapshot,
            )
        except Exception:
            return None


class MobileActionTool(ToolSpec):
    name = "mobile_action"

    description = (
        "Perform touch interactions on the mobile device screen.\n\n"
        "Actions:\n"
        "- click: Tap on element by index (element_index required)\n"
        "- long_press: Long press element (element_index required, duration_ms optional)\n"
        "- type: Input text into field (text required, element_index optional to focus first)\n"
        "- swipe: Swipe gesture (start and end coordinates required as [x,y] arrays). Coordinates beyond screen bounds are clamped.\n"
        "- system_button: Press system button (button required: back/home/enter/recents)\n"
        "- wait: Wait for UI updates (duration_ms optional, default 1000ms)"
    )

    def __init__(self) -> None:
        self._handlers: dict[str, ActionHandler] = {
            "click": ClickActionHandler(),
            "long_press": LongPressActionHandler(),
            "type": TypeActionHandler(),
            "swipe": SwipeActionHandler(),
            "system_button": SystemButtonActionHandler(),
            "wait": WaitActionHandler(),
        }

    @property
    def parameter_schema(self) -> dict:
        return {
            "type": "object",
            "properties": {
                "action": {"type": "string", "description": "The action to perform"},
                "element_index": {"type": "integer", "description": "Element index for click, long_press, or type (to focus first)"},
                "text": {"type": "string", "description": "Text to input for type action"},
                "start": {
                    "type": "array",
                    "description": "[x, y] start coordinates in pixels for swipe",
                    "items": {"type": "integer"},
                },
                "end": {
                    "type": "array",
                    "description": "[x, y] end coordinates in pixels for swipe",
                    "items": {"type": "integer"},
                },
                "button": {
                    "type": "string",
                    "description": "System button for system_button action",
                    "enum": ["back", "home", "enter", "recents"],
                },
                "duration_ms": {
                    "type": "integer",
                    "description": "Duration in ms for wait (default 1000) or long_press (default 1000)",
                },
            },
            "required": ["action"],
            "additionalProperties": False,
        }

    def validate(self, params: dict) -> ValidationResult:
        action = params.get("action")
        if not action:
            return ValidationResult.invalid("Missing required parameter: action")
        handler = self._handlers.get(action)
        if handler is None:
            return ValidationResult.invalid(f"Unknown action: {action}")
        return handler.validate(params)

    def create_invocation(self, params: dict) -> ToolInvocation:
        action = params.get("action")
        handler = self._handlers[action]
        return handler.create_invocation(params)


class ClickActionHandler:
    action_name = "click"

    def validate(self, params: dict) -> ValidationResult:
        if "element_index" not in params:
            return ValidationResult.invalid("click action requires element_index")
        idx = params.get("element_index")
        if not isinstance(idx, int) or idx < 0:
            return ValidationResult.invalid("element_index must be >= 0")
        return ValidationResult.ok()

    def create_invocation(self, params: dict) -> ToolInvocation:
        idx = params["element_index"]
        return UIActionInvocation(
            tool_name="mobile_action",
            params=params,
            description=f"Click element {idx}",
            ui_action=Click(idx),
        )


class LongPressActionHandler:
    action_name = "long_press"
    default_duration_ms = 1000

    def validate(self, params: dict) -> ValidationResult:
        if "element_index" not in params:
            return ValidationResult.invalid("long_press action requires element_index")
        idx = params.get("element_index")
        if not isinstance(idx, int) or idx < 0:
            return ValidationResult.invalid("element_index must be >= 0")
        return ValidationResult.ok()

    def create_invocation(self, params: dict) -> ToolInvocation:
        idx = params["element_index"]
        duration_ms = int(params.get("duration_ms", self.default_duration_ms))
        return UIActionInvocation(
            tool_name="mobile_action",
            params=params,
            description=f"Long press element {idx} for {duration_ms}ms",
            ui_action=LongClick(idx, duration_ms),
        )


class TypeActionHandler:
    action_name = "type"

    def validate(self, params: dict) -> ValidationResult:
        text = params.get("text")
        if not isinstance(text, str) or not text:
            return ValidationResult.invalid("type action requires text")
        if "element_index" in params:
            idx = params.get("element_index")
            if not isinstance(idx, int) or idx < 0:
                return ValidationResult.invalid("element_index must be >= 0 if provided")
        return ValidationResult.ok()

    def create_invocation(self, params: dict) -> ToolInvocation:
        text = params["text"]
        idx = params.get("element_index")
        description = f"Type '{text}' into element {idx}" if isinstance(idx, int) and idx >= 0 else f"Type '{text}' into focused field"
        return UIActionInvocation(
            tool_name="mobile_action",
            params=params,
            description=description,
            ui_action=Type(text=text, element_index=idx if isinstance(idx, int) and idx >= 0 else None),
        )


class SwipeActionHandler:
    action_name = "swipe"
    default_duration_ms = 300

    def validate(self, params: dict) -> ValidationResult:
        start = params.get("start")
        end = params.get("end")
        if not isinstance(start, list) or len(start) != 2:
            return ValidationResult.invalid("start must be an array of [x, y]")
        if not isinstance(end, list) or len(end) != 2:
            return ValidationResult.invalid("end must be an array of [x, y]")
        if any(not isinstance(val, int) or val < 0 for val in start + end):
            return ValidationResult.invalid("Coordinates must be non-negative integers")
        return ValidationResult.ok()

    def create_invocation(self, params: dict) -> ToolInvocation:
        start = params["start"]
        end = params["end"]
        duration_ms = int(params.get("duration_ms", self.default_duration_ms))
        return UIActionInvocation(
            tool_name="mobile_action",
            params=params,
            description=f"Swipe from ({start[0]},{start[1]}) to ({end[0]},{end[1]})",
            ui_action=Swipe(start[0], start[1], end[0], end[1], duration_ms),
        )


class SystemButtonActionHandler:
    action_name = "system_button"

    def validate(self, params: dict) -> ValidationResult:
        button = params.get("button")
        if not isinstance(button, str):
            return ValidationResult.invalid("system_button action requires button parameter")
        if button.lower() not in {"back", "home", "enter", "recents"}:
            return ValidationResult.invalid("button must be one of: back, home, enter, recents")
        return ValidationResult.ok()

    def create_invocation(self, params: dict) -> ToolInvocation:
        button = params["button"].lower()
        return UIActionInvocation(
            tool_name="mobile_action",
            params=params,
            description=f"Press {button} button",
            ui_action=SystemButton(SystemButtonType(button)),
        )


class WaitActionHandler:
    action_name = "wait"
    default_wait_ms = 1000

    def validate(self, params: dict) -> ValidationResult:
        duration_ms = int(params.get("duration_ms", self.default_wait_ms))
        if duration_ms < 0:
            return ValidationResult.invalid("duration_ms must be non-negative")
        if duration_ms > 30000:
            return ValidationResult.invalid("duration_ms must be <= 30000 (30 seconds)")
        return ValidationResult.ok()

    def create_invocation(self, params: dict) -> ToolInvocation:
        duration_ms = int(params.get("duration_ms", self.default_wait_ms))
        return UIActionInvocation(
            tool_name="mobile_action",
            params=params,
            description=f"Wait {duration_ms}ms for UI to settle",
            ui_action=Wait(duration_ms),
        )
