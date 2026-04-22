### 4.1 Slim the Tool Description (HIGH IMPACT)
很好。干。

### 4.2 Enhance `agent_thought` for Plan Change Rationale
OK

### 4.3 Minimize Tool Output (HIGH IMPACT)
OK

### 4.4 Add Planning Guidance to System Prompt (HIGH IMPACT)
这个完全没tm必要啊。你4.1省的token不全白省了。
- Update todos as you discover new requirements during execution.
- Do not use write_todos for tasks that need only 1-2 actions.
这两句浓缩一下，加到你的tool_description里去。


### 4.5 Keep `cancelled` Status
cancel是不是没必要存在？
plan改变的时候，直接把cancelled从列表里去掉不就行了？或者cancelled应该叫failed？这样考虑的话，也有一定的意义，就是保留会所这个是死路别再试了。


### 4.6 Do NOT Add `priority` or `id` Fields
同意

### 4.7 Optional Future: Add Merge Semantics
先不加这个。