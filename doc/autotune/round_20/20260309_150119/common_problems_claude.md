# Round 20 Eval Analysis — Common Problems

**Run ID**: `20260309_150119` | **Date**: 2026-03-09 | **Model**: gpt-5.4
**Tasks**: 8 evaluated | **Pass Rate**: 3/8 = 37.5%

## Scorecard

| Task | Score | Turns | Root Cause |
|------|-------|-------|------------|
| RecipeAddMultipleRecipes | 1.0 | 24 | Success — Broccoli skill guided correct navigation |
| RecipeAddMultipleRecipesFromMarkor | 1.0 | 27 | Success — Broccoli skill guided correct navigation |
| RecipeAddMultipleRecipesFromMarkor2 | 1.0 | 31 | Success — Broccoli skill + no Source fabrication |
| MarkorMergeNotes | 0.0 | 23 | Extension not cleared (.md default) — content was correct |
| MarkorDeleteNewestNote | 0.0 | 10 | stat failed (shell can't access files), agent guessed wrong |
| TasksHighPriorityTasksDueOnDate | 0.0 | 25 | Same wrong answer — possible priority scale inversion |
| RecipeAddMultipleRecipesFromImage | 0.0 | 23 | Image read from thumbnail not full-screen, hallucinated content |
| ExpenseAddMultipleFromGallery | 0.0 | 16 | Still doesn't scroll category RecyclerView |

### By Category

| Category | Count | Tasks |
|----------|-------|-------|
| Success | 3 | RecipeAddMultipleRecipes, RecipeAddMultipleRecipesFromMarkor, RecipeAddMultipleRecipesFromMarkor2 |
| Reasoning (Skill Noncompliance) | 1 | MarkorMergeNotes |
| Execution (Shell Access) | 1 | MarkorDeleteNewestNote |
| Reasoning (Possible Scale Error) | 1 | TasksHighPriorityTasksDueOnDate |
| Perception (Image) | 1 | RecipeAddMultipleRecipesFromImage |
| Reasoning (No Scroll) | 1 | ExpenseAddMultipleFromGallery |

## Previous Round Comparison

| Fix Applied | Status | Impact |
|-------------|--------|--------|
| Broccoli app skill (multi-recipe nav) | **WORKING** | 3 Recipe tasks: 0.0→1.0. Edit-as-create eliminated. |
| Revert Markor shell-write | Working | Agent no longer cancels UI dialog. Content correct in R20. |
| Markor creation-time via stat | Ineffective | stat can't access Markor's files at all (empty listing). |
| Scroll exhaustion in Tasks.org | Partially working | Agent scrolled to bottom (3 scrolls, improvement). Still wrong answer. |
| Settings skill UI preference | N/A | TurnOnWifiAndOpenApp not retested (already passes). |

## Common Problems

### P1: MarkorMergeNotes extension not cleared — HIGH — 1 task

**Affected**: MarkorMergeNotes

**Root Cause**: The agent typed the filename correctly but left the `.md` extension default. The skill already says to clear the extension field for extensionless filenames, but the agent didn't follow it. Content and structure were 100% correct — only the `.md` suffix caused the failure.

**Proposed Fix**: Strengthen the extension guidance with a concrete example action sequence. Move it to a CRITICAL section in the skill.

---

### P2: TasksHighPriorityTasksDueOnDate priority scale — HIGH — 1 task

**Affected**: TasksHighPriorityTasksDueOnDate

**Root Cause**: Agent reads Marketing Campaign Launch as 4th/rightmost radio button = None priority, but the evaluator expects it as the answer (implying it's High). Either (a) the priority scale is inverted (4th=High, 1st=None — opposite of what the skill says), or (b) the a11y tree radio button ordering doesn't match the visual layout. Need to investigate by checking an old passing trace (R3 scored 1.0).

**Proposed Fix**: Investigate the R3 passing trace to determine the actual priority scale. Update the Tasks.org skill accordingly.

---

### P3: RecipeAddMultipleRecipesFromImage — perception — 1 task

**Affected**: RecipeAddMultipleRecipesFromImage

**Root Cause**: Agent reads recipe image from thumbnail view in Gallery, not full-screen. Small text → hallucinated recipe names/content. Only 2 of 3 recipes extracted. 5+ consecutive failures across rounds.

**Proposed Fix**: Candidate for `cannot_handle`. Alternatively, add Gallery app skill: "always open image full-screen before extracting text."

---

### P4: MarkorDeleteNewestNote — shell inaccessible — 1 task

**Affected**: MarkorDeleteNewestNote

**Root Cause**: Shell `stat` and `ls -lt` both fail on Markor's storage path (empty listing or "no such file"). Agent has no way to determine creation time. Markor UI only shows modification time. 4 consecutive failures.

**Proposed Fix**: Candidate for `cannot_handle`. No reliable creation-time source exists — Markor UI shows modification time, shell can't access files.

---

### P5: ExpenseAddMultipleFromGallery — no scroll — 1 task

**Affected**: ExpenseAddMultipleFromGallery

**Root Cause**: Agent sees `scrollable: true` on category RecyclerView but never scrolls it. "Clothes" and "Health Care" categories exist off-screen. 5 consecutive failures.

**Proposed Fix**: Add Pro Expense app skill with explicit category scroll instruction. If still fails after skill, candidate for `cannot_handle`.

---

## Next Steps

### Must Fix
1. **MarkorMergeNotes extension clearing**: Strengthen guidance with concrete action example. Move to CRITICAL section.
   - Targets: MarkorMergeNotes
   - Implementation: edit Markor skill
   - Owner layer: app skill: `net.gsantner.markor`

2. **TasksHighPriorityTasksDueOnDate priority scale investigation**: Check R3 passing trace to determine actual scale. Fix skill if inverted.
   - Targets: TasksHighPriorityTasksDueOnDate
   - Implementation: investigate, then edit Tasks.org skill
   - Owner layer: app skill: `org.tasks`

3. **Pro Expense app skill — category scroll**: Add skill with explicit "scroll category row to find all categories" instruction.
   - Targets: ExpenseAddMultipleFromGallery
   - Implementation: new app skill `app/src/main/assets/app_skills/com.thetechnocafe.expensemanager/SKILL.md`
   - Owner layer: app skill: `com.thetechnocafe.expensemanager`

### Accept as Capability Gap
- **RecipeAddMultipleRecipesFromImage**: 5+ consecutive 0.0. Image OCR from thumbnails is fundamentally unreliable. Cannot open full-screen reliably due to Gallery navigation complexity. Move to `cannot_handle`.
- **MarkorDeleteNewestNote**: 4 consecutive 0.0. Shell can't access Markor's storage for creation-time metadata. Markor UI only shows modification time. No viable path to determine "newest by creation time." Move to `cannot_handle`.
