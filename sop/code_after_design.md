# Prework
1. Read everything under @doc/todo/[TODO]. Coming up with execution plan.

# Execution
At each phase of the execution, repeat the following:

- 2.1 start executing the phase using a new subagent. use /coding-standards , /tdd when necessary. test and verify.
- 2.2 Start a new subagent to do /code-review. write your review to the same folder as the design doc.
- 2.3 Start a new subagent to do remove any redundant/dead code. and do /code-simplifier . Ensure code quality.
- 2.4 Git commit after every phase finishes.
- 2.5 /update-doc, and update the relevant doc/todo/docs with your implementation details.

# Important Notes

- /ultra-think ! This is really important piece for my whole project. Write really really well thought code. Like you are Linus Torvalds.

- Manage your context properly with todo/ subagents/ agent-team. So context window removes unnecessary history when needed. 
    - Ideally, for every todo item, the separation is clear, you work on it with a new subagent, so previous todo item's history does not contaminate the context window. 
    - If you have tools to compact your context, try to find a point to use it when you have used 50% of your context window.

