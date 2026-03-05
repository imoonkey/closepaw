# Group 4 Round 1 Re-eval — Common Problems Summary

**Run ID**: 20260227_222506
**Tasks**: 28 (20 group 4 + 8 group 2/3 re-runs)
**Status**: Complete — 28/28 finished (1 infra failure: VlcCreateTwoPlaylists)
**Final Score**: 14/27 scored tasks passing (51.9%)
**Scripted Success Rate**: 46.4% (includes infra failures as 0)
**Goal Claim Precision**: 61.1% (agent said "success" but scorer said 0.0 in ~39% of cases)

## Final Scorecard

| # | Task | Score | Root Cause Category |
|---|------|-------|----------|
| 1 | ExpenseDeleteDuplicates2 | 0.0 | FalseCompletion |
| 2 | MarkorAddNoteHeader | 0.0 | ActionFailure |
| 3 | MarkorCreateNoteAndSms | **1.0** | — |
| 4 | MarkorDeleteAllNotes | **1.0** | — |
| 5 | MarkorDeleteNewestNote | **1.0** | — |
| 6 | MarkorEditNote | 0.0 | ActionFailure |
| 7 | MarkorMergeNotes | 0.0 | NavigationFailure |
| 8 | MarkorMoveNote | **1.0** | — |
| 9 | MarkorTranscribeVideo | 0.0 | PerceptionGap |
| 10 | OsmAndMarker | 0.0 | NavigationFailure |
| 11 | OsmAndTrack | 0.0 | NavigationFailure |
| 12 | RecipeAddMultipleRecipes | **1.0** | — |
| 13 | RecipeAddMultipleRecipesFromImage | 0.0 | TurnExhaustion |
| 14 | RecipeAddMultipleRecipesFromMarkor | 0.0 | FalseCompletion |
| 15 | RecipeDeleteDuplicateRecipes2 | 0.0 | FalseCompletion |
| 16 | RecipeDeleteMultipleRecipes | **1.0** | — |
| 17 | RecipeDeleteMultipleRecipesWithNoise | **1.0** | — |
| 18 | RetroPlayingQueue | **1.0** | — |
| 19 | RetroPlaylistDuration | 0.0 | FalseCompletion |
| 20 | SimpleCalendarAnyEventsOnDate | **1.0** | — |
| 21 | SimpleCalendarEventOnDateAtTime | **1.0** | — |
| 22 | SimpleCalendarEventsInNextWeek | 0.0 | InfraError (LLM timeout) |
| 23 | SimpleCalendarLocationOfEvent | **1.0** | — |
| 24 | SimpleCalendarNextMeetingWithPerson | **1.0** | — |
| 25 | SimpleSmsReplyMostRecent | 0.0 | InfraError (emulator SMS) |
| 26 | SportsTrackerActivitiesOnDate | 0.0 | FalseCompletion |
| 27 | TasksHighPriorityTasks | **1.0** | — |
| 28 | VlcCreateTwoPlaylists | N/A | InfraError (task init crash) |

### Summary by Root Cause

| Category | Count | Tasks |
|----------|-------|-------|
| **Pass** | 14 | — |
| FalseCompletion | 5 | ExpenseDeleteDuplicates2, RecipeAddMultipleRecipesFromMarkor, RecipeDeleteDuplicateRecipes2, RetroPlaylistDuration, SportsTrackerActivitiesOnDate |
| ActionFailure | 2 | MarkorAddNoteHeader, MarkorEditNote |
| NavigationFailure | 3 | MarkorMergeNotes, OsmAndMarker, OsmAndTrack |
| PerceptionGap | 1 | MarkorTranscribeVideo |
| TurnExhaustion | 1 | RecipeAddMultipleRecipesFromImage |
| InfraError | 3 | SimpleCalendarEventsInNextWeek, SimpleSmsReplyMostRecent, VlcCreateTwoPlaylists |

### Adjusted Score (excluding infra errors)
**14/25 = 56.0%** when excluding the 3 infra failures the agent couldn't control.

## Common Problem Categories

### P1: `type` Action — clear=false Still Replaces Content (ActionFailure) — 2 tasks
**Affected**: MarkorAddNoteHeader, MarkorEditNote
**Root Cause**: `NodeActionPerformer.setTextOnNode()` (line 182-220) always uses `ACTION_SET_TEXT` regardless of `clear` parameter. When `clear=false`, the expected semantic is "insert at cursor without clearing existing content", but the actual behavior is still full text replacement. The difference from `clear=true` is only that `clear=true` does an extra empty-string ACTION_SET_TEXT call before setting text — both paths replace everything.
**Impact**: Any task requiring text insertion (prepend, append, insert at cursor) silently destroys existing content.
**Fix — Code change in NodeActionPerformer**:
When `clear=false`:
1. Read current text from the node (`node.text`)
2. Get cursor position (selection start/end from AccessibilityNodeInfo)
3. Insert the new text at cursor position within the existing text
4. Call `ACTION_SET_TEXT` with the combined text
5. Restore cursor to expected position (after inserted text)

This makes `type(clear=false)` actually mean "insert at cursor" as the tool definition implies. No new action type needed — `type` without `clear=true` should already have this semantic.

**Prompt change**: Once the code fix is in, add to tool docs: "`type` with `clear=false` (default) inserts text at the current cursor position without clearing existing content. Use `clear=true` to replace the entire field."

**Decision**: Fix execution layer, not prompt. No shell workaround — shell can't solve non-file text fields. No new `insert_text` action — `type(clear=false)` already has the right semantic, execution just needs to match it.

### P2: Duplicate Detection — LLM Reasoning Gap (FalseCompletion) — 3 tasks
**Affected**: ExpenseDeleteDuplicates2, RecipeDeleteDuplicateRecipes2, RecipeAddMultipleRecipesFromMarkor
**Root Cause**: Eval task says "delete exact duplicates." Agent interprets as "entries matching on ALL fields" while eval defines duplicates by primary identifier (name/title) only. Two factors: (1) ambiguous task wording; (2) qwen3.5's common sense reasoning is too literal.
**Impact**: Agent leaves duplicates undeleted.
**Assessment**: Model reasoning quality issue. Prompt hint would be low ROI and risks overfitting.
**Decision**: 先不做。等换模型后观察是否自然解决。

### P3: Markor Navigation Failure (NavigationFailure) — 1 task
**Affected**: MarkorMergeNotes
**Root Cause**: Agent couldn't navigate from Markor's editor view back to the file list. Spent 14 turns trying different UI paths (toolbar buttons, More options, system back).
**Impact**: Agent never created the merged file.
**Fix**:
1. **App-specific tip** (add to prompt's App Tips section): "In Markor, to return from editor to file list: tap the left-arrow/Navigate Up button in the toolbar's top-left corner. The system Back button also works."
2. **Shell guardrail prompt** (general, not Markor-specific):
   ```
   ### shell
   Use shell ONLY when:
   - The task is file management (create/read/write/move/delete files on disk)
   - You are confident shell is the best tool (e.g., querying content providers, checking system state)
   - The target data is accessible from the shell (known path, proper permissions)

   Do NOT use shell to interact with app-internal data — most apps store data in private databases
   or app-specific directories that shell cannot access. If you try 2 shell commands that fail
   (permission denied, file not found, no effect), switch to UI-based approach immediately.
   ```

### P4: OsmAnd UI Inaccessible (NavigationFailure) — 2 tasks
**Affected**: OsmAndMarker, OsmAndTrack
**Root Cause**: OsmAnd's map interface is largely invisible to a11y. Most controls aren't exposed. Small town searches return country-level results only.
**Impact**: Both OsmAnd tasks fail completely.
**Fix**:
1. **Task overrides**: Add `{ perception_mode: hybrid }` for OsmAnd tasks in `eval/config/default.yaml`.
2. **No prompt tips yet** — don't add unverified OsmAnd knowledge. Re-evaluate after running with hybrid mode.

### P5: Visual Content Perception — Model Capability Limit (PerceptionGap) — 1 task
**Affected**: MarkorTranscribeVideo
**Root Cause**: **VERIFIED: Hybrid mode IS working.** Both MarkorTranscribeVideo and RecipeAddMultipleRecipesFromImage have `screenshot_attached: true` on all 30 LLM requests (checked trace.jsonl). The override pipeline works: `resolve_task_bridge_config()` → Intent extras → `MainActivityIntentApplier` → `PerceptionConfig.Hybrid()`.

The actual issue is **model capability** — qwen3.5 cannot effectively extract text from images/video frames even when screenshots are provided.

**Note**: RecipeAddMultipleRecipesFromImage was originally classified here but has been **reclassified to TurnExhaustion** after full 30-turn analysis. Hybrid mode actually worked well — the agent successfully read 3 recipes from the image in a single turn. The failure was purely turn budget (37 turns needed, 30 available). See per_task/RecipeAddMultipleRecipesFromImage_claude.md for full analysis.

**Impact**: Tasks requiring reading text from images/video fail despite correct screenshot delivery.
**Assessment**: Fundamental model quality limitation. Options: better model, OCR tooling, or prompt strategy for video. 先不管了。

### P6: Turn Exhaustion — Semantic Loops (NavigationFailure) — 3 tasks
**Affected**: MarkorMergeNotes (30 turns), MarkorTranscribeVideo (30 turns), OsmAndTrack (30 turns)
**Root Cause**: Agent exhausts turn budget without completing the task.

**Loop detection status — current implementation summary**:

The loop detection system lives in `LoopDetectionPolicy.kt`. It covers both screen-level and action-level:

**Screen-level detection:**
- **Cycle detection**: Multi-state cycle patterns (e.g., A→B→C→A→B→C, same screen appearing 3+ times in history), using Jaccard similarity on screen element tokens
- **Stable screens**: N consecutive identical screens (85%+ similarity)

**Action-level detection (implemented):**
- **Repeated identical actions**: Same ActionSignature appearing 3+ consecutive times → WARNING
- **Scroll dominance**: 4+ consecutive scrolls → WARNING
- **Tool dominance**: Same tool type 3+ consecutive times with no progress → WARNING

**ActionSignature format** (ActionSignature.kt:20-78): `"mobile_action:click:idx=12"`, `"mobile_action:click:text=save"`, `"scroll:down"`, `"open_app:markor"`, `"shell"` etc.

**Progress gate**: If consecutive similar screens have ≥2 token content changes, CRITICAL downgrades to WARNING (avoids false positives).

**3-tier escalation**:
| Tier | Level | Trigger | Action |
|------|-------|---------|--------|
| 1 | ADVISORY | First CRITICAL | Inject warning into LLM prompt |
| 2 | BLOCK | ≥2 consecutive CRITICAL (configurable) | Block repeated action + force rewrite directive |
| 3 | FORCE_COMPLETE | ≥5 consecutive CRITICAL (configurable) | Force `complete_task(status=failure)` |

**Why turn exhaustion still happens**: Loop detection catches *identical* action repeats, but in these failure cases the agent tries *semantically similar but technically different* actions (clicking different elements, trying different menu paths). Each action signature is different, so the identical-action detector doesn't trigger. Screens may also vary slightly due to menus opening/closing.

**Fix**:
1. **General strategy-pivot prompt**: "If you have tried 3+ different approaches to achieve the same sub-goal without success, stop and reassess. Consider: (a) Is there a completely different path? (b) Can you use shell commands instead of UI? (c) Should you skip this step and try the next part of the task?"
2. **Semantic loop detection** (future): Detect when different actions lead to the same observable state repeatedly. Requires comparing *outcome* state across turns, not just action signatures.

### P7: Semantic Field Confusion in QA Tasks (FalseCompletion) — 1 task
**Affected**: SportsTrackerActivitiesOnDate
**Root Cause**: Agent answered with track NAMES instead of activity TYPES. The system prompt already instructs on this distinction but qwen3.5 ignored it.
**Impact**: QA answers use visually prominent but semantically wrong data.
**Fix**:
1. **OpenTracks app-specific tip**: "In OpenTracks, the track list shows track names, NOT activity types. To find the activity type (e.g., 'running', 'walking'), you must tap into each track's detail view."
2. **General pre-completion verification prompt**: "Before completing a QA task, re-read the goal and verify your answer matches the exact field/attribute requested (e.g., 'type' vs 'name', 'location' vs 'title')."
   - Keep this general. No overfitting.

### P8: Infra Reliability (InfraError) — 3 tasks
**Affected**: SimpleCalendarEventsInNextWeek, SimpleSmsReplyMostRecent, VlcCreateTwoPlaylists

**SimpleCalendarEventsInNextWeek**: LLM API never returned a response for 15 min; agent executed 0 actions. TODO:Qi — investigate whether this was truly a timeout or something else.

**SimpleSmsReplyMostRecent**: Agent used the correct app (`com.simplemobiletools.smsmessenger`). Scoring checks `content://sms/sent` DB + foreground app. Agent satisfied the foreground app check but the SMS wasn't written to the content provider despite appearing as a sent bubble in the UI. This is an **infra/emulator setup issue** — the emulator's telephony stack didn't process the outbound SMS (status bar showed "No internet", send button initially disabled).

**VlcCreateTwoPlaylists**: `initialize_task()` threw `RuntimeError: VlcCreateTwoPlaylists.initialize_task() is already called.` Fix needed in eval harness.

**Decision**: SMS infra 问题暂不修，优先级低。VLC init bug 需要在 eval harness 里修。LLM timeout TBD by Qi.

## Priority Ranking (Updated)

1. **P1 (type clear=false replaces)** — HIGH. Code fix in `NodeActionPerformer.setTextOnNode()`. 2 tasks + any future insert-at-cursor tasks.
2. **P5 (model can't read images/video)** — HIGH. Hybrid mode confirmed working; need stronger vision model or OCR tool. 1 task (MarkorTranscribeVideo). RecipeAddMultipleRecipesFromImage reclassified to TurnExhaustion.
3. **P2 (duplicate reasoning)** — MEDIUM. LLM reasoning quality. 先不做，等换模型后看. 3 tasks.
4. **P3 (Markor navigation)** — MEDIUM. Add app-specific tip + shell guardrail prompt. 1 task.
5. **P6 (turn exhaustion / semantic loops)** — MEDIUM. General strategy-pivot prompt. 3 tasks.
6. **P7 (field confusion)** — MEDIUM. App tip + general verify prompt. Not overfitting. 1 task.
7. **P4 (OsmAnd UI)** — LOW. Add hybrid mode override. Don't add unverified prompt tips. 2 tasks.
8. **P8 (infra)** — MIXED. VLC init bug fixable. SMS is emulator issue, 先不修. LLM timeout TBD by Qi.

## What Improved from Group 2/3

- **Loop detection FP**: NOT observed — the progress-gate fix is working. Zero false positive loop terminations.
- **Calendar tasks**: 4/5 passing (vs likely 0/5 before fixes). The 1 failure was LLM infra, not agent error.
- **Recipe tasks**: 3/5 passing. Failures are turn exhaustion (image — agent workflow correct, needs more turns), reasoning (duplicates from Markor), not loop issues.

## Achievable Score Ceiling

**Current**: 14/27 = 51.9%

Fixable with code/prompt changes:
- P1 code fix: +2 (MarkorAddNoteHeader, MarkorEditNote)
- P2 prompt hint: +1~3 (depends on LLM reasoning improvement)
- P3 app tip: +1 (MarkorMergeNotes)
- P7 app tip: +1 (SportsTrackerActivitiesOnDate)

Requires model upgrade or new tooling:
- P5 vision: +1 (MarkorTranscribeVideo) — needs better model or OCR tool

Requires increased turn budget:
- RecipeAddMultipleRecipesFromImage: +1 if max_turns increased to 40+ (agent workflow is correct, just needs more turns)

Infra fixes:
- P8: +1~2 (VLC init fix, SMS if emulator fixed)

Hard problems (no clear fix):
- RetroPlaylistDuration (complex duration calculation)
- OsmAndMarker/OsmAndTrack (a11y-hostile UI, even hybrid may not be enough)

**Realistic target with P1+P3+P7 fixes**: ~18/27 = 66.7%
**Theoretical ceiling with all fixes**: 25/27 = 92.6%

---

## Appendix: Qi Notes & Discussion

### P1 Discussion

(
    Qi Note:
    1.这个在于现在的type工具背后execution实现和prompt里的语义不一致。正常情况下clear如果是true，那晴空是理所应当的。clear如果是false，那可能不该用ACTION_SET_TEXT，或者即便用ACTION_SET_TEXT，也要execution去找当前cursor在文本的什么位置，然后把type的文本插入到原有文本相应位置，然后set完整文本back to the box，同时保持cursor在应该的位置。
    2. shell is not the right answer。万一在编辑的文本框不是disk文件呢，或者shell没permission呢。这个不解决根本问题。It's the answer to the wrong problem。
    3. Could we implement an `insert_text` action that truly inserts at cursor position? 回答：type without clear=true不是本来就该是这个语义吗？不然呢？
)

**Claude Answer to Qi Notes:**
1. 同意。验证了代码：`setTextOnNode()` (NodeActionPerformer.kt:182-220) 在 `clear=false` 时仍然直接用 `ACTION_SET_TEXT(text)` 替换全部内容，和 `clear=true` 唯一区别是少一次空字符串的 ACTION_SET_TEXT 调用。Fix 方案已改为你说的路径：`clear=false` 时，读 `node.text` 获取现有文本，通过 `AccessibilityNodeInfo.getTextSelectionStart()/End()` 获取 cursor 位置，把 input text 插入到 cursor 处，然后用 `ACTION_SET_TEXT` 设回完整文本，最后设 cursor 到插入文本之后。
2. 同意。已删除 shell workaround 方案。shell 只能解决 Markor 这一个特例，对非文件场景（表单、搜索框、聊天输入框等）完全无用。根本问题在 execution 层。
3. 同意。不需要新建 `insert_text` action。`type(clear=false)` 就应该是 insert-at-cursor 的语义，现在是 execution 实现没做对。修正 `setTextOnNode()` 即可。

### P2 Discussion

(Qi Note:
    1. 这个是eval task表述不清楚吗？这个是不是有点太task specific了？这个感觉更像是题意理解不同的问题，或者llm model的common sense reasoning有点差？
)

**Claude Answer to Qi Note:**
确实是两个因素叠加：(1) eval task 表述模糊——"delete exact duplicates" 没有定义 "duplicate" 到底匹配哪些字段；(2) qwen3.5 的 common sense reasoning 不够——人类直觉上 "重复的费用条目" 就是同名条目，但 qwen3.5 过于 literal 地理解为所有字段都要一样。
加一个 task-specific 的 prompt 太 hacky。上面改为了一个轻量的通用 hint（"focus on primary identifier"），但这本质上还是在弥补模型推理能力的不足。如果换一个更强的模型，可能就不需要这个 hint 了。这个 fix 的 ROI 不高，可以先不做，等换模型后看看是否自然解决。

（Qi Note 2nd round: 同意，先不做了。）

**Claude Answer to Qi Note round 2:**
同意。P2 不做 prompt change，等换模型后观察。

### P3 Discussion

（Qi Note:
1. 可以加到 app-specific tips里去。
2. 先别实现这个了。shell确定work吗？能access Marokor files吗？另外就是本地文件改了，Markor app里会反映出来吗。
）

**Claude Answer to Qi Notes:**
1. 同意，加 app-specific tip。已更新为只保留这个方案。
2. 同意，已删除 shell 方案。未验证的问题包括：(a) Markor 默认存储路径是 `/storage/emulated/0/Documents/markor/`，shell 需要对应权限；(b) 即使 shell 修改了文件，Markor 不一定会实时刷新显示（可能有文件缓存/MediaStore 同步问题）；(c) 在 eval 场景下，scorer 检查的可能是 Markor app 的数据库状态，不是文件系统。这些都没验证过，不应该推荐。

(Qi Note round 2:
1. 好的。
2. 关于shell的使用，加个prompt：只在file management或者确定shell为最佳工具情况下使用，在别的app都会有permission问题，不确定是否有效的时候别用。)

**Claude Answer to Qi Note round 2:**
1. 好的。
2. 同意。加一个通用的 shell guardrail prompt（已更新到主文的 P3 Fix 部分）。这个既覆盖了 Markor 场景（file management 可以用 shell），又限制了在别的 app（如 Broccoli recipe DB、SMS content provider 等）滥用 shell 的情况。

### P4 Discussion

(
    Qi Note:
    1. 嗯 hybrid肯定可以加。
    2. Prompt change要加确实有用的。现在不知道的话，先不加。
)

**Claude Answer to Qi Notes:**
1. 同意。可以直接在 `eval/config/default.yaml` 的 `task_overrides` 里加 `OsmAndMarker: { perception_mode: hybrid }` 和 `OsmAndTrack: { perception_mode: hybrid }`。
2. 同意。不加未验证的 prompt tips。等加了 hybrid 后跑一次 eval run，看看 agent 在 hybrid 模式下能看到什么，再决定是否需要 app-specific tips。

### P5 Discussion

(
    Qi Note:
    1. 你现在就可以直接verify trace啊，看看llm request里有没有加image。task_overrides确保把它们用hybrid mode了，而不是a11y only。
)

**Claude Answer to Qi Note:**
Done. 已直接验证了两个 task 的 trace：
- **MarkorTranscribeVideo** (artifact aw_20260227_222506_MarkorTranscribeVideo_9_0): `meta.json` 中 `config.screenshot_input: true`。trace.jsonl 中全部 30 个 `llm_request` 事件均为 `screenshot_attached: true`。
- **RecipeAddMultipleRecipesFromImage** (artifact aw_20260227_222506_RecipeAddMultipleRecipesFromImage_13_0): 同上，`meta.json` 有 `screenshot_input: true`，全部 30 个 llm_request 均为 `screenshot_attached: true`。

结论：hybrid mode 在 eval 中确实激活了，screenshot 确实发送给了 LLM。问题不是 infra bug，而是 qwen3.5 的 vision 能力不足，无法从截图中提取文字。之前说的 "hybrid mode may not be activating" 是误判。

（Qi Note round 2:
1. 好的，这个问题不好解决，先不管了。
2. RecipeAddMultipleRecipesFromImage 的  per_task/ analysis 没有analyze完全部30个turn。我看都进行挺好的啊，是不是只是turn不够？再多几个turn就能搞完了？还是还有别的问题？仔细分析。
）

**Claude Answer to Qi Note round 2:**
1. 同意，先不管。
2. **已完成全部 30 turn 分析（见 per_task/RecipeAddMultipleRecipesFromImage_claude.md 更新版）。你说得对——agent 做得很好，就是 turn 不够。**

   详细时间线：
   - **Turns 1-5** (navigation): 打开 Gallery → 找到 recipes.jpg → 全屏显示
   - **Turn 6** (image reading): hybrid mode 成功从截图读出 3 个 recipe，写入 scratchpad，然后 `open_app("Broccoli")`
   - **Turns 7-17** (recipe 1): Eggplant Parmesan — 完整输入所有字段 + save + navigate back = 11 turns
   - **Turns 18-27** (recipe 2): Baked Cod with Lemon and Dill — 同样完整输入 = 10 turns
   - **Turns 28-30** (wasted): 3 个 turn 花在验证已保存的 recipe（scroll、search）而不是开始 recipe 3 — **Chickpea Vegetable Soup 从未开始输入**

   **Budget 数学**: 7 (navigation) + 10×3 (recipes) = 37 turns needed, 30 available. Deficit = 7 turns. 即使省掉 3 turn 的验证浪费 + 1 turn 的 wait，也只有 ~33 turns of useful work，recipe 3 仍然会 incomplete。

   **结论**: Root cause 从 **PerceptionGap** 改分类为 **TurnExhaustion**。Hybrid mode 实际上工作得很好——agent 在一个 turn 内就从截图提取了全部 3 个 recipe。问题纯粹是 multi-item data entry 的 turn budget 不够。

   **次要问题**: Ingredients 字段用了 placeholder 文本（"varies", "subject to change", "as desired"）而不是实际 ingredient 列表。即使 3 个 recipe 都输入了，这些 placeholder 在 fuzzy matching 时可能也不 pass。但这是次要 issue，主要 blocker 是 turn budget。

### P6 Discussion

（
Qi Note:
1. 嗯，这个loop detection还解决不了是吧？ 可以加一些general principles prompt。
2. 现在loop detection是怎么做的？summarize一下，我现在觉得这里是一团浆糊。没有action-level loops吗？之前我记得要加，难道一直没加？
）

**Claude Answer to Qi Notes:**
1. 对，当前的 loop detection 解决不了 semantic loops。已加了 general strategy-pivot prompt 的建议。
2. **Action-level loops 已经加了。** 总结已写在主文 P6 部分。具体来说 LoopDetectionPolicy.kt 里有三种 action-level 检测：repeated identical actions（同一 ActionSignature 连续 3+ 次）、scroll dominance（连续 4+ 次 scroll）、tool dominance（同一 tool type 连续 3+ 次）。ActionSignature（ActionSignature.kt）能区分到 `click:idx=12` 或 `click:text=save` 的粒度。架构是清晰的。但它们只能抓 *完全相同* 的 action，抓不了 *语义相同但表面不同* 的 action（比如依次点 idx=5, idx=7, idx=9 但都是在试图达到同一个目标）。这种 semantic loop 需要更高层的检测逻辑（比如比较 action 的 outcome state 而不是 action signature）。

### P7 Discussion

（
Qi: 这种specific的事情，不能搞得太hacky，只能加app-specific tip和 general verify prompt。不能搞overfit!
）

**Claude Answer to Qi Note:**
同意。方案已调整为只有两项：(1) OpenTracks app-specific tip（这是正当的 app knowledge，不算 overfit）；(2) 通用的 pre-completion verification prompt。不加任何 programmatic gate 或 task-specific hack。

### P8 Discussion

(Qi Note:
1. llm timeout不管。而且可能不是time out? TODO:Qi
2. Simple SMS应该直接打开Simple SMS (android world specific的)？打开android system自带的messenger不行？你帮我看看scripted success的逻辑。
3. VLC: fix initialize_task bugs for VlcCreateTwoPlaylists
)

**Claude Answer to Qi Notes:**
1. 同意，不管 LLM timeout。已标记 TODO:Qi。
2. 已查看 scoring 逻辑。**必须用 Simple SMS Messenger**，不能用系统自带的。具体来说：
   - 任务模板 (sms.py:60-63): `"Reply to the most recent text message using Simple SMS Messenger with message: {message}"` — 明确指定了 Simple SMS Messenger。
   - 评分逻辑 (sms_validators.py:268-272): `in_correct_app = adb_utils.extract_package_name(...) == "com.simplemobiletools.smsmessenger"` — 硬编码检查前台 app 必须是这个 package。如果不在这个 app 里，直接返回 0.0。
   - 评分还检查 `content://sms/sent` 数据库 (sms_validators.py:214-216)。
   - Android World 选用 Simple SMS Messenger 的原因 (sms_validators.py:188-194)：default Android messaging app 有 "hidden caching issues"，UI 不会立即反映 DB 状态变化，而 Simple SMS Messenger 的 UI 和 SQLite sms table 是实时同步的。
   - 我们的 agent 打开了正确的 app（第一次试 "Simple SMS Messenger" 没找到，第二次用 "SMS Messenger" 成功打开了 `com.simplemobiletools.smsmessenger`）。评分失败是因为 emulator telephony 没把 SMS 写入 `content://sms/sent`，不是 app 选择问题。
3. 同意，VLC init bug 需要在 eval harness 里修。

（Qi Note round 2:
2. "因为 emulator telephony 没把 SMS 写入 `content://sms/sent`"，所以，这也是个infra setup的问题？
）

**Claude Answer to Qi Note round 2:**
2. 是的，这是一个 **infra/emulator setup 问题**。Agent 的行为完全正确（正确的 app、正确的对话、输入消息、点 send、UI 显示发送成功）。问题出在 emulator 的 telephony stack 没有正常工作——SMS 在 app UI 里显示为已发送（sent bubble + input field cleared），但没有写入 `content://sms/sent` 数据库（scorer 检查的就是这个 DB）。状态栏也显示 "No internet"，send button 初始状态为 disabled（2 秒后才 enable），这些都是 emulator telephony subsystem 不可靠的信号。

   分类保持 **InfraError**。修复方向：(a) 确保 emulator 有正常的 telephony configuration（SIM card、网络连接），(b) 在 eval run 前加一个 SMS send/receive 的 pre-flight check，(c) 如果 emulator telephony 无法可靠工作，考虑是否需要用真机或其他方案。

(Qi Note round 3: 这个听着有点复杂，先不修了)

**Claude Answer to Qi Note round 3:**
同意。SMS infra 问题暂不修，优先级低。等后续有需要 SMS 任务稳定通过时再排查 emulator telephony 配置。
