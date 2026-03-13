Context:
1. 我现在doc/main/agent/memory.md里是的memory设计和实现。
2. original design doc is at doc/todo/0.5_memory/final/design.md.
3. doc/todo/0.5_memory/review下有非常很多repo的memory设计和实现review。不过这些review只能做参考，因为实际很多都遗漏了或者错误地描述了很多细节。有些需要你自己复查repo来确认。

Next Step: design a v2 that hit a good balance between usefulness, system complexity and minimalism/KISS. And ready for production release.


# Final Design Sketch
## V2-Minimal Draft (Agreed Half)

Goal: reduce repeated UI exploration cost on this device with minimal complexity.

### Storage Model
- Persistent memory is scope-first:
  - `memory/user.md`
  - `memory/device.md`
  - `memory/apps/<package>.md`
- No separate session-log memory in v2.
- Existing session history remains the per-session record.
- If episodic memory is ever added later, it should be derived from session history, not introduced as another primary store.

### Persistent Memory Semantics
- Fixed file layout:
  - `user.md`: `Facts`, `Preferences`
  - `device.md`: `Facts`, `Pitfalls`, `Verification`
  - `apps/<package>.md`: `App Skill Overrides`, `Preferences`, `Operational Notes`
- `Operational Notes` in app memory can mix local facts, pitfalls, and verification hints in plain language.
- No inline prefixes inside `Operational Notes`; write plain-language bullets directly.
- Avoid over-classifying app memory entries.
- Every entry uses full timestamp:
  - `[YYYY-MM-DD HH:MM:SS TZ]`

### Recall / Retrieval
- File-based truth source.
- No SQLite / vector retrieval in v2.
- Deterministic recall only:
  - always load `user.md`
  - always load `device.md`
  - load current `apps/<package>.md`
- v2 keeps files small and reads them directly.
- If files later become too large, add minimal progressive disclosure:
  - headings + line numbers
  - read specific rows/sections on demand

### Write Path
- Keep dedicated memory write path; do not reduce memory to generic `read_file` / `write_file`.
- `app skill` = public/provider-owned baseline.
- `app memory` = private/local delta.
- If `app skill` conflicts with `app memory`, trust `app memory`.
- Promotion into persistent memory should come from task outcome + existing session history, not from a separate session-log memory layer.

### Still Open
- Exact promote timing and trigger points.


# In-Doc Discussion
这个section是你和我聊天交流的地方。You answer me in place. Consensus will be used to update the Final Design Sketch section above.


## General Memory Category
 - 似乎常见是persistent memory + daily log memory。我没有daily log memory的设计，在想要不要补上。
 - Codex 2026-03-13 16:52:27 EDT: 我建议补，但把它定义成 `session log / episodic log`，不要叫 `daily log memory` 作为主语义层。
 - Codex 2026-03-13 16:52:27 EDT: `persistent memory` 必做；`session log` 可选，但职责要收窄成 append-only raw evidence，不默认 recall，只用于 debug / audit / promotion source。
 - Qi: 你看看review里openclaw和其他claws的daily log是怎么设计的？意义是什么？我们不要考虑参考它们的设计吗？ 他们的daily log就是一天一个文件，里面其实存的是每个session的一些append-only的信息。当然也不是raw log。raw log是另外存的。
 - Codex 2026-03-13 17:20:29 EDT: 我看了 OpenClaw 本体。它的主设计确实是两层：`MEMORY.md` = curated long-term memory；`memory/YYYY-MM-DD.md` = append-only daily memory。另有 `/new` / `/reset` hook 会把 session 摘成 `memory/YYYY-MM-DD-slug.md`。
 - Codex 2026-03-13 17:20:29 EDT: 所以你说得对，OpenClaw 的 daily memory 不是 raw log，而是一个 `semi-curated episodic layer`。pre-compaction silent flush 也会提醒模型把 durable notes 先写到 canonical daily file。
 - Codex 2026-03-13 17:20:29 EDT: 但它能这样做的前提是：默认有 `memory_search` + `memory_get`，而且 recall corpus 覆盖 `MEMORY.md + memory/*.md`。也就是说，它不是先 merge 再 recall，而是把 daily files 直接当 recall corpus。
 - Codex 2026-03-13 17:20:29 EDT: 这点对我们很关键。我们如果坚持 `user/device/current_app` 的 deterministic recall，而不做跨很多 episodic files 的 search，那就不能直接照抄 OpenClaw 的 daily layer。
 - Codex 2026-03-13 17:20:29 EDT: 所以我的更新判断是：应该参考 OpenClaw，但只借它的“append-only episodic layer + promotion source”这个思想，不要直接复制它的 recall 形态。
 - Qi: 我们v2要session log作为memory吗？
 - Codex 2026-03-13 18:34:35 EDT: 我现在的最终判断是：v2 不要再做一层独立的 `session log memory`。
 - Codex 2026-03-13 18:34:35 EDT: 原因不是 OpenClaw 不好，而是我们 repo 里已经有完整的 session history 持久层：`SessionHistoryManager` + `SessionRecordingService` + `SessionStorage`，会把每个 session 自动写到 `/files/sessions/*.json`。
 - Codex 2026-03-13 18:34:35 EDT: 在这个前提下，再加一层 session log memory，会和现有 history 高度重叠：
   1. 都是 per-session record
   2. 都要命名 / 持久化 / 清理
   3. 都会变成 promote source
 - Codex 2026-03-13 18:34:35 EDT: 这样会让系统里出现两套“session层记录”，不够 KISS。
 - Codex 2026-03-13 18:34:35 EDT: 所以 v2 我建议：
   1. `persistent memory` 做好
   2. `session history` 继续做 session record
   3. 如果需要 promotion，就从现有 session history / task outcome 里提炼，不新增 session log memory

## Persistent Memory Design
 - persistent memory要分类吗？是一个MEMORY.md，还是分USER_PROFILE.md, DEVICE_INFO.md, SOUL.md, AGENTS.md, etc.? 
 - Codex 2026-03-13 16:52:27 EDT: 我不建议回到一个大一统 `MEMORY.md`，也不建议现在引入 `SOUL.md` / `AGENTS.md` 这类人格层文件。
 - Codex 2026-03-13 16:52:27 EDT: 对 Android agent 更合适的是 3 个 scope-first 文件：

```text
memory/
  user.md
  device.md
  apps/
    <package>.md
```

 - Codex 2026-03-13 16:52:27 EDT: 如果以后真出现稳定的新 scope，再加第四类；v2 不要预建 taxonomy。
 - 现在的memory有一个不那么好的结构化的设计：不该分workflow/pitfall，应该分scope（user or app-specific?）和kind?  kind label该有哪些？（fact/preference/event/note/summary/knowledge/task)
 - Codex 2026-03-13 16:52:27 EDT: `scope + kind` 的方向是对的，scope 应该是一等维度，kind 只是轻量标签。
 - Codex 2026-03-13 17:20:29 EDT: 我现在想收紧一点。`workflow` 的确太模糊，v2 可以直接去掉。
 - Codex 2026-03-13 17:20:29 EDT: kind 我改成只保留 4 个：`fact`, `preference`, `pitfall`, `verification`。
 - Codex 2026-03-13 17:20:29 EDT: 我会排除 `event`, `task`, `note`, `summary`, `knowledge`, `workflow`。它们要么更像 session/history，要么边界太泛，后面会把 retrieval 搞脏。
 - Codex 2026-03-13 16:52:27 EDT: timestamp 统一用 full timestamp，不是只有日期。格式建议：`[YYYY-MM-DD HH:MM:SS TZ]`。
 - Qi: 你给我说说user.md device.md都存什么？apps/<package>.md又存什么？我觉得这个要认真想一想。
 - Codex 2026-03-13 17:20:29 EDT: 同意，这里要非常清楚。我的定义是：
 - Codex 2026-03-13 17:20:29 EDT: `user.md` 只存“跨 app、由用户拥有、会影响执行方式”的稳定信息。比如：语言偏好、隐私/确认偏好、默认操作偏好（优先搜索、不用语音输入、除非明确要求否则不改通知设置）。
 - Codex 2026-03-13 17:20:29 EDT: `device.md` 只存“跨 app、由设备环境决定”的稳定信息。比如：Android/OEM/navigation mode、work profile、有无实体按键、输入法/locale、设备级 quirks。这里可以有 device-wide pitfall，但不放 app-specific 内容。
 - Codex 2026-03-13 17:20:29 EDT: `apps/<package>.md` 存“这个 app 在本机上的本地知识”：local overrides、app-specific user preference、UI structure facts、pitfalls、verification hints。
 - Codex 2026-03-13 17:20:29 EDT: 一个简单边界：
   1. 换 app 还成立 -> `user.md` 或 `device.md`
   2. 只在这个 package 成立 -> `apps/<package>.md`
   3. 只在这次任务里有意义 -> 不进 persistent memory
 - Codex 2026-03-13 18:32:34 EDT: 我现在的答案是：persistent memory 的每个文件应该有 `light fixed format`，但不要上重 schema。
 - Codex 2026-03-13 18:32:34 EDT: 也就是说：
   1. 固定文件名 / scope
   2. 固定标题
   3. 固定一级 section
   4. section 下面是 timestamped bullet entries
 - Codex 2026-03-13 18:32:34 EDT: 我不建议上 YAML frontmatter、JSON schema、复杂字段表。Markdown section 就够了。
 - Codex 2026-03-13 18:32:34 EDT: 一个具体草案可以是：

```md
# User Memory

## Facts
- [2026-03-13 18:32:34 EDT] User's name is Qi.

## Preferences
- [2026-03-13 18:32:34 EDT] Prefer search over scrolling when possible.
```

```md
# Device Memory

## Facts
- [2026-03-13 18:32:34 EDT] Device uses gesture navigation.

## Pitfalls
- [2026-03-13 18:32:34 EDT] BACK may dismiss keyboard before leaving screen.

## Verification
- [2026-03-13 18:32:34 EDT] Re-check page title after BACK on OEM settings screens.
```

```md
# App Memory: com.android.settings
> Local delta over app skill. If conflict exists, trust this file.

## App Skill Overrides
- [2026-03-13 18:32:34 EDT] Search is more reliable than scrolling on this build.

## Preferences
- [2026-03-13 18:32:34 EDT] User prefers search when available.

## Operational Notes
- [2026-03-13 18:32:34 EDT] Developer Options is under System.
- [2026-03-13 18:32:34 EDT] BACK may dismiss keyboard first.
- [2026-03-13 18:32:34 EDT] Verify by confirming the target page title after navigation.
```

 - Codex 2026-03-13 18:32:34 EDT: 这里 `App Skill Overrides` 不是 kind，而是 app file 的一个固定 section，因为它跟 `app skill` 的关系很重要。
 - Codex 2026-03-13 18:29:04 EDT: 这版设计是能帮助 personalization 的，但我会把它定位成 `light personalization / operational personalization`，不是完整 user profile system。
 - Codex 2026-03-13 18:29:04 EDT: 像姓名、常用邮箱、语言偏好、默认账号，这些都可以作为 `user.md` 里的 `fact` 记住，只要它们会跨 app 反复影响任务执行。
 - Codex 2026-03-13 18:29:04 EDT: 但这里有个重要约束：`user.md` 是默认 recall 的。如果把太多 PII 都塞进去，就等于每个 turn 都把这些信息发给模型。
 - Codex 2026-03-13 18:29:04 EDT: 所以我建议：
   1. `user.md` 只放少量高价值、经常复用的 profile facts
   2. 对姓名 / 邮箱 / 电话 / 地址这类 PII，默认不要自动学习，只有用户明确说“记住这个”或产品上显式允许时才写入
   3. 密码、OTP、token、信用卡这类 secrets 永远不进 memory
 - Codex 2026-03-13 18:29:04 EDT: 换句话说，这版可以记住“用户是谁、偏好什么、常用哪个邮箱”，但不应该演化成一个无边界的私人档案库。
 - Codex 2026-03-13 18:32:34 EDT: 这里也顺带说明为什么要 fixed sections：以后如果要 selective read，按 heading/line 读取会比从一堆自由文本里现找稳定得多。
 - Qi: USER/DEVICE要不要二合一？
 - Codex 2026-03-13 18:34:35 EDT: 我现在的判断是：`语义上分，物理上可以先合一`，这是最 KISS 的折中。
 - Codex 2026-03-13 18:34:35 EDT: 也就是说，在 memory model 里仍然保留 `user scope` 和 `device scope`，但 v2-minimal 未必要落成两个文件。
 - Codex 2026-03-13 18:34:35 EDT: 原因是：
   1. 两者的 ownership 不同：`user` 是人的稳定信息，`device` 是环境信息
   2. 两者的生命周期不同：`device` 更容易因换机 / 系统更新 / 配置变化而失效
   3. 两者的隐私策略不同：`user` 可能含 PII，`device` 通常不含
 - Codex 2026-03-13 18:34:35 EDT: 但从 v2 工程角度看，它们也有很强的相似性：
   1. 都是 cross-app memory
   2. 都会默认 recall
   3. 当前规模都很小
 - Codex 2026-03-13 18:34:35 EDT: 所以如果你想进一步减复杂度，我觉得完全可以先落成一个文件，比如：

```md
# Global Memory

## User Facts
- [2026-03-13 18:34:35 EDT] User's name is Qi.

## User Preferences
- [2026-03-13 18:34:35 EDT] Prefer search over scrolling when possible.

## Device Facts
- [2026-03-13 18:34:35 EDT] Device uses gesture navigation.

## Device Pitfalls
- [2026-03-13 18:34:35 EDT] BACK may dismiss keyboard before leaving screen.

## Device Verification
- [2026-03-13 18:34:35 EDT] Re-check page title after BACK on OEM settings screens.
```

 - Codex 2026-03-13 18:34:35 EDT: 这样做的好处是：
   1. 少一个文件
   2. recall 更简单
   3. 语义边界还在，因为 section 仍然分开
 - Codex 2026-03-13 18:34:35 EDT: 真正需要拆回两个文件的时机，大概是：
   1. 要做跨设备同步/迁移
   2. `user` 的隐私策略要单独控制
   3. `device` 更新/清理要单独做
 - Codex 2026-03-13 18:34:35 EDT: 所以如果你问我 v2 该怎么选，我现在略偏向：
   - `global.md`（内部再分 user/device sections）
   - `apps/<package>.md`
 - Codex 2026-03-13 18:34:35 EDT: 这比 `user.md + device.md + apps/<pkg>.md` 更简，但没有丢掉 user/device 这个 conceptual boundary。
 - Qi: 那就分开USER.md和DEVICE.md吧。 app memory里 Overrides/Facts/Pitfalls/Verification区分不清楚
 - Codex 2026-03-13 18:34:35 EDT: 同意，v2 就明确分开 `USER.md` 和 `DEVICE.md`。
 - Codex 2026-03-13 18:34:35 EDT: app memory 那边我也同意你这条反馈，不要再分成 `Overrides/Facts/Pitfalls/Verification` 四段。
 - Codex 2026-03-13 18:34:35 EDT: 我现在更推荐 app memory 只保留 3 个 section：
   1. `App Skill Overrides`
   2. `Preferences`
   3. `Operational Notes`
 - Codex 2026-03-13 18:34:35 EDT: 理由很简单：
   1. `App Skill Overrides` 是和 `app skill` 的关系边界，必须单独保留
   2. `Preferences` 是用户偏好，也值得单独保留
   3. 其他 app-local learnings 不值得再细分；文件本来就不大，强分类只会增加歧义
 - Codex 2026-03-13 18:34:35 EDT: 也就是说，原来那些 `Facts/Pitfalls/Verification` 在 app memory 里都收进 `Operational Notes`，直接用自然语言写清楚即可。
 - Codex 2026-03-13 18:34:35 EDT: 一个更简的 app memory 例子：

```md
# App Memory: com.android.settings
> Local delta over app skill. If conflict exists, trust this file.

## App Skill Overrides
- [2026-03-13 18:34:35 EDT] Search is more reliable than scrolling on this build.

## Preferences
- [2026-03-13 18:34:35 EDT] User prefers search when available.

## Operational Notes
- [2026-03-13 18:34:35 EDT] Developer Options is under System.
- [2026-03-13 18:34:35 EDT] BACK may dismiss keyboard first.
- [2026-03-13 18:34:35 EDT] Verify by confirming the target page title after navigation.
```
 - Codex 2026-03-13 18:34:35 EDT: 这比之前那版更符合 KISS。
 - Qi: ok
 - Codex 2026-03-13 18:34:35 EDT: 那这条就定了：`Operational Notes` 不加 `Pitfall:` / `Verify:` 这类 inline prefixes，直接自然语言写。
 - Qi: “kind 我建议只保留 5 个：`fact`, `preference`, `workflow`, `pitfall`, `verification`。”，fact/preference我同意。 pitfall是不是只出现在app/里？workflow和verification感觉有点模糊，定义和边界是什么？workflow感觉很难写好？
 - Codex 2026-03-13 17:20:29 EDT: 你这个质疑是对的，所以我把 `workflow` 去掉了。
 - Codex 2026-03-13 17:20:29 EDT: 我现在建议的 scope-kind matrix 是：
   1. `user.md`: `Facts`, `Preferences`
   2. `device.md`: `Facts`, `Pitfalls`, `Verification`
   3. `apps/<package>.md`: `App Skill Overrides`, `Preferences`, `Operational Notes`
 - Codex 2026-03-13 17:20:29 EDT: `pitfall` 不只会出现在 app，也可能出现在 device。比如某设备的 gesture/back/permission quirk 是跨 app 的。
 - Codex 2026-03-13 17:20:29 EDT: 边界我想这样定：
   1. `fact`: 结构/状态/操作事实。例：Developer Options 在 System 下；这个 app 的搜索在本机可用。
   2. `preference`: 用户希望默认怎么做。例：有搜索时优先搜索。
   3. `pitfall`: 已知失败模式、误导性 UI、容易踩坑的行为。例：BACK 先收键盘；返回后滚动位置丢失。
   4. `verification`: 如何确认成功。例：检查标题、toggle 状态、目标项是否可见。
 - Codex 2026-03-13 17:20:29 EDT: 原来很多我叫 `workflow` 的内容，认真拆以后其实是 `fact` 或 `pitfall`。真正多步 procedure 更像 app skill，不该塞进 persistent memory。
 - Qi: 如果不存daily log，那timestamp还有意义吗？
 - Codex 2026-03-13 17:20:29 EDT: 有意义。即使没有 daily log，persistent memory 也会因为 app 更新、系统更新、用户改设置而过期。
 - Codex 2026-03-13 17:20:29 EDT: timestamp 至少有三个用途：
   1. 冲突时偏向较新的条目
   2. 发现 memory 可能 stale（比如 app 更新后）
   3. debug “这条知识是什么时候学到的”
 - Codex 2026-03-13 17:20:29 EDT: 但 timestamp 在 v2 只是 metadata，不要把 retrieval 设计建立在它上面。

### app skill 和 app memory
app skill 我想以后做成一个public的东西，更新是相当于我们as provider负责的。app memory是私人的。里面分两类:
1. app skill里写的东西有些不对，比如本机或者本地的app版本，导致有一些app skill不对。这里可以重写。
2. 更偏用户preference？
 - Codex 2026-03-13 16:52:27 EDT: 这个切分我认同。`app skill` 是 provider-owned baseline；`app memory` 是 local/private delta。
 - Codex 2026-03-13 16:52:27 EDT: app memory 最该存的就是三类：
   1. local override
   2. app-specific user preference
   3. local fact / pitfall / verification learnings
 - Codex 2026-03-13 16:52:27 EDT: 关键契约要写清楚：如果 `app skill` 和 `app memory` 冲突，信 `app memory`。
 - Codex 2026-03-13 16:52:27 EDT: 文件结构上我会倾向于 light sections，而不是纯 flat bullets，例如：

```md
# App Memory: com.android.settings
> Local and device-specific. If conflict with app skill, trust this file.

## App Skill Overrides
- [2026-03-13 16:52:27 EDT] Search works more reliably than scrolling on this device build.

## Preferences
- [2026-03-13 16:52:27 EDT] User prefers search when available.

## Operational Notes
- [2026-03-13 16:52:27 EDT] Developer Options is under System.
- [2026-03-13 16:52:27 EDT] BACK may dismiss keyboard first.
- [2026-03-13 16:52:27 EDT] Verify by confirming the target page title after navigation.
```

## Daily Log Memory
- 这个一般claws都用什么格式？
 - Codex 2026-03-13 16:52:27 EDT: 常见确实是 `MEMORY.md + HISTORY/session log/daily note` 这种二层结构，但很多 claws 里的 daily log 更像 raw evidence store，不是高质量默认 recall source。
 - Codex 2026-03-13 16:52:27 EDT: 所以我建议这里做成很朴素的 `session log`，格式不用聪明：

```md
# Session Log
- [2026-03-13 10:21:00 EDT] Goal: Open Developer Options
- [2026-03-13 10:23:14 EDT] Observation: Search is available on Settings home
- [2026-03-13 10:24:05 EDT] Learned: Search is faster than scrolling for this task
- [2026-03-13 10:25:31 EDT] Outcome: Success
```
- Qi:看看Openclaw怎么做的。
 - Codex 2026-03-13 17:20:29 EDT: OpenClaw 的实现我确认了：
   1. docs 把 `memory/YYYY-MM-DD.md` 定义成 append-only daily memory
   2. pre-compaction `memory flush` 默认提示模型把 durable memory 追加到这个 canonical daily file
   3. `/new` / `/reset` 的 `session-memory` hook 会生成 `memory/YYYY-MM-DD-slug.md`，里面是 session metadata + conversation summary
 - Codex 2026-03-13 17:20:29 EDT: 所以它的 daily layer 本质上是“半结构化 session/episode memory”，不是 raw execution log。
 - Codex 2026-03-13 17:20:29 EDT: 但它有一点和我们不同：OpenClaw 的 recall 可以 search 全部 `memory/*.md`，所以 daily files 不需要先 merge 才有价值。
 - Codex 2026-03-13 18:32:34 EDT: 回到“固定格式”这个问题：OpenClaw 对 persistent memory 的处理其实更松。
 - Codex 2026-03-13 18:32:34 EDT: 我确认到的是：
   1. `MEMORY.md` 是 optional curated long-term memory
   2. docs/AGENTS template 会告诉 agent “写 significant events / decisions / opinions / lessons learned”
   3. 但它没有像 `USER.md` 那样给 `MEMORY.md` 一个固定模板文件
   4. 它真正固定的是文件角色和文件名，不是 section schema
 - Codex 2026-03-13 18:32:34 EDT: 换句话说，OpenClaw 更像：
   - `USER.md` 这种 profile file 有模板
   - `MEMORY.md` 没有强格式，只要求 small/curated/human-editable
   - `memory/YYYY-MM-DD.md` 主要固定的是 canonical filename 和 append-only usage
 - Codex 2026-03-13 18:32:34 EDT: 我觉得我们可以比 OpenClaw 稍微更结构化一点，因为我们的 recall 不是 semantic search，而是 deterministic file recall。固定 section 对我们更值钱。

- 它如何被merge进persistent memory？
 - Codex 2026-03-13 16:52:27 EDT: 我不建议“merge 全部”，只应该 promote durable, reusable items。
 - Codex 2026-03-13 16:52:27 EDT: 一个够用的 promote gate 是：
   1. cross-task reusable?
   2. likely still true next session?
   3. specific enough to help action?
 - Codex 2026-03-13 16:52:27 EDT: 满足条件的条目再升格成 `fact/preference/pitfall/verification`。所以这更像 `promote selected items`，不是 `compress everything`。
 - Qi:看看Openclaw怎么做的。
 - Codex 2026-03-13 17:20:29 EDT: OpenClaw 默认主线其实没有一个很强的“daily -> MEMORY.md 自动 merge”流程。
 - Codex 2026-03-13 17:20:29 EDT: 它更像是把 `MEMORY.md` 和 `memory/*.md` 并列成两个 memory layers，然后靠 `memory_search` / `memory_get` 一起召回。
 - Codex 2026-03-13 17:20:29 EDT: 这再次说明：如果我们不做跨 episodic files 的 search，那我们自己的 session log 最好不要直接参与默认 recall，而是作为 promotion source。换句话说，我们的“merge”就是 explicit promotion，不是 OpenClaw 那种 unified search。
 - Codex 2026-03-13 18:34:35 EDT: 现在结合 repo 里的现有 history 系统，我会再收紧一步：v2 连“session log 作为 memory 层”都先不要做。
 - Codex 2026-03-13 18:34:35 EDT: 如果以后真的要加 episodic memory，我也建议它是 `derived from session history`，不是另一套 primary file store。


## tool specs
我的tool 要不要干脆改成 read_file, write_file? memory的部分，加一些system prompts来组织？
 - Codex 2026-03-13 16:52:27 EDT: 我不建议把 memory 主写路径降级成纯 `read_file/write_file`。
 - Codex 2026-03-13 16:52:27 EDT: 原因是 memory 需要明确 contract：scope routing、validation、caps/eviction、未来 consolidation/backends 的演进空间。
 - Codex 2026-03-13 16:52:27 EDT: 我的倾向是：
   1. 写入保留 dedicated memory tool
   2. 读取继续以系统自动 recall 为主
   3. 如需深挖，再加 `read_file lines` 或 `read_memory_section`
 - Codex 2026-03-13 16:52:27 EDT: 这样比“全靠 system prompt 让模型自己写 markdown”更稳，也比重型 memory API 更 KISS。
- Qi:看看Openclaw怎么做的。
 - Codex 2026-03-13 17:20:29 EDT: OpenClaw 这里是 hybrid，不是纯一边倒：
   1. 读：有 dedicated tools，`memory_search` + `memory_get`
   2. 写：默认更像 generic file-writing habit + hidden flush/hook，而不是强 typed write API
 - Codex 2026-03-13 17:20:29 EDT: 这说明两件事：
   1. 它也没有把 memory 简化成纯 `read_file/write_file`
   2. 它把真正需要 contract 的地方放在 recall，而不是 write
 - Codex 2026-03-13 17:20:29 EDT: 对我们来说，我还是更偏向 dedicated write path。因为我们的 scope 更小、更清晰，typed write 可以顺手解决 routing / validation / cap / future consolidation。

## Storage/Retrieval Architecture
1. 我现在就是纯粹file，然后全文读入，需要做sqlit/vector-retrieval之类的吗？现在引入这个复杂度值不值得，有什么帮助？
 - Codex 2026-03-13 16:52:27 EDT: 以当前 retrieval shape 看，我的判断是 `not worth it yet`。
 - Codex 2026-03-13 16:52:27 EDT: 你现在的问题本质上是 deterministic lookup，不是 semantic retrieval：
   1. always load `user`
   2. always load `device`
   3. lookup current `app`
 - Codex 2026-03-13 16:52:27 EDT: SQLite/vector/graph 只有在 memory 规模上来、query-driven search 变强需求、或者 package lookup 不再够用时才值得引入。
 - Codex 2026-03-13 16:52:27 EDT: 在那之前，file 更适合作为 truth source。
 - 行，就不加这些吧。
2. 我想能不能在这个基础上做minimal的优化。一个方向时progressive discloure。比如memory md 默认load进来是只有一个"目录"(各级标题，和行号)。然后agent可以read具体的内容。用read file specific rows就完事了？
 - Codex 2026-03-13 16:52:27 EDT: 我支持做 minimal progressive disclosure，但不是只有目录。
 - Codex 2026-03-13 17:20:29 EDT: 你说得对，`Current / Best Known` 这层没必要，是额外复杂度。
 - Codex 2026-03-13 17:20:29 EDT: 我现在更倾向于：
   1. v2 先继续保持小文件 + 全文读入
   2. 如果后面文件真的开始长，再做“标题目录 + 行号 + 按 section 读取”
   3. 不维护额外 summary section
 - Codex 2026-03-13 17:20:29 EDT: 所以对你这个问题，我现在的回答更简单：对，`目录 + read specific rows` 作为将来的 minimal optimization 就够了；v2 本身甚至可以先不做。
 - Codex 2026-03-13 17:20:29 EDT: 我现在的 v2-minimal 倾向可以压成一句：
   - file-based truth source
   - scope-first files
   - small kind vocabulary (`fact/preference/pitfall/verification`)
   - full timestamps
   - deterministic recall
   - optional session log as promotion source, not default recall
 - Qi: 你这current best known又是啥，感觉是没什么意义的复杂度啊。
 - Codex 2026-03-13 17:20:29 EDT: 同意，这个点你是对的。`Current / Best Known` 我撤回，不进 v2。
