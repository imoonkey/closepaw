# Round 19 Eval Analysis — Common Problems

**Run ID**: `20260309_143418` | **Date**: 2026-03-09 | **Model**: gpt-5.4
**Tasks**: 9 evaluated | **Pass Rate**: 1/9 = 11.1%

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| TurnOnWifiAndOpenApp | 1.0 | 7 | Success (UI fallback after shell fail) |
| TasksHighPriorityTasksDueOnDate | 0.0 | 18 | Incomplete scroll + incomplete priority check |
| MarkorDeleteNewestNote | 0.0 | 10 | Creation-time vs modification-time confusion |
| MarkorMergeNotes | 0.0 | 17 | Skill-induced regression: shell-write guidance caused UI dialog abandonment |
| ExpenseAddMultipleFromGallery | 0.0 | 19 | Failed to scroll category RecyclerView |
| RecipeAddMultipleRecipes | 0.0 | 26 | Edit-as-create: overwrote recipe 1 with recipe 2 data |
| RecipeAddMultipleRecipesFromImage | 0.0 | 32 | Perception hallucination + missing recipe count |
| RecipeAddMultipleRecipesFromMarkor | 0.0 | 32 | Edit-as-create: same pattern as RecipeAddMultipleRecipes |
| RecipeAddMultipleRecipesFromMarkor2 | 0.0 | 24 | Fabricated Source field value |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Success | 1 | TurnOnWifiAndOpenApp |
| Reasoning (Navigation) | 3 | RecipeAddMultipleRecipes, RecipeAddMultipleRecipesFromMarkor, RecipeAddMultipleRecipesFromMarkor2 |
| Reasoning (Incomplete) | 1 | TasksHighPriorityTasksDueOnDate |
| Reasoning (Premature Give-up) | 1 | ExpenseAddMultipleFromGallery |
| Skill Misdirection | 1 | MarkorMergeNotes |
| Perception + Skill Gap | 1 | MarkorDeleteNewestNote |
| Perception (Image) | 1 | RecipeAddMultipleRecipesFromImage |

## Previous Round Comparison

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| Tasks skill: scratchpad accumulation rule | Partially working | Agent accumulated on scroll (turns 3-5) but overwrote in priority-checking phase. Did not prevent failure. |
| Markor skill: shell-write for merge tasks | **REGRESSION** | Agent canceled working UI dialog to attempt shell (which lacks permissions). Made MarkorMergeNotes worse. |
| Markor skill: newest-note date-sort strategy | Followed but wrong | Agent used date-sort correctly but modification-time ≠ creation-time. Wrong note deleted. |
| Eval config: max_turns increases | No impact | RecipeAddMultipleRecipes used 26/50 turns, RecipeAddMultipleRecipesFromMarkor2 used 24/50. Turn budget was not the bottleneck. |

## Common Problems

### P1: Broccoli Edit-as-Create — HIGH — 3 tasks

**Affected**: RecipeAddMultipleRecipes, RecipeAddMultipleRecipesFromMarkor, RecipeAddMultipleRecipesFromMarkor2 (Source field)

**Root Cause**: After saving recipe 1, agent lands on the detail view. Instead of navigating back to the recipe list to create a new recipe, the agent clicks "Edit" and overwrites recipe 1's fields with recipe 2's data. The agent treats Edit as a blank form reuse rather than an update of the existing record. Additionally, RecipeAddMultipleRecipesFromMarkor2 fabricated a Source field value (`recipes.txt`) not present in the input data.

**Proposed Fix**:
- Add Broccoli app skill: "To add multiple recipes, ALWAYS navigate back to the recipe list (Navigate Up / back arrow) before creating a new recipe. Never use Edit on an existing recipe to create a different recipe. Leave Source and Categories empty unless explicitly provided in the input data."
- Owner layer: app skill: `com.flauschcode.broccoli`

---

### P2: Markor shell-write regression — HIGH — 1 task

**Affected**: MarkorMergeNotes

**Root Cause**: R19 Markor skill added "prefer shell_command to write directly" for merge tasks. But the agent process lacks permission to access `/storage/emulated/0/Documents/markor/`. The agent followed the skill, canceled a working UI dialog, attempted shell (which failed), and gave up. The R19 skill change was counterproductive.

**Proposed Fix**:
- Revert the shell-write guidance. Replace with: "Always use the Markor UI for file creation. If the target filename has no extension, clear the extension field in the new-file dialog."
- Owner layer: app skill: `net.gsantner.markor`

---

### P3: Markor creation-time vs modification-time — MED — 1 task

**Affected**: MarkorDeleteNewestNote

**Root Cause**: "Newest note" = most recently created, but Markor's date-sort shows modification time. Agent used date-sort as the skill advised, picked the most recently modified file, which was not the most recently created. No creation-time sort exists in Markor's UI.

**Proposed Fix**:
- Update Markor skill: "For 'newest/oldest note', use `shell_command` with `stat -c '%W %n' /storage/emulated/0/Documents/markor/*` to check creation time (Birth field). Markor's date-sort shows modification time which may differ from creation time."
- If shell stat also fails due to permissions, this task may need to be parked.
- Owner layer: app skill: `net.gsantner.markor`

---

### P4: Scroll-before-give-up missing — MED — 2 tasks

**Affected**: ExpenseAddMultipleFromGallery, TasksHighPriorityTasksDueOnDate

**Root Cause**: ExpenseAddMultipleFromGallery: category RecyclerView was scrollable but agent assumed visible categories were the complete set and gave up. TasksHighPriorityTasksDueOnDate: agent scrolled twice but stopped before reaching the end, missing a dynamically-created eval task.

**Proposed Fix**:
- Core prompt or general guidance: "When you believe a UI element is missing or a list is complete, scroll any nearby scrollable container to check for hidden items before giving up."
- Tasks.org skill: strengthen to "You MUST scroll until list content stops changing. Two scrolls is rarely enough."
- Owner layer: core prompt (general) + app skill: `org.tasks` (specific)

---

### P5: Image perception instability — LOW — 1 task

**Affected**: RecipeAddMultipleRecipesFromImage

**Root Cause**: Agent rewrote scratchpad 6 times with wildly different recipe transcriptions from the same screenshot. Eventually settled on 2 of 3 recipes. Image OCR via screenshot is unreliable.

**Proposed Fix**: Limited options without better OCR infrastructure. Consider parking if this pattern repeats.

---

## Next Steps

### Must Fix
1. **Broccoli app skill — multi-recipe navigation**: Add guidance to always navigate back to recipe list between recipe additions. Never use Edit to create a new recipe. Leave unmapped fields (Source, Categories) empty.
   - Targets: RecipeAddMultipleRecipes, RecipeAddMultipleRecipesFromMarkor, RecipeAddMultipleRecipesFromMarkor2
   - Implementation: new app skill `app/src/main/assets/app_skills/com.flauschcode.broccoli/SKILL.md`
   - Owner layer: app skill: `com.flauschcode.broccoli`

2. **Revert Markor shell-write guidance**: Remove "prefer shell_command for merge tasks" — it causes regression since shell lacks permissions. Restore UI-only approach. Add extension-clearing guidance.
   - Targets: MarkorMergeNotes
   - Implementation: edit `app/src/main/assets/app_skills/net.gsantner.markor/SKILL.md`
   - Owner layer: app skill: `net.gsantner.markor`

### Should Fix
3. **Markor creation-time via shell stat**: For newest/oldest note tasks, use `stat` to check creation time instead of relying on Markor's modification-time sort.
   - Targets: MarkorDeleteNewestNote
   - Implementation: edit `app/src/main/assets/app_skills/net.gsantner.markor/SKILL.md`
   - Owner layer: app skill: `net.gsantner.markor`

4. **Scroll exhaustion enforcement**: Strengthen scroll-until-stuck guidance in Tasks.org skill. Consider adding general "scroll before giving up" to core prompt.
   - Targets: TasksHighPriorityTasksDueOnDate, ExpenseAddMultipleFromGallery
   - Implementation: edit Tasks.org skill + consider core prompt addition
   - Owner layer: app skill: `org.tasks` + core prompt (lightweight)

5. **Settings skill — prefer UI over shell for Wi-Fi**: Shell `svc wifi` fails without ADB permissions. Recommend UI navigation as primary approach.
   - Targets: TurnOnWifiAndOpenApp (optimization, already passes)
   - Implementation: edit `app/src/main/assets/app_skills/com.android.settings/SKILL.md`
   - Owner layer: app skill: `com.android.settings`

### Accept as Capability Gap
- **RecipeAddMultipleRecipesFromImage**: Image OCR perception is unstable (6 rewrites). Has never passed across 5+ rounds. Candidate for `cannot_handle` if R20 Broccoli skill fix doesn't help.
