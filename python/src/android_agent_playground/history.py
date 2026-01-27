from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class TruncationPolicy(Enum):
    NONE = -1
    CONSERVATIVE = 8000
    AGGRESSIVE = 2000
    MINIMAL = 500


@dataclass(frozen=True)
class ResponseItem:
    def estimate_tokens(self) -> int:
        raise NotImplementedError


@dataclass(frozen=True)
class Message(ResponseItem):
    role: str
    content: str
    name: str | None = None

    def estimate_tokens(self) -> int:
        return int(len(self.content) * 0.25) + 4


@dataclass(frozen=True)
class FunctionCall(ResponseItem):
    call_id: str
    name: str
    arguments: dict

    def estimate_tokens(self) -> int:
        return int((len(self.name) + len(str(self.arguments))) * 0.25) + 10


@dataclass(frozen=True)
class FunctionCallOutput(ResponseItem):
    call_id: str
    content: str
    success: bool = True
    truncated: bool = False

    def estimate_tokens(self) -> int:
        return int(len(self.content) * 0.25) + 4


@dataclass(frozen=True)
class HistoryConfig:
    default_truncation_policy: TruncationPolicy = TruncationPolicy.CONSERVATIVE
    max_token_budget: int = 100_000
    auto_compress: bool = True
    auto_compress_threshold: float = 0.85


class HistoryManager:
    def __init__(self, config: HistoryConfig | None = None) -> None:
        self._config = config or HistoryConfig()
        self._items: list[ResponseItem] = []
        self._last_token_estimate: int | None = None

    def add_item(self, item: ResponseItem) -> None:
        processed = self._process_item(item, self._config.default_truncation_policy)
        self._items.append(processed)
        self._last_token_estimate = None

    def record_items(self, new_items: list[ResponseItem], policy: TruncationPolicy | None = None) -> None:
        truncation = policy or self._config.default_truncation_policy
        for item in new_items:
            self._items.append(self._process_item(item, truncation))
        self._last_token_estimate = None

    def for_prompt(self) -> list[ResponseItem]:
        return self._normalize_history(self._items)

    def estimate_token_count(self) -> int:
        if self._last_token_estimate is not None:
            return self._last_token_estimate
        estimate = sum(item.estimate_tokens() for item in self._items)
        self._last_token_estimate = estimate
        return estimate

    def compress(self, target_tokens: int) -> None:
        threshold = len(self._items) // 2
        for i in range(threshold):
            item = self._items[i]
            if isinstance(item, FunctionCallOutput):
                self._items[i] = self._truncate_output(item, TruncationPolicy.AGGRESSIVE)

        while self.estimate_token_count() > target_tokens and len(self._items) > 2:
            self._remove_first_item()

    def _process_item(self, item: ResponseItem, policy: TruncationPolicy) -> ResponseItem:
        if isinstance(item, FunctionCallOutput):
            return self._truncate_output(item, policy)
        return item

    def _truncate_output(self, output: FunctionCallOutput, policy: TruncationPolicy) -> FunctionCallOutput:
        if policy == TruncationPolicy.NONE:
            return output
        max_chars = int(policy.value / 0.25)
        if len(output.content) <= max_chars:
            return output
        truncated = output.content[:max_chars] + f"\n...[truncated, {len(output.content) - max_chars} chars omitted]"
        return FunctionCallOutput(
            call_id=output.call_id,
            content=truncated,
            success=output.success,
            truncated=True,
        )

    def _remove_first_item(self) -> None:
        if not self._items:
            return
        removed = self._items.pop(0)
        if isinstance(removed, FunctionCall):
            self._items = [
                item
                for item in self._items
                if not (isinstance(item, FunctionCallOutput) and item.call_id == removed.call_id)
            ]
        self._last_token_estimate = None

    def _normalize_history(self, items: list[ResponseItem]) -> list[ResponseItem]:
        result = list(items)
        call_ids = {item.call_id for item in result if isinstance(item, FunctionCall)}
        output_call_ids = {item.call_id for item in result if isinstance(item, FunctionCallOutput)}

        missing_outputs = call_ids - output_call_ids
        for call_id in missing_outputs:
            call_index = next(
                (idx for idx, item in enumerate(result) if isinstance(item, FunctionCall) and item.call_id == call_id),
                None,
            )
            if call_index is not None:
                result.insert(
                    call_index + 1,
                    FunctionCallOutput(call_id=call_id, content="[Output not recorded]", success=False),
                )

        result = [
            item
            for item in result
            if not (isinstance(item, FunctionCallOutput) and item.call_id not in call_ids)
        ]
        return result
