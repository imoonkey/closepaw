from __future__ import annotations

from dataclasses import dataclass

from android_agent_playground.models import ScreenImage, ScreenSnapshot
from android_agent_playground.perceptor import Perceptor
from android_agent_playground.tools.registry import ToolRegistry


DEFAULT_SYSTEM_PROMPT = """
You are an Android automation agent. You control the device using tools.

Your task is to achieve the user's goal by:
1. Observing the current screen state (provided as a JSON list of UI elements)
2. Deciding what action to take based on the screen
3. Executing the action using available tools
4. Observing the result and continuing until done
5. If you have achieved the goal, call complete_task to wrap up. Do NOT call it prematurely.

Your start screen maybe the Android Agent app itself, or any other screen.
Your actions should almost always start with directly opening or navigating to the right app/page first.
""".strip()

LOCAL_PROMPT_SUFFIX = """
## LOCAL MODEL TOOL CALLING

- Use function calling with the registered tools. Do NOT emit <action> tags or raw JSON.
- Call exactly one tool per turn (mobile_action or app_control) unless you are completing.
- When the goal is achieved, call complete_task with status and answer.
""".strip()


@dataclass(frozen=True)
class UserContext:
    text: str
    image: ScreenImage | None


class AgentPromptBuilder:
    def __init__(
        self,
        base_prompt: str | None,
        default_prompt: str,
        local_prompt_suffix: str,
        llm_backend: str,
        tool_registry: ToolRegistry,
    ) -> None:
        self._base_prompt = base_prompt
        self._default_prompt = default_prompt
        self._local_prompt_suffix = local_prompt_suffix
        self._llm_backend = llm_backend
        self._tool_registry = tool_registry

    def build_system_prompt(self) -> str:
        prompt = self._base_prompt or self._default_prompt
        if self._llm_backend.lower() == "local":
            return f"{prompt}\n\n{self._local_prompt_suffix}"
        return prompt

    def build_user_context(self, snapshot: ScreenSnapshot) -> UserContext:
        screen_json = Perceptor.to_prompt_json(snapshot)
        tool_names = ", ".join(sorted(self._tool_registry.get_names()))
        image = snapshot.image if self._llm_backend.lower() == "openai" else None
        image_hint = "\nScreenshot attached." if image else ""

        text = (
            f"Current screen state ({len(snapshot.elements)} elements):\n"
            f"```json\n{screen_json}\n```\n\n"
            f"Available tools: {tool_names}\n\n"
            f"{image_hint}\n"
            "What action should I take next to achieve the goal?"
        ).strip()

        return UserContext(text=text, image=image)
