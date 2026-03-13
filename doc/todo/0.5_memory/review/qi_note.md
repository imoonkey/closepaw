# Goal
给每个claws的memory系统写一个summary。包含
1. product层面：
 - long-term memory分几类，如何结构化。如是否有daily memory (append only), user memory, soul/agent persona memory, project/task memory. 
 - is every memory type just a holistic file? or what is the structure/semi-structure for each type here.
2. system层面：
 - overall architecture, 
 - storage/indexing solution, 
 - write methods, retrieval methods
 - memory 写入时间: 是通过tool_call让agent主动，还是session执行中在某个点自动写入；还是有单独cron job定期读取所有session history，来总结写入memory的，等等。
3. lifecycle层面：
 - eviction/cap策略: memory满了怎么办，有没有条目上限
 - dedup/consolidation: 重复或矛盾的memory怎么处理 (如mem0的ADD/UPDATE/DELETE模型)
 - temporal decay: 旧memory是否衰减/过期 (如OpenClaw的30天半衰期)
4. injection层面：
 - token budget: 每次turn/session注入多少memory，怎么控制量 (如我们的elastic budget 6KB)
 - progressive loading: 是否有L0/L1/L2分级加载 (如OpenViking的title→summary→full)
 - scoping: memory按什么维度隔离注入 (per-app, per-user, per-device, per-session)
5. 抽象层面：
 - reflection/synthesis: 原始fact是否被提炼成更高层知识 (如OpenClaw的Retain→Recall→Reflect三阶段)
 - working memory ↔ long-term memory: scratchpad和持久store之间的流转关系 (如Letta的core/archival split)

# Task
1. For every repo in .reference/claws and .reference/mem: Delegate to separate agents to write the analysis for each repo to doc/todo/0.5_memory/review/per_repo/*_memory_<yourname:claude|codex>.md. Focus on thing that matters.
2. Then write a summary to doc/todo/0.5_memory/review/memory_<yourname:claude|codex>.md, summarzing common aspects, and then comment on unique aspects of each. 
3. 跟我来讨论一下，我的memory(doc/main/agent/memory.md) 跟 它们的memory处理有什么不同。

Think and research in English, but do final writing in Chinese. Do it independently, don't look at another agent's analysis (e.g., claude's analysis if you are codex).