# Design Task
阅读 doc/todo/0.01_virtual_display/stage4/qi_note.md for 我的最初想法。doc/todo/0.01_virtual_display/stage4/final_ui_design.md 是我迭代后现阶段的ui设计。现在开始进行这一阶段的系统设计。

- 阅读 doc/todo/0.01_virtual_display/stage4下的所有 system_design* 和 design_review*的system部分。
- 然后再阅读 doc/todo/0.01_virtual_display/stage4/compatibility_faq.md，尤其是关于What is the optimal architecture for Performance + UX? (The Hybrid Model)的讨论。这个可能是之前的design没出现的考量。你帮我想想，有必要的话上网查查api是否可行，然后决定要不要用hybrid mode，我的建议是如果可行就用这个模式。


# General Principles
write the design like if you are Linus Torvalds.
- 拥抱KISS principle，keep it simple stupid. 避免过度设计，避免过度工程化。嵌套层数不要太深。
- 设计high readability的code。
- 设计的过程，不要考虑代码的backward compatibility，最后把陈旧的历史代码可以直接deprecate，我产品还没有release，不需要考虑任何向后兼容。代码质量高，可读性高，只需要反映最新最优的实现，这对我更重要。
- 阅读我已有的代码，确保你的设计跟现有的codebase是aligned。

- 这个feature对我的项目至关重要，请用 /ultra-think 来设计，深思熟虑，考虑周全。 @.ai-dev/skills/ultra-think/SKILL.md

# Note
- 你会把你的设计写到 doc/todo/0.01_virtual_display/stage4 下的 final_system_design.md 下。


# Design Review
Review all the design files in doc/todo/[xxx]/ 。对design进行比较和评审。关注点在于设计本身，而不在writing style。请把你的design_review写到相同folder下的design_review_[your model name, e.g., claude/codex/gemini].md。


# Product/UX Design
下面是一个更专业、但仍然偏你这种“短、硬、可执行”风格的版本（我尽量保留你原来的节奏和措辞感）：

---

# Product / UX Design
Your are the best product manager and ux designer in the world. You design product like Steve Jobs.

## 1) Start from the problem
* For any feature, **state the problem first** (or infer the real one).
* Clarify **who** has the problem, **when/where** it happens, and **why it matters**.
* Define success: what changes in user behavior or outcome if we solve it?

## 2) Design the experience
* Propose the simplest flow that solves the problem end-to-end.
* If opinions/requirements exist, respect them—but prioritize **user value + clarity + speed**.
* Call out key tradeoffs (e.g., power vs simplicity) and choose intentionally.

## 3) Specify interaction precisely
* Describe the flow as a **state machine**: states, transitions, triggers, guards, and side effects.
* For each state: what the user sees, can do, system responses, loading/empty/error.
* Cover edge cases: permissions, latency, retries, cancellation, invalid input, offline, partial failure.

## 4) No broken windows
* No dead ends, no ambiguous states, no “nothing happens” interactions.
* Every button/component must have: purpose, enabled/disabled rules, and feedback.
* Ensure consistency: copy, layout, and behavior match the rest of the product.

------
Now, read doc/todo/0.02_smart_capsule/qi_note.md, doc/todo/0.02_smart_capsule/qi_ui.md, for my thoughts, and do your product/UX design.
- Read doc/todo/0.02_smart_capsule/smart_capsule_design_codex.md, doc/todo/0.02_smart_capsule/smart_capsule_v2_claude.md for some design drafts. Use or ditch them as you see fit.
- You do not need to design any code/implementation yet, but you can and maybe should read the code to better understand the current UX.
- You can read doc/main especially doc/main/ui/ for references.

Write your design to doc/todo/0.02_smart_capsule/ux_design_1.md