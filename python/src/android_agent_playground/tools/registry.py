from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable, Protocol
import uuid

from android_agent_playground.models import ScreenSnapshot
from android_agent_playground.platform import AndroidPlatform


@dataclass(frozen=True)
class ValidationResult:
    valid: bool
    errors: list[str] | None = None

    @staticmethod
    def ok() -> "ValidationResult":
        return ValidationResult(valid=True)

    @staticmethod
    def invalid(errors: list[str] | str) -> "ValidationResult":
        if isinstance(errors, str):
            errors = [errors]
        return ValidationResult(valid=False, errors=errors)


class ToolSpec(Protocol):
    name: str
    description: str
    parameter_schema: dict

    def validate(self, params: dict) -> ValidationResult: ...
    def create_invocation(self, params: dict) -> "ToolInvocation": ...


class ToolInvocation(Protocol):
    tool_name: str
    params: dict

    def get_description(self) -> str: ...
    def execute(self, context: "ToolExecutionContext") -> "ToolExecutionResult": ...


class ToolExecutionContext(Protocol):
    platform: AndroidPlatform
    current_snapshot: ScreenSnapshot | None

    def is_cancelled(self) -> bool: ...


@dataclass(frozen=True)
class ToolObservationScreenState:
    accessibility_tree: str
    element_count: int
    snapshot: ScreenSnapshot | None = None


@dataclass(frozen=True)
class ToolObservationTextOutput:
    content: str


ToolObservation = ToolObservationScreenState | ToolObservationTextOutput


@dataclass(frozen=True)
class ToolExecutionSuccess:
    output: str
    data: Any | None = None
    observation: ToolObservation | None = None


@dataclass(frozen=True)
class ToolExecutionFailure:
    error: str
    exception: Exception | None = None


@dataclass(frozen=True)
class ToolExecutionCancelled:
    reason: str = "Cancelled"


ToolExecutionResult = ToolExecutionSuccess | ToolExecutionFailure | ToolExecutionCancelled


@dataclass(frozen=True)
class ToolCallResultSuccess:
    call_id: str
    output: str
    data: Any | None = None
    observation: ToolObservation | None = None


@dataclass(frozen=True)
class ToolCallResultError:
    call_id: str
    error: str
    exception: Exception | None = None


@dataclass(frozen=True)
class ToolCallResultCancelled:
    call_id: str
    reason: str


ToolCallResult = ToolCallResultSuccess | ToolCallResultError | ToolCallResultCancelled


@dataclass(frozen=True)
class SimpleToolExecutionContext:
    platform: AndroidPlatform
    current_snapshot: ScreenSnapshot | None
    _cancelled: Callable[[], bool]

    def is_cancelled(self) -> bool:
        return self._cancelled()


@dataclass(frozen=True)
class SimpleToolRouterContext:
    platform: AndroidPlatform
    current_snapshot: ScreenSnapshot | None = None
    _cancelled: Callable[[], bool] | None = None

    def is_cancelled(self) -> bool:
        if self._cancelled is None:
            return False
        return self._cancelled()


class ToolRegistry:
    def __init__(self) -> None:
        self._tools: dict[str, ToolSpec] = {}

    def register(self, tool: ToolSpec) -> None:
        self._tools[tool.name] = tool

    def register_all(self, *tools: ToolSpec) -> None:
        for tool in tools:
            self.register(tool)

    def get(self, name: str) -> ToolSpec | None:
        return self._tools.get(name)

    def get_names(self) -> set[str]:
        return set(self._tools.keys())

    def generate_openai_tools(self) -> list[dict]:
        tools = []
        for tool in self._tools.values():
            tools.append(
                {
                    "type": "function",
                    "function": {
                        "name": tool.name,
                        "description": tool.description,
                        "parameters": tool.parameter_schema,
                    },
                }
            )
        return tools


class ToolRouter:
    def __init__(self, registry: ToolRegistry) -> None:
        self._registry = registry

    def execute(
        self,
        tool_name: str,
        params: dict,
        context: SimpleToolRouterContext,
        call_id: str | None = None,
    ) -> ToolCallResult:
        resolved_call_id = call_id or self._generate_call_id()
        tool = self._registry.get(tool_name)
        if tool is None:
            return ToolCallResultError(resolved_call_id, f"Unknown tool: {tool_name}")

        validation = tool.validate(params)
        if not validation.valid:
            errors = ", ".join(validation.errors or [])
            return ToolCallResultError(resolved_call_id, f"Validation failed: {errors}")

        invocation = tool.create_invocation(params)
        exec_context = SimpleToolExecutionContext(
            platform=context.platform,
            current_snapshot=context.current_snapshot,
            _cancelled=context.is_cancelled,
        )

        result = invocation.execute(exec_context)
        if isinstance(result, ToolExecutionSuccess):
            return ToolCallResultSuccess(
                call_id=resolved_call_id,
                output=result.output,
                data=result.data,
                observation=result.observation,
            )
        if isinstance(result, ToolExecutionCancelled):
            return ToolCallResultCancelled(resolved_call_id, result.reason)
        if isinstance(result, ToolExecutionFailure):
            return ToolCallResultError(resolved_call_id, result.error, result.exception)
        return ToolCallResultError(resolved_call_id, "Unknown execution result")

    def _generate_call_id(self) -> str:
        return uuid.uuid4().hex[:8]
