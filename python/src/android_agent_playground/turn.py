from __future__ import annotations

from dataclasses import dataclass
import json
from typing import Iterable

from android_agent_playground.history import FunctionCall, FunctionCallOutput, HistoryManager, Message
from android_agent_playground.llm import LLMClient, LLMResponse, LLMToolCall
from android_agent_playground.prompt import UserContext
from android_agent_playground.tools.registry import ToolRegistry


@dataclass(frozen=True)
class ToolCallRequest:
    call_id: str
    name: str
    arguments: dict


@dataclass(frozen=True)
class TurnResult:
    content: str | None
    tool_calls: list[ToolCallRequest]
    is_complete: bool


@dataclass(frozen=True)
class TurnStreamEvent:
    kind: str
    data: object | None = None


class TurnInputBuilder:
    def __init__(self, history_manager: HistoryManager) -> None:
        self._history = history_manager

    def build(self, user_context: UserContext) -> list[dict]:
        estimated_tokens = self._history.estimate_token_count()
        if estimated_tokens > 20_000:
            self._history.compress(15_000)

        items: list[dict] = []
        for item in self._history.for_prompt():
            if isinstance(item, Message):
                if item.role in ("user", "assistant"):
                    items.append({"role": item.role, "content": item.content})
            elif isinstance(item, FunctionCall):
                items.append(
                    {
                        "role": "assistant",
                        "content": "",
                        "tool_calls": [
                            {
                                "id": item.call_id,
                                "type": "function",
                                "function": {
                                    "name": item.name,
                                    "arguments": json.dumps(item.arguments),
                                },
                            }
                        ],
                    }
                )
            elif isinstance(item, FunctionCallOutput):
                items.append(
                    {
                        "role": "tool",
                        "tool_call_id": item.call_id,
                        "content": item.content,
                    }
                )

        items.append(self._build_user_context_item(user_context))
        return items

    def _build_user_context_item(self, user_context: UserContext) -> dict:
        if user_context.image is None:
            return {"role": "user", "content": user_context.text}

        return {
            "role": "user",
            "content": [
                {"type": "text", "text": user_context.text},
                {"type": "image_url", "image_url": {"url": user_context.image.to_data_url()}},
            ],
        }


class Turn:
    COMPLETE_TASK_TOOL = "complete_task"

    def __init__(self, history_manager: HistoryManager, tool_registry: ToolRegistry, llm_client: LLMClient) -> None:
        self._history = history_manager
        self._tool_registry = tool_registry
        self._llm_client = llm_client
        self._input_builder = TurnInputBuilder(history_manager)

    def run(self, system_prompt: str, user_context: UserContext, model_name: str) -> TurnResult:
        messages = self._input_builder.build(user_context)
        tools = self._tool_registry.generate_openai_tools()
        full_system_prompt = self._build_system_prompt(system_prompt)
        response = self._llm_client.chat_with_tools(
            system_prompt=full_system_prompt,
            messages=messages,
            tools=tools,
            model=model_name,
        )
        return self._process_response(response)

    def run_streaming(
        self,
        system_prompt: str,
        user_context: UserContext,
        model_name: str,
    ) -> Iterable[TurnStreamEvent]:
        messages = self._input_builder.build(user_context)
        tools = self._tool_registry.generate_openai_tools()
        full_system_prompt = self._build_system_prompt(system_prompt)
        try:
            for event in self._llm_client.chat_with_tools_streaming(
                system_prompt=full_system_prompt,
                messages=messages,
                tools=tools,
                model=model_name,
            ):
                if event.kind == "text_delta":
                    yield TurnStreamEvent(kind="text_delta", data=event.data)
                elif event.kind == "tool_call":
                    yield TurnStreamEvent(kind="tool_call_received", data=event.data)
                elif event.kind == "complete":
                    if isinstance(event.data, LLMResponse):
                        result = self._process_response(event.data)
                        yield TurnStreamEvent(kind="complete", data=result)
        except Exception as exc:
            yield TurnStreamEvent(kind="error", data=exc)

    def _build_system_prompt(self, base_prompt: str) -> str:
        return (
            f"{base_prompt}\n\n"
            "## CRITICAL RULES\n\n"
            "1. EXECUTE ONE ACTION PER TURN. Call mobile_action or app_control, then STOP and wait.\n"
            "2. NEVER call complete_task together with other actions in the same turn.\n"
            "3. Only call complete_task in the next turn AFTER you see the result of your action has achieved user's goal\n\n"
            "## Element Selection\n\n"
            '- Use the "index" field to identify elements (e.g., element_index=5)\n'
            '- Only click elements with "clickable": true\n'
            '- Only type in elements with "editable": true\n\n'
            "## ReAct Loop\n\n"
            "Each turn:\n"
            "1. OBSERVE: Read the screen state JSON\n"
            "2. THINK: Identify what action to take\n"
            "3. ACT: Call ONE tool (mobile_action, app_control)\n"
            "4. WAIT: You will receive the new screen state in the next turn\n\n"
            "## Completion\n\n"
            "Call complete_task ONLY when:\n"
            "- You see the target screen/content after your action succeeded\n"
            "- You have verified the goal is achieved by checking the screen state\n"
            "- NEVER call complete_task before executing and verifying an action\n"
        )

    def _process_response(self, response: LLMResponse) -> TurnResult:
        text_content = response.text_content
        tool_calls = response.tool_calls

        parsed_tool_calls = [self._convert_tool_call(call) for call in tool_calls]
        complete_task_call = next((call for call in parsed_tool_calls if call.name == self.COMPLETE_TASK_TOOL), None)
        is_complete = complete_task_call is not None
        return TurnResult(content=text_content, tool_calls=parsed_tool_calls, is_complete=bool(is_complete))

    def _convert_tool_call(self, call: LLMToolCall) -> ToolCallRequest:
        try:
            args = json.loads(call.arguments) if call.arguments else {}
        except json.JSONDecodeError:
            args = {}
        return ToolCallRequest(call_id=call.call_id, name=call.name, arguments=args)
