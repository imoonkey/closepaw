Following are reference repos under .reference/

# mobile_agent/
All are mobile-use agents, implemented in python, using adb for interaction.
- MAI-UI: from Alibaba, latest work, this repo is about agent framework, while the overall MAI-UI project also includes customized model. In this repo, it primarily uses MAI-UI-xxx models. It has a very detailed documentation.
- MobileAgent: from Alibaba. There are different versions, e.g., MobileAgentV1, MobileAgentV2, MobileAgentV3. V3 is the latest. V1/V2 are just agentic framework, while V3 also enabled/included customized model (GUI-Owl-xB). V3 multi-agents: Manager -> Operator(Executor) -> 执行动作 -> ActionReflector（可选 Notetaker）.

Three models that are from startups
- DroidRun: they started with a11y info-only mostly when they first started the project, and then added on screenshot inputs. From a startup DroidRun. Have two mode - 1) CodeAct agent and 2) Manager-Executor mode.
- Minitap mobile-use: from a startup Minitap. It has the most complicated multi-agent design: planner -> orchestrator -> contextor -> cortex -> executor -> executor_tools -> summarizer.
- autodevice: this repo is a fork of android_world evaluation repo, with autodevice agent added on. AutoDev agent uses planner-executor mode.
These three are building agents, not customizing models, using SoTA LLMs (e.g., GPT, Claude, Gemini etc.). Their primary customers are enterprise mobile developers using them for QA/testing. They all have high score on android_world, but you can see they have heavy prompt engineering, and sophisticated multi-agent framework, which can make it less generalizable for other tasks, and also slow and expensive.

# coding_agent
As coding agents are general agents, these repos are great resources to learn best agent framework practices. These are battle-tested real-world products from world-class companies and/or well-known open-source projects. Refer to them when you design engineering-heavy parts of your agent, like tool registry, multi-agent framework, execution engine, etc.
- Gemini-cli
- Codex
- ... 


# model
Repos under this also have mobile-use agents, as it completes the whole pipeline, but they are more focused on model customization. Besides model customization, you can refer to them for agent design when needed.



# eval
- android_world: is a benchmark for mobile-use agents by google.
- mobile_world: is a newer harder benchmark for mobile-use agents by Alibaba.
Both have their own baseline agent implementations for evaluation. Besides evaluation, you can refer to them for agent design when needed.