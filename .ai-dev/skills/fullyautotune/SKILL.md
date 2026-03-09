---
name: fullyautotune
description: Fully automated multi-round `/autotune` loop. Use when you want to keep iterating on a targeted eval set without waiting for human review between rounds.
---

Given a targeted optimization target, e.g., a list of eval tasks to run successfully, run the /autotune process automatically multiple rounds.

# Principles
- **KISS and First-principle thinking.**
- **The goal is always to eventually help real user** and you should not overfit to the eval tasks. Consider this the training dataset, you will be eventually tested on the test dataset (real user tasks). 
- **Minimalism: Maximize the efficiency of every token you put in the prompt.**: Always ask yourself: "Will this change actually help real users, or just the eval tasks?" Question yourself on every addition, or every bit of complexity. 
  - Derivative: Be epsecially careful in adding changes to the core prompt, only generally applicable. App Skill prompts are more flexible and can be more specific, but still should try to cover more potential tasks not in the eval set. 


# Multi-agent collaboration
- For Step 1 implementation, choose one agent (out of claude and codex) to implement the fix, the other to review the changes.
- For Step 4 analysis, follow /double-design for the per-task analysis and summarization, and align on the final summarization. Use /multmux to start a separate agent for your counterpart (e.g., it is claude if you are codex, and vice versa).

# Fully Auto-tune Process
the only open step in the /autotune circle is the review, but it is now relying on the /double-design's alignment, no need to wait for human review. Once aligned, automatically proceed to next round, create a new round_x folder under doc/autotune/round_x. Make sure to document your work, and commits every round (or even multiple commits per round if you have multiple relatively large improvements in one round). If there is currently uncommited work from last round, make sure to commit it before starting the next round.

Know when to stop: You will try multiple things until you improve the performance of the model, or exhausts ways to improve the performance (e.g., a targeted improvement on tasks failed 3 times). Add it to cannot_handle_group.txt if you think it is currently out of reach, and move on to other tasks.