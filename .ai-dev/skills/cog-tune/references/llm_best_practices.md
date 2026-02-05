# LLM Best Practices (Prompt, Tools, Multi-Agent)

Use these as guardrails when proposing cognition improvements. Prefer minimal, testable changes.

## Prompting

- Put clear, explicit instructions first; be specific about desired behavior.
- Use few-shot examples only when needed; show the target output format directly.
- Define success criteria and evaluate with targeted tests; not all failures are prompt issues.

## Tool use

- Define tools with clear names, descriptions, and JSON input schemas; the model relies on these definitions.
- Prefer strict schema validation to reduce malformed tool calls.
- Provide tool-call examples for ambiguous tools or arguments.

## Context management

- Keep system prompt stable; move task-specific detail into user context or artifacts.
- Trim redundant history; summarize and point to artifacts for evidence.

## Multi-agent coordination

- Use explicit roles; define a handoff contract (goal, progress, blockers, next actions).
- Preserve reasoning/action traces to improve interpretability and debugging.

## Sources

- [OpenAI prompting guide](https://platform.openai.com/docs/guides/prompting)
- [OpenAI function calling guide](https://platform.openai.com/docs/guides/function-calling)
- [OpenAI prompt engineering best practices (PDF)](https://help.openai.com/en/articles/10032626-prompt-engineering-best-practices-for-chatgpt)
- [Anthropic prompt engineering overview](https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/overview)
- [Anthropic tool use overview](https://docs.anthropic.com/en/docs/build-with-claude/tool-use/overview)
- [ReAct paper](https://arxiv.org/abs/2210.03629)
- [AutoGen paper](https://arxiv.org/abs/2308.08155)
