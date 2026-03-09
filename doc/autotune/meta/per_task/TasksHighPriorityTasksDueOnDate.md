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
