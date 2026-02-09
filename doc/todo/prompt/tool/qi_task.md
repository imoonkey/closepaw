# Improve a specific tool

每个tool，
1. 首先要定义好input,output，这两个是直接进入LLM context的。每部分都有两个优化目标: 1. maximize info and tool/agent task success, 2. minimize token usage，尽管有时候这两个目标是矛盾的。the art is in the trade-off.
2. 其次要优化好execution的部分。
三部分缺一不可。

1. My current tool prompts are documented at @doc/todo/prompt/current_status_claude.md.
2. Under .reference/mobile_agent/, there are multiple reference repos for mobile use agents: AutoDev (which is a fork of android_world repo with autodev agent added on), DroidRun, Minitap, and MobileAgentV3. Under .reference/eval, there are two evaluation repos, which has baseline implementations too that you can check.

Now you will work on improving a specific tool's reference implementation.


1. Research this tool's implementation in each reference repo. Sometimes it is by a different name but for similar purpose, or one tool could be breaked into multiple tools. Find how that tool is defined, note down the exact details (e.g., full prompt of tool itself, and how the system prompt mentioned it if any, and which agent has this tool if it is mult-agent) and its code evidence location. Use subagents to research each reference repo independently.
2. Once done, summarize the different implementation's pros and cons.
3. Compare against my prompt of that tool, propose improvement plan. Pay attention to all the nitty-gritty details. You goal is to maximize tool efficiency while not overcomplicate the tool or overbloated tool prompt. You optimize for max(agent success rate) and min(token usage cost).
4. Write the plan under doc/todo/prompt/tool/[tool_name]/analysis_[yourname, e.g., claude/codex/gemini etc.].md

(Don't yet) 4. implement the changes.
(Don't yet) 5. Once done, update current_status_claude.md and doc/todo/prompt/tool/[tool_name]/ to reflect latest status.


The tool you are working on now is: