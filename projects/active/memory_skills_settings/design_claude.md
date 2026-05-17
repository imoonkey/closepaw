# Memory in Settings (v5 design)

> View and edit memory files from inside the app. Per-app stuff lives in
> App Access. Agent Skills become toggles in Tools. Memory editing is
> session-exclusive: when any non-Shutdown session exists, the editor
> locks. Single writer, no concurrency framework.
> Last updated: 2026-05-17 (v5 — Codex round 3, gate concretized + small nits)

## Goal

From Settings the user can:

- See and fix what's in `user.md`, `device.md`, `apps/<pkg>.md` without ADB.
- Toggle individual Agent Skills on/off (same place as `termux_shell`,
  `browser_script`).
- Manage everything about a single app (tier + memory + skill) in one place.

Non-goals (v1): markdown rendering, search inside memory, diff, importing
skills, full Agent Skill editing.

## Approach

Three independent moves; each is the simplest thing that works for its
domain.

### Memory editor: free-text TextField

Plain `TextField` over the raw file. To make this honest we have to delete
the canonicalizer in `MemoryStore` — otherwise the very next read silently
re-formats away any free text the user typed (Codex caught this). The
canonicalizer was there to keep agent-written content tidy; we replace it
with a tiny **append-after-heading helper with explicit insertion rules**
(see [Append insertion rules](#append-insertion-rules)). Read becomes
side-effect-free.

Why text editor over structured editor (bullets-with-X-and-+):
- Less UI: no add-modal, no section picker, no per-bullet delete confirm.
- Honest about what the agent sees: WYSIWYG vs. prompt input.
- Free-text edits survive (canonicalizer is gone).
- Trade-off: user has to write Markdown by hand. Memory files are short and
  the format is forgiving — this is fine for a power-user surface.

**Content sanitation stays.** Deleting the canonicalizer ≠ deleting input
sanitation on `append`. Tool-supplied content still gets trimmed, control
chars stripped, newlines folded to single space, capped at 2000 chars.
That prevents an LLM from injecting a `\n## Pitfalls\n` heading inside a
single bullet's `content` argument.

### Per-app stuff lives in App Access (inline)

`AppAccessSettingsPage` already enumerates installed apps with search +
filter + tier selector. It's the natural per-app home. We add — **inline,
not navigation** — a tap-to-expand area below the tier chips showing:

- App memory editor (only if `filesDir/memory/apps/<pkg>.md` exists, or if
  the user taps "+ Memory" to create a blank one).
- App skill viewer (only if `assets/app_skills/<pkg>/SKILL.md` exists and
  parses cleanly, read-only).

**Affordance rule (resolves the "row identical" contradiction Codex flagged):**

- **Has memory or skill** → row shows a small ⌄ chevron on the right; tap
  the row body (anywhere except tier chips) to expand. Summary chips
  ("Memory" / "Skill") sit next to the package name as a glance signal.
- **Has neither** → row shows a small `+ Memory` action chip in the same
  slot as the chevron. Tap creates an empty file and opens the expanded
  editor in EDIT mode.

So default visual is: nothing-different rows for apps without content (the
small `+ Memory` chip is the only delta), expand-affordance for apps with
content. No invisible expansion.

**Bounded inline editor.** Memory files can in theory grow to 8 KB. An
unbounded `TextField` inside a `LazyColumn` item is unusable — keyboard,
selection, inner/outer scroll all fight. The inline `MemoryFileEditor`
variant inside App Access is rendered with `heightIn(max = 240.dp)` plus
internal vertical scroll. Tier selector cannot be pushed off-screen.
For a full-screen view, the editor has a "↗ Open" affordance that opens
the same content in a dedicated `MemoryFileEditorPage` (same composable,
unbounded). The standalone Settings → Memory page always uses the
unbounded variant.

**Per-app content index, not per-row IO.** Page-scoped
`AppAccessContentIndex` is loaded once when the page mounts:

```kotlin
data class AppContentSummary(val hasMemory: Boolean, val hasSkill: Boolean)
class AppAccessContentIndex(
    private val memoryStore: MemoryStore,
    private val assets: AssetManager,
    private val skillParser: SkillFrontmatterParser,
) {
    suspend fun load(): Map<String, AppContentSummary>      // one IO pass
    fun update(pkg: String, summary: AppContentSummary)     // after save/delete
}
```

- Memory side: `MemoryStore.listAppPackages()` → set of packages with files.
- Skill side: `assets.list("app_skills")` → for each, parse `SKILL.md`
  frontmatter; skip entries that don't parse cleanly. (Existing parser
  already returns null on failure.)
- Save/delete in editor calls `index.update(pkg, …)` → chips refresh
  without re-scanning.

Rows read from the index synchronously — no `produceState` per row, no
filesystem touch during scroll.

Why not a per-app detail page: you said inline. Plus list-with-expansion
keeps the search/filter UX intact — drilling into a page would break
"scroll through 20 apps with memory and verify them all."

### Agent Skill toggles in Tools

`ToolsSection` (under Agent Behavior) is already the home of agent
capability toggles: `termux_shell`, `browser_script`. Agent Skills are
conceptually the same. One toggle row per discovered skill.

**Activation timing: next session.** Codex caught that naïvely persisting
`disabledAgentSkills` to `AppSettingsStore` would *not* affect a running
session, because `AgentSkillManager` holds a `catalog` constructed at
session start. We pick the KISS resolution: disabling a skill takes
effect on the **next session**, full stop. No mid-session live filtering,
no "already-injected bodies stay visible" weirdness, no provider
plumbing. Sessions are cheap to restart; the toggle is rare. The UI
clearly labels: *"Changes apply to the next session."*

Mechanism:

- `AgentSkillCatalog` stays a pure scanner: `allDiscovered():
  List<AgentSkillEntry>` — every valid skill on disk, no filtering. Used
  by the UI to populate toggle rows.
- `SessionServices.create()` reads
  `AppSettingsStore.disabledAgentSkills.value` **once** and passes the
  set into `AgentSkillManager` as `val disabledNames: Set<String>`. The
  manager filters inside `catalogPrompt()`, `activate()`, and
  `activateExplicitMentions()`.
- `ActivateSkillTool` gets a new `ActivationResult.Disabled(name)`
  branch — model gets a clean failure if it tries.
- Toggle row UI: iterates `allDiscovered()`; checked = `name !in
  AppSettingsStore.disabledAgentSkills`. Disabled skills stay visible so
  the user can re-enable.

Persistence: `AppSettingsStore.disabledAgentSkills: Set<String>` (default
empty → everything enabled, including newly-installed skills).

**Tap-to-view affordance.** `ToolSettingsCard` today is one big clickable
card with a trailing `Switch`. Adding "tap title to open viewer" on the
same card creates click ambiguity. Extend `ToolSettingsCard` with an
optional `trailingInfoIcon: (() -> Unit)?` slot — a real `IconButton`
between the title text and the switch. Click info icon → bottom sheet
with SKILL.md body. Small extension; keeps visual consistency with the
other two tool rows.

### Settings Home IA

Per your suggestion, merge "Voice" into "Behavior" and add "Memory" there.

| Section | Rows |
|---------|------|
| **Behavior** | LLM & Authentication, Agent Behavior, **Memory** *(new)* |
| **Access** | Permissions & Advanced, App Access |
| **About** | Open Source Licenses |

## Components

### New

| File | Role |
|------|------|
| `ui/settings/AppAccessContentIndex.kt` | Page-scoped index of `package → (hasMemory, hasSkill)`. Single IO pass at page mount; updated incrementally on save/delete. Rows read synchronously — no per-row IO. |
| `ui/settings/MemorySettingsPage.kt` | "Memory" page. Lists **User** and **Device** rows only (per-app moved to App Access). Tap → editor. Top banner when session active. |
| `ui/settings/MemoryFileEditor.kt` | View/Edit `TextField` with Save / Discard / Delete. Two variants: **unbounded** (used by `MemorySettingsPage` and a future full-screen App Access drill-in) and **bounded** (`heightIn(max = 240.dp)` + internal scroll, used inline in App Access expansion). Bounded variant has an "↗ Open" affordance for the unbounded editor. **Observes session-active state and locks write actions accordingly.** |
| `ui/settings/AgentSkillToggleRows.kt` | Loops `AgentSkillCatalog.allDiscovered()`, renders one `ToolSettingsCard` per skill using the new `trailingInfoIcon` slot. |
| `ui/settings/AppRowExpansion.kt` | The expanded area inside `AppRowItem`: bounded memory editor + read-only app skill viewer. |

### Changed

| File | Change |
|------|--------|
| `memory/MemoryStore.kt` | Delete canonicalizer (`loadSections`, `buildCanonicalDocument`, legacy `user_prefs.md` + inline `[kind]`-prefix migration). Add `read(scope, packageName): String?` (raw file content, side-effect-free), `write(scope, packageName, content): SaveResult`, `delete(scope, packageName)`, `listAppPackages(): List<String>`. Rewrite `append(scope, section, content, packageName)` per [Append insertion rules](#append-insertion-rules) — input sanitation (trim, strip control chars, fold newlines, 2000-char cap) preserved. `writtenThisSession` / `hasWrittenThisSession()` stay where they are (session-local since each session constructs its own store). Net ~110 LOC removed, ~70 added. |
| `memory/MemoryRecaller.kt` | Switch from `readUserMemory`/etc. to `read(scope)`. Filter via `?.takeIf { it.isNotBlank() }` so an empty `+ Memory`-created file doesn't produce a blank `## Recalled Memory` block. Skip files over the 8 KB cap (log warning, don't inject — prevents stale oversize files from inflating prompt). |
| `agent/Agent.kt` | Unchanged. (Still calls `services.memoryStore.hasWrittenThisSession()`.) |
| `tool/impl/RememberExperienceTool.kt` | Unchanged. Still calls `append`. Blocked-app gate stays. |
| `session/SessionServices.kt` | Unchanged construction of per-session `MemoryStore`. Read `AppSettingsStore.disabledAgentSkills.value` once at session start; pass as immutable `Set<String>` into `AgentSkillManager`. |
| `agent/cognition/skills/AgentSkillManager.kt` | Constructor gains `disabledNames: Set<String>`. `catalogPrompt()`, `activate()`, `activateExplicitMentions()` filter against it. `ActivationResult` gains `Disabled(name)` branch. |
| `agent/cognition/skills/AgentSkillCatalog.kt` | Rename `all()` → `allDiscovered()` to make pure-discovery semantics explicit. No filtering inside the catalog itself. |
| `tool/impl/ActivateSkillTool.kt` | Add branch for `ActivationResult.Disabled` → returns failure result with reason. |
| `app/AppSettingsStore.kt` | Add `disabledAgentSkills: StateFlow<Set<String>>` + `setSkillDisabled(name, Boolean)`. |
| `ui/settings/ToolSettingsCard.kt` | Add optional `trailingInfoIcon: (() -> Unit)?` slot between title block and switch. Renders an `IconButton` with `Icons.Outlined.Info`. Null = unchanged. |
| `ui/settings/ToolsSection.kt` | Append `AgentSkillToggleRows()` after `BrowserScriptToolRow`. |
| `ui/settings/AppAccessSettingsPage.kt` | Load `AppAccessContentIndex` alongside installed-apps scan. `AppRowItem` gains tap-to-expand area, expand chevron when content exists, `+ Memory` chip when empty. Existing tier selector unchanged. |
| `ui/settings/SettingsSheet.kt` | Add `SettingsPage.MEMORY`; dispatch. |
| `ui/settings/SettingsHomePage.kt` | Move "LLM & Authentication" from "Voice" into "Behavior" section. Drop the empty "Voice" header. Add "Memory" row. |

### Removed

- `MemoryStore.readUserMemory()`, `readDeviceMemory()`, `readAppMemory()` — collapse into `read(scope, packageName?)`.
- Legacy `user_prefs.md` migration path — pre-release; we don't carry it.
- Legacy `[fact]`/`[pitfall]`/etc. inline-prefix normalization on read — same reason. (Append-time input sanitation is **not** removed; see Memory editor section.)

## Memory editor: state machine

```
            ┌────────┐     [Edit]      ┌────────┐
   open ───►│ VIEW   ├────────────────►│ EDIT   │
            └────┬───┘                  └─┬───┬──┘
                 ▲    [Save] ◄────────────┘   │
                 │                            │
                 │    [Discard] ──────────────┘
                 │
                 │
                 └── [Delete + confirm] ──► pops back to list
```

`VIEW` shows file content in a selectable, read-only `TextField`. `EDIT`
makes it mutable + shows Save / Discard. `Delete` is available in both
modes (with confirm dialog). Save is disabled while writing; failure
leaves the editor in `EDIT` with an inline error.

### Concurrency: single-writer model with explicit edit gate

We do **not** design a shared-monitor / revision-hash / merge-conflict
machine. The Settings page is meant to be isolated; mid-session memory
editing is a niche case that does not justify a concurrency framework.

Rule: **when any non-Shutdown session exists (or creation is in progress),
the memory editor is locked.**

#### `MemoryEditGate`

A single app-scoped `StateFlow<Boolean>` named `memoryEditLocked`. Source
of truth: `SessionCoordinator` (the existing single-session owner —
`currentSession: AgentSession?` plus `AgentSession.state: StateFlow<SessionState>`).
The coordinator currently exposes `currentSession` as a plain `var`, so a
small extension is needed: a `StateFlow<SessionState?>` that emits the
current session's state (or `null` when no session exists). Wiring:

```kotlin
// Added to SessionCoordinator:
private val _currentSessionState = MutableStateFlow<SessionState?>(null)
val currentSessionState: StateFlow<SessionState?> = _currentSessionState

// On every currentSession assignment, kick off a coroutine that
// collects session.state into _currentSessionState; on shutdown/clear,
// emit null. This is the only added complexity.

// MemoryEditGate:
val memoryEditLocked: StateFlow<Boolean> =
    sessionCoordinator.currentSessionState
        .map { state -> state != null && state != SessionState.Shutdown }
        .stateIn(scope, SharingStarted.Eagerly, initialValue = true)
```

- Locks for **Created / Running / Idle / TakeoverPending / Paused** — any
  state where an `append` could fire next. Only `Shutdown` or `null`
  unlocks.
- "Creation in progress" collapses into `state == Created`, which is
  locked.
- Initial value `true` (safe default while flow is collecting).
- Single-session model means we don't need a registry — there's at most
  one session at a time, and `SessionCoordinator` owns it.

#### Two-layer enforcement

1. **UI layer:** `MemoryFileEditor` observes `memoryEditLocked`. When
   true, Save / Discard / Delete / `+ Memory` buttons are disabled;
   banner: **"Session is open. Stop the session to edit memory."**
   Typed buffer is preserved (don't discard the user's work).
2. **Action layer (TOCTOU safe):** every save/delete/create handler
   re-checks `memoryEditLocked.value` **immediately before** calling
   `MemoryStore.write/delete`. If locked, abort with no-op and surface a
   toast: **"Memory edit aborted — a session just started."** (Same copy
   for Save, Delete, and `+ Memory` — single line is clearer than
   action-specific text.) This catches the race where the user clicked
   Save, the coroutine launched, and a session was created before the
   write happened.

#### What this deletes

- No `MemoryStoreHolder` singleton (each session constructs its own
  `MemoryStore` as today; Settings constructs its own pointed at the
  same dir; the gate guarantees they're never both writing).
- No `SessionMemoryWriteTracker` extraction (`writtenThisSession` stays
  on the session-local `MemoryStore`).
- No `snapshot`/`revision`/`expectedRevision`/conflict UI.
- No mtime guard.

What we still keep:

- `@Synchronized` per `MemoryStore` instance (already there).
- Atomic temp-file replace on every write (already there).
- Append insertion rules and input sanitation (next section).

### Blocked apps

The agent's write path (`RememberExperienceTool`) refuses to write app
memory when the foreground app is `BLOCKED`. The Settings editor does not
— a UI edit by the user is the explicit consent the tool gate exists to
require.

To make this visible: in App Access, when the expanded row's app is
classified `BLOCKED`, the memory editor shows a warning chip
above the TextField: *"This app is set to Reject. Saved memory will still
be recalled when this app is foreground."* No silent behavior, no surprise.

### Size cap

8192 UTF-8 bytes per file, hard. Save with content over the cap returns
`SaveResult.TooLarge`; UI shows "Memory files feed the prompt directly —
keep them short." Per-entry content cap stays 2000 characters. Recaller
also **skips** files that exceed the cap (logged warning), so a stale
oversize file written before this change can't silently inflate the
prompt.

### Append insertion rules

The `append(scope, section, content, packageName?)` rewrite is the only
remaining schema-aware mutation. Returns `Boolean` (matches existing
caller contract — `RememberExperienceTool` stays signature-compatible).
Internal failures (too-large, invalid name, IO) are logged. Explicit
rules, all tested:

1. **Content sanitation (correct order):**
   1. Fold any `\r\n`, `\r`, `\n`, `\t` to a single space (do this first
      — newlines are control chars, stripping them before fold would
      glue tokens together).
   2. Strip remaining Unicode `Cc` control chars.
   3. Collapse runs of whitespace into single spaces; trim outer.
   4. Truncate to `maxContentLength` (2000 **characters**).
   5. Reject empty post-sanitation result.
2. **Format entry:** `- [${timestamp}] ${sanitized_content}` where
   timestamp uses the existing `TIMESTAMP_FORMATTER`.
3. **File doesn't exist:** create with title + intro (per `DocumentSpec`),
   then section skeleton (every section in `MemorySchema.sectionsFor(scope)`
   gets a `## ${heading}` line with no bullets), insert the entry under
   the target section. Atomic temp-file write.
4. **File exists, heading exists exactly once:** parse the file as a line
   list. Locate `## ${section.heading}`. Insert the new bullet
   immediately before the next `^## ` line, or at EOF if none. Write
   back atomically.
5. **File exists, heading missing:** append `\n## ${section.heading}\n${entry}\n`
   at EOF. Don't try to "merge" with existing free text — user-added
   sections (or non-canonical text the user typed) stay untouched above.
6. **File exists, heading duplicated:** insert under the **last**
   occurrence (most recently authored, most likely what the user meant).
   Log a warning. Tested.
7. **Size check (UTF-8 bytes):** if the resulting file would exceed
   8192 bytes (UTF-8 encoded), return `false` and log
   `"memory append rejected: file would exceed 8 KB"`. No partial write.
8. **Whole operation runs under the store's `@Synchronized` monitor.**

Why these rules and not "smart merge": every clever heuristic is a way to
silently break user edits. Pick the boring rule that's easy to predict.

**Caps recap:**
- Per-entry content: **2000 characters** (existing `maxContentLength`).
- Per-file: **8192 UTF-8 bytes** (new). Applies to `append` and `write`;
  recall also skips files over this cap (defensive against pre-cap files).

## Agent Skill toggle rows

Inside `ToolsSection`, after the existing `BrowserScriptToolRow`:

```
[Termux Shell                ]  (existing)
[Browser Script              ]  (existing)
── Agent Skills ──
[Browser Use         ⓘ  ☐/☒ ]
[Calendar Date Math  ⓘ  ☐/☒ ]
[…                          ]
```

- Rendered only if `AgentSkillCatalog.allDiscovered()` is non-empty.
  **First-run note:** the catalog scans `filesDir/skills/`, which is
  populated by `BundledAgentSkillInstaller` on first session bootstrap.
  If the user opens Settings → Agent Behavior before ever starting a
  session, `filesDir/skills/` would be empty and the section would be
  invisible. `AgentSkillToggleRows` invokes the bundled installer
  before its first scan to avoid that hidden-state.
- Each row is a `ToolSettingsCard` with skill name as title, frontmatter
  description as subtitle, toggle on the right. The new
  `trailingInfoIcon` slot holds an `IconButton` (Info) between the title
  block and the switch → tap opens a bottom sheet with the SKILL.md body
  (read-only TextField).
- Toggling writes to `AppSettingsStore.setSkillDisabled(name, !checked)`.
- **Effect: next session.** `AgentSkillManager` was constructed at
  session start with the disabled-set snapshot; running session is
  unaffected. Subtitle shows *"Changes apply to next session"* when the
  current toggle state differs from the value at session start.

## App Access per-app expansion

`AppRowItem` today is a single card with: icon + label + package name + tier
selector. We add (inline, below the tier selector):

```
[icon] App Name           [Memory] [Skill]   ← summary chips when content exists (from AppAccessContentIndex)
       com.example.app                  ⌄    ← chevron when content exists, "+ Memory" chip when not
       Allow / Ask / Reject              ← existing tier chips, unchanged
       ─────────────────────────────────
       (expanded:)
       App Skill (read-only viewer)
       <text body, full inline>
       App Memory
       <bounded MemoryFileEditor, heightIn(max=240.dp), internal scroll>
       [↗ Open full editor]              ← opens MemoryFileEditorPage with same content
```

- Default collapsed: row visually identical to today except for the trailing
  chevron / `+ Memory` chip in the top-right slot.
- Chips and trailing affordance both read from `AppAccessContentIndex`
  (preloaded once on page mount; updated on save/delete).
- Expansion state local to row (`rememberSaveable` keyed by package).
- Tap `+ Memory` on an empty row → creates an empty file via `MemoryStore.write(...)`,
  index updated, row expands into EDIT mode.
- Long memory does NOT make the row tall: the bounded editor is capped at
  240 dp with internal scroll. The "↗ Open" affordance escapes to the
  full-screen `MemoryFileEditorPage` (same composable family, unbounded).
- **No double-buffer race.** The "↗ Open" button is disabled while the
  bounded editor is dirty (in EDIT mode with unsaved changes). User must
  Save or Discard first; full page then re-loads file fresh. Same
  applies the other direction: a dirty full-page editor doesn't get
  silently overwritten by a stale inline buffer because inline can't
  open it while dirty.
- Blocked-app: warning chip above the bounded editor: *"This app is set
  to Reject. Reject only blocks the agent from writing this memory;
  saved entries are still recalled when this app is foreground."*

## Tasks

| Slug | Scope | Acceptance criteria | Depends on |
|------|-------|---------------------|------------|
| `memory_store_raw_refactor` | `memory/MemoryStore.kt`, `memory/MemoryRecaller.kt`, tests | Canonicalizer + legacy migrations removed. New `read` (raw, side-effect-free), `write(scope, packageName, content): SaveResult` (atomic temp-file replace; SaveResult covers Success/TooLarge/IoError; cap = 8192 UTF-8 bytes), `delete`, `listAppPackages`. `append` rewritten per [Append insertion rules](#append-insertion-rules), still returns `Boolean` (signature-compatible with `RememberExperienceTool`); over-cap returns false + logs reason. Sanitation order: fold whitespace → strip control → collapse → trim → truncate. `MemoryRecaller` switched to `read` + `takeIf { isNotBlank() }` + over-cap skip. `writtenThisSession` untouched. Old `readUserMemory`/`readDeviceMemory`/`readAppMemory` removed, call sites updated. JVM tests: raw round-trip (free text + unknown headings survive); sanitation order (`"foo\nbar"` → `"foo bar"` not `"foobar"`); append on new file / missing heading / duplicate heading (last wins) / heading followed by free text / oversize file rejected (file cap measured in UTF-8 bytes); recall over-cap file is skipped; recall blank file omitted. | — |
| `memory_edit_gate` | `app/MemoryEditGate.kt`, `session/SessionCoordinator.kt` (small extension), tests | Add `SessionCoordinator.currentSessionState: StateFlow<SessionState?>` (collects active session.state, emits null when no session). **Critical: `createAndSubmit` must emit `Created` BEFORE invoking the suspending `AgentSession.create` block** — otherwise the creation-in-progress window isn't locked. Aborted creation emits `null`. `MemoryEditGate.memoryEditLocked: StateFlow<Boolean>` = `currentSessionState.map { it != null && it != SessionState.Shutdown }`. Initial value true. JVM tests: locked when Created/Running/Idle/TakeoverPending/Paused; unlocked only when no session OR Shutdown; transition from Shutdown → Created flips lock back to true within next emission; **creation suspending while no `AgentSession` exists yet, gate remains locked**. | — |
| `memory_file_editor` | `ui/settings/MemoryFileEditor.kt`, `ui/settings/MemoryFileEditorPage.kt`, compose tests | Two variants share one composable: `MemoryFileEditor(bounded: Boolean)` — bounded uses `heightIn(max=240.dp)` + internal scroll + "↗ Open" affordance; unbounded fills available space. VIEW/EDIT toggle, Save/Discard/Delete with confirm, oversize error, IO on `Dispatchers.IO`, save disabled while writing, failure keeps EDIT with inline error. **Observes `MemoryEditGate.memoryEditLocked` — when true, Save/Discard/Delete/`+ Memory` disabled, banner "Session is open. Stop the session to edit memory." shown; typed buffer preserved.** **Action-time re-check**: every save/delete handler re-reads `memoryEditLocked.value` inside the coroutine immediately before calling `MemoryStore.write/delete`; if locked, abort with toast "Memory edit aborted — a session just started." **Bounded vs unbounded buffer:** "↗ Open" disabled while bounded editor is dirty. Bounded compose test: 8 KB content remains editable, tier selector remains visible above, IME doesn't hide Save/Discard. Gate test: open editor, simulate session start → buttons lock, banner appears, buffer preserved; click Save while locked transition is in flight → write aborted, file unchanged. | `memory_store_raw_refactor`, `memory_edit_gate` |
| `memory_settings_page` | `ui/settings/MemorySettingsPage.kt`, `ui/settings/SettingsSheet.kt`, `ui/settings/SettingsHomePage.kt` | "Memory" row under "Behavior". Page lists User + Device only. Tap → unbounded `MemoryFileEditorPage`. Per-app does NOT appear here. "LLM & Authentication" moves into Behavior; "Voice" header dropped. Top banner when session active. | `memory_file_editor` |
| `app_access_content_index` | `ui/settings/AppAccessContentIndex.kt`, tests | Single IO pass loads `Map<package, AppContentSummary>` from `MemoryStore.listAppPackages()` + `assets.list("app_skills")` (filtered to those whose `SKILL.md` parses cleanly). Incremental `update(pkg, summary)` API. JVM tests cover load, update, invalid skill skip. | `memory_store_raw_refactor` |
| `app_access_per_app_expansion` | `ui/settings/AppAccessSettingsPage.kt`, `ui/settings/AppRowExpansion.kt`, compose tests | `AppRowItem` gains trailing chevron (when content exists) or `+ Memory` chip (when not). Summary chips next to label from index. Expanded area renders read-only app skill viewer (if asset exists) and bounded memory editor (if file exists, or after `+ Memory` creates one). Tier selector unchanged. Blocked-app warning chip shown above editor. Save/delete call `index.update(...)`. Compose test: expand → edit → save → chip appears; collapse → expand again preserves nothing-in-progress; `+ Memory` on empty row creates + expands. | `memory_file_editor`, `app_access_content_index` |
| `agent_skill_disable_core` | `app/AppSettingsStore.kt`, `agent/cognition/skills/AgentSkillCatalog.kt`, `agent/cognition/skills/AgentSkillManager.kt`, `tool/impl/ActivateSkillTool.kt`, `session/SessionServices.kt`, JVM tests | `disabledAgentSkills` persisted as `StateFlow<Set<String>>`. `AgentSkillCatalog.all()` renamed `allDiscovered()` (no filtering). `AgentSkillManager` constructor takes `disabledNames: Set<String>` and filters in `catalogPrompt`/`activate`/`activateExplicitMentions`. `ActivationResult.Disabled(name)` added; `ActivateSkillTool` returns clean failure. `SessionServices.create()` snapshots `disabledAgentSkills.value` once. Tests: catalog prompt omits disabled; `activate` returns `Disabled`; explicit mentions filter disabled; toggle change does NOT affect already-constructed manager (verifies KISS "next session" semantics). | — |
| `agent_skill_toggle_ui` | `ui/settings/ToolSettingsCard.kt`, `ui/settings/AgentSkillToggleRows.kt`, `ui/settings/ToolsSection.kt`, compose tests | `ToolSettingsCard` gains optional `trailingInfoIcon: (() -> Unit)?` slot (Info `IconButton` between title block and switch). `AgentSkillToggleRows` invokes `BundledAgentSkillInstaller` **on `Dispatchers.IO`** once before scanning (first-run safety, idempotent), then iterates `allDiscovered()`; toggle persists via `AppSettingsStore.setSkillDisabled`. Info icon → bottom sheet with SKILL.md body (read-only TextField). Row label includes "Changes apply to next session" subtitle when toggle differs from initial value. Compose test: switch toggles store; info icon opens viewer; switch click does NOT open viewer; click target separation reflected in a11y nodes; first-run with empty filesDir/skills still renders the section. | `agent_skill_disable_core` |
| `docs_update` | `doc/main/agent/memory.md`, `doc/main/agent/agent_skills.md`, `doc/main/app/settings.md` | Document: canonicalizer removed → memory files WYSIWYG; explicit append insertion rules; single-writer model (Settings editor locks when session active); Settings → Memory page; App Access per-app expansion; bounded vs unbounded editor; Agent Skill toggle next-session semantics; blocked-app warning copy. | All above |

## Trade-offs

**Delete the canonicalizer.** The existing `loadSections` +
`buildCanonicalDocument` machinery exists to keep agent-written memory
tidy. Once humans edit the same files, that tidy-up is a footgun: it
silently strips anything that doesn't match `- [timestamp]` under a known
heading. We could keep the canonicalizer and add a `replaceCanonical`
write path that validates user input — but that's more code, more error
states ("section heading X not allowed", "bullet at line 7 missing
timestamp"), and a worse UX. Free-form files with a smart `append` are
simpler. The agent's bullets still look uniform because `append` formats
them; the human's free text passes through unmolested.

**Inline expansion vs. detail page in App Access.** Detail page would
isolate the editor (cleaner focus). Inline keeps search + filter +
"check 20 apps in a row" workflows intact. You picked inline; trade-off
accepted.

**Free-text editor vs. structured editor.** Structured = no parser
heuristics — but it's more UI code (add-modal, section picker, per-bullet
delete) and the agent literally sees the file content, so a WYSIWYG text
editor is more honest. Free-text wins for this user base.

**8 KB cap.** Memory files in practice are <4 KB. Cap is purely defensive
against paste-the-internet accidents. Smaller than my earlier 64 KB
because oversize content directly inflates every prompt — a recall budget
discussion is the right fix if anyone ever bumps the cap.

**Single-writer model (no shared monitor, no revision hash).** Settings
edits are locked while a session runs; agent writes are confined to
sessions. The two sets of writers never overlap in time, so we don't need
`MemoryStoreHolder`, `SessionMemoryWriteTracker` extraction, content
hashes, or merge UI. Settings stays the "isolated thing." The cost is one
banner and a disabled Save button while a session runs — a fine price for
an entire concurrency framework deleted.

**Drop legacy migration.** `user_prefs.md` → `user.md` and inline
`[fact]`-prefix normalization were transitional. CLAUDE.md says pre-release
+ no backcompat hacks; this is exactly that scope.

## Open Questions

1. **Bottom sheet vs. inline body for Agent Skill tap-to-view.** Bottom
   sheet is consistent with Material patterns; inline expansion under the
   skill row keeps everything on one page. Mild preference for bottom
   sheet — agent skill bodies are often longer than app skills.
2. **"+ Memory" for blocked apps.** Show the affordance, but creation
   triggers the warning chip immediately? Or hide the affordance entirely
   for `BLOCKED` apps? Lean toward show-with-warning — user can always
   re-classify the app first if they want.
