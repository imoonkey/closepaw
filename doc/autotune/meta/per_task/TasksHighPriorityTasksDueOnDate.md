## R57 (20260317_194418/20260317_205840) — gpt-5.4 — PASS
- Score: 1.0 | Context: app skill rewrite validation sweep (-65.6% skill tokens)
- Clean pass, rewritten skill injected correctly.


### R44 (20260313_095409)
- Score: 1.0 | Turns: 26 | RECOVERED — detail-view verification mandate followed

## R41 (20260312_173354) — gpt-5.4 — FAIL
- Score: 0.0 | Turns: 19/50 | REGRESSION from R39 (1.0).
- Same failure as R21 retry1: agent included "Back Up Computer Files" whose detail view shows due="Saturday, October 7" (not Oct 18). Relied on list-view "Wed" label without verifying full-format due date in detail view. Skill guidance was present but not applied.
- Answer: "Marketing Campaign Launch, Back Up Computer Files" (expected: "Marketing Campaign Launch" only).
- Priority reading correct (4th radio = High). Due-date disambiguation was the sole failure point.
- Recommendation: add explicit "verify due date in detail view" step to skill when task goal filters by date.

## R39 (20260312_094821) — gpt-5.4 — PASS
- Score: 1.0 | Turns: 27 | Clean pass in 27 turns. (unchanged)

## R21 (20260309_163832) — gpt-5.4 — PASS
- Score: 1.0, Turns: 26
- Due date disambiguation fix worked. Agent correctly used full-format date ("Saturday, October 7") to exclude Back Up Computer Files (due Oct 7, not Oct 18). Answered "Marketing Campaign Launch" correctly.

## R21 retry1 (20260309_161737) — gpt-5.4
- Score: 0.0, Turns: 23
- Priority scale fix correct (4th=High). But agent included "Back Up Computer Files" — misread "Wednesday" field in detail view as due date (actual due date is "Saturday, October 7"). Added due date disambiguation to skill.

## R21 initial (20260309_153628) — gpt-5.4
- Score: 0.0, Turns: 1
- LLM API infra error (OpenAIIoException). Transient proxy issue, not agent problem.

## R20 (20260309_150119) — gpt-5.4
- Score: 0.0, Turns: 25
- Better mechanics (exhaustive scroll, all candidates checked). Same wrong answer — priority scale may be inverted.

## R19 (20260309_143418) — gpt-5.4
- Score: 0.0, Turns: 18
- Reasoning: incomplete scroll + checked 3/4 candidates, missed eval task below fold

# TasksHighPriorityTasksDueOnDate

## R18 (gpt-5.4, 20260309_135649)
- Score: 0.0 | Turns: 21 | Priority scale fix worked (correctly read leftmost=High). But scratchpad overwrite on scroll lost "Learn to Play Guitar" candidate. Answer: "Renew Driver's License" (partial — missed dynamically-created task).

## R17 (gpt-5.4, 20260309_132808)
- Score: 0.0 | Turns: 13 | ReasoningError — priority scale inverted (rightmost=None, agent read as High). Also insufficient scroll + candidate loss.

## R16 (gpt-5.4, 20260309_125810)
- Score: 0.0 | Turns: 15 | FalseCompletion — found pre-existing "Renew Driver's License" but never scrolled to see dynamically-created task below fold. Priority logic correct.

## R4 (AT-R4, run 20260305_151648)
- **Score**: 0.0 | **Turns**: 9/30 | **Agent**: qwen3.5
- **Behavior**: ReasoningError. REGRESSED from 1.0 in R3. Date-label tip helped (agent correctly IDed Wed=Oct 18). But priority radio buttons have no a11y labels. Agent gave up at turn 9 vs R3's 21 turns.
- **R4 fix result**: Date-label tip worked but a11y gap on priority UI. Regression due to natural variance (qwen3.5 nondeterminism).

## AT-R3 (20260304_152742) - PASS
- Score: 1.0
- Turns: 21/30
- First run, passed. Tasks app task — benefits from R4 date-label tip as regression canary.
