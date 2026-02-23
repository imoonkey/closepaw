# System Task
Given a goal/task (可能是一个product & ux design，也可能是别的), you will conduct system design and implementation.

## TODOs management
Depending on the scope of the Product/UX change. 
- If the product/UX design scope is large, break it down to multiple system design and implementation Stages.
- If the product/UX design scope is reasonable, treat it as one system design & implementation Stage.

For each Stage, generate TODOs for the three steps below.

# System-Step-1: System Design
You are the best software system architect and best AI researcher.

## General Principles
Write the design like if you are Linus Torvalds.
- 拥抱KISS principle，keep it simple stupid. 避免过度设计，避免过度工程化。嵌套层数不要太深。
- 大道至简，我希望我的code是minimal nested layers, minimal redundancy。 如果你能用更简单的逻辑实现同样的功能，do it。如果你能把edge case通过巧妙的设计变成一个canonical case，而不用特殊处理，或者你能类似的简化状态机，do it。
- 设计high readability的code。
- 设计的过程，不要考虑代码的backward compatibility，最后把陈旧的历史代码可以直接deprecate，我产品还没有release，不需要考虑任何向后兼容。代码质量高，可读性高，只需要反映最新最优的实现，这对我更重要。
- 阅读我已有的代码，确保你的设计跟现有的codebase是aligned。

- 这个feature对我的项目至关重要，请用 /ultra-think 来设计实现，深思熟虑，考虑周全。 @.ai-dev/skills/ultra-think/SKILL.md

## Notes
- For system, write into doc/todo/[project]/system/. When you do the design, write your design doc to doc/todo/[project]/system/system_design_[your_model_name].md. Your model name can be codex, claude, gemini or something else to identify yourself.

### General References
- The best starter pointers to understand the overall system is doc/main. The doc can be outdated, always consider code the source-of-truth.
- You should read the code to better understand the current system.


### Project-specific doc references
- Docs about ongoing projects are typically organized under doc/todo/[project]/*. 
- Under the folder, qi_*.md, master_*.md are master-given inputs that you should respect. Or in other docs, master inputs are marked with *qi note* or *master note*. You should ALWAYS read these master notes, and treat them seriously (though it may not mean literally).
- Caveat: If there are design docs (system_design_*.md) or system_design_review_*.md, you will not read those first, and do your design independently. But afer you do your design, you can read them and incorporate their strengths to improve your design.

## Design Review
Review your own design docs，确保UI design全部被cover了。如果存在gap，则iterate system design -> design review的loop。


## Master-Interaction Process
The first line of your design doc will be status: xxx. There are a few status
- draft
- reviewed
- approved

We will use this to guide the design iterate process:
- [draft] When you first write the design, mark it as draft status. 
    - Then you wait: start using sop/check_review.sh to check its status, monitor its status change, until it changes to reviewed or approved.
        - If the master specified that we are going YOLO mode, you can directly proceed to next step System Implementation, no need to wait.
- Master will review it, and mark it reviewed or approved. Once you detected the status change with the script, go to correponding steps below.
- [reviewed] If a design doc is marked as reviewed, read the review note added to the doc, and iterate on your design to address those review notes. Then you mark the doc as draft status and go wait for master review again.
- [approved] If a design doc is marked as approved. It may still have notes, address those notes, and procced to UX-Step-2.


# System-Step-2: System Implementation
The next step is to implement the system design by doing code implementation. You will refer to sop/code_work.md for procedures.

# System-Step-3: System Verification
Review all the code changes, verify if 
1. has the system design has been FULLY implemented?
2. has the ORIGINAL system goal/task specified by the master been fully designed and implemented.

If some implementation is incomplete.
- For small local fixes, create one todo item for them all, directly fix them (as a small task, following procedures in sop/code_work.md).
- For the gaps:
    - DO ANOTHER ROUND OF THIS DOC's PROCESS, create TODO items for all three steps in this doc to close the gap.
    - If there is only design gap, then you can skip System-Step-1.

DO NOT STOP UNTIL YOU FULLY IMPLEMENT THE TARGETED SCOPE!