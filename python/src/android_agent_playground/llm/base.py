from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Protocol


@dataclass(frozen=True)
class LLMToolCall:
    call_id: str
    name: str
    arguments: str


@dataclass(frozen=True)
class LLMResponse:
    text_content: str | None
    tool_calls: list[LLMToolCall]


class LLMClient(Protocol):
    def chat_with_tools(
        self,
        system_prompt: str,
        messages: list[dict],
        tools: list[dict],
        model: str,
    ) -> LLMResponse: ...

    def chat_with_tools_streaming(
        self,
        system_prompt: str,
        messages: list[dict],
        tools: list[dict],
        model: str,
    ) -> Iterable["LLMStreamEvent"]: ...


@dataclass(frozen=True)
class LLMStreamEvent:
    kind: str
    data: object | None = None


class MockLLMClient:
    def chat_with_tools(
        self,
        system_prompt: str,
        messages: list[dict],
        tools: list[dict],
        model: str,
    ) -> LLMResponse:
        tool_call = LLMToolCall(
            call_id="mock-call",
            name="complete_task",
            arguments='{"status":"failure","answer":"Mock backend configured.","reason":"No LLM backend selected."}',
        )
        return LLMResponse(text_content=None, tool_calls=[tool_call])

    def chat_with_tools_streaming(
        self,
        system_prompt: str,
        messages: list[dict],
        tools: list[dict],
        model: str,
    ) -> Iterable[LLMStreamEvent]:
        response = self.chat_with_tools(system_prompt, messages, tools, model)
        if response.text_content:
            yield LLMStreamEvent(kind="text_delta", data=response.text_content)
        for call in response.tool_calls:
            yield LLMStreamEvent(kind="tool_call", data=call)
        yield LLMStreamEvent(kind="complete", data=response)
