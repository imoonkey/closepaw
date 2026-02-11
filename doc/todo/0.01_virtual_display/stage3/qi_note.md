为了实现 doc/todo/0.01_virtual_display/qi_note.md，我现在 doc/todo/0.01_virtual_display/stage1/final_design.md 和 doc/todo/0.01_virtual_display/stage2/review_summary_stage2_design.md 都实现完了。

现在有个未完成的任务：doc/todo/0.01_virtual_display/stage2/platform_reorg_plan.md 还差一点。Virtual Display 侧的重构目标（Definition of Done 前两条）已满足。但 "AccessibilityPlatform的部分还没有refactor。


帮我看VirtualDisplayPlatform和AccessibilityPlatform的code，
1. 看看怎么refactoring，能减少spaghetti code，两边怎么能做aligned。
2. 两边有一些code是不是该share来减少duplicates？



write the design like if you are Linus Torvalds.
- 拥抱KISS principle，keep it simple stupid. 避免过度设计，避免过度工程化。嵌套层数不要太深。
- 设计high readability的code。
- 设计的过程，不要考虑代码的backward compatibility，最后把陈旧的历史代码可以直接deprecate，我产品还没有release，不需要考虑任何向后兼容。代码质量高，可读性高，只需要反映最新最优的实现，这对我更重要。
- 阅读我已有的代码，确保你的设计跟现有的codebase是aligned。

- 这个feature对我的项目至关重要，请用 /ultra-think 来设计，深思熟虑，考虑周全。 @.ai-dev/skills/ultra-think/SKILL.md

- 你会把你的设计写到 stage3/ 下的 refactor_design_[your model name, e.g., claude/codex/gemini].md 下。写作过程中你不会参考这个folder下别的design，独立思考。