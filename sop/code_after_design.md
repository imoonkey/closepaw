# General Principles
- 拥抱KISS principle，keep it simple stupid. 避免过度设计，避免过度工程化。嵌套层数不要太深。
- 设计和实现high readability的code。
- 设计和实现的过程，不要考虑代码的backward compatibility，最后把陈旧的历史代码可以直接deprecate，我产品还没有release，不需要考虑任何向后兼容。代码质量高，可读性高，只需要反映最新最优的实现，这对我更重要。
- 阅读我已有的代码，确保你的设计跟现有的codebase是aligned。


# Process 1: Design & Plan
1. Read the design doc, or if needed under @doc/todo/[TODO]. Coming up with execution plan.

# Process 2: Phased implementation
At each phase of the execution, repeat the following:

- 2.1 start executing the phase using a new subagent. use /coding-standards  , /tdd  when necessary. test and verify.
- 2.2 Start a new subagent to do an independent /code-review . write your review to the same folder as the design doc.
- 2.3 address the issues mentioned in the code review.
- 2.4 Git commit after every phase finishes.


# Process 3: Verification and Wrap up
Once all implementation done:
3. /visual-debug, run `./scripts/setup.sh && ./scripts/debug-run.sh --basic --vd "play a [fill in some singer] song on youtube"` and check the `debug-output` to make sure it works end to end on at least on case (note sometimes when the run output itself says sucess, it may just failed quitely, check the actual debug-output trace to verify). If the run is not successful, write a review, and go back to Process 2 to implement.

Once visual debug passes:
4. Start a new subagent to do remove any redundant/legacy/dead code. and do /code-simplifier . Ensure code quality.
5. /update-docs. Besides updating doc/{main|dev}, skills/ etc., update the relevant doc/todo/docs with your implementation details.


# Important Notes

- /ultra-think ! This is really important piece for my whole project. Write really really well thought code. Write the code like you are Linus Torvalds.

- Manage your context properly with todo/ subagents/ agent-team. So context window removes unnecessary history when needed. 
    - Ideally, for every todo item, the separation is clear, you work on it with a new subagent or summarize context after each todo item, so previous todo item's history does not contaminate the context. 
    - If you have tools to summarize your context, try to find a point to use it after you have used 50% of your context window. So you always have a less cluttered context.

- Note that your subagents does not use the same model as you, you can consider it less good at deep thinking than you. You only delegate relatively narrowly scoped and clearly defined tasks to them. You do the design and major code writing yourself.

- each of the `/xxx` mentioned above is a SKILL or AGENT, if you cannot interpret it as skill, read .ai-dev/{skills|agents}/xxx/SKILL.md directly.
