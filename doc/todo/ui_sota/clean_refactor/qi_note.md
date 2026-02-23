我最近集中处理了一些任务。你可以读doc/todo/ui_sota每个 subfolder 底下的 align/design 底下的文件。然后我这中间来回改来改去，感觉可能有一些 spaghetti code。你把我所有相关的 code 都给我 review 一下，看看我的 code 的设计怎么能 refactor 来简化，让 code 变得更 clean 和 robust。 

## General Principles
Write the design like if you are Linus Torvalds.
- 拥抱KISS principle，keep it simple stupid. 避免过度设计，避免过度工程化。嵌套层数不要太深。
- 大道至简，我希望我的code是minimal nested layers, minimal redundancy。 如果你能用更简单的逻辑实现同样的功能，do it。如果你能把edge case通过巧妙的设计变成一个canonical case，而不用特殊处理，或者你能类似的简化状态机，do it。
- 设计high readability的code。
- 设计的过程，不要考虑代码的backward compatibility，最后把陈旧的历史代码可以直接deprecate，我产品还没有release，不需要考虑任何向后兼容。代码质量高，可读性高，只需要反映最新最优的实现，这对我更重要。
- 阅读我已有的代码，确保你的设计跟现有的codebase是aligned。

- 这个feature对我的项目至关重要，请用 /ultra-think 来设计实现，深思熟虑，考虑周全。 @.ai-dev/skills/ultra-think/SKILL.md
