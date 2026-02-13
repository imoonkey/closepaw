# Product/UX Work
Given a goal/task, you will conduct product & ux design and implementation.

## TODOs management
For a product work task, generate TODOs for the three steps below.

# UX-Step-1: Product/UX Design
Your are the best product designer and ux designer in the world. You design product like Steve Jobs.

## General Principles
### 1) Start from the problem
* For any feature, **state the problem first** (or infer the real one).
* Clarify **who** has the problem, **when/where** it happens, and **why it matters**.
* Define success: what changes in user behavior or outcome if we solve it?

### 2) Design the experience
* Propose the simplest flow that solves the problem end-to-end.
* If opinions/requirements exist, respect them—but prioritize **user value + clarity + speed**.
* Call out key tradeoffs (e.g., power vs simplicity) and choose intentionally.

### 3) Specify interaction precisely
* Describe the flow as a **state machine**: states, transitions, triggers, guards, and side effects.
* For each state: what the user sees, can do, system responses, loading/empty/error.
* Cover edge cases: permissions, latency, retries, cancellation, invalid input, offline, partial failure.

### 4) No broken windows
* No dead ends, no ambiguous states, no “nothing happens” interactions.
* Every button/component must have: purpose, enabled/disabled rules, and feedback.
* Ensure consistency: copy, layout, and behavior match the rest of the product.

### Treat with high importance
- 这个feature对我的项目至关重要，请用 /ultra-think 来设计实现，深思熟虑，考虑周全。 @.ai-dev/skills/ultra-think/SKILL.md

## Notes
- For product/ux, write into doc/todo/[project]/ux/. When you do the design, write your design doc to doc/todo/[project]/ux/ux_design_[your_model_name].md. Your model name can be codex, claude, gemini or something else to identify yourself.

### General References
- You do not need to design any code/system implementation yet, but you can and maybe should read the code to better understand the current UX.
- The best starter pointers to understand the overall product is doc/main. For UX especially doc/main/ui. The doc can be outdated, always consider code the source-of-truth.

### Project-specific doc references
- Docs about ongoing projects are typically organized under doc/todo/[project]/*. 
- Under the folder, qi_*.md, master_*.md are master-given inputs that you should respect. Or in other docs, master inputs are marked with *qi note* or *master note*. You should ALWAYS read these master notes, and treat them seriously (though it may not mean literally).
- Caveat: If there are design docs (ux_design_*.md) or ux_design_review_*.md, you will not read those first, and do your design independently. But afer you do your design, you can read them and incorporate their strengths to improve your design.


## Design Review
Review your own design docs，确保original specified goal/task全部被cover了。如果存在gap，则iterate product/ux design -> design review的loop。


## Master-Interaction Process
The first line of your design doc will be status: xxx. There are a few status
- draft
- reviewed
- approved

We will use this to guide the design iterate process:
- [draft] When you first write the design, mark it as draft status. 
    - Then you wait: start using sop/check_review.sh to check its status, monitor its status change, until it changes to reviewed or approved.
        - If the master specified that we are going YOLO mode, you can directly proceed to next step Product/UX Implementation, no need to wait.
- Master will review it, and mark it reviewed or approved. Once you detected the status change with the script, go to correponding steps below.
- [reviewed] If a design doc is marked as reviewed, read the review note added to the doc, and iterate on your design to address those review notes. Then you mark the doc as draft status and go wait for master review again.
- [approved] If a design doc is marked as approved. It may still have notes, address those notes, and procced to UX-Step-2.


# UX-Step-2: Product/UX Implementation
The next step is to implement the Product/UX design by doing system design and code implementation. You will refer to sop/system_work.md for procedures.


# UX-Step-3: Product/UX Verification
Review all the code changes, verify if 
1. has the design has been FULLY implemented?
2. has the ORIGINAL product/UX goal/task specified by the master been fully designed and implemented.

If some implementation is incomplete.
- For small local fixes, create one todo item for them all, directly fix them (as a small task, following procedures in sop/system_work.md).
- For the gaps:
    - create TODO items for all three steps in this doc to close the gap.
    - If there is only design gap, then you can skip UX-Step-1.

DO NOT STOP UNTIL YOU FULLY IMPLEMENT THE TARGETED SCOPE!