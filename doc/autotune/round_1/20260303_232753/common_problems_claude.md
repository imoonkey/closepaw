# Round 1 (Autotune) Eval Analysis — Common Problems

**Run ID**: `20260303_232753` | **Date**: 2026-03-04 | **Model**: qwen3.5
**Tasks**: 12 evaluated | **Pass Rate**: 2/12 = 16.7%

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| ExpenseDeleteDuplicates2 | 0.0 | 9/50 | CannotHandle — ambiguous "exact duplicate" definition, moved to cannot_handle |
| MarkorAddNoteHeader | 0.0 | 30/30 | ActionFailure — cursor at wrong position + select-replace loop |
| MarkorCreateNote | **1.0** | 9/30 | Success (regression canary) |
| MarkorEditNote | **1.0** | 11/30 | Success (regression canary) |
| MarkorMergeNotes | 0.0 | 22/30 | FalseCompletion — file extension mismatch (.md appended) |
| RecipeAddMultipleRecipesFromImage | 0.0 | 35/50 | InfraError — LLM provider SSE crash at turn 35 |
| RecipeAddMultipleRecipesFromMarkor | 0.0 | 35/50 | ActionFailure — type action hint text contamination |
| RecipeDeleteDuplicateRecipes2 | 0.0 | 45/50 | CannotHandle — over-deleted (4 instead of 1), task has intentional same-description noise trap |
| RetroPlaylistDuration | 0.0 | 30/30 | NavigationFailure — couldn't discover add-to-playlist workflow |
| SportsTrackerActivitiesOnDate | 0.0 | 15/30 | FalseCompletion — reported track names instead of activity types |
| VlcCreatePlaylist | 0.0 | 19/30 | ActionFailure — type action hint text contamination |
| VlcCreateTwoPlaylists | 0.0 | 30/30 | ActionFailure — type action hint text contamination |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Success | 2 | MarkorCreateNote, MarkorEditNote |
| ActionFailure (type hint contamination) | 3 | RecipeAddMultipleRecipesFromMarkor, VlcCreatePlaylist, VlcCreateTwoPlaylists |
| FalseCompletion | 2 | MarkorMergeNotes, SportsTrackerActivitiesOnDate |
| ActionFailure (cursor/editing) | 1 | MarkorAddNoteHeader |
| NavigationFailure | 1 | RetroPlaylistDuration |
| InfraError | 1 | RecipeAddMultipleRecipesFromImage |
| CannotHandle (moved) | 2 | ExpenseDeleteDuplicates2, RecipeDeleteDuplicateRecipes2 |

## Previous Round Comparison (vs R3)

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| P1-R3: max_turns override to 50 | **Ineffective** | Tasks aren't turn-limited — they FalseComplete or Error early. ExpenseDeleteDuplicates2 used 9/50 turns. RecipeAddMultipleRecipesFromMarkor used 35/50. The bottleneck is agent behavior, not turn budget. |
| P2-R3: cannot_handle exclusion | **Working** | OsmAndMarker, OsmAndTrack, MarkorTranscribeVideo correctly excluded from this run. |
| P3-R3: Markor no-shell directive | **Partial** | MarkorAddNoteHeader: agent did NOT use shell for writes (improvement), but still used `cat` to read. MarkorMergeNotes: agent tried one shell read, got denied, recovered. Net: fewer wasted turns on shell, but underlying task workflows still fail. |
| P6-R3: VLC init fix | **Working** | No infra crash on VLC tasks. Both VlcCreatePlaylist and VlcCreateTwoPlaylists ran to completion. Failure is now purely agent/tool behavior. |

## Common Problems

### P1: `type` Action Hint Text Contamination — HIGH — 3-4 tasks

**Affected**: VlcCreatePlaylist, VlcCreateTwoPlaylists, RecipeAddMultipleRecipesFromMarkor, (partially RecipeAddMultipleRecipesFromImage)

**Root Cause**: When the `type` action enters text into an EditText field that has hint/placeholder text (e.g., "Playlist name", "Title", "Description"), it concatenates the typed text with the existing hint text instead of clearing it first. This produces corrupted values:
- Expected `"Ultimate Fails Series"` → Actual `"Playlist nameUltimate Fails Series"`
- Expected `"Thai Peanut Noodle Salad"` → Actual `"TitleThai Peanut Noodle Salad"`

The scorer checks exact field values, so all affected tasks score 0.0.

**Proposed Fix**:
- **Platform fix**: In `NodeActionPerformer.setTextOnNode()` line 197, the clear=false branch reads `node.text` which returns hint text on empty fields. Fix: check `node.isShowingHintText` (API 26+) to detect hint and treat as empty string. One-line change:
  ```kotlin
  val existing = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
  ```
  With double-safe fallback comparing `node.text == node.hintText`.
- Add unit tests: hint text field + clear=false, hint text field + clear=true, existing text + clear=false, empty field no hint + clear=false.

---

### P2: FalseCompletion Without Verification — HIGH — 2 tasks

**Affected**: SportsTrackerActivitiesOnDate, (MarkorMergeNotes partially)

*Note: ExpenseDeleteDuplicates2 and RecipeDeleteDuplicateRecipes2 moved to cannot_handle — ambiguous "exact duplicate" definition + intentional noise trap make these unreasonable eval targets.*

**Root Cause**: The agent claims success without verifying its work against the task requirements. Specific patterns:
- **SportsTrackerActivitiesOnDate**: Agent reported track names instead of activity types, despite the system prompt explicitly warning about this distinction.
- **MarkorMergeNotes**: Agent created file with `.md` extension when scorer expected no extension.

**Proposed Fix**:
- Add cognitive guardrail to system prompt: "Before calling `complete_task(success)`, verify your result: (1) Re-read the original goal. (2) Check that your actions match what was asked — e.g., if you were asked to delete items, confirm the items are gone. If you were asked for specific data, confirm it matches the requested category. (3) If the task says 'delete' but you found nothing to delete, re-examine your criteria."
- For scratchpad-heavy tasks: "Use append-only scratchpad tracking for multi-step tasks. Keep a running tally (e.g., 'Deleted 3/4 duplicates') rather than overwriting status each turn."

---

### P3: Markor File Extension Default — MED — 1 task

**Affected**: MarkorMergeNotes

**Root Cause**: Markor's "Create new file" dialog has two fields: name and extension (defaulting to `.md`). When the task requires a file without extension (e.g., `mIObBbo4`), the agent types the name correctly but doesn't clear the extension field, producing `mIObBbo4.md`.

**Proposed Fix**:
- Add to Markor app tips: "When creating a new file in Markor, the dialog has a separate extension field (defaults to `.md`). If the target filename has no extension or a different extension, clear or change the extension field before saving."

---

### P4: Markor Cursor Positioning for Header Insertion — MED — 1 task

**Affected**: MarkorAddNoteHeader

**Root Cause**: The agent typed header text assuming cursor was at position 0, but text landed after existing content. Spent 14 turns in a select-all/replace loop trying to fix it, discovered Markor's Special Key menu ("Document Start", "Select All") but ran out of turns.

**Proposed Fix**:
- Add to Markor app tips: "To insert text at the beginning of a document: use Markor's Special Keys menu (the icon above the keyboard) → 'Jump to Beginning' or type at the very start. If content edits go wrong, use Undo (Special Keys → Undo) rather than attempting complex select-and-replace operations."

---

### P5: App Workflow Discovery Gaps — MED — 2 tasks

**Affected**: RetroPlaylistDuration, SportsTrackerActivitiesOnDate

**Root Cause**:
- **RetroPlaylistDuration**: Agent spent 11 turns trying to discover "add song to playlist" workflow. Eventually found it (3-dot menu → Add to playlist → select playlist) but had already wasted too many turns. Also couldn't see song durations (not in a11y tree).
- **SportsTrackerActivitiesOnDate**: Agent reported track names instead of activity types. The activity type is only shown as an icon (not text) in the a11y tree. The Edit form (More options → Edit) likely has a labeled type field, but agent never explored it.

**Proposed Fix**:
- Add Retro Music app tip: "To add songs to a playlist: navigate to Songs tab, tap the 3-dot menu on a song → 'Add to playlist' → select the target playlist. Song durations are visible in the song list."
- Update OpenTracks app tip: "Activity TYPE is not visible as text in the track list or Stats view (it's an icon only). To find the activity type: tap a track → More options → Edit. The activity type field is in the edit form."

---

### P6: LLM Provider SSE Error Classification — LOW — 1 task

**Affected**: RecipeAddMultipleRecipesFromImage

**Root Cause**: LLM provider returned an SSE error at turn 35, killing the session. Retry logic already exists (`CloudStreamRetryRunner.kt`, MAX_RETRIES=5, exponential backoff), but the SSE error was likely classified as non-retryable by `OpenAIErrorClassifier`. The retry policy only covers `RateLimitException` and `TransientException`.

**Proposed Fix**:
- Check `OpenAIErrorClassifier` logic — SSE stream disconnects should be classified as `TransientException` (retryable), not `RuntimeException` (fatal). No new retry infra needed, just fix error classification.

---

## Next Steps

### Must Fix
1. **`type` action hint text fix** (P1): In `NodeActionPerformer.setTextOnNode()` line 197, use `node.isShowingHintText` to detect hint text and treat as empty string. Add unit tests for hint text scenarios.
   - Targets: VlcCreatePlaylist, VlcCreateTwoPlaylists, RecipeAddMultipleRecipesFromMarkor, RecipeAddMultipleRecipesFromImage
   - Implementation: `platform/NodeActionPerformer.kt` — `setTextOnNode()` clear=false branch

2. **`agent_thought` ordering** (Q7): Move `agent_thought` field before `action` in all tool schemas so LLM generates chain-of-thought reasoning before decision.
   - Targets: All tasks (general quality improvement)
   - Implementation: MobileActionTool, WaitTool, DelegateTaskTool, OpenAppTool, ScratchpadTool, WriteTodosTool, AskUserTool, SystemButtonTool — 8 tool schemas total

3. **Completion verification guardrail** (P2): Add pre-completion self-check to system prompt.
   - Targets: SportsTrackerActivitiesOnDate, MarkorMergeNotes
   - Implementation: `agent/definition/StandaloneAgentDef.kt` — system prompt rules section

### Should Fix
4. **Markor file extension tip** (P3): Add tip about clearing extension field in new file dialog.
   - Targets: MarkorMergeNotes
   - Implementation: StandaloneAgentDef.kt — Markor app tips

5. **Markor cursor positioning tip** (P4): Add tip about Special Keys → Jump to Beginning + Undo.
   - Targets: MarkorAddNoteHeader
   - Implementation: StandaloneAgentDef.kt — Markor app tips

6. **App workflow tips** (P5): Add Retro Music and OpenTracks workflow hints.
   - Targets: RetroPlaylistDuration, SportsTrackerActivitiesOnDate
   - Implementation: StandaloneAgentDef.kt — app tips section

7. **Re-enable `write_todos` tool** (Q12): Remove from `excluded_tools` in eval config. Add soft guidance in system prompt for multi-step tasks.
   - Targets: RecipeAddMultipleRecipes, ExpenseAddMultiple, and other multi-step tasks
   - Implementation: `eval/config/default.yaml` + StandaloneAgentDef.kt

8. **Keyboard visibility flag** (Q3): Add `keyboardVisible` boolean to `ScreenSnapshot` and prompt JSON. Detected during traverse when keyboard nodes are filtered. Helps agent understand BACK key behavior and screen occlusion.
   - Targets: MarkorAddNoteHeader, all text input scenarios
   - Implementation: `perception/Perceptor.kt`, `model/Models.kt`

### Backlog
9. **SSE error classification** (P6): Check `OpenAIErrorClassifier` — SSE stream disconnects should be classified as `TransientException` (retryable). Retry infra already exists.
   - Targets: RecipeAddMultipleRecipesFromImage
   - Implementation: `llm/OpenAIErrorClassifier.kt`

10. **Generic cursor positioning** (Q4): Add `ACTION_SET_SELECTION`-based cursor positioning to tool layer (e.g., `cursor_position` param or `set_cursor` action). Works on all editable fields without app-specific UI.
    - Targets: MarkorAddNoteHeader, general text editing
    - Implementation: `platform/NodeActionPerformer.kt`, `tool/impl/MobileActionTool.kt`

### Max Turns Reassessment
11. **Revert max_turns override**: The max_turns=50 increase (R3 P1) was ineffective — tasks FalseComplete early rather than exhausting turns. Consider reverting to default to save eval budget, or keep temporarily until P1/P2 fixes land.

### Accept as Capability Gap
- **ExpenseDeleteDuplicates2** — ambiguous "exact duplicate" definition + visually identical noise entries with different hidden fields. (NEW: AT-R1)
- **RecipeDeleteDuplicateRecipes2** — same issue: intentional same-description noise trap, agent over-deleted (4 instead of 1). (NEW: AT-R1)
- R3 exclusions (OsmAnd*, MarkorTranscribeVideo) remain valid.


# Qi Note:

**1. MarkorMergeNotes 我看trace成功了呀，为什么script没过? 是text错了，还是少空格换行之类的，还是别的什么原因？**

> **文件名不匹配。** Agent创建了 `mIObBbo4.md`，scorer期望的是 `mIObBbo4`（无扩展名）。Markor新建文件对话框有一个单独的extension字段，默认值 `.md`，agent没清除它。Scorer在filename check就fail了（`check_file_or_folder_exists`），content根本没被检查。这就是P3问题（Markor file extension default），加了tip就能解。

**2. ExpenseDeleteDuplicates2 先放到cannot handle list吧。**

> 收到。Round 2会加到 `eval/config/cannot_handle_group.txt`。（原因见Q9——task design有intentional same-description noise trap，agent在list view无法区分hidden fields不同的visually identical entries。）

**3. MarkorAddNoteHeader Turn 6 这个 navigate up button是不是实际不可点击的？反正action没反应啊。Turn 7的system back怎么也没用啊。**

> - **Turn 6**: Agent点的不是navigate up button，而是toolbar的ViewGroup title区域。Click成功执行了（action层面没报错），但这个元素不是button，没有导航响应。Agent误认了target。
> - **Turn 7**: System BACK的第一次按下只收起了键盘（Android标准行为——soft keyboard visible时第一个BACK dismiss keyboard，不离开当前screen）。需要连按两次BACK才能离开editor。

Qi Note: 那 Turn 7这个情况，我现在perception会filter掉keyboard，导致sanitized a11y tree里没有keyboard。是不是在这种情况下，在sanitized tree里保留一个 "KeyboardVisible" boolean flag更合理？(or something like that)。Agent在看到这个flag时，就知道BACK会先dismiss keyboard，而不是直接离开screen。

> 好想法。当前 `Perceptor.kt:251` 用 `isKnownKeyboardNode()` 过滤keyboard nodes但没留任何痕迹。加一个flag很合理：
>
> **实现方案**: traverse时如果命中keyboard node，设一个 `keyboardDetected = true` flag。在 `ScreenSnapshot` 加一个 `val keyboardVisible: Boolean = false`，然后在 `toPromptJson()` 输出时附一行 `"keyboard_visible": true`。
>
> Agent看到这个flag就能推断：(1) BACK会先dismiss keyboard (2) 输入框当前active (3) 屏幕下半部分被键盘遮挡、可能有元素不可见。这不只解决MarkorAddNoteHeader的问题，对所有涉及text input的场景都有帮助。Round 2可以一起做。

**4. MarkorAddNoteHeader Turn 3/4, 这个click element behavior符合预期吗？在正常人类语言里，点击一个对话框element，会默认到beginning吗？是不是可以加app tip，如果操作cursor具体位置，target 要用x,y坐标，而不是element index?**

> **Click实现是对的，但不符合agent的意图。** `click(element_index=X)` 点击的是element的center坐标，cursor会落在文本中间偏后位置，不是beginning。这个行为本身correct（点击中心点），但对"把cursor移到开头"这个目标不够用。
>
> 不建议用x,y坐标定位cursor——太fragile（受字体大小、行高、scroll位置影响）。最可靠的方案是app tip: **Special Keys menu（工具栏键盘图标）→ Jump to Beginning**。Agent在turn 27-28自己发现了这个方法，只是之前浪费了太多turns。

Qi Note: 这里用special keys没问题，但是别的文本编辑器不一定有这个功能啊？

> 对。Special Keys是Markor特有的。通用cursor定位方案有两条路：
>
> 1. **ACTION_SET_SELECTION**: Accessibility API原生支持，可以 `performAction(ACTION_SET_SELECTION, Bundle{SELECTION_START=0, SELECTION_END=0})` 把cursor移到position 0。不依赖任何app UI。当前action schema没暴露这个能力。
> 2. **Home key event**: `KeyEvent.KEYCODE_MOVE_HOME` 把cursor移到行首。通过 `AccessibilityService.dispatchGesture` 或 `performGlobalAction` 发送。
>
> 建议：在tool层加一个 `cursor_position` 参数（或独立 `set_cursor` action），底层用 ACTION_SET_SELECTION 实现。这是最通用的方案，对所有editable field都有效。Markor tip作为补充（Special Keys对于document-level navigation更方便），不作为唯一方案。这个可以排到后面的round做。

Qi Note: 嗯，这个generic cursor positioning solution后面再做吧。

**5. MarkorAddNoteHeader Turn 21的click (x,y)失败啊。是人点这个坐标也一样(app里reaction就这么design的)，还是execution结果跟人点击不一样？Turn 26好像成功把cursor放到beginning了。**

> - **Turn 21**: 是Android标准行为，人点也一样。当时text处于selected状态 + context menu可见，tap (50,400) 落在了selected region内，系统把它当作selection调整操作，不会取消selection和dismiss menu。
> - **Turn 26**: 此时context menu已经被BACK键dismiss了，tap (10,285) 点在极左侧，成功将cursor放到行首。是预期行为。
> - **最终**: Agent在Turn 27-28发现 Special Keys → Document Start，这是最稳定的方法（不依赖坐标）。


**6. 我的click实现还是有些bug。**

> 确认。从trace看click在多数场景work，但在特定UI state（text selection active + context menu visible）下行为不理想。不过Turn 21那个case不是click实现的bug，是Android系统行为。如果有其他具体case可以用 `/action-debug` 深入排查。

**7. 我发现我所有的action都是agent_thought在最后，但我其实是想反过来的，先thought再action，这样虽然是输出同样格式的json，但这个输出顺序，相当于chain-of-thoughts在前，结果decision action在后，似乎更合理？可能能提高成功率？**

> 思路完全正确。当前 `MobileActionTool.kt` schema里 `agent_thought` 在 `action` 之后定义（properties列表末尾）。JSON key顺序确实影响LLM的生成顺序——先生成reasoning再生成decision更符合chain-of-thought原理。
>
> **实现**: 把schema definition里 `agent_thought` 字段移到 `action` 前面即可。Low-risk、potentially high-impact。Round 2可以做。

Qi Note: 好的，注意覆盖所有tools.

> 确认。需要改的tools（都有 `agent_thought` 字段）：MobileActionTool, WaitTool, DelegateTaskTool, OpenAppTool, ScratchpadTool, WriteTodosTool, AskUserTool, SystemButtonTool。共8个tool schema，全部把 `agent_thought` 移到properties列表第一位。

**8. 关于type：本来是默认clear=true。但是有些任务是在Markor文本编辑器里添加文本，type的自然语义本身也只是打字，而不包含clear。所以默认clear=false。而ACTION_SET_TEXT不支持这个。所以execution code变成了拼接text(我忘了有没有考虑cursor位置，没有的话又是bug)。现在这个设计在没有hint text的情况下是ok的，但一旦有hint text就会有问题。你看看我代码，帮我想想这里该怎么设计。**

> 看了 `NodeActionPerformer.kt:182-248`：
>
> **好消息**: Cursor位置有考虑（line 198-201 用了 `textSelectionStart`/`textSelectionEnd`），这部分没bug。
>
> **问题根源**: Line 197 `val existing = node.text?.toString() ?: ""`。空字段显示hint text时，`node.text` 返回hint text（如 "Playlist name"），不是 `""`。
>
> **推荐fix**:
> ```kotlin
> // Line 197, 替换:
> val existing = node.text?.toString() ?: ""
> // 为:
> val existing = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
> ```
> `AccessibilityNodeInfo.isShowingHintText` 是 API 26+ (Android 8.0)，minSdk应该够。精确区分hint vs user input。
>
> Double-safe fallback:
> ```kotlin
> val existing = when {
>     node.isShowingHintText -> ""
>     node.text != null && node.hintText != null
>         && node.text.toString() == node.hintText.toString() -> ""
>     else -> node.text?.toString() ?: ""
> }
> ```
>
> clear=true路径不受影响（先set ""再set text）。整体设计sound，只需要这一行fix。

Qi Note: 好的，这么改改试试。加点test case覆盖有hint text的情况。

> 收到。Round 2实现：(1) `isShowingHintText` fix in NodeActionPerformer (2) 加unit test覆盖：hint text field + clear=false、hint text field + clear=true、有existing text + clear=false、空field无hint + clear=false。

**9. RecipeDeleteDuplicateRecipes2 到底是agent只delete了3个，还是agent delete了4个但scorer只发现了3个？如果是前者，那就是agent的under-counting问题；如果是后者，那就是scorer的识别问题。这个需要确认一下。**

> **Over-delete，不是under-delete。** Agent删了4个recipe（不是3个）。
>
> DB里有12个recipes：2个exact duplicates（target）+ 10个noise。Noise里故意有3个same-title-same-description variants（但hidden fields如source_url、servings不同）。Agent在list view无法区分这些visually identical entries，多删了。
>
> Scorer要求：删exactly 1个target + 其余11个全部intact（`validate_rows_removal_integrity`: target removed + ALL other rows still present + no new rows）。Agent删了4个 → collateral damage到noise rows → 0.0。
>
> 这是task design intentional的陷阱（test agent是否over-zealous），不是undercounting。配合Q2决定，放到cannot_handle合理。

Qi Note: 行，先放cannot_handle，我感觉这个也算定义不清楚。两个问题自己对于什么叫exact duplicate的理解都不一样。

> 同意。Task把same-title-same-description但hidden fields不同的entries也混进去，然后要求agent只删"exact duplicate"——但agent只能看到title和description，无法判断hidden fields。定义模糊 + 信息不对称 = 不合理的评测。cannot_handle合理。RecipeDeleteDuplicateRecipes2 和 ExpenseDeleteDuplicates2 一起加。

**10. Markor file extension tip: sounds good.**

> 确认，Round 2加到 StandaloneAgentDef.kt 的 Markor tips 里。

**11. 我的LLM应该本来就有retry logic？**

> **已经有了。** `CloudStreamRetryRunner.kt` 实现了 MAX_RETRIES=5 + exponential backoff (1s→60s)。Retry policy在 `CloudStreamRetryPolicy.kt`，只对 `RateLimitException` 和 `TransientException` retry。
>
> RecipeAddMultipleRecipesFromImage 的SSE error可能被 `OpenAIErrorClassifier` 分类为了non-retryable的 `RuntimeException`。需要检查error classifier逻辑，看是否需要把SSE stream中断也归为transient error。P6 severity降级为LOW（已有retry infra，只需调整error分类）。

**12. 我现在把write_todo给turn off了，长程task是不是需要它？考虑把它加回来？**

> 当前在 `eval/config/default.yaml` 里 `excluded_tools: "ask_user,write_todos"` 关闭。
>
> 对长程multi-step tasks（RecipeAddMultipleRecipes、ExpenseAddMultiple等），write_todos能帮agent跟踪进度、避免遗漏或重复操作。建议：
> - **Re-enable write_todos**: 从excluded_tools移除，只保留 `excluded_tools: "ask_user"`
> - System prompt加soft guidance: "对需要重复操作的任务（添加/删除多个items），先用write_todos规划步骤并逐步标记完成"
> - 可以在Round 2一起做，作为一个low-risk improvement