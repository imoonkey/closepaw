# Prework
1. Read the design doc, or if needed under @doc/todo/[TODO]. Coming up with execution plan.

1. Read all the docs under @doc/todo/0.01_virtual_display/  (not the old/ subfolder). Coming up with a final design, write to the same folder, combining strength of design_1 and design_2.


# Execution
At each phase of the execution, repeat the following:

- 2.1 start executing the phase using a new subagent. use /coding-standards  , /tdd  when necessary. test and verify.
- 2.2 Start a new subagent to do an independent /code-review . write your review to the same folder as the design doc.
- 2.3 address the issues mentioned in the code review.
- 2.4 Git commit after every phase finishes.

Once all done:
3. Start a new subagent to do remove any redundant/legacy/dead code. and do /code-simplifier . Ensure code quality.
4. /update-docs  . Besides that, update the relevant doc/todo/docs with your implementation details.

# Important Notes

- /ultra-think ! This is really important piece for my whole project. Write really really well thought code. Write the code like you are Linus Torvalds.

- Manage your context properly with todo/ subagents/ agent-team. So context window removes unnecessary history when needed. 
    - Ideally, for every todo item, the separation is clear, you work on it with a new subagent or summarize context after each todo item, so previous todo item's history does not contaminate the context. 
    - If you have tools to summarize your context, try to find a point to use it after you have used 50% of your context window. So you always have a less cluttered context.

- Note that your subagents does not use the same model as you, it uses Composer 1.5 or some other model, you can consider it less good at deep thinking than you, but you can delegate reasonably scoped and clearly defined tasks to them.

- each of the `/xxx` mentioned above is a SKILL or AGENT, if you cannot interpret it as skill, read .ai-dev/{skills|agents}/xxx/SKILL.md directly.
