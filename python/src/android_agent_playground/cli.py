from __future__ import annotations

import typer
from rich.console import Console

from android_agent_playground.agent import Agent
from android_agent_playground.config import AgentConfig
from android_agent_playground.history import HistoryManager
from android_agent_playground.llm import MockLLMClient, OpenAILLMClient
from android_agent_playground.platform import AdbPlatform
from android_agent_playground.tools import AppControlTool, CompleteTaskTool, MobileActionTool, ToolRegistry, ToolRouter


app = typer.Typer(no_args_is_help=True)
console = Console()


@app.command()
def run(
    goal: str = typer.Option(..., "--goal", help="Task goal for the agent"),
    serial: str | None = typer.Option(None, "--serial", help="ADB serial"),
    max_turns: int = typer.Option(50, "--max-turns", help="Maximum turns before stopping"),
    model: str = typer.Option("gpt-5.2", "--model", help="Model name"),
    backend: str = typer.Option("openai", "--backend", help="LLM backend: openai|mock"),
    debug: bool = typer.Option(False, "--debug", help="Enable debug logging"),
    enable_screenshot: bool = typer.Option(False, "--enable-screenshot", help="Attach screenshot to LLM input"),
) -> None:
    config = AgentConfig(
        goal=goal,
        max_turns=max_turns,
        debug_mode=debug,
        model=model,
        llm_backend=backend,
        adb_serial=serial,
        enable_screenshot_input=enable_screenshot,
    )

    platform = AdbPlatform(
        serial=serial,
        enable_screenshot=enable_screenshot,
        adb_timeout_s=config.adb_timeout_s,
    )

    tool_registry = ToolRegistry()
    tool_registry.register_all(MobileActionTool(), AppControlTool(), CompleteTaskTool())
    tool_router = ToolRouter(tool_registry)
    history = HistoryManager()

    if backend == "mock":
        llm_client = MockLLMClient()
    else:
        llm_client = OpenAILLMClient()

    def emitter(message: str) -> None:
        console.print(message)

    agent = Agent(
        config=config,
        platform=platform,
        history_manager=history,
        tool_registry=tool_registry,
        tool_router=tool_router,
        llm_client=llm_client,
        event_emitter=emitter,
    )

    result = agent.run()
    console.print(f"Agent stopped: {result}")
