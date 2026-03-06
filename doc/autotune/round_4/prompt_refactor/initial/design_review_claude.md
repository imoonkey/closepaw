# Design Review: Codex Prompt Refactor Design

Reviewer: Claude
Reviewed: `design_codex.md`
Reference: `design_claude.md` (my own design, for comparison)

## Overall Assessment

Strong design. The Codex doc arrives at the same core conclusion I did — three-layer ownership (core prompt, tool descriptions, dynamic hints) — but frames the problem more sharply and proposes a more disciplined migration path. There are a few gaps and one significant design disagreement worth discussing.

**Verdict: Align on this design with amendments below.**

---

## Where We Agree

### 1. Root Problem Diagnosis

Both designs identify the same root cause: **no rule ownership model**. Codex's framing is crisper — "the real problem is not just 'the prompt is long'. The problem is that there is no rule ownership model" — and the consequences (tuning adds to easiest place, drift, permanence of app quirks) are well articulated. I identified 6 specific problems (P1-P6); Codex frames them as ownership failures. Same substance, Codex's framing is more actionable.

### 2. Three-Layer Split

Both designs converge on:
- System prompt = cross-tool behavioral policy
- Tool descriptions = self-contained tool-local semantics
- Dynamic hints = app/task-specific knowledge

The split principle is stated nearly identically. Both explicitly reject the "minimal tool descriptions" and "everything in prompt" extremes.

### 3. App Tips → Dynamic Injection

Both designs propose moving app tips to a registry keyed by package/app. Both cite DroidRun's `app_card` and MobileAgent's `additional_knowledge` as the pattern to follow.

### 4. autodevice as Cautionary Tale

Both flag autodevice's ~800-line prompts as what happens when task-specific patches accumulate. Good shared diagnosis.

### 5. Reference Analysis Conclusions

The reference analysis sections reach the same conclusions despite different emphasis. Both identify DroidRun as having the cleanest ownership model, MAI-UI as structurally simple but lacking tool separation, and minitap as demonstrating aggressive concern separation.

---

## Where Codex's Design is Stronger

### S1: "Task Modes" Section

Codex proposes a **Task Modes** section with explicit sub-modes (Manipulation, Information, Blocked, Unsupported). My design spreads QA behavior across "Strategies > Information Gathering" and puts vision limitations in the tool description.

**Codex is right here.** Task modes are a first-class concern that deserves its own section. The current QA block is "bolted on" (Codex's words) — my design partially un-bolts it but doesn't give it proper standing. The 4-mode taxonomy (Manipulation/Information/Blocked/Unsupported) is clean and generalizable.

**Action: Adopt the Task Modes section.**

### S2: Migration Phase 1 — Rule Inventory

Codex's Phase 1 is "tag every existing rule as CORE_PROMPT / TOOL_DESC / APP_HINT / TASK_HINT / REMOVE." My Phase 1 jumps straight to enriching tool descriptions.

Codex's approach is better because it forces explicit ownership decisions before any code changes. My approach risks moving content to tool descriptions without a clear principle for what stays vs goes.

**Action: Add a rule inventory phase before code changes.**

### S3: Hint Quality Bar

Codex defines an explicit quality bar for hints: "stable across runs, broadly useful, not already implied by a tool description, not a one-off evaluator patch." My design decides to keep all current app tips without a filter.

This is an important distinction. Some current tips (e.g., Expense: "verify category matches source text exactly") smell like one-off eval patches. Codex's quality bar would correctly filter these out.

**Action: Apply the quality bar to each hint during the inventory phase.**

### S4: "No Per-Tool Sections" Rule

Codex states explicitly: "if a rule requires a per-tool heading, it probably belongs in that tool's description." This is a clean heuristic I didn't state.

**Action: Adopt as a design invariant.**

### S5: Phase 6 — Ownership Checks / Snapshot Tests

Codex proposes lightweight tests to prevent regression to the current state: no per-tool sections in core prompt, app hints only from registry, duplication minimized. My design has no guardrails against re-accumulation.

**Action: Add ownership checks to the migration plan.**

### S6: MobileAgent V3.5 Analysis

Codex analyzed V3.5 (`mobile_use/utils.py`) which I missed entirely. V3.5 has the cleanest tool-centric prompt in the MobileAgent family — the prompt is mostly about output shape and interaction contract, with tool semantics in function signatures. This is a more relevant reference for our tool-calling architecture than V2/V3.

**Gap in my design: I should have found and analyzed V3.5.**

---

## Where My Design is Stronger

### M1: Concrete Prompt Skeleton

My design provides the actual section hierarchy with bullet-point content and a "What Moves Where" table mapping every current section to its destination. Codex provides a skeleton too (Section 3), but mine is more granular about content allocation.

When it comes to implementation, the "What Moves Where" table will be the primary reference.

### M2: Tool Description Enrichment Details

My design specifies exactly what to add to each tool description (MobileActionTool, ShellTool, CompleteTaskTool) with specific text. Codex lists which tools need enrichment but doesn't specify the content.

### M3: App Tip Resolution Logic

My design includes the runtime resolution algorithm (detect foreground app → lookup → inject) and the distinction between GLOBAL tips (always injected: date labels, "Nh" format) and per-app tips. Codex describes the registry concept but not the resolution logic.

### M4: Cross-Reference Summary Table

My design includes a comparison matrix across all 6 references (app tips location, tool desc style, behavioral guidance, critical rules first, examples, conditional sections). This makes patterns visible at a glance.

### M5: Critical Rules First (from minitap)

My design explicitly adopts minitap's pattern of putting critical behavioral rules at the top of the prompt, citing LLM attention patterns. Codex doesn't discuss prompt ordering. The "Critical Rules" section in my hierarchy is positioned immediately after Role for this reason.

---

## Disagreements

### D1: Section Granularity — "Strategies" Subsections vs Flat Modes

My design has a **Strategies** section with 4 subsections (Recovery, Information Gathering, Multi-Step Tasks, Verification). Codex keeps sections flat with 1-4 bullets each.

**Assessment:** Codex is probably right that subsections add nesting without proportional value. However, "Working Memory And Planning" (Codex's Section 4) conflates two distinct concerns: memory discipline (scratchpad) and task planning (todos). These serve different purposes and are used in different contexts.

**Recommendation:** Use Codex's flat structure but split memory from planning if they exceed 3-4 bullets combined.

### D2: Prompt Builder Architecture

Codex proposes `StandalonePromptSections.kt` + `PromptHintCatalog.kt` + `PromptHintSelector.kt` + `StandaloneAgentDef.kt` as separate files. My design keeps it simpler: `AppTips.kt` + modified `StandaloneAgentDef.kt`.

**Assessment:** Codex's 4-file decomposition risks over-engineering for a ~80-100 line prompt. The prompt string itself is small; the assembly logic shouldn't be more complex than the prompt. A single `AppTips.kt` registry with injection in the existing prompt builder is sufficient. If prompt assembly grows more complex later (conditional sections, task-mode injection), refactor then.

**Recommendation:** Start with my simpler 2-file approach. Codex's architecture is the right direction but premature at current scale.

### D3: TaskHint vs Always-Injected Global Tips

Codex proposes `TaskHint` as a separate bucket from `AppHint` — keyed by task pattern, not app. My design folds cross-app tips (date labels, "Nh" time, exact filename matching) into `AppTips.GLOBAL` (always injected).

**Assessment:** This is a meaningful design choice. TaskHints keyed by task pattern require pattern detection ("does the goal mention dates?"), which adds complexity and false-negative risk. Always-injected global tips are simpler and the token cost is trivial (~20 tokens).

**Recommendation:** Start with always-injected globals (my approach). Consider TaskHint only if the global set grows beyond ~5 bullets and we have evidence that irrelevant tips cause confusion.

### D4: Number of Migration Phases

Codex: 7 phases. Mine: 4 phases.

Codex adds: rule inventory (Phase 1), prompt builder refactor (Phase 3), QA simplification (Phase 5), ownership checks (Phase 6), evaluate and prune (Phase 7).

**Assessment:** Phases 1 (inventory) and 6 (ownership checks) add real value. Phase 3 (prompt builder architecture) is premature per D2. Phase 5 (QA simplification) should be part of the main restructure, not a separate phase. Phase 7 (evaluate and prune) is the same as my Phase 4 with a better name.

**Recommendation:** 5 phases: Inventory → Tool Enrichment → App Tips Infra → System Prompt Restructure → Eval + Prune. Add ownership checks as part of the app tips infra phase.

---

## Gaps in Codex's Design

### G1: No Discussion of Prompt Ordering / Attention Patterns

Codex doesn't discuss where in the prompt the most important rules should go. The "Critical Rules First" pattern (from minitap) is absent. This matters because LLMs attend differently to the beginning vs middle vs end of system prompts.

### G2: No Token Budget Discussion

Neither design discusses the token budget, but Codex especially should have noted it. The current prompt is ~205 lines (~800-1000 tokens). Tool descriptions add ~200-300 tokens each. With 9 tools, that's ~2000-2700 tokens of tool descriptions. The system prompt reduction from ~1000 to ~400-500 tokens is meaningful but the total prompt payload (system + tools + screen state + history) still matters.

### G3: Foreground App Detection for Hint Injection

Codex says "inject only hints relevant to the current goal/app" but doesn't discuss how to detect the current app. The agent may navigate across multiple apps during a task. My design notes detection via foreground package from the accessibility service, but neither of us addresses the multi-app case (e.g., task involves both Markor and Calendar — which app tips get injected?).

**Recommendation for both designs:** Inject tips for all apps mentioned in the goal (via name matching) PLUS the current foreground app. This handles multi-app tasks and navigational discovery.

### G4: No Examples of the Enriched Tool Descriptions

Codex lists which tools need enrichment but doesn't show what the enriched descriptions would look like. My design at least specifies the content to add. For implementation, concrete before/after examples of tool descriptions would be valuable.

---

## Gaps in My Design

### G5: No V3.5 Analysis

As noted in S6, I missed MobileAgent V3.5 entirely. It's the most relevant reference for our tool-calling architecture.

### G6: No Rule Inventory Phase

Codex's inventory phase is genuinely valuable. I should have included it.

### G7: No Ownership Regression Prevention

Codex's snapshot tests and ownership check idea addresses a real risk: the refactored prompt slowly accumulating patches again. My design has no mechanism to prevent this.

### G8: Overfit Rules Not Explicitly Called Out

Codex specifically identifies "< 5 turns remaining" and other likely overfit rules. My design treats all current content as equally valid for relocation, which misses the opportunity to prune.

---

## Recommended Aligned Design

Based on both designs, the aligned plan should be:

1. **Ownership model:** Three layers (core prompt, tool descriptions, dynamic hints) — both agree
2. **Section hierarchy:** Codex's 7 sections with my "Critical Rules First" ordering
3. **Task Modes:** Adopt Codex's explicit mode taxonomy
4. **Dynamic hints:** My `AppTips.kt` registry with Codex's quality bar applied to each hint
5. **Migration:** 5 phases (Inventory → Tool Enrichment → Hints Infra → Restructure → Eval+Prune)
6. **Architecture:** Start simple (2 files), add complexity only if needed
7. **Guardrails:** Add ownership checks per Codex's Phase 6
8. **Multi-app hints:** Inject for all apps in goal + current foreground
