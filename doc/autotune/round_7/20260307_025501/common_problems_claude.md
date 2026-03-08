# Round 7 Eval Analysis — Common Problems

**Run ID**: `20260307_025501` | **Date**: 2026-03-07 | **Model**: qwen3.5
**Tasks**: 8 evaluated | **Pass Rate**: 1/8 = 12.5% | **Total Score**: 1.5/8

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| RecipeAddMultipleRecipesFromImage | 0.0 | 50 | TurnExhaustion |
| RecipeAddMultipleRecipesFromMarkor | 0.0 | 50 | TurnExhaustion |
| SportsTrackerActivitiesCountForWeek | 0.0 | 30 | TurnExhaustion + FalseCompletion |
| TasksCompletedTasksForDate | 0.0 | 14 | StateCorruption + SelfContradiction |
| TasksDueNextWeek | 0.0 | 7 | FalseCompletion |
| TasksIncompleteTasksOnDate | 0.0 | 11 | DestructiveExploration |
| VlcCreatePlaylist | 1.0 | 20 | Success |
| VlcCreateTwoPlaylists | 0.5 | 30 | TurnExhaustion |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Success | 1 | VlcCreatePlaylist |
| TurnExhaustion | 4 | RecipeAddMultipleRecipesFromImage, RecipeAddMultipleRecipesFromMarkor, SportsTrackerActivitiesCountForWeek, VlcCreateTwoPlaylists |
| DestructiveExploration / StateCorruption | 2 | TasksCompletedTasksForDate, TasksIncompleteTasksOnDate |
| FalseCompletion | 2 | TasksDueNextWeek, SportsTrackerActivitiesCountForWeek |

## Previous Round Comparison

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| Loop detection redesign (remove all WARNING advisories) | **Effective** | VlcCreateTwoPlaylists 0.0→0.5; Recipe tasks used all turns instead of self-terminating; no regressions |
| Tasks app skill (org.tasks/SKILL.md) | **Not injected** | App skill was created but not loaded — package name mismatch likely |
| OpenTracks skill (verify wording) | Partial | Agent used edit flow (R6: didn't) but ran out of turns |
| System prompt rule 6 rewrite | Working | No premature pivots observed |

## Common Problems

### P1: One-Action-Per-Turn Architecture Bottleneck — HIGH — 4 tasks

**Affected**: RecipeAddMultipleRecipesFromImage, RecipeAddMultipleRecipesFromMarkor, SportsTrackerActivitiesCountForWeek, VlcCreateTwoPlaylists

**Root Cause**: The agent architecture allows at most one screen-changing action per turn. For form-filling (Recipe tasks: 6 fields × click+type per recipe = ~16 turns/recipe), file-by-file operations (VLC: 3 turns/file for long_press+menu+select), and item-by-item inspection (OpenTracks: 4-5 turns/track to open→More→Edit→back→back), the per-item cost exceeds what the turn budget allows. RecipeAddMultipleRecipesFromMarkor saved 2/3 recipes (R6: 1/3 partial). VlcCreateTwoPlaylists completed 1/2 playlists (R6: 0/2). Both are directly improved by the loop detection removal but still exhausted turns.

**Proposed Fix**:
- Primary: implement a batch form-fill tool (`batch_type` or `fill_form`) that fills multiple text fields in a single turn
- Secondary: teach VLC multi-select in app skill (after long-press, tap to select additional items → reduces 3 turns/file to ~1)
- Tertiary: for information-extraction tasks like SportsTracker, use shell + SQLite as a shortcut (1-2 turns instead of N×5)

Qi Note:
- 我可以把system不再要求一次只有一个screen action吗，prompt里只有click/swipe/scroll有限制，但是鼓励填表的时候可以type多个？这个会有问题吗？比如element不稳定之类导致multi type actions无法有效执行？看看reference mobile agent都怎么处理的 (sop/adhoc/reference_analysis.md)
- 先不搞 shell+SQLite了 app skill。这个可能会有权限问题，同步问题等等。

Claude Reply:
**Reference agents调研结果**：5个reference agent里，4个（MAI-UI, MobileAgent V3, DroidRun, AutoDevice）都是严格1-action-per-turn。只有Minitap是conditional multi-action：允许batch predictable actions（如form-filling type），但navigation/tap必须isolate。Minitap的做法是sequential execution + abort-on-first-failure。

**我们的约束**：两层enforcement —
1. Prompt层：StandaloneAgentDef.kt L32 "Prefer at most one screen-affecting action per turn"
2. Code层：TurnToolPolicy.kt `arbitrateToolCalls()` 硬性drop所有screen-changing tools只留第1个。`mobile_action`整体被标记为screen-changing，不区分click vs type。

**Multi-type可行性分析**：
- ✅ 可行路径：参考Minitap模式，允许同一turn多个`mobile_action(type)`调用。TurnExecutionPhaseRunner已经sequential执行 + 每个action后re-capture screen（500ms delay），所以element稳定性有一定保障。
- ⚠️ 风险：LLM在planning phase一次性指定所有field targets（element ID/bounds），但type第1个field后keyboard出现或field reflow可能导致后续target失效。不过type通常不引起大幅布局变化（不像click导航到新页面），风险可控。
- 🔧 实现：需改TurnToolPolicy允许多个`mobile_action`当且仅当都是type action（或更简单：创建`fill_form`新tool接受field array一次填多个）。
- 📊 收益：Recipe form-fill从~16 turns/recipe降到~4 turns/recipe（6 fields一次填），3 recipes从48 turns降到12 turns，完全在budget内。

**建议**：Minitap模式最稳（conditional multi-action），实现选`fill_form`新tool vs 放宽policy各有trade-off。`fill_form`更安全（封装在tool内部可以逐个执行+验证），放宽policy更通用但需要LLM正确判断何时可以batch。推荐先尝试放宽prompt+policy（成本低），如果element stability出问题再fallback到fill_form tool。

**Shell+SQLite**：收到，R8不做。

Qi Note:
Minitap是单独实现了fill_form tool吗？还是generically让一个turn出现多个type？

Claude Reply:
**Generic多action，没有fill_form tool。** 具体实现：
- Cortex（决策层）输出JSON array：`"[{action1}, {action2}, ...]"`，每个action是独立的tap/type/swipe
- Executor（执行层）LLM解析JSON → 生成多个独立tool call（LangChain `parallel_tool_calls=True`）
- ExecutorToolNode **sequential执行**，abort-on-first-failure
- UI tree刷新：**所有action执行完后才刷新**（下一轮Contextor pass），不是每个action之间刷新
- 隔离规则：`back`, `launch_app`, `open_link`等navigation action必须单独一个turn

**对比我们的架构**：
- 我们的TurnExecutionPhaseRunner已经是sequential执行，且每个action后有500ms delay + re-capture screen（比Minitap更保守/安全）
- 所以最简单的实现：放宽TurnToolPolicy允许多个`mobile_action`（不限于type），prompt里加Minitap风格的隔离规则（navigation action必须单独）
- 我们不需要新tool，只需改policy + prompt

**Confirmed design (per Qi review)**：
- 保持per-action re-capture（500ms + screen capture），不改为Minitap的all-at-once刷新。多花500ms/action换更好的observability和false-success detection。
- 加abort-on-first-failure：action execution报failure时skip剩余actions。TurnExecutionPhaseRunner加check即可。
- 实现scope：TurnToolPolicy放宽（允许多个mobile_action）+ prompt加navigation隔离规则 + execution phase加abort-on-failure。

---

### P2: Tasks App Skill Not Injected — HIGH — 3 tasks

**Affected**: TasksCompletedTasksForDate, TasksDueNextWeek, TasksIncompleteTasksOnDate

**Root Cause**: The org.tasks/SKILL.md file was created but per-task analysis shows no evidence the agent received the skill guidance. Agent behavior (clicking checkboxes, not resolving dates, not scrolling) matches R6 pre-skill behavior. Likely cause: the app skill injection pipeline matches on the foreground package name, and `org.tasks` may not be the exact package name the Tasks.org app uses on this device. Need to verify with `adb shell dumpsys window | grep mCurrentFocus` while Tasks.org is open.

**Proposed Fix**:
- Verify the actual Tasks.org package name on the emulator and rename the skill directory accordingly
- For TasksCompletedTasksForDate and TasksIncompleteTasksOnDate: add guidance to NOT click checkboxes (they toggle state). Instead read checked/unchecked property from a11y node attributes.

Qi Note: 好。

Claude Reply: 收到，R8执行：verify package name + rename skill dir + add checkbox guidance。

---

### P3: Destructive Observation (Checkbox Toggle) — MED — 2 tasks

**Affected**: TasksCompletedTasksForDate, TasksIncompleteTasksOnDate

**Root Cause**: Agent clicks checkboxes to "inspect" completion status, but clicking toggles the state. TasksCompletedTasksForDate wasted turns and contradicted its own scratchpad. TasksIncompleteTasksOnDate spent 6 of 11 turns on failed undo attempts. This is a general anti-pattern: the agent treats all UI elements as read-only, not realizing some clicks have side effects.

**Proposed Fix**:
- Add to core prompt or Tasks app skill: "For information tasks, observe element properties (checked, selected) from the screen state. Do not click toggle controls (checkboxes, switches) unless you intend to change them."
- Check if the a11y tree exposes `isChecked` property for checkboxes — if so, the agent can read state without clicking

Qi Note:
1. task app skill，这个不值得加core prompt。core prompt需要very careful，只加最必要的东西。
2. SGTM。

Claude Reply:
1. 同意。移到Tasks app skill而非core prompt。R8 Next Steps #4已更新：owner layer从core prompt改为app skill。
2. 收到，会verify a11y tree是否暴露`isChecked`属性。

---

### P4: Scroll Overlap Double-Counting — LOW — 1 task

**Affected**: TasksDueNextWeek

**Root Cause**: Agent scrolled and recorded tasks but likely double-counted items visible in overlapping viewports. Answered "20" (evaluator disagreed). Without deduplication, scroll-based enumeration is inherently error-prone.

**Proposed Fix**:
- Add enumeration guidance to org.tasks skill: "When counting items across scrolls, use anchor items (first/last visible) to detect overlap. Write each viewport's unique additions to scratchpad."

Qi Note:
这个可能有几个原因。
- 它显示Tue，可能是这周也可能是下周。我看有不一样颜色，不知道是不是颜色区分了。这个需要确认一下。
- 另外就是有些crossed out了，这些应该不算due了。agent不知道是不是a11y tree里没有保留属性来区分(e.g., isChecked)。
- agent倒是没有算duplicate，或者double counting。

Claude Reply:
收到，P4分析有误。修正root cause：
1. **Tue歧义**：同意，"Tue"可能是本周也可能是下周。颜色区分在screenshot里可见但a11y tree可能不暴露颜色信息。R8需要确认：(a) Tasks.org用什么属性区分overdue vs upcoming，(b) a11y tree是否包含该属性。
2. **Crossed out = completed**：同意，这些应该不算"due"。需要确认a11y tree里crossed-out items是否有`isChecked=true`或其他可区分属性。
3. **非double-counting**：我原来的分析有误，撤回scroll overlap判断。实际问题是agent无法区分同名日期label的不同周 + 无法识别completed items。

这些都可以在Tasks app skill里加guidance解决，前提是R8先确认a11y tree暴露了哪些属性。

Qi Note:
- 现在cog-tune的分析质量不高。修改 cog-tune skill和autotune skill里调用cog-tune的部分，要求subagent分析每个task，这样context clean。然后每个task的template文件里需要逐个看每个turn，分析每个turn reasoning是否合理，action call/params是否合理，action execution是否成功（尤其提醒很多action，比如click，可能报成功，但是前后screen其实没变，没有真成功，需要逐帧看screen state来验证）。必要的时候看screenshot image，来visually对比a11y tree里是不是缺了什么，导致agent有weird behavior，来discover potential的perception improvement。

Claude Reply:
收到。这是重要的质量提升。R8开始前先改cog-tune skill：
1. **autotune skill**：Step 4已经要求subagent per task（"Use subagent so you have cleaner context"），但需要更explicit地enforce。
2. **cog-tune per-task template**：增加mandatory turn-by-turn analysis section。一个表格，每个turn一行，覆盖下面方面，一行放不下的key turn可以底下再展开。每turn要求：
   - Reasoning质量评估（LLM thought是否合理）
   - Action call + params正确性（target element是否match intent）
   - Action execution验证（**对比pre/post action screen state**，flag "reported success but screen unchanged" = false success）
   - 当turn有weird behavior时，要求看screenshot image对比a11y tree，发现perception gap
3. **False-success detection emphasis**：特别强调click等action可能report success但UI没变，需要逐帧screen state验证。

这个改动在R8 Step 1之前做，这样R8的analysis quality就能提升。

---

## Next Steps (Updated per Qi Notes)

### Pre-Round Setup
0. **Upgrade cog-tune analysis quality**: Modify cog-tune skill template to require turn-by-turn analysis (reasoning, action correctness, execution verification with pre/post screen comparison, screenshot review for perception gaps). Update autotune skill's Step 4 to enforce subagent-per-task.
   - Implementation: `.ai-dev/skills/cog-tune/SKILL.md`, `.ai-dev/skills/cog-tune/assets/per_task_analysis_template.md`, `.ai-dev/skills/autotune/SKILL.md`

### Must Fix
1. **Verify and fix Tasks app skill injection**: Check actual package name for Tasks.org on emulator (`adb shell pm list packages | grep task`). Rename `app_skills/org.tasks/` to match. Add checkbox/toggle observation guidance + date-label disambiguation + completed-item filtering guidance (all in app skill, NOT core prompt).
   - Targets: TasksCompletedTasksForDate, TasksDueNextWeek, TasksIncompleteTasksOnDate
   - Implementation: `app/src/main/assets/app_skills/<correct_package>/SKILL.md`
   - Owner layer: app skill
   - R8 prerequisite: verify a11y tree exposes `isChecked`, color/overdue attributes

2. **VLC multi-select teaching**: Add to VLC app skill: "After long-pressing one file to enter selection mode, tap additional files to select them. Then use 'Add to playlist' to add all at once."
   - Targets: VlcCreateTwoPlaylists
   - Implementation: `app/src/main/assets/app_skills/org.videolan.vlc/SKILL.md`
   - Owner layer: app skill

### Should Fix
3. **Multi-action per turn (Minitap-style)**: Allow multiple `mobile_action` calls per turn. Keep per-action re-capture + add abort-on-first-failure. Prompt adds navigation isolation rule (click that navigates to new screen must be alone in turn).
   - Targets: RecipeAddMultipleRecipesFromImage, RecipeAddMultipleRecipesFromMarkor, VlcCreateTwoPlaylists
   - Implementation (~15-20 lines code change + test updates):
     - `TurnToolPolicy.kt` L63: `screenCalls.firstOrNull()` → keep all `screenCalls`. Update KDoc L12.
     - `TurnExecutionPhaseRunner.kt` L51: add abort-on-failure check in for loop (break if `toolResult is ToolCallResult.Error`).
     - `StandaloneAgentDef.kt` L32: replace "Prefer at most one screen-affecting action per turn" with navigation isolation rule.
     - `TurnToolPolicyTest.kt`: update test expectations (no longer drop 2nd screen action).
   - Owner layer: core prompt + infra
   - Risk: element stability — monitor in R8 eval

### Accept as Capability Gap
- ~~Shell+SQLite shortcut~~ (dropped per Qi Note — permission/sync risks)
- RecipeAddMultiple* tasks: if multi-type relaxation works, these become feasible. If not, park as architecture gap.
