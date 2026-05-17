# KISS Design Review: Memory & Skills in Settings

> Review target: `projects/active/memory_skills_settings/design_claude.md`
> Reviewer: Codex
> Date: 2026-05-17

## Verdict

方向对一半：**in-app plain-text editor for memory** 是对的，external editor / SAF round-trip 都是烂泥潭。路径-only 也不够，因为核心需求是“不用 ADB 修错记忆”。

但这份 v1 不是 KISS。它把三个不同问题揉在一起了：

1. 编辑 runtime memory。
2. 浏览 runtime Agent Skills。
3. 浏览 bundled App Skills，并从 skill viewer 深链到 app memory override。

真正该 ship 的 v1 只有 **Settings -> Memory：查看、编辑、删除 memory 文件**。Skills viewer 至少降到 v1b，`Add override` deep-link 直接砍。现在的设计看起来简单，是因为把复杂度藏在 “same composable / raw write / deep-link” 这些词后面了。

## Scope Call

推荐：**Reduce**。

v1 scope 应该是：

- Memory 可查看、可编辑、可删除单个文件。
- User + Device 永远显示；App memory 只显示已有 `filesDir/memory/apps/*.md`。
- App memory override 通过正常 memory editor 完成，不从 skill page 跳转。
- Skills 不进 v1，最多在文档里继续说明路径和 immutable/bundled 限制。

如果一定要带 skills：

- 只读 viewer 可以作为独立 v1b。
- 不做编辑。
- 不做 `Add override` deep-link。
- 不承诺 “installed skills” 与 active session catalog 完全一致，只显示当前磁盘/asset 状态。

## Findings

### High: `writeRaw` 会破坏现有 MemoryStore 的 canonical contract

设计说 `writeRaw` 不做 markdown parsing / section validation，用户写坏 markdown 后 `MemoryRecaller` 仍会 verbatim 注入。这个判断是错的。

当前 `MemoryStore.readDocument()` 会 `loadSections()`，只保留固定 heading 下 `- [` 开头的 entries，然后 `buildCanonicalDocument()` 重写 canonical 文件。任意自由文本、未知 heading、非 timestamp bullet 都会被忽略或在下一次 read 时消失。也就是说 raw editor 不是“写什么 recall 什么”，而是“写了以后可能被 canonicalizer 吃掉”。这比不做功能更糟，因为用户以为保存成功。

改法：

- 不要叫 `writeRaw`。叫 `replaceCanonical(scope, packageName, content)`。
- Save 必须解析成 `MemorySchema.sectionsFor(scope)` 的 canonical document；无法解析的内容返回错误，不静默丢。
- `read` 给 editor 的内容应是 canonical document，包括空 section；保存后仍 canonical。
- 如果想真 raw，那就要让 recall 也 raw read，等于推翻 MemoryStore V2 的固定 schema。不要这么做。

### High: 这会绕过 memory security gate，设计没有承认

`RememberExperienceTool` 明确禁止在 blocked foreground app 下写 memory，也禁止 app-scoped memory 写到 blocked package。Settings raw editor 如果直接写 `apps/<pkg>.md`，就绕过了这条边界。

用户手动编辑可以被视为 explicit user intent，但必须写清楚。否则安全模型会变成“LLM 不许写，UI 可以随便写”，后续没人知道这是产品决策还是漏洞。

改法：

- 对 bundled `BLOCKED` app：允许查看和删除 app memory，默认不允许保存 app memory。
- 如果要允许用户强行保存，必须有明确 warning 和确认；别把它混在普通 save path 里。
- User/device memory 也可能含敏感数据，至少在 Memory home 上加一句简短 warning，别藏在 docs。

### High: `MemoryStore @Synchronized` 不保证 UI 和 active session 串行化

设计说 user-save 和 `remember_experience` 会 serialize，因为 `MemoryStore` 是 `@Synchronized`。只有同一个 `MemoryStore` instance 才成立。

当前 `SessionServices.create()` 每个 session 构造自己的 `MemoryStore(File(context.filesDir, "memory"))`。Settings 如果自己 new 一个 store，就不是同一把 monitor。设计又说从 `MainActivityContent` 传 `MemoryStore` / `AgentSkillCatalog`，但这些现在只挂在 `AgentSession -> SessionServices`，settings 在没有 active session 时也应该可用。

改法二选一：

- KISS：删掉“会 serialize”的承诺，接受 last-writer-wins，并在 save 前做 mtime check。这个其实比假装同步更诚实。
- 更稳：引入 app-scoped `MemoryStoreHolder.get(context)`，`SessionServices` 和 Settings 共用同一个 instance。这个是小改，不是复杂架构。

### Medium: Skills viewer 是 v1 膨胀，不是 memory editor 的自然延伸

Memory、Agent Skill、App Skill 的来源不同：

- Memory：`filesDir/memory`，runtime writable，固定 schema。
- Agent Skill：`filesDir/skills`，session catalog start 时扫描，activation 时读 file body。
- App Skill：APK assets，runtime immutable，通过 `AssetManager` load，prompt 注入时 strip frontmatter body。

“One composable serves every file” 只能复用 UI 壳，不能消灭这些 source/write semantics。把 skills 放进 v1 会带来 asset enumeration、body-vs-source 展示、bundled skill overwrite、session catalog stale 等一堆解释成本。

改法：

- v1 只做 Memory。
- v1b 再做 read-only Skills。
- v1b viewer 明确显示的是 “prompt body” 还是 “source SKILL.md”。别混用。

### Medium: `Add override` deep-link 是典型过度设计

从 App Skill row 跳到 app memory editor，并把 cursor 放到 `## App Skill Overrides`，这不是 v1 必需品。它会逼 `SettingsSheet` 支持跨页面参数、目标 package、初始 edit mode、cursor placement。收益只是省一次用户导航。

改法：

- v1/v1b 都砍。
- 如果保留 skill viewer，只显示一句静态说明：override lives in app memory under `App Skill Overrides`。
- 真要跳转，做成 v2 的 routing task，不要塞进 skill page acceptance criteria。

### Medium: `Clear all` 不在用户问题里，且破坏 KISS

目标写的是 “wipe a single scope”。任务表却加了 Memory page 底部 “Clear all”。这是危险 destructive bulk action，需要更强确认、测试和文案。它不是编辑错事实的核心路径。

改法：

- v1 只允许删除当前文件。
- “Clear all memory” 以后如果真的要做，单独设计。

### Medium: UI I/O 和错误状态没有设计清楚

`MemoryStore` 当前是同步 file I/O。Settings composable 不能在 main thread 上直接 read/write。设计只说 toast saved，没有说 loading、save failure、oversize、permission/disk error、discard reload failure 怎么表现。

改法：

- Parent page 用 coroutine + `Dispatchers.IO` load/save/delete。
- `FileEditorPage` 只做 pure UI state，不做 I/O。
- Save button during write disabled；失败留在 edit mode，并显示 error text。
- Oversize 是 hard failure，不是 “soft limit”。

### Low: 64 KB cap 太大，且跟现有 per-entry cap 不一致

现有 `MemoryStore.maxContentLength` 是单条 entry 2000 chars。设计突然允许 64 KB per file，会让 recalled memory 直接吃 prompt budget。Memory V2 文档说 memory deliberately small and deterministic，这个 cap 不该变成“paste the internet but under 64 KB”。

改法：

- 先用 16 KB hard cap per file，甚至 8 KB 都够。
- 错误文案直接说 memory files are prompt input, keep them short。
- 以后如果要大文件，再先做 recall budget，不是在 editor 放大口子。

### Low: Settings wiring 会继续撑大 `SettingsSheet` 签名

`SettingsSheet` 已经参数很多。再传 `MemoryStore`、`AgentSkillCatalog` 继续恶化。尤其 `AgentSkillCatalog` 不是 app-level singleton，也没有 `all()` API，设计里的 `AgentSkillCatalog.all()` 不存在。

改法：

- Memory v1：传一个 `MemoryStore` 或 `MemoryStoreHolder` 就够；不要传 skill catalog。
- Skills v1b：页面内部基于 `context.filesDir/skills` 构造 listing，App Skills 用 `AssetManager.list("app_skills")`，不要依赖 active session catalog。

## Boundary Decisions

### Memory editable: yes, but canonical-only

合理。用户确实需要修错 facts/preferences。路径-only 不解决“不用 ADB”的问题。

但 editor 必须尊重 MemoryStore schema：fixed sections, timestamp bullets, package validation, atomic replace。不要用 raw write 绕开 schema。

### Skills read-only: right boundary, wrong release

只读是对的。编辑 Agent Skills 会撞上 session catalog immutable；编辑 bundled `browser-use` 会被 installer 覆盖；编辑 App Skills 不可能直接写 assets。

但 read-only skills viewer 不该拖进 Memory v1。它是另一个 feature。

### Bundled skills not editable: correct

`BundledAgentSkillInstaller` 会在 session bootstrap refresh `browser-use`。任何运行时修改都可能被覆盖。以后如果支持 user-installed skill editing，必须把 `.install-complete` / `metadata.bundled` 这类 bundled marker 当作 hard no-edit gate。

### App Skill override through App Memory: correct

不要 copy-on-write assets，不要 runtime-edit bundled app skills。Override section 是正确归宿。只是 v1 不需要从 skill viewer deep-link 过去。

## Canonical Cases

当前设计把 edge cases 包装成 special cases：

- EMPTY 是 VIEW 的子状态，这个好。
- Missing file save empty no-op，这个也好。
- Memory vs skill 不是同一个 canonical file case。一个是 writable canonical memory doc，一个是 read-only prompt guidance source。
- Broken markdown 不是“用户的问题”。在当前 MemoryStore 下它会被 parser/canonicalizer吞掉。必须转成 canonical parse failure。
- Concurrent edit 不是“rare 所以不管”。用 mtime check 作为 canonical save guard，很便宜，应该进 v1。

建议的 canonical save flow：

```text
open memory file
  -> read canonical document + mtime
edit
save
  -> if mtime changed: show conflict, require reload
  -> parse content into MemorySchema sections
  -> reject unknown/non-entry content with line number
  -> write canonical temp file + rename
  -> reload canonical content + mtime
```

这不是 over-engineering；这是避免“保存成功但内容消失”的最低成本。

## Revised Task Split

### 1. `memory_store_document_io`

Scope:

- `MemoryStore.kt`
- `MemoryStoreDocumentIoTest.kt`

Acceptance:

- `readDocumentForEdit(scope, packageName): MemoryDocumentSnapshot(content, lastModifiedMillis, exists)`
- `replaceDocumentFromEdit(scope, packageName, content, expectedLastModifiedMillis): SaveResult`
- `delete(scope, packageName): Boolean`
- `listAppPackages(): List<String>`
- Uses existing package validation and temp-file replace.
- Saves only parseable canonical docs; rejects unknown headings / non-entry text with line number.
- Detects mtime conflict.
- Hard file cap, preferably 8-16 KB.

### 2. `memory_settings_page`

Scope:

- `MemorySettingsPage.kt`
- `FileEditorPage.kt` or `MemoryFileEditorPage.kt`

Acceptance:

- Shows User, Device, and existing App memory rows.
- File size / empty label.
- Tap opens editor.
- Load/save/delete run on `Dispatchers.IO`.
- Save failure is visible and leaves edit buffer intact.
- Delete only deletes current file with confirm.
- No “Clear all”.

### 3. `settings_sheet_memory_wiring`

Scope:

- `SettingsSheet.kt`
- `SettingsHomePage.kt`
- `MainActivityContent.kt` only if dependency injection is needed.

Acceptance:

- Adds one Settings home row: `Memory`.
- Put under existing advanced/data area; do not add `Skills`.
- Does not require active session.
- If sharing lock with active session matters, use a shared `MemoryStoreHolder`.

### 4. `memory_settings_tests`

Scope:

- JVM tests for store parse/save/delete/conflict.
- Compose/android test for navigation and editor save/delete happy path.

Acceptance:

- `SettingsNavTest` still passes.
- Memory row opens editor.
- Editing a wrong fact persists and `MemoryRecaller.recall()` sees the canonical result.
- Invalid package name and malformed content do not save.

### 5. `docs_update`

Scope:

- `doc/main/agent/memory.md`
- `doc/main/app/settings.md`

Acceptance:

- Documents Settings -> Memory.
- Explains canonical format.
- Explains edits affect next turn.
- Explains blocked app memory behavior.

## Deferred Work

- Read-only Agent Skills viewer.
- Read-only App Skills viewer.
- App Skill -> App Memory override deep-link.
- User-installed Agent Skill editing.
- Runtime catalog invalidation.
- Full markdown rendering/search/diff.
- Clear all memory.

## Bottom Line

The core product decision is good: **don’t outsource editing app-private files to Android intents**.

The v1 design still needs a haircut. Build Memory editor first, make it canonical and honest, and do not drag skills into the same release just because both filenames end in `.md`.
