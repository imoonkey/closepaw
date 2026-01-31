# DroidRun - leaderboard methodology summary (from repo)

## Sources (local)
- `.reference/mobile_agent/droidrun/README.md`
- `.reference/mobile_agent/droidrun/docs/concepts/architecture.mdx`
- `.reference/mobile_agent/droidrun/docs/concepts/shared-state.mdx`
- `.reference/mobile_agent/droidrun/docs/concepts/prompts.mdx`
- `.reference/mobile_agent/droidrun/docs/v3/concepts/android-tools.mdx`
- `.reference/mobile_agent/droidrun/droidrun/agent/manager/manager_agent.py`
- `.reference/mobile_agent/droidrun/droidrun/agent/executor/executor_agent.py`
- `.reference/mobile_agent/droidrun/droidrun/agent/droid/droid_agent.py`
- `.reference/mobile_agent/droidrun/droidrun/agent/droid/state.py`
- `.reference/mobile_agent/droidrun/droidrun/config/prompts/manager/system.jinja2`
- `.reference/mobile_agent/droidrun/droidrun/config/prompts/executor/system.jinja2`

## Architecture and agent roles
- **Multi-agent orchestrator** (`DroidAgent`) with two modes:
  - **Reasoning mode**: ManagerAgent (planning) → ExecutorAgent (actions) → optional ScripterAgent (off-device Python).
  - **Direct mode**: CodeActAgent executes without planning overhead for simple tasks.
- **Shared state** (`DroidAgentState`) is the coordination hub: plan/subgoal, action history, error flags, memory, current/previous device state, visited apps, and custom variables.
- **App cards** provide app-specific guidance, loaded via local/server/composite providers and injected into prompts.

## Tooling and action model
- **ADB tools API** (Android) exposes explicit primitives: `tap_by_index`, `swipe`, `input_text`, `press_key`/`back`, `start_app`, `list_packages`, `install_app`, `get_state`, `take_screenshot`.
- `get_state()` caches UI elements for index-based targeting; subsequent `tap_by_index` relies on this cache.
- **Atomic actions** for agents include click/long_press/type/swipe/system_button/open_app/get_state/take_screenshot/remember/complete, with executor choosing one atomic action per turn.
- **Memory** is first-class (`remember`, `get_memory`), and the Manager prompt requires memory usage over clipboard unless requested.

## Screen perception
- **A11y-first state**: `get_state()` returns the accessibility tree and phone state; DroidRun converts this into a formatted device state string used by Manager/Executor.
- **Screenshot as optional vision input**: Manager/Executor attach screenshots when vision is enabled, and the shared state tracks both current and previous device states for before/after reasoning.
- **Index-based targeting**: actions like `tap_by_index` depend on the cached elements from the most recent `get_state()` call.

## Prompt and context design
- **Jinja2 prompt templates** for manager/executor/codeact/scripter (customizable via prompt strings).
- Manager prompt includes device date, app cards, error history, and explicit memory instructions (`<add_memory>` tags).
- Manager message construction **injects memory and current device state into the latest user message**, and **previous device state** into the prior user message for before/after reasoning.
- Executor prompt is **literal and mechanical**, focusing on subgoal execution with strict atomic action selection.
- Structured output support via output schema injection, plus a text-manipulation flow for in-place edits.

## Why this likely performs well
- **Planner/Executor split** reduces confusion between strategy and UI manipulation.
- **Shared state + action history** keeps recovery and progress coherent across agents.
- **App cards** reduce exploration time in common apps and standardize navigation steps.
- **Differential context** (current + previous state) improves reasoning about state transitions.
- **Scripter** offloads non-UI computation to Python for efficiency and accuracy.

## Where our Android agent can improve (actionable)
- Add a **shared state object** for action history, error flags, and memory shared across steps.
- Inject **previous UI state + last action summary** into prompts to help interpret transitions.
- Add **app cards** for top apps (Gmail/Maps/Settings) and inject into system prompt.
- Add **memory tool** with explicit prompts and “memory over clipboard” guidance.
- Add **mode switching**: direct mode for simple tasks, reasoning mode (planner/executor) for complex workflows.
- Provide **structured output schema** support for tasks requiring exact formatted answers.
- Add **off-device scripter** for web/API/data tasks that don’t require UI.
