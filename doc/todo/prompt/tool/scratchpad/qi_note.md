
### Proposal A: Remove `read` and `list` actions [CRITICAL, Token Savings]
这个反过来，scratchpad prompt默认只list key，不给出value，这样省prompt。
list action可以去掉。

### Proposal B: Reduce `write` output verbosity [HIGH, Token Savings]
这个好，就这么干。

### Proposal C: Smart truncation in context rendering [MEDIUM, Token Savings]
看Proposal A comment: prompt里只show key了。

等等，如果history里的write action的tool_call全保留，那不是本来就在context里面吗？
- notepad其实主要是为了pass around agent是吗？
- 单agent内可以稍微解决long-range可能会forget的问题。不然感觉没什么必要？


### Proposal D: Improve tool description with actionable patterns [HIGH, Success Rate]
可以加到tool description里？但是length确实是加长了，token消耗更多了。


### Proposal E: Reduce MAX_VALUE_LENGTH from 2048 to 500 [LOW, Token Savings]
这个先不改

### Proposal F: Add empty-state nudge in reminder [LOW, Success Rate]
这个直接显示在scratchpad的部分就好了，不必要单独再加reminder。我不太喜欢reminder，不好维护，比较ugly。


# Proposal C 的反思
- 首先，write有没有意义：single ReAct agent里，如果每次screen state都fully保留，那notepad没用！因为之前的状态都在里面存着呢啊！所有写进scratchpad的内容都已经在chat history里了。但是如果history里面的screen内容删掉了，被summarize了，这就有用了。
- 其次，read有没有意义：如果是single Agent，那没意义或意义很小，key->value的写操作都在上面tool_call里了，除非长任务前面被大量压缩了，那后面的有意义。