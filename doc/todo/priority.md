0. 在autotune R36-38，我们做了一些prompt generalization的工作，我想在现在的基础上，continue my /prompt-tune: two targets: 1. system prompt, app skills, tool description 需要都覆盖到。目标是在maximize prompt generalization, minimize prompt tokens的同时，提升agent的表现。2. 评估指标：task success rate, always-on token count (system prompt + tool description), and total/average app skill token count. The process should be iterative, not always in one direction. 因为现在整体偏less generalizable, more verbose，我们可以先尝试删除，然后再把必须加的加上(in a better way)，加的过程中遵循简单structure，具体structure可以改。删多了再加，加多了再删。直到取得最佳平衡。

1. memory (user_profile.md device_info.md, app_memory.md 不分workflow/pitfall等等？)
 - 似乎常见是persistent memory + daily log memory。
 模式	数量	Repos
仅持久事实	5	hermes-agent, letta, lettabot, nanobot, zeroclaw
仅日志	0	(无)
两者都有	7	CoPaw, ironclaw, leon, mimiclaw, nextclaw, openclaw, picoclaw
都不用 / 其他方案	12	ClawX, LobsterAI, MemOS-claw, MemOS, OpenViking, PageIndex, Second-Me, mem0, memU, nano-claw, poco-agent, supermemory

 - 不该分workflow/pitfall，应该分scope（user/app）和kind（fact/preference/event/note/summary/knowledge/task)


2. session 管理 check
3. 安全，权限管理，tool approval
4. login auth (openclaw 怎么用的openai auth而不走api key?参考一下)
5. 想想release还差啥。
6. run tasks on 60 common apps. 构建60个app的skills。
8.
7. 定时任务。