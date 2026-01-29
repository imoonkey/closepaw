from __future__ import annotations

from dataclasses import dataclass
import json
import re
import time
from xml.etree import ElementTree

from android_agent_playground.models import Bounds, PerceptionElement, Point, ScreenSnapshot


@dataclass(frozen=True)
class _TraversalMode:
    interactive_only: bool


class Perceptor:
    MAX_ELEMENTS = 80
    MAX_STRING_LENGTH = 60

    @staticmethod
    def from_uiautomator_xml(xml: str) -> ScreenSnapshot:
        timestamp_ms = int(time.time() * 1000)
        if not xml:
            return ScreenSnapshot(timestamp_ms=timestamp_ms, elements=[])
        try:
            root = ElementTree.fromstring(xml)
        except ElementTree.ParseError:
            return ScreenSnapshot(timestamp_ms=timestamp_ms, elements=[])

        elements: list[PerceptionElement] = []
        seen_keys: set[str] = set()

        Perceptor._traverse(root, elements, seen_keys, _TraversalMode(interactive_only=True))
        if len(elements) < Perceptor.MAX_ELEMENTS:
            Perceptor._traverse(root, elements, seen_keys, _TraversalMode(interactive_only=False))

        return ScreenSnapshot(timestamp_ms=timestamp_ms, elements=elements[: Perceptor.MAX_ELEMENTS])

    @staticmethod
    def to_prompt_json(snapshot: ScreenSnapshot) -> str:
        items = []
        for elem in snapshot.elements:
            items.append(
                {
                    "index": elem.index,
                    "text": elem.text,
                    "id": elem.resource_id,
                    "class": elem.class_name,
                    "desc": elem.description,
                    "clickable": elem.is_clickable,
                    "editable": elem.is_editable,
                    "scrollable": elem.is_scrollable,
                    "center": [elem.center.x, elem.center.y],
                }
            )
        return json.dumps(items, indent=2, ensure_ascii=True)

    @staticmethod
    def _traverse(
        node: ElementTree.Element,
        elements: list[PerceptionElement],
        seen_keys: set[str],
        mode: _TraversalMode,
    ) -> None:
        if len(elements) >= Perceptor.MAX_ELEMENTS:
            return

        if node.tag == "node":
            text_raw = (node.attrib.get("text") or "")[: Perceptor.MAX_STRING_LENGTH]
            desc_raw = (node.attrib.get("content-desc") or "")[: Perceptor.MAX_STRING_LENGTH]
            resource_id = (node.attrib.get("resource-id") or "")[: Perceptor.MAX_STRING_LENGTH]
            class_name = node.attrib.get("class") or ""
            class_short = class_name.split(".")[-1] if class_name else ""

            clickable = node.attrib.get("clickable") == "true"
            scrollable = node.attrib.get("scrollable") == "true"
            editable = node.attrib.get("editable") == "true" or class_short.endswith("EditText") or class_short.endswith("TextInputEditText")

            has_content = bool(text_raw.strip()) or bool(desc_raw.strip())
            should_keep = (clickable or editable or scrollable) if mode.interactive_only else (clickable or editable or scrollable or has_content)

            bounds = Perceptor._parse_bounds(node.attrib.get("bounds", ""))
            key = Perceptor._build_element_key(
                resource_id=resource_id,
                class_name=class_short,
                text=text_raw,
                desc=desc_raw,
                bounds=bounds,
                is_clickable=clickable,
                is_editable=editable,
                is_scrollable=scrollable,
            )

            if should_keep and key not in seen_keys:
                seen_keys.add(key)
                index = len(elements)
                element = PerceptionElement(
                    index=index,
                    text=Perceptor._normalize_whitespace(text_raw),
                    resource_id=resource_id,
                    class_name=class_short,
                    description=Perceptor._normalize_whitespace(desc_raw),
                    is_clickable=clickable,
                    is_editable=editable,
                    is_scrollable=scrollable,
                    bounds=bounds,
                    center=Point(x=bounds.center_x, y=bounds.center_y),
                )
                elements.append(element)

        for child in list(node):
            Perceptor._traverse(child, elements, seen_keys, mode)

    @staticmethod
    def _build_element_key(
        resource_id: str,
        class_name: str,
        text: str,
        desc: str,
        bounds: Bounds,
        is_clickable: bool,
        is_editable: bool,
        is_scrollable: bool,
    ) -> str:
        return (
            f"{resource_id}|{class_name}|{text}|{desc}|"
            f"{'1' if is_clickable else '0'}"
            f"{'1' if is_editable else '0'}"
            f"{'1' if is_scrollable else '0'}|"
            f"{bounds.left},{bounds.top},{bounds.right},{bounds.bottom}"
        )

    @staticmethod
    def _parse_bounds(bounds_str: str) -> Bounds:
        match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds_str)
        if match:
            left, top, right, bottom = map(int, match.groups())
            return Bounds(left=left, top=top, right=right, bottom=bottom)
        return Bounds(left=0, top=0, right=0, bottom=0)

    @staticmethod
    def _normalize_whitespace(text: str) -> str:
        text = re.sub(r"[ \t]+", " ", text)
        text = re.sub(r"\n{2,}", "\n", text)
        return text.strip()
