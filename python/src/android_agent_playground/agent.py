from __future__ import annotations

from dataclasses import dataclass
import time
from typing import Callable

from android_agent_playground.config import AgentConfig
from android_agent_playground.history import FunctionCall, FunctionCallOutput, HistoryManager, Message
from android_agent_playground.perceptor import Perceptor
from android_agent_playground.prompt import AgentPromptBuilder, DEFAULT_SYSTEM_PROMPT, LOCAL_PROMPT_SUFFIX
from android_agent_playground.tools.registry import (
    SimpleToolRouterContext,
    ToolCallResultCancelled,
    ToolCallResultError,
    ToolCallResultSuccess,
    ToolObservationScreenState,
    ToolObservationTextOutput,
    ToolRegistry,
    ToolRouter,
)
from android_agent_playground.turn import Turn, TurnResult
from android_agent_playground.platform import AndroidPlatform


@dataclass(frozen=True)
class ObservationScreenState:
    accessibility_tree: str


@dataclass(frozen=True)
class ObservationTextOutput:
    content: str


Observation = ObservationScreenState | ObservationTextOutput


@dataclass(frozen=True)
class TurnOutcomeContinue:
    pass


@dataclass(frozen=True)
class TurnOutcomeComplete:
    message: str


@dataclass(frozen=True)
class TurnOutcomeError:
    message: str
    recoverable: bool


@dataclass(frozen=True)
class TurnOutcomeCancelled:
    pass


TurnOutcome = TurnOutcomeContinue | TurnOutcomeComplete | TurnOutcomeError | TurnOutcomeCancelled


class AgentStopReason:
    GOAL_ACHIEVED = "goal_achieved"
    USER_REQUESTED = "user_requested"
    MAX_TURNS = "max_turns"
    ERROR = "error"


class Agent:
    def __init__(
        self,
        config: AgentConfig,
        platform: AndroidPlatform,
        history_manager: HistoryManager,
        tool_registry: ToolRegistry,
        tool_router: ToolRouter,
        llm_client,
        event_emitter: Callable[[str], None] | None = None,
    ) -> None:
        self._config = config
        self._platform = platform
        self._history = history_manager
        self._tool_registry = tool_registry
        self._tool_router = tool_router
        self._llm_client = llm_client
        self._event_emitter = event_emitter
        self._turn_count = 0
        self._stop_requested = False

        self._prompt_builder = AgentPromptBuilder(
            base_prompt=config.system_prompt,
            default_prompt=DEFAULT_SYSTEM_PROMPT,
            local_prompt_suffix=LOCAL_PROMPT_SUFFIX,
            llm_backend=config.llm_backend,
            tool_registry=tool_registry,
        )

    def stop(self) -> None:
        self._stop_requested = True

    def run(self) -> str:
        self._emit("Starting agent...")
        self._history.add_item(Message(role="user", content=f"Goal: {self._config.goal}"))

        while self._should_continue():
            if self._turn_count >= self._config.max_turns:
                self._emit("Max turns reached")
                return AgentStopReason.MAX_TURNS

            outcome = self._execute_turn()

            if isinstance(outcome, TurnOutcomeContinue):
                time.sleep(self._config.ui_settle_delay_ms / 1000.0)
                continue
            if isinstance(outcome, TurnOutcomeComplete):
                self._emit("Goal achieved")
                return AgentStopReason.GOAL_ACHIEVED
            if isinstance(outcome, TurnOutcomeCancelled):
                self._emit("Cancelled")
                return AgentStopReason.USER_REQUESTED
            if isinstance(outcome, TurnOutcomeError):
                if not outcome.recoverable:
                    self._emit(f"Error: {outcome.message}")
                    return AgentStopReason.ERROR
                self._emit(f"Recoverable error: {outcome.message}")
                time.sleep(self._config.ui_settle_delay_ms / 1000.0)

        return AgentStopReason.USER_REQUESTED

    def _execute_turn(self) -> TurnOutcome:
        self._turn_count += 1
        turn_id = f"turn-{self._turn_count}"
        self._emit(f"Turn {self._turn_count} start")

        try:
            self._emit("Scanning screen...")
            snapshot = self._platform.capture_screen()

            if self._stop_requested:
                return TurnOutcomeCancelled()

            self._emit("Thinking...")
            turn = Turn(self._history, self._tool_registry, self._llm_client)
            system_prompt = self._prompt_builder.build_system_prompt()
            user_context = self._prompt_builder.build_user_context(snapshot)
            result: TurnResult = turn.run(system_prompt, user_context, self._config.model)

            if result.content:
                self._history.add_item(Message(role="assistant", content=result.content))

            has_completion_tool = any(call.name == "complete_task" for call in result.tool_calls)
            has_non_completion_tool = any(call.name != "complete_task" for call in result.tool_calls)

            selected_tool = next((call for call in result.tool_calls if call.name != "complete_task"), None)
            if selected_tool is None and result.tool_calls:
                selected_tool = result.tool_calls[0]
            tool_calls_to_execute = [selected_tool] if selected_tool else []

            if tool_calls_to_execute:
                time.sleep(0.2)
                current_snapshot = self._platform.capture_screen()
                for tool_call in tool_calls_to_execute:
                    self._history.add_item(
                        FunctionCall(
                            call_id=tool_call.call_id,
                            name=tool_call.name,
                            arguments=tool_call.arguments,
                        )
                    )

                    context = SimpleToolRouterContext(
                        platform=self._platform,
                        current_snapshot=current_snapshot,
                    )
                    tool_result = self._tool_router.execute(
                        tool_name=tool_call.name,
                        params=tool_call.arguments,
                        context=context,
                        call_id=tool_call.call_id,
                    )

                    observation, observed_snapshot = self._resolve_observation(tool_result, tool_call.name)
                    if observed_snapshot is not None:
                        current_snapshot = observed_snapshot

                    self._history.add_item(
                        FunctionCallOutput(
                            call_id=tool_call.call_id,
                            content=self._format_tool_result(tool_result, observation),
                            success=isinstance(tool_result, ToolCallResultSuccess),
                        )
                    )

            should_complete = result.is_complete and not has_non_completion_tool
            if should_complete:
                summary = None
                for call in result.tool_calls:
                    if call.name == "complete_task" and isinstance(call.arguments, dict):
                        summary = call.arguments.get("answer") or call.arguments.get("summary")
                        break
                return TurnOutcomeComplete(summary or result.content or "Goal achieved")
            return TurnOutcomeContinue()
        except Exception as exc:
            message = str(exc)
            recoverable = "timeout" in message.lower() or "connection" in message.lower()
            return TurnOutcomeError(message=message or "Unknown error", recoverable=recoverable)
        finally:
            self._emit(f"Turn {self._turn_count} complete")

    def _resolve_observation(self, tool_result, tool_name: str) -> tuple[Observation, object | None]:
        observation: Observation = ObservationTextOutput("No observation captured.")
        observed_snapshot = None

        if isinstance(tool_result, ToolCallResultSuccess):
            if isinstance(tool_result.observation, ToolObservationScreenState):
                observation = ObservationScreenState(tool_result.observation.accessibility_tree)
                observed_snapshot = tool_result.observation.snapshot
            elif isinstance(tool_result.observation, ToolObservationTextOutput):
                observation = ObservationTextOutput(tool_result.observation.content)

        if observed_snapshot is None and tool_name != "complete_task":
            capture = self._capture_observation_with_snapshot()
            observation = capture[0]
            observed_snapshot = capture[1]

        if tool_name == "complete_task" and observed_snapshot is None:
            observation = ObservationTextOutput("Completion acknowledged; no screen captured.")

        return observation, observed_snapshot

    def _capture_observation_with_snapshot(self) -> tuple[Observation, object | None]:
        time.sleep(0.5)
        snapshot = self._platform.capture_screen()
        accessibility_tree = Perceptor.to_prompt_json(snapshot)
        return ObservationScreenState(accessibility_tree), snapshot

    def _format_tool_result(self, result, observation: Observation) -> str:
        if isinstance(result, ToolCallResultSuccess):
            result_text = f"Success: {result.output}"
        elif isinstance(result, ToolCallResultError):
            result_text = f"Error: {result.error}"
        elif isinstance(result, ToolCallResultCancelled):
            result_text = f"Cancelled: {result.reason}"
        else:
            result_text = "Unknown result"

        if isinstance(observation, ObservationScreenState):
            observation_text = f"Screen after action:\n{observation.accessibility_tree}"
        else:
            observation_text = observation.content

        return f"{result_text}\n\n{observation_text}"

    def _should_continue(self) -> bool:
        return not self._stop_requested

    def _emit(self, message: str) -> None:
        if self._event_emitter:
            self._event_emitter(message)
