# KISS Design Review v2: Memory & Skills in Settings

> Review target: `projects/active/memory_skills_settings/design_claude.md`
> Reviewer: Codex
> Date: 2026-05-17

## Verdict

**CHANGES_REQUESTED.**

这版比 v1 干净很多。App Access 内联、free-text memory、Agent Skill toggle、砍独立 Skills 页面，这些方向都对。尤其删除 canonicalizer，方向是对的：用户编辑的是 prompt 输入文件，系统就别在背后偷偷吃文本。

但别自欺欺人：当前设计还有几个硬 bug。`mtime` 不是可靠冲突锁；`Agent Skill toggle next turn 生效` 按现在代码是假的；`MemoryStoreHolder` 会把 session-local `writtenThisSession` 污染成 app-global；App Access 的长 editor 和 chip 索引不能靠一句 “LazyColumn fine” 糊过去。

## Plan Summary

目标是让 Settings 支持：

- raw memory 文件查看、编辑、删除；
- App Access 里管理 per-app tier + memory + app skill；
- Agent Behavior -> Tools 里开关 Agent Skills；
- 不再维护 MemoryStore canonicalizer。

成功标准不是“页面能打开”。成功标准是：用户保存的字节会原样进入 recall，agent append 不会写错 section，运行中的 session 不会被 stale catalog 或 stale buffer 搞坏。

## What Already Exists

- `MemoryStore` 现在是 canonical document store：`readDocument()` 会 `loadSections()` 再 `buildCanonicalDocument()`，并可能在 read 时重写文件。见 `app/src/main/kotlin/ai/closepaw/memory/MemoryStore.kt:144`。
- `SessionServices.create()` 每次 session new 一个 `MemoryStore`，不是 app singleton。见 `app/src/main/kotlin/ai/closepaw/session/SessionServices.kt:200`。
- `MemoryStore` 里有 session 状态：`writtenThisSession`。`Agent` 用它决定失败时是否 auto-retain app memory。见 `app/src/main/kotlin/ai/closepaw/memory/MemoryStore.kt:38` 和 `app/src/main/kotlin/ai/closepaw/agent/Agent.kt:142`。
- `AgentSkillManager` 现在构造时固定一个 `AgentSkillCatalog`，`catalogPrompt()` 只是读这个固定 catalog。见 `app/src/main/kotlin/ai/closepaw/agent/cognition/skills/AgentSkillManager.kt:15`。
- `TurnPlanningPhaseRunner` 每 turn 调 `services.agentSkillManager.catalogPrompt()`，但这不等于重新扫描或重新过滤 Settings。见 `app/src/main/kotlin/ai/closepaw/agent/TurnPlanningPhaseRunner.kt:81`。
- `AppAccessSettingsPage` 当前只在 IO 里加载 installed apps；row 本身没有 memory/app-skill 索引。见 `app/src/main/kotlin/ai/closepaw/ui/settings/AppAccessSettingsPage.kt:74`。

## Critical

None.

## High

1. **Agent Skill toggle 的 “next turn 生效” 现在是假的。**

   设计说 catalog next turn re-filter，因为 `TurnPlanningPhaseRunner` 每次重建 prompt。错。runner 每 turn 调的是同一个 `AgentSkillManager.catalogPrompt()`；manager 里的 catalog 是 session 创建时构造的固定对象。只把 disabled set 写进 `AppSettingsStore`，运行中 session 不会自动感知。

   更糟的是，设计自己前后矛盾：组件表说 `AgentSkillToggleRows.kt` loop `AgentSkillCatalog.all()`，但 `AgentSkillCatalog.all()` 又计划过滤 disabled。那 disabled skill 会从 UI 消失，用户拿什么再打开？

   Fix:
   - `AgentSkillCatalog` 保持纯 discovery：`allDiscovered()` 永远返回磁盘上合法 skill。
   - `AgentSkillManager` 接收 `disabledNamesProvider: () -> Set<String>` 或 `AppSettingsStore`，并在 `catalogPrompt()`、`activate()`、`activateExplicitMentions()` 每次调用时过滤。
   - `ActivateSkillTool` 不要自己猜状态；它应该走 manager 的同一套 gate，返回明确的 disabled failure。
   - UI toggle rows 必须基于 `allDiscovered()` 渲染，checked = `name !in disabledSet`。
   - 文档要说清楚：toggle 只能阻止之后的 catalog/activation；当前 turn 已返回给模型的 skill body、历史里的 tool result，不会魔法消失。

2. **`MemoryStoreHolder` 会把 session 状态变成全局状态。**

   app-scoped singleton 解决同一进程里 `@Synchronized` 不是同一把锁的问题，这个方向对。但当前 `MemoryStore` 不只是文件 store，它还带着 `writtenThisSession`。一旦变 singleton，任何上一个 session 的 append、甚至 Settings 写入如果你顺手 set 了 flag，都会影响下一个 session 的 auto-retain fallback。

   Fix:
   - `MemoryStore` 只能负责文件 IO 和 monitor。
   - `writtenThisSession` 从 `MemoryStore` 挪到 session-local tracker，比如 `SessionMemoryWriteTracker`。
   - `RememberExperienceTool` / auto-retain path 成功 append 后标记 tracker。
   - Settings editor 写文件不能污染 session-local “agent 是否写过 memory”。

3. **`mtime` 不是可靠冲突检测。**

   设计把 `expectedMtime` 当 stale-buffer guard。这个太薄。文件系统 timestamp 粒度、同毫秒连续写、rename 后 metadata 行为，都可能让 “agent 写了，用户再保存” 没被发现。你们要防的是 silent overwrite；那就别用一个可能碰巧相等的 timestamp 当真相。

   Fix:
   - editor open 返回 `MemoryDocumentSnapshot(content, revision)`。
   - `revision` 至少是 `lastModified + size + sha256(content)`，更简单也可以直接传 `expectedContentHash`。
   - `write()` 在同一个 `@Synchronized` block 里重新读取当前文件，比较 revision，再写 tmp + rename。
   - `append()` 和 `write()` 都必须在同一把 monitor 下执行完整 read-modify-write。

4. **删 canonicalizer 可以，但 `append` 算法必须写死，不许“聪明”。**

   删除 `loadSections/buildCanonicalDocument` 是正确方向；raw memory 就应该 raw recall。问题是设计只说 “ensure heading exists, append timestamped bullet”。这不是算法，是愿望。

   必须定义并测试这些规则：
   - 新文件：创建 title + scope 默认 section skeleton，再把 entry 放进目标 section。
   - heading 存在：只匹配精确 `## ${section.heading}`，把 bullet 插到该 heading 后、下一个 `## ` heading 前。
   - heading 不存在：在 EOF 追加一个新的 `## ${section.heading}` block。
   - heading 重复：选第一个或最后一个都行，但必须固定、写测试、打 log。个人建议选最后一个，最符合“用户最近手写的 section”直觉。
   - content 来自 tool input，仍然要单行化：trim、去控制字符、折叠换行，避免 LLM 用 `content` 注入新 heading。删除 legacy prefix migration，不等于删除 append input sanitation。
   - 保留 2000-char per-entry cap；新增 8KB per-file cap。别把 `maxContentLength` 偷换成 file cap。

## Medium

1. **App Access 长 editor 不能直接撑高 LazyColumn row。**

   “Long memory makes the row tall — fine, it's a LazyColumn” 这句话不合格。8KB TextField 放进 lazy item，焦点、键盘、selection、inner/outer scroll 都会变难用。默认折叠只能保护列表浏览，保护不了展开后的编辑体验。

   Fix:
   - 嵌入 App Access 的 `MemoryFileEditor` 必须有 `heightIn(max = ...)`，内部滚动。
   - 展开区只给轻量 editor；如果要 full-height 编辑，再从 row 打开同一个 editor sheet/page。用户要求 inline，不等于要无限高 inline。
   - Compose test 覆盖：展开一个接近 8KB 的 memory，tier selector 不被挤没，TextField 可编辑，键盘弹出时 Save/Discard 仍可达。

2. **App Access 需要 content index，不能每个 row 自己读文件/asset。**

   summary chips 要知道 package 是否有 memory / app skill。当前页面只加载 installed apps。按 row `produceState` 去查 `filesDir` 和 `AssetManager.open()`，就是把 IO 分散进列表重组里。

   Fix:
   - 新增一个小的 `AppAccessContentIndex`：一次 IO 读 `MemoryStore.listAppPackages()` 和 `assets.list("app_skills")`。
   - index 作为 `AppAccessSettingsPage` state 传给 rows。
   - memory save/delete 后只更新对应 package 的 index entry。
   - app skill chip 只对合法、可 parse 的 `SKILL.md` 显示；别只因为目录存在就显示。

3. **“row identical” 和 “所有 app 都能 + Add memory” 互相打架。**

   设计说默认 collapsed row looks identical；又说没有 memory/skill 的 app 也在 expansion 里显示 `+ Add memory`。那用户怎么展开一个没有 affordance 的 row？如果每个 row 都有 expand toggle，就不是 identical。

   Fix:
   - 选一个，不要两头占。
   - KISS 方案：有 memory/skill 的 row 显示 expand affordance；没有的 row 显示一个很小的 `+ Memory` action chip。点它创建空文件并展开。
   - 别把 invisible expansion 当 feature。

4. **空文件 recall 语义要明确。**

   `+ Add memory` 会创建空 app file。`read()` 如果直接返回空字符串，`MemoryRecaller` 可能生成只有 `## Recalled Memory` 但没有内容的 prompt block。小 bug，但蠢。

   Fix:
   - `read()` 可以 raw return empty for editor。
   - `MemoryRecaller` 应该只 add `read()?.takeIf { it.isNotBlank() }`。
   - 8KB hard cap 要在 `write()`、`append()`、recall 入口都考虑：旧文件如果已经超限，别原样塞进 prompt。

5. **`ToolSettingsCard` 不支持“只点标题打开 viewer”。**

   现有 `ToolSettingsCard` 的 `onRowClick` 是整张 card clickable，右侧还有 `Switch`。Agent Skill row 如果用它做 “tap title/subtitle -> bottom sheet”，很容易变成点 switch 也触发行点击，或者无障碍语义混乱。

   Fix:
   - 给 Agent Skill row 做专用 row，或者给 `ToolSettingsCard` 增加 trailing info icon / content-click slot。
   - sketch 里已经有 `ⓘ`，就用真实 IconButton，不要让整张 toggle card 同时承担 viewer navigation。

6. **任务拆分基本对，但还有两个 scope 包太胖。**

   `agent_skill_toggles` 同时改 persistence、manager gate、tool gate、UI、tests。这里至少拆成：
   - `agent_skill_disable_core`: `AppSettingsStore` + `AgentSkillManager` dynamic filtering + `ActivateSkillTool` tests。
   - `agent_skill_toggle_ui`: rows + viewer + compose tests。

   `app_access_per_app_expansion` 也太胖，缺一个前置：
   - `app_access_content_index`: memory/app-skill existence indexing and refresh semantics。
   - 然后再做 row expansion。

## Low

1. **Drop legacy migration 是可以的。**

   `user_prefs.md` migration 和 inline `[fact]` prefix normalization 都是 pre-release 历史包袱。按项目规则，不要背。但 append input sanitation 不是 legacy migration，别一起删。

2. **Blocked app 决策现在说清楚了。**

   UI edit 是 explicit consent，允许写 blocked app memory + warning chip，这个可以。warning 文案要直说：Reject 只限制 agent/tool 写入，不限制用户手动保存；保存后仍会被 recall。

3. **Settings IA 可以按设计走。**

   Voice 并入 Behavior、Memory 行放 Behavior，没有问题。别再开独立 Skills 页面。

## Answers To The Asked Questions

**删 canonicalizer 是否安全？**

安全，前提是 read/recall 真 raw，并且 `append` 成为唯一 markdown-aware mutation。不要再有 read-time rewrite。read 不该有 side effect。

**append read-modify-write 会不会有 corner case？**

会，而且都是显而易见的：heading 不存在、heading 重复、LLM content 带换行/heading、文件为空、文件超 8KB、用户把 section 改成奇怪格式。解决方式不是恢复 canonicalizer，而是把 append insertion rules 写死，并加测试。

**内联展开是否合理？**

合理，但长文件必须限制 inline editor 高度。App Access 是管理列表，不是全屏文本编辑器。默认折叠不等于展开后可以把列表撑成灾难。

**Agent Skill toggle 是否 next turn 生效？**

按现有代码，不生效。除非 `AgentSkillManager` 每次 `catalogPrompt/activate` 都读取当前 disabled set。已经返回到当前 turn / history 的 skill 内容也不会被撤回，只能从后续 prompt catalog 和后续 activation 里消失。

**已激活 skill 怎么办？**

现在 `activeSkills` 基本是死状态：`activate()` add 进去，但 prompt 注入不是靠它。短期做法：disable 后阻止后续 catalog/activation，并文档说明历史不清除。长期如果 active skill bodies 真的变成跨 turn 注入，就必须在 disable 时从 active set 移除，或者注入前过滤 active set。

**任务拆分还有 over-scope 吗？**

有。Agent skill core/UI 拆开；App Access content index/row expansion 拆开。其它拆分基本能接受。

## Test Plan

必须补这些测试，别只测 happy path：

- `MemoryStore` raw round-trip：未知 heading、普通段落、手写 bullet 保存后 recall 原样可见。
- `append` insertion：new file、missing heading、duplicate heading、free text between sections、content with newline/`##` injection、file cap exceeded。
- conflict：same `mtime` but different content hash must conflict。
- singleton/session split：两个 session 共享 store monitor，但 `writtenThisSession` 或替代 tracker 每个 session 独立。
- `MemoryRecaller` ignores blank files and refuses/skips over-cap files.
- Agent skill disable core：disabled skill absent from catalog prompt, `activate_skill` rejects, explicit `/skill-name` mention does not inject body, re-enable works in same running session.
- UI: disabled skill remains visible in toggle list; switch does not open viewer; info tap opens viewer.
- App Access: chips come from preloaded index; save/delete updates chip; 8KB memory expansion stays usable; blocked app warning appears but save still works.

## Not In Scope

- Full markdown renderer.
- Search/diff/import.
- Editing Agent Skills or App Skills.
- Copy-on-write app skills.
- Dedicated Skills page.
- Merge UI for memory conflicts.

## Next Step

Update the design before implementation:

1. Define `MemoryDocumentSnapshot` with content hash/revision, not only mtime.
2. Move session write tracking out of `MemoryStore` before introducing `MemoryStoreHolder`.
3. Specify exact `append` insertion rules.
4. Redesign skill disabling around dynamic `AgentSkillManager` filtering.
5. Add `AppAccessContentIndex` and bounded inline editor requirements.

After those edits, this is implementable. Without them, it will look simple in the diff and behave wrong in production.
