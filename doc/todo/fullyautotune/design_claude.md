# Fullyautotune Skill Restructure — Design

## Problem

Three nested loop concepts create confusion:

```
ralph-loop (mechanical re-feed)
  └── fullyautotune (multi-round orchestration)
        └── autotune (single round: FIX→PREPARE→RUN→ANALYZE→STOP)
```

- Who owns iteration? ralph-loop or fullyautotune?
- fullyautotune's core principles (anti-overfit, token minimalism) are buried in the wrong skill
- autotune STOPs for human review, fullyautotune overrides that — conflicting contracts

## Goal

Clear single-responsibility ownership. Two loops max:

```
ralph-loop (iteration engine, generic)
  └── autotune (one round per iteration, domain-specific)
```

## Design

### 1. Merge fullyautotune into autotune + prompt-tune

Fullyautotune is deleted as a standalone skill. Its content distributes to where it's most actionable:

#### → prompt-tune (prompt quality principles)

Add as **top-level principles** in prompt-tune SKILL.md preamble (not buried in checklist):

- **Anti-overfit**: "Will this change help real users or just eval tasks?" Every prompt edit must pass this test.
- **Token minimalism**: Maximize efficiency of every token. Core prompt must be generally applicable. App skills more flexible but should cover beyond eval set.
- **Training-dataset awareness**: Eval tasks are training data. Always consider whether a change generalizes to unseen user tasks.

Currently prompt-tune has these as a checkbox item in "Anti-pattern check." Promote to first-class principles.

#### → autotune (iteration strategy)

Add to autotune SKILL.md:

- **Step 4 — Multi-agent analysis**: Use `/double-design` pattern for per-task analysis and summarization, then align. Use `/multmux` for counterpart agent.
- **Completion criteria** (new section, replaces STOP):
  - Performance improved → proceed to next round
  - Targeted improvement failed 3 times → add to `cannot_handle_group.txt`, move on
  - All actionable items exhausted → signal completion
- **Remove STOP-for-human**: Each round completes naturally. Ralph-loop handles re-entry.

#### → deleted (ralph-loop handles these)

- "No need to wait for human review" — ralph-loop's job
- "Proceed automatically to next round" — ralph-loop's job
- "Commit every round" — already in autotune Step 1

### 2. Make autotune stateless per-iteration

Each ralph-loop iteration = one autotune round. Autotune discovers state from files:

- Current round number: count `doc/autotune/round_*` directories
- Last round's next-steps: read latest `common_problems_*.md`
- Failed tasks: read latest scoreboard

No internal loop. One round, then exit. Ralph-loop re-invokes.

### 3. Ralph-loop prompt template

Usage becomes:

```
/ralph-loop "Run one /autotune round. Read last round's common_problems
and next_steps to determine fixes. Target eval tasks from last round's
failures plus regression canaries." --max-iterations 10
--completion-promise "ALL TARGETS PASSING OR EXHAUSTED"
```

Optionally keep a convenience alias in fullyautotune that just generates this ralph-loop invocation, but no logic of its own.

## Changes Summary

### prompt-tune SKILL.md

**Add** top-level "Principles" section before "When to Use":

```markdown
## Principles

Every prompt change must pass these gates:

1. **Anti-overfit** — Does this help real users, not just eval tasks?
   - Core prompt: must be generally applicable to any app/task
   - App skills: must cover beyond the specific eval task that triggered it
   - If the answer is "only helps this one eval task" → do not add

2. **Token minimalism** — Is every token earning its keep?
   - Core prompt target: ~80-100 lines
   - App skills: <20 lines (loaded every turn when app is foreground)
   - Question: can this be said in fewer words without losing clarity?

3. **Generalization** — Eval tasks are training data. Always ask:
   would this change also help an unseen user task in the same app?
```

### autotune SKILL.md

**Modify** Step 4 — add multi-agent collaboration:

```markdown
### Multi-agent analysis
- Use `/double-design` pattern: each agent analyzes independently, cross-review, align
- Use `/multmux` to start counterpart agent
```

**Replace** STOP section with completion criteria:

```markdown
## Completion

After Step 4, evaluate whether to continue:

- **Continue**: analysis identified actionable fixes with expected impact
- **Stop — capability gap**: targeted improvement failed 3+ times →
  add to `cannot_handle_group.txt`
- **Stop — exhausted**: all high/med priority items addressed or parked
- Signal completion to ralph-loop: `<promise>ALL TARGETS PASSING OR EXHAUSTED</promise>`
```

**Add** stateless round discovery:

```markdown
## Round Discovery

Each invocation discovers its round from existing state:
- Round number: next after latest `doc/autotune/round_*`
- Previous analysis: latest `common_problems_*.md` in last round
- Failed tasks: `doc/autotune/meta/scoreboard.json`
- No internal loop — one round per invocation
```

### fullyautotune SKILL.md

**Option A** (preferred): Delete entirely. Ralph-loop + autotune covers it.

**Option B**: Reduce to a thin convenience wrapper:

```markdown
# Fullyautotune

Convenience wrapper. Equivalent to:

/ralph-loop "Run one /autotune round. Read last round's analysis
to determine fixes." --max-iterations 10
--completion-promise "ALL TARGETS PASSING OR EXHAUSTED"

All logic lives in /autotune and /prompt-tune.
```

## Migration

1. Update prompt-tune SKILL.md — add Principles section
2. Update autotune SKILL.md — add multi-agent, completion criteria, round discovery; remove STOP
3. Delete or gut fullyautotune SKILL.md
4. Test: `/ralph-loop /autotune --max-iterations 2 --completion-promise "ROUND COMPLETE"` on a small task set
5. Update CLAUDE.md skill list if fullyautotune is removed
