# KISS Design Review v3: Memory & Skills in Settings

> Review target: `projects/active/memory_skills_settings/design_claude.md`
> Reviewer: Codex
> Date: 2026-05-17

## Verdict

**CHANGES_REQUESTED, but close.**

v4 address 了 v2 里大部分真正的问题：append rules 写实了，AppAccessContentIndex 对了，bounded editor 对了，Agent Skill 改成 next-session 是 KISS，ToolSettingsCard 的 info slot 也对。

剩下的不是“大架构”问题，是 single-writer 这根承重梁还没锁死。你不能一边删 revision/hash/shared monitor，一边把 “session-active source TBD” 留给实现时随缘。这个 gate 如果不精确定义，数据丢失 race 还在。

## Findings

### High: single-writer 还没有闭合，除非先定义一个全局 edit gate

设计说 “agent session active → editor view-only”，但 active source 还是 open question，并且文案混用了 “session active” 和 “agent is running”。这不够。

当前代码里 session 有 `Created / Running / Idle / TakeoverPending / Paused / Shutdown`。agent append 只发生在任务执行期间，但任务可以从 `Created` 或 hot `Idle` 立刻转 `Running`，而 Settings save 是异步 IO。只靠 Compose 按钮 disabled，会有 TOCTOU：

1. editor 看到 inactive，用户点 Save；
2. save coroutine 准备写文件；
3. 用户/overlay/auto-start 创建或恢复 session；
4. session append 和 Settings whole-file write 用两个 `MemoryStore` instance 写同一个文件；
5. 最后写入者赢，另一个写入被吃掉。

这正是你想通过 single-writer 删除的 race。

Fix:

- 定义一个 app-wide `MemoryEditGate` 或等价 `StateFlow<Boolean>`，不是 “implementation time decided”。
- `locked = any session exists and state != Shutdown OR session creation/reload is in progress`。如果你只 lock `Running`，就没有 single-writer。
- Save / Delete / `+ Memory` 不能只靠 UI disabled；action handler 在调用 `MemoryStore.write/delete` 前必须同步 re-check gate，active 就 abort。
- 如果你想允许 hot `Idle` 时编辑，那就必须让 session start 和 memory write 共享一个 gate/mutex。那已经不是这版 KISS 了。KISS 选择是：有 open session 就 view-only，停掉 session 再编辑。
- banner 文案别写 “Agent is running”。写 “Session is open. Stop the session to edit memory.” 否则 Idle/Paused 被锁时 UI 会说谎。

这个可以塞进 `memory_file_editor` task，不一定要新增第 9 个 task。但 acceptance criteria 必须把 action-time gate check 和 session-creation-in-progress 覆盖写进去。

### Medium: 同一个文件可能有两个 Settings editor buffer

App Access bounded editor 有 “↗ Open full editor”。如果 full editor 是同一文件的第二个 independent buffer，用户可以在 inline 和 full-screen 两边交替保存，自己和自己 race。single-writer 只讨论了 Settings vs agent，没有讨论 Settings vs Settings。

Fix:

- “Open full editor” 必须 move/share 同一个 editor state，或者打开 full editor 时 inline editor退出 EDIT/丢弃临时 buffer。
- 最简单：bounded inline 只做 quick edit；点 “Open” 前如果 dirty，先要求 Save/Discard，full page 重新 load 文件。

### Medium: append result contract 和 “RememberExperienceTool unchanged” 冲突

Append rules 里说 oversize 返回 `AppendResult.TooLarge`，但 component table 又说 `RememberExperienceTool.kt` unchanged and still calls `append`。当前 tool 期待 Boolean。你不能同时改变返回类型又不改 caller。

Fix:

- 要么 `append()` 继续返回 `Boolean`，TooLarge 只 log false。
- 要么引入 `AppendResult`，那 `RememberExperienceTool` 和 auto-retain path 都必须更新，tool 返回明确 failure，比如 “memory file too large”。

我建议用 `AppendResult`，因为 over-cap 是可解释失败，不该伪装成 generic “Failed to save memory.”

### Low: sanitation rule 顺序有个小坑

Rule 1 写 “strip control chars, fold newlines”。`\n` / `\r` 本身就是 control chars。先 strip 就没东西可 fold，`foo\nbar` 会变成 `foobar`，不是 `foo bar`。

Fix:

- 先把 `\r\n|\r|\n|\t` 这类 whitespace 归一成空格，再 strip 剩余 control chars，再 collapse whitespace。

### Low: 8 KB cap 要明确 bytes 还是 chars

文档说 8 KB per file，但 tests 可能按 `String.length` 写，文件写入按 UTF-8 bytes 可能超。Prompt budget 更接近 chars/token，filesystem cap 更接近 bytes。

Pick one. KISS：用 UTF-8 bytes for file cap，error 文案仍然讲 prompt input。

### Low: Agent Skill UI discovery 要保证 first-run 不空

Agent skills 目前是 `SessionServices.create()` 时从 assets install 到 `filesDir/skills`。如果用户第一次打开 Settings → Agent Behavior 时还没启动过 session，`AgentSkillCatalog.allDiscovered()` 扫 filesDir 可能是空的。

Fix:

- UI discovery path either invokes the same bundled installer before scanning, or scans bundled assets directly.
- 不要让 “没有 session 之前没有 Agent Skill toggle” 变成隐性行为。

## Asked Questions

### single-writer model 是否真的闭合？

**还没有。** 思路是对的，但必须补全局 gate：

- lock all non-shutdown sessions, not just Running;
- include session creation/reload in progress;
- write/delete/+Memory action-time re-check;
- include service-owned sessions, not only `ChatViewModel`'s currently attached session.

补完这四条后，single-writer 就能替代 `MemoryStoreHolder` / revision hash / mtime guard。

### append insertion rules 还有遗漏吗？

大体 OK。还要修：

- sanitation 顺序；
- `AppendResult` vs Boolean caller contract；
- 8 KB bytes/chars 定义。

Heading missing / duplicate last-wins / free text preservation / over-cap no partial write 都已经 address。

### v2 High/Medium 是否都 address 或 deferred？

基本是：

- Agent Skill live toggle: address，改 next-session，清楚且 KISS。
- disabled skill 从 UI 消失: address，UI 用 `allDiscovered()`。
- `MemoryStoreHolder` / `writtenThisSession`: deferred by single-writer，前提是 gate 修好。
- mtime/hash conflict: deferred by single-writer，前提同上。
- append vague rules: address。
- App Access long editor: address。
- per-row IO: address。
- invisible expansion: address。
- blank/over-cap recall: address。
- ToolSettingsCard click ambiguity: address。

唯一没真正 address 的就是 single-writer 的可信 active source。文档最后还把它列成 open question，这不能留到 implementation freestyle。

### 8 个任务是否合理？

合理。不要再拆大了。

但要改两个 acceptance criteria：

- `memory_file_editor`: add explicit `MemoryEditGate` / session-active source, action-time re-check, and creation-in-progress coverage.
- `memory_store_raw_refactor`: resolve `AppendResult` contract and sanitation order.

`agent_skill_disable_core` / `agent_skill_toggle_ui` 拆得对；`app_access_content_index` / `app_access_per_app_expansion` 拆得也对。

## Test Additions

Add these, then this is implementable:

- Save clicked while session starts before IO write: write aborts, file unchanged.
- Hot Idle session exists: editor locked, or if you choose otherwise, prove session start cannot race save.
- Service-owned session running while MainActivity settings is open: editor locked.
- `+ Memory` action races session creation: create aborts.
- Inline editor dirty then Open full editor: no stale double-buffer save.
- `append("foo\n## Pitfalls\nbar")` writes one bullet body without a heading injection and with readable spacing.
- Oversize append returns the documented result and `RememberExperienceTool` surfaces the right failure.

## Bottom Line

v4 is the right shape. Fix the edit gate and the small append contract nits. After that: **LGTM**.
