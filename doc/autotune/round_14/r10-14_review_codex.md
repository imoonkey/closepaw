# R10-R14 Diff Review vs `1a30312700a18bfc457ce7eeb13246ae7ecb8eef`

## Detailed Change Summary

### Scope at a glance
- Diff span: `1a30312700a18bfc457ce7eeb13246ae7ecb8eef..HEAD`
- Commits: 12
- Files changed: 22 tracked files
- Net diff: 456 insertions, 14 deletions
- Change mix:
  - Runtime behavior: 3 Kotlin files
  - App guidance: 4 app skill files
  - Eval targeting/config: 7 config files
  - Documentation/reporting: 8 doc files

### Big-picture theme
This diff is a focused autotune sequence for qwen3.5 on the 22 tasks that were still failing after the earlier R8/R9 work. The runtime code changes are small and low-risk: prompt guidance got tighter, accessibility text normalization now preserves blank lines, and app-skill lookup emits a debug log. Most of the work happened in app skills, round-specific eval lists, and round analysis docs. The claimed trajectory in the docs is:

- R10: 12/22
- R11: 16/22
- R12: 16/22
- R13: 18/22
- R14: 20/22

One note before the chronology: there is no new standalone `doc/autotune/round_11/analysis.md` in this diff, so Round 11 has to be reconstructed from commit `22ba8b9`, `eval/config/autotune_round_11.txt`, and the changelog entries.

### Round-by-round catch-up

#### Round 10: first targeted hardening pass (`f5d26b5`, `7c48dd7`)
This round introduced the foundation changes that the later rounds built on.

- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`
  - Added critical rule `#10`: if a task mentions a source and a destination, open the destination app and enter data there instead of creating an artifact in the source app.
  - Rewrote the Information-mode guidance to require exact field reading from the accessibility tree, scratchpad evidence capture, and full scrolling before counting.
  - Expanded Completion guidance to require exact filename+extension verification, postcondition checks for file ops, date verification before answering date-specific questions, and scratchpad cross-checking before `complete_task`.
- `app/src/main/kotlin/com/moonkey/androidagent/perception/PerceptorInternals.kt`
  - Changed newline normalization to preserve paragraph breaks by collapsing 2+ newlines to `\n\n` instead of `\n`.
- `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnPlanningPhaseRunner.kt`
  - Added `Log.d` so each turn records whether an app skill was found for the current package.
- App skill updates:
  - `app/src/main/assets/app_skills/org.tasks/SKILL.md`
    - Replaced the old checkbox-state assumption with a diff-based `Show completed` workflow.
    - Added stronger anti-guessing guidance for counts and metadata.
  - `app/src/main/assets/app_skills/com.simplemobiletools.calendar.pro/SKILL.md`
    - Added month-grid navigation guidance, NumberPicker handling, 24-hour time interpretation, and post-save verification expectations.
  - `app/src/main/assets/app_skills/code.name.monkey.retromusic/SKILL.md`
    - New skill explaining the correct flow for adding songs to playlists from the Songs tab rather than from an empty playlist view.
- Eval targeting:
  - `eval/config/autotune_round_10.txt` defines the 22-task retune subset.
- Documented outcome:
  - `doc/autotune/round_10/20260308_221303/common_problems_claude.md` records the first big jump to 12/22 and clusters the remaining failures into Tasks.org, SportsTracker, and a few one-off problems.

#### Round 11: soften anti-hallucination, make Tasks guidance more actionable, first OpenTracks rewrite (`22ba8b9`)
This round reacted to R10 traces rather than changing core runtime code beyond one prompt line.

- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`
  - Softened the information-task rule from a blanket “NEVER infer metadata from display names or titles” to a more targeted rule: do not guess metadata from visual appearance alone, and follow app-skill guidance when the app exposes a reliable reading strategy.
- `app/src/main/assets/app_skills/de.dennisguse.opentracks/SKILL.md`
  - Round 11 rewrote OpenTracks to trust list-view track names as activity descriptors and avoid Edit mode for activity classification.
  - The intended benefit was to trade strict verification for speed on SportsTracker tasks that were dying on turn budget.
- `app/src/main/assets/app_skills/org.tasks/SKILL.md`
  - Added the exact overflow-menu location for `Show completed`.
  - Added explicit priority-reading guidance and turn-budget expectations for priority queries.
- Eval targeting:
  - `eval/config/autotune_round_11.txt` schedules the 11 carryover failures from R10 plus a retry for `SimpleCalendarEventsInNextWeek`.
- Documented outcome:
  - The changelog attributes four new passes to this round: `TasksHighPriorityTasksDueOnDate`, `TasksIncompleteTasksOnDate`, `MarkorAddNoteHeader`, and `SimpleCalendarEventsInNextWeek`.

#### Round 12: OpenTracks shell/sqlite experiment (`9ab7243`, `65c1e48`)
This round is the main dead-end in the sequence.

- `app/src/main/assets/app_skills/de.dennisguse.opentracks/SKILL.md`
  - Abandoned the name-based strategy and instructed the agent to query OpenTracks data through shell+`sqlite3` so activity type would come from the authoritative database fields.
- Eval targeting:
  - `eval/config/autotune_round_12.txt` re-runs the seven remaining failures.
- Analysis/writeup:
  - `doc/autotune/round_12/analysis.md` says the shell strategy failed outright because the agent process cannot access another app’s private data directory without root or `run-as`.
  - The same analysis notes that the newly added 50-turn override was not active for the actual run, so SportsTracker tasks still executed with 30 turns.
- Documented outcome:
  - 0/7 for the round.
  - The shell experiment was abandoned immediately afterward.

#### Round 13: revert to UI Edit flow, fix date reasoning and turn budget (`fdbb921`, `f143b32`)
Round 13 backed out the failed shell approach and made two targeted adjustments that immediately paid off.

- `app/src/main/assets/app_skills/de.dennisguse.opentracks/SKILL.md`
  - Reverted the shell/sqlite approach.
  - Established a strict UI path for authoritative activity-type reads:
    1. Open the track from the list.
    2. Open More options -> Edit.
    3. Read the Activity type field.
    4. Back out twice.
  - The skill now explicitly says track names are user labels and not authoritative for activity type.
- `app/src/main/assets/app_skills/org.tasks/SKILL.md`
  - Added a concrete Sunday-boundary example for “due next week”.
  - Added “submit your best answer from scratchpad evidence if you are low on turns” for long priority queries.
- `eval/config/default.yaml`
  - Added `max_turns: 50` for:
    - `SportsTrackerActivitiesCountForWeek`
    - `SportsTrackerLongestDistanceActivity`
    - `SportsTrackerTotalDistanceForCategoryOverInterval`
    - `SportsTrackerTotalDurationForCategoryThisWeek`
    - `TasksHighPriorityTasks`
- Eval targeting:
  - `eval/config/autotune_round_13.txt` re-runs the same seven tasks under the updated strategy and turn budget.
- Analysis/writeup:
  - `doc/autotune/round_13/analysis.md` records two concrete wins:
    - `ExpenseAddMultipleFromMarkor` finally passes, attributed to the R10 cross-app destination rule.
    - `TasksHighPriorityTasks` passes once the agent gets 50 turns and explicit partial-answer guidance.
  - The same analysis shows the new OpenTracks Edit flow was correct but still too expensive at roughly 8 turns per inspected track.

#### Round 14: hybrid OpenTracks heuristic with name-based triage + Edit verification (`f3376a4`, `b0d63e5`)
This is the strongest behavioral improvement in the whole stack of changes.

- `app/src/main/assets/app_skills/de.dennisguse.opentracks/SKILL.md`
  - Keeps Edit as the authoritative source for counted tracks.
  - Adds “smart filtering”:
    - prioritize ambiguous or target-looking track names
    - skip names that are clearly unrelated to the requested activity
    - still verify every counted track through Edit
  - This is a hybrid of the failed R11 and R13 extremes:
    - R11 trusted names too much
    - R13 trusted names not at all
    - R14 uses names only as a negative filter to reduce search cost
- Eval targeting:
  - `eval/config/autotune_round_14.txt` narrows to 3 SportsTracker tasks plus 2 Tasks retries.
- Analysis/writeup:
  - `doc/autotune/round_14/analysis.md` shows:
    - `SportsTrackerActivitiesCountForWeek` succeeded in 18 turns instead of dying at 50+
    - `SportsTrackerLongestDistanceActivity` succeeded in 48 turns
    - `SportsTrackerTotalDistanceForCategoryOverInterval` was progressing but got cut off by OpenRouter 402 credit exhaustion
    - the two remaining Tasks retries also died to the same credit problem before meaningful execution
- Documented outcome:
  - 2/5 overall
  - 2/2 non-infra
  - cumulative claim moves to 20/22

#### Post-R14 cleanup: bookkeeping, changelogs, next-round prep (`3636d68`, `6d763d9`, `71b373d`)
These commits mostly update evaluation bookkeeping and documentation.

- `eval/config/cannot_handle_group.txt`
  - Adds `TasksCompletedTasksForDate`
  - Adds `TasksDueNextWeek`
- `eval/config/autotune_round_15.txt`
  - Adds the next shortlist:
    - `SportsTrackerTotalDistanceForCategoryOverInterval`
    - `TasksCompletedTasksForDate`
    - `TasksDueNextWeek`
- `doc/autotune/meta/changelog.md`
  - Backfills summaries for R10-R14, including claimed pass progression, per-round rationale, and postmortem notes.
- `doc/changelog.md`
  - Adds a top-level product changelog entry summarizing the R10-R14 autotune arc.
- `doc/main/infra/platform.md`
  - Documents the newline-preservation behavior in `PerceptorInternals.kt`.

### Final end state by area

#### Agent runtime
- Prompt is more verification-heavy and more explicit about:
  - cross-app source vs destination routing
  - evidence-based answering
  - exact file-operation postconditions
  - date verification
  - scratchpad cross-checking
- Perception now preserves blank-line structure.
- Planning now logs whether app-skill lookup succeeds.

#### App skills
- Calendar skill is more deterministic about month navigation, picker handling, and verification.
- Retro Music skill codifies the correct playlist-add flow.
- Tasks skill now includes:
  - relative-date resolution
  - `Show completed` diff workflow
  - priority drilldown guidance
  - full-list scanning requirements
  - low-turn partial-answer advice
- OpenTracks skill went through three strategy reversals and ended at:
  - authoritative read from Edit
  - heuristic triage from track names
  - immediate scratchpad capture

#### Eval/config surface
- Dedicated task lists were added for R10, R11, R12, R13, R14, and R15.
- `default.yaml` now grants 50 turns to the hardest SportsTracker tasks and `TasksHighPriorityTasks`.
- `cannot_handle_group.txt` was expanded after R14.

#### Documentation surface
- New round analysis docs exist for R10, R12, R13, and R14.
- The meta changelog and top-level changelog now summarize the whole tuning arc.
- Not all documentation is fully self-consistent; chronology and task classification drift in a few places.

## Review Findings

### High
1. `TasksDueNextWeek` was moved into `cannot_handle`, but the round analyses describe it as a solvable reasoning/counting bug rather than a structural impossibility. `eval/config/cannot_handle_group.txt:9` removes it from future tuning, while `doc/autotune/round_13/analysis.md:31-32` and `doc/autotune/round_14/analysis.md:29-31` both frame it as an off-by-1 model error. That makes the claimed 20/22 end-state cleaner on paper by excluding a task that still looks fixable.

### Medium
1. `eval/config/autotune_round_15.txt:1-3` still schedules `TasksCompletedTasksForDate` and `TasksDueNextWeek`, even though they were just added to `eval/config/cannot_handle_group.txt:8-9`. That leaves the next-round task list internally inconsistent and invites accidental reruns or confusing bookkeeping.
2. The live Tasks skill still teaches the `Show completed` toggle workflow at `app/src/main/assets/app_skills/org.tasks/SKILL.md:13-20`, but `doc/autotune/round_13/analysis.md:34-35` and `doc/autotune/round_14/analysis.md:29-30` say that accessibility clicks on that toggle do not actually change state. For real user tasks, the agent is still being taught an action path that the current execution layer apparently cannot perform reliably.
3. `doc/autotune/meta/changelog.md` has chronology drift for OpenTracks. The Round 11 entry at `doc/autotune/meta/changelog.md:56-60` says the shell/sqlite strategy happened in R11, but `doc/autotune/round_12/analysis.md:20-26` and commit `9ab7243` show that shell/sqlite was the Round 12 experiment. The Round 10 entry also credits an OpenTracks skill change that does not appear in `f5d26b5`'s patch.

## Overall Assessment
- The actual code changes are small and mostly sensible.
- The strongest technical improvement is the final OpenTracks hybrid strategy, which finally balances correctness and turn budget.
- The main risk in this diff is not the Kotlin code. It is evaluation bookkeeping and skill text drifting away from what the analyses say the agent can really do.

## Recommendation
`CHANGES_REQUESTED`

Before treating R10-R14 as “done”, I would:

1. Remove `TasksDueNextWeek` from `cannot_handle` and keep it in active retune scope until the off-by-1 failure is understood.
2. Decide whether `TasksCompletedTasksForDate` is truly parked; if yes, update the Tasks skill to fail fast instead of teaching the broken toggle path.
3. Reconcile `autotune_round_15.txt` with the `cannot_handle` list.
4. Fix the meta changelog chronology so future rounds are built on an accurate history.
