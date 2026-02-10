# Design Review: Multi-Tool Per Turn

## 1. Summary of Context
The core requirement (`qi_note.md`) is to lift the "single tool per turn" restriction. Specifically:
- **Constraint**: Allow multiple "cognitive" tools (`write_todos`, `scratchpad`) in the same turn as 0 or 1 "mobile action".
- **Goal**: Improve efficiency (don't waste turns on pure state updates).
- **Hint**: `qi_note.md` suggested parallelism/async for cognitive tools but explicitly requested a "KISS" design.

## 2. Design 1: The "Policy Patch" Approach
**Philosophy**: "Cognitive tools are cheap; sequential execution is fine. The problem is just the filter."

### Analysis
- **Mechanism**: Adds `isScreenChanging` boolean to `ToolName`. Rewrites `arbitrateToolCalls` to keep all cognitive tools + max 1 screen tool.
- **Execution**: Strictly sequential (Cognitive → Screen).
- **Pros**:
    - **Extreme Simplicity**: modifying existing logic rather than adding new architecture.
    - **Safety**: Sequential execution eliminates race conditions in state updates or logging.
    - **Pragmatism**: Correctly identifies that `write_todos` takes non-blocking milliseconds, so parallelism adds complexity for negligible gain (vs the seconds-long mobile action).
- **Cons**:
    - **Rigid Coupling**: Hardcodes "Cognitive first, Screen last" logic inside the arbitration result structure implies implicit execution order.
    - **Boolean Semantics**: `isScreenChanging` is a bit "magical"—it splits the world into two, but doesn't explicitly model "Completion" as a third category (though it handles it logic-wise).

**Score: 9/10** (Winning on "KISS")

## 3. Design 2: The "Execution Engine" Approach
**Philosophy**: "Different tools have different physics; the engine shoud reflect that. State is parallelizable; UI is serial."

### Analysis
- **Mechanism**: Introduces explicit classifier (`UI_MUTATING`, `STATE_ONLY`, `COMPLETION`).
- **Execution**: Hybrid. `stateCalls` run `async`, `uiCalls` run serial.
- **Pros**:
    - **Semantic Clarity**: The 3 categories map perfectly to the domain model.
    - **Future Proofing**: If cognitive tools became expensive (e.g., calling another LLM/agent), this architecture handles it better.
- **Cons**:
    - **Accidental Complexity**: Introducing `coroutineScope` and `async` handling requires structured concurrency and careful error propagation.
    - **Log/History Re-serialization**: As noted in the design itself, "History manager is not designed for concurrent mutation", requiring extra logic to preserve deterministic log ordering.
    - **Over-engineering**: Optimize for performance where none is lost. The bottleneck is the LLM and the Android UI, not the loop iterating over in-memory list updates.

**Score: 7/10**

## 4. Deep Dive: Design Thinking & Critique

### The "Parallelism" Trap
`qi_note.md` mentioned "parallel/async" as a *possibility*.
- **Design 2** took this as a requirement to be engineered.
- **Design 1** critically challenged this: "Cognitive tools are in-memory microsecond operations. The overhead... is more code than the time saved."

**This is the defining difference.** Design 1 demonstrated better engineering judgment by rejecting the premise of parallelism for this specific use case. In Android automation, 99% of latency is the LLM generation and the UI transition. Optimizing the 1ms it takes to update a Todo list by making it async is false efficiency that incurs technical debt (concurrency bugs).

### The "Completion" Handling
- **Design 1**: Handles `complete_task` via policy rules (defer if screen action exists).
- **Design 2**: Handles `complete_task` as a specific execution phase (always run last).
Design 2 is actually cleaner here. Conceptualizing "Completion" as a phase rather than a tool type that competes for a slot is a stronger mental model.

### The "Classifier" vs "Property"
- **Design 1**: `val isScreenChanging` on the sealed class.
- **Design 2**: `TURN_TOOL_POLICY.classify(tool)`.
Design 1 is more "Kotlin-idiomatic" (data-centric). Design 2 is more "Enterprise Java" (service-centric). Given the existing codebase uses sealed classes heavily, Design 1 fits the grain of the wood better.

## 5. Verdict & Recommendation

**Winner: Design 1**

It is the superior design because it solves the *actual* problem (arbitration policy blocking useful tools) without inventing a new execution model for a problem we don't have (slow cognitive tools).

**Modification Recommendation**:
Adopt Design 1, but borrow the *clarity* of Design 2's "Completion" logic. Specifically, while `isScreenChanging` is fine, ensure that the `complete_task` logic is as explicit as Design 2's "run last" philosophy, even if implemented sequentially.

The "Cognitive First, Screen Last" ordering in Design 1 is crucial for one subtle reason Design 1 mentioned: Loop Detection. If the screen action isn't the last thing seen, the agent might get confused about what state it's in. Design 1 caught this. Design 2's async model might obscure "what happened last" if not carefully serialized.
