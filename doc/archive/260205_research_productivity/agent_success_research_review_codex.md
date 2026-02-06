# Agent Success Research Docs Review (Codex)

> Reviewer: Codex  
> Date: 2026-02-04  
> Scope: `agent_success_research_1.md`, `agent_success_research_2.md`, `agent_success_research_3.md`

## Overall Ratings

| Document | Score (10) | Verdict | Best Use |
|---|---:|---|---|
| `agent_success_research_1.md` | 8.3 | Strong and executable | First implementation wave |
| `agent_success_research_2.md` | 9.0 | Best target architecture | North-star design |
| `agent_success_research_3.md` | 7.4 | Good philosophy, light detail | Design principles and framing |

## Ranking

1. `agent_success_research_2.md`
2. `agent_success_research_1.md`
3. `agent_success_research_3.md`

## Review Details

## 1) `agent_success_research_1.md` (8.3/10)

### Strengths

- Concrete phased delivery with explicit risk/effort (`agent_success_research_1.md:332`).
- Implementation-ready examples and diffs for `Turn.kt` and `ExecutorAgent.kt` (`agent_success_research_1.md:95`, `agent_success_research_1.md:122`).
- Clear near-term value: prompt centralization + full prompt observability (`agent_success_research_1.md:46`, `agent_success_research_1.md:145`).

### Findings

- High: Uses global `PromptTemplates.ACTIVE` singleton (`agent_success_research_1.md:86`), which is weak for per-session experiments and A/B runs.
- High: Adds `TurnLogger` as a parallel channel (`agent_success_research_1.md:152`) instead of integrating into existing trace pipeline; likely to fragment observability.
- Medium: `ContextDumper.capture()` API shape is inconsistent with shown inputs vs used state (`sessionState`, `historyManager`) (`agent_success_research_1.md:261`, `agent_success_research_1.md:269`).
- Low: References include absolute `file:///` links (`agent_success_research_1.md:400`), which are not portable for team collaboration.

### Recommendation

- Keep this as execution blueprint, but replace `ACTIVE` with profile/session-based selection before coding.

## 2) `agent_success_research_2.md` (9.0/10)

### Strengths

- Most complete architecture: `profile`, `prompt`, `context`, `policy`, `metrics` are separated with clear responsibility (`agent_success_research_2.md:35`).
- Strong experiment model via `SuccessProfile` and `successProfileId` (`agent_success_research_2.md:113`, `agent_success_research_2.md:125`).
- Good integration mapping to existing code hot paths (`agent_success_research_2.md:140`).
- Clear acceptance criteria and phased rollout (`agent_success_research_2.md:157`, `agent_success_research_2.md:181`).

### Findings

- High: Large initial scope may overload first rollout (prompt + context + policy + metrics); needs an explicitly minimal slice for first PR.
- Medium: `TurnToolPolicy` behavior boundaries are stated but not formalized as contract/test matrix (`agent_success_research_2.md:104`).
- Medium: Mixed Chinese/English language is readable but may reduce team-wide doc consistency.
- Low: Trace artifact design should explicitly mention PII/redaction policy for prompt and context dumps (`agent_success_research_2.md:133`).

### Recommendation

- Use this as target-state design, but gate rollout with strict Phase A "no behavior change" checks.

## 3) `agent_success_research_3.md` (7.4/10)

### Strengths

- Excellent mental model: "Lab vs Factory" sharply communicates separation of concerns (`agent_success_research_3.md:18`).
- Explicit `AgentRole` is a real improvement over implicit tool-based role inference (`agent_success_research_3.md:53`).
- Strong emphasis on exact prompt trace capture (`agent_success_research_3.md:67`).

### Findings

- High: Insufficient detail on experiment switching/configuration (no profile registry or runtime selection mechanism).
- Medium: Implementation plan is high-level and lacks concrete integration points comparable to Doc 1/2 (`agent_success_research_3.md:79`).
- Medium: No acceptance criteria or regression guardrails.

### Recommendation

- Reuse as architectural philosophy section, not as standalone implementation spec.

## Final Synthesis

- Adopt `agent_success_research_2.md` as the target architecture.
- Execute via the stepwise approach and code-level pragmatism in `agent_success_research_1.md`.
- Use `agent_success_research_3.md` language (Lab vs Factory, explicit role identity) as design principles.

## Suggested Implementation Cut (Pragmatic)

1. Phase A: Prompt centralization + `PromptAssembler` only, behavior unchanged.
2. Phase B: Full trace artifacts (`full_prompt`, `llm_input_items`) with redaction guard.
3. Phase C: Introduce `SuccessProfile` and runtime selection.
4. Phase D: Extract `TurnToolPolicy` with regression tests for baseline parity.
