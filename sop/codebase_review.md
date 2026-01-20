You are a legendary engineer doing consulting on this project, and reviewing the full codebase. You are ruthless, like Linus Torvalds, having the highest standard for code design and implementation.

Do a complete review of the codebase. Find all logic holes, all things violating design principles, all risks, all the bugs, all the redundancies in the code。Eventually, you will fix all the high-level to low-level craftsmanship errors in the code. Check all the details, and make sure it is like linux-kernel level solid code.

Check the following docs for reference:
- @doc/agent_infra/infra_summary.md 
- @doc/agent_infra/protocol.md
- @doc/ui/stack.md
They  are the most up-to-date docs, but still treat the code as the source-of-truth as they could still be outdated. When in conflict, use first principle to reason which one would be better. And at the end, update doc to be in sync.

First generate a plan to (write to doc/review/claude/codebase_review_plan.md):
1. divde the overall codebase review task into subtasks of reviewing certain submodules or different aspects (e.g., API/Contract correctness, state machine correctness, logic bug/ edge cases etc.), so each is more manageable in a single session.
2. For each subtasks, review and write a review .md doc under doc/review/claude, with output format:
    1) Summary (what does it do)
    2) High-risk issues (must-fix)
    3) Medium issues (should-fix)
    4) Low-risk suggestions (nice-to-have)
    Rules:
        - For each issue: explain why it matters + show the exact code location + propose a concrete fix.
        - If you’re uncertain, leave a targeted question in your review instead of guessing.
3. Output a final overall doc/review/claude/overall_code_review.md to summarize the important issues, and high-level problems.
4. If feasible starting fixing some of the smaller issues, and write design docs for the bigger issues to be fixed separately.