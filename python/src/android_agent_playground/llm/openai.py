from __future__ import annotations

from typing import Iterable
import os

from openai import OpenAI

from android_agent_playground.llm.base import LLMClient, LLMResponse, LLMStreamEvent, LLMToolCall


class OpenAILLMClient(LLMClient):
    def __init__(self, api_key: str | None = None) -> None:
        key = api_key or os.getenv("OPENAI_API_KEY")
        if not key:
            raise RuntimeError("OPENAI_API_KEY is not set")
        self._client = OpenAI(api_key=key)

    def chat_with_tools(
        self,
        system_prompt: str,
        messages: list[dict],
        tools: list[dict],
        model: str,
    ) -> LLMResponse:
        full_messages = [{"role": "system", "content": system_prompt}] + messages
        response = self._client.chat.completions.create(
            model=model,
            messages=full_messages,
            tools=tools,
            tool_choice="auto",
        )

        message = response.choices[0].message
        text_content = message.content if message.content else None
        tool_calls = []
        for call in message.tool_calls or []:
            tool_calls.append(
                LLMToolCall(
                    call_id=call.id,
                    name=call.function.name,
                    arguments=call.function.arguments,
                )
            )

        return LLMResponse(text_content=text_content, tool_calls=tool_calls)

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
