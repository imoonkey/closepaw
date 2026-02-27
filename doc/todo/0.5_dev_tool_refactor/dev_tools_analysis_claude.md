# Dev Tools & Skills Relationship Analysis

## Current Landscape

### Three Script Directories

```
scripts/          Shell scripts for interactive development (human-facing)
eval/             Python eval pipeline (automated batch testing)
inspection_tool/  Python trace analysis & visualization (post-mortem)
```

### Four Debug/Eval Skills

```
/cog-tune         LLM cognition analysis (reasoning, prompt, context)
/action-debug     Action execution reliability (click/scroll/swipe)
/visual-debug     Live agent debugging (turn-by-turn screenshots + logs)
/ux-visual-debug  App UX QA from user perspective (ADB-driven)
```

---

## Dependency Map

```
                        ┌─────────────────────────────┐
                        │       Human / Skill          │
                        └──────────┬──────────────────-┘
                                   │
      ┌────────────────┬───────────┼───────────┬──────────────────┐
      v                v           v           v                  v
  setup.sh      debug-run.sh   logs.sh   action-test.sh    runner.py
      │               │                      │                │
      │               │                      │          ┌─────┼──────────┐
      │               v                      │          v     v          v
      │        replay_compiler.py             │    preflight  execution  task_loader
      │               │                      │          │     │
      │               v                      │          │     v
      │        derived/steps.jsonl            │          │  native_agent_bridge
      │                                      │          │     │
      │                                      │          │     v
      │                                      │          │  completion_monitor
      │                                      v          │     │
      │                              debug-output/      │     v
      │                              action-test/       │  trace_parser
      │                                                 │     │
      │                                                 v     v
      v                                              eval/results/
   Device state                                      per_task.jsonl
                                                     summary.json

Post-mortem analysis:
  summarize.py ──reads──> per_task.jsonl ──> metrics JSON
  compare_runs.py ──reads──> summary.json x2 ──> deltas JSON
  a11y_token_stats.py ──reads──> trace artifacts ──> token CSV
  server.py ──serves──> replay_v2 web UI
```

### Cross-Directory Calls

| From | To | What |
|------|----|------|
| `debug-run.sh` | `inspection_tool/replay_compiler.py` | Auto-compile traces after run |
| `prepare_baseline.sh` | `eval/aw_bridge/prepare_baseline.py` | Delegate to Python |
| `cog-tune/prepare_cog_review.py` | `inspection_tool/replay_compiler.py` | Compile if missing |
| `ux-visual-debug/agent_link.py` | `scripts/debug-run.sh`, `scripts/setup.sh` | Launch agent for linked UX testing |

---

## Skill Relationship Matrix

| | cog-tune | action-debug | visual-debug | ux-visual-debug |
|---|---|---|---|---|
| **Focus** | LLM reasoning quality | Action execution reliability | Live agent behavior | App UX quality |
| **When** | Post-eval or post-debug-run | After cog-tune finds Execution root cause | During/after a debug-run | When iterating app UI/UX |
| **Input** | trace.jsonl, per_task.jsonl, screenshots | trace artifacts + action-test.sh results | debug-run output (screenshots + logs) | ADB screenshots + UI dumps |
| **Output** | Root cause report + code patches | Action debug report + fix proposals | Turn-by-turn analysis + fix | UX QA report (pass/fail per flow) |
| **Scripts used** | replay_compiler, a11y_token_stats, summarize, compare_runs | action-test.sh | debug-run.sh, logs.sh | adb_ux_runner.py, debug-run.sh (linked) |
| **Targets** | Prompts, context packing, policies, tool schemas | NodeActionPerformer, GestureInjector, executors | Perceptor, Agent, tools | Compose UI, capsule states, overlay |

### Intended Flow Between Skills

```
/visual-debug  ──"something is wrong"──>  /cog-tune  ──"root cause = Execution"──>  /action-debug
     ^                                        │
     │                                        v
     └───── "root cause = Reasoning" ──── fix prompt/context
                                              │
                                              v
                                     re-run eval to validate
                                              │
                                              v
                                     /cog-tune again (loop)

/ux-visual-debug  (independent, tests app UX, not agent cognition)
```

---

## Problems & Observations

### 1. /visual-debug is stale and overlaps with /cog-tune

**/visual-debug** was written early. It describes a lightweight "look at screenshots and logs" workflow. But now that we have:
- Structured traces (trace.jsonl + derived/steps.jsonl)
- replay_compiler.py for indexed replay
- a11y_token_stats.py for context analysis
- Formal root cause taxonomy (Perception/Context/Reasoning/Execution/Observation/Orchestration)

.../visual-debug duplicates a subset of /cog-tune but with weaker structure:
- Same issue categories (Perception, Reasoning, Execution, Observation) but without the full taxonomy
- Same "look at screenshots + logs" pattern but without trace-based analysis
- References `debug-output/agent.log` and `turn_N.png` — the old output format. Current debug-run.sh produces structured `trace/` directories.
- Doesn't mention replay_compiler.py, a11y_token_stats.py, or steps.jsonl.
- No connection to eval pipeline.

**Recommendation**: Fold /visual-debug into /cog-tune. Cog-tune already handles both debug-run and eval entry points. Visual-debug's lightweight "quick look" pattern can become a "Quick debug" section in cog-tune, or cog-tune can reference it as the "start here for simple issues" shortcut. Either way, maintaining two separate skills with overlapping scope creates confusion about which to use.

### 2. /action-debug is well-scoped and complements /cog-tune correctly

/action-debug has a clear boundary: it only runs when cog-tune identifies an Execution root cause. The skill documents its own relationship to cog-tune explicitly. The action-test.sh script it depends on is self-contained.

**No changes needed.** The skill-to-skill handoff pattern (cog-tune -> action-debug) is clean.

### 3. /ux-visual-debug has its own scripts that partially duplicate inspection_tool/

The skill has `adb_ux_runner.py`, `ux_runner_core.py`, and `agent_link.py` under `.ai-dev/skills/ux-visual-debug/scripts/`. These are independent from `inspection_tool/` — they use raw ADB (uiautomator dump, screencap) rather than accessibility trace analysis.

This separation makes sense because:
- ux-visual-debug tests the **app's own UI**, not the agent's reasoning
- It doesn't need trace.jsonl — it drives the device directly
- The `agent_link.py` integration with debug-run.sh is a nice touch for linked testing

**Observation**: This skill hasn't been used yet. The scenario mode (`scenario_a11y_lifecycle.json` etc.) looks mature in spec but unvalidated. The AI-Interactive mode is more useful in practice for exploratory testing.

**Minor suggestion**: When it does get used, consider whether `adb_ux_runner.py` should live in `scripts/` (since it's a general ADB automation tool) or stay in the skill directory (since it's tightly coupled to the skill workflow). For now, keeping it in the skill is fine.

### 4. inspection_tool/ is underspecified as a category

`inspection_tool/` currently holds:
- `replay_compiler.py` — trace compilation
- `server.py` — web UI for trace viewing
- `a11y_token_stats.py` — token analysis
- `main.py` — placeholder

These are all **post-mortem analysis** tools. The directory name "inspection_tool" is vague. It's really "trace analysis" or "trace tools."

But renaming directories creates churn across skills, scripts, and docs. The name isn't blocking anything.

**Recommendation**: Don't rename. But if you ever add more trace analysis scripts, keep them here. The implicit contract is: "inspection_tool/ = things that read traces and produce analysis artifacts."

### 5. eval/analysis/ is thin but correctly placed

Only two scripts (summarize.py, compare_runs.py) live here. They're pure post-processing with no side effects. This is clean separation from the eval runner code in `eval/aw_bridge/`.

**No changes needed.**

### 6. scripts/ has organic growth but is manageable

Current scripts:
- `debug-run.sh` — core interactive debugging
- `setup.sh` — build + install
- `logs.sh` — logcat viewer
- `action-test.sh` — action isolation testing
- `prepare_baseline.sh` — eval baseline prep
- `setup-ai-config.sh` — IDE config symlinks
- `agent_process_visual_debug.md` — the old visual debug guide (referenced by /visual-debug)

The `.md` file in scripts/ is odd — it's documentation, not a script. It should probably live in `doc/` or get absorbed into the /visual-debug (or /cog-tune) skill.

**Recommendation**: Move `agent_process_visual_debug.md` to `doc/dev/` or absorb its content into /cog-tune. Update references.

---

## Summary of Recommendations

### Skill Changes

| Priority | Action | Rationale |
|----------|--------|-----------|
| **P1** | Merge /visual-debug into /cog-tune | Overlapping scope, visual-debug references stale output format, cog-tune already covers the full workflow |
| **P2** | Update /visual-debug references to current trace format | If not merging: at minimum fix `debug-output/agent.log` and `turn_N.png` references to use `trace/` directory |
| **P3** | Validate /ux-visual-debug with a real run | The skill spec looks solid but hasn't been exercised; validate scenarios work before investing more |

### Script/Folder Changes

| Priority | Action | Rationale |
|----------|--------|-----------|
| **P2** | Move `scripts/agent_process_visual_debug.md` to `doc/dev/` | Documentation shouldn't live in scripts/ |
| **P3** | No folder renames needed | inspection_tool/, eval/, scripts/ boundaries are reasonable despite imperfect naming |

### What NOT to Change

- **Don't consolidate scripts/ and inspection_tool/** — shell scripts (device-interactive) and Python analysis (post-mortem) serve different purposes
- **Don't move ux-visual-debug scripts to scripts/** — they're tightly coupled to the skill
- **Don't rename inspection_tool/** — the churn isn't worth the clarity gain
- **Don't add more skills for the eval loop** — current skill coverage is sufficient (discussed separately)
