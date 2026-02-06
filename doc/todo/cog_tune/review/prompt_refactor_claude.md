# Review: refactor: simplify prompts and completion schema

**Commit**: `767f577` (2026-02-04)  
**Reviewer**: Claude  
**Files Changed**: 18 (+396, -269)

## Summary

Major refactoring that:
1. **Removes `PromptVariant` abstraction** — inlines role-specific rules directly into `PlannerPromptTemplate` and `ExecutorPromptTemplate`
2. **Removes `SharedPromptRules` and `FailureRecoveryRules`** — eliminates modular prompt composition
3. **Simplifies `complete_task` schema** — drops `reason` parameter, expects failures to include reason in `answer`
4. **Simplifies `PromptAssembler`** — now just picks role-based template + appends state context
5. **Adds `a11y_token_stats.py`** — new diagnostic script for a11y tree token analysis

---

## Critical

*None found.*

---

## High

### 1. Loss of Prompt Reusability
这个是故意的，不要给我改回来。

**Where**: `SharedPromptRules.kt` (deleted), `FailureRecoveryRules.kt` (deleted)  
**Issue**: Previously, rules like `localModelToolCalling`, `plannerRoleRules`, and `executorRoleRules` were modular and composable. Now they're hardcoded into templates.

**Why it matters**: 
- Future prompt tuning requires editing 60+ line template strings instead of isolated rule blocks
- Can't A/B test individual rules without duplicating entire templates
- Violates DRY — similar rules now duplicated across planner/executor templates (e.g., "Use function calling tools only")

**Recommendation**: Consider keeping a thin `PromptRules` object with shared fragments. The templates can still be self-contained but import common rules. This preserves testability without the complexity of full `PromptVariant`.

---

### 2. Removed `reason` Parameter May Degrade Observability
这个是故意的，不要给我改回来。
**Where**: `CompleteTaskTool.kt:32-33`, `SubAgentRunner.kt:145-148`  
**Issue**: The `reason` field was a structured way to capture failure causes. Now failures must embed the reason in `answer`.

**Why it matters**:
- Structured data is easier to parse for analytics and debugging
- The test update shows this: `"answer":"Could not find Notion app: Not installed"` conflates user-facing answer and machine-readable reason
- Harder to build automated failure categorization

**Recommendation**: If simplification is the goal, keep `reason` as an optional field or use a structured format like `status: failure | reason: X | answer: Y`. This preserves backward compatibility and observability.

---

## Medium

### 3. Deleted Tests Without Replacement

**Where**: `AgentPromptBuilderTest.kt` — removed 4 tests  
- `buildSystemPrompt uses planner rules when only planner tools are visible`
- `buildSystemPrompt uses executor rules when mobile action is visible`
- `buildSystemPrompt appends local backend suffix`
- `buildSystemPrompt uses concise prompt variant when profile switches`

**Issue**: These tests validated prompt composition logic. New tests only check base prompt passthrough.

**Recommendation**: Add tests verifying:
- `DefaultPromptAssembler` correctly selects planner vs executor template based on tools
- State context is properly appended
- Template contents match expected patterns

---

### 4. `BuiltinCognitionProfiles.concise` Removed Without Migration Path

**Where**: `BuiltinCognitionProfiles.kt`, `CognitionProfileRegistryTest.kt:17-19`  
**Issue**: The `concise` profile is deleted. Test now just verifies fallback to baseline.

**Why it matters**: Any existing sessions configured with `cognitionProfileId = "concise"` will silently fall back to baseline without warning.

**Recommendation**: Add deprecation logging when an unrecognized profile ID is requested, or throw an explicit error if strictness is preferred over silent fallback.

---

### 5. New Python Script Lacks Type Hints and Tests

**Where**: `inspection_tool/a11y_token_stats.py`  
**Issue**: 308-line script with dataclass, file parsing, and tokenization — no tests, no mypy.

**Positive**: Well-structured with `@dataclass`, argparse, proper error handling.

**Recommendation**: 
- Add `py.typed` marker and run mypy
- Add at least one integration test with sample data
- Consider adding to CI if this becomes a regular diagnostic tool

---

## Low

### 6. Minor: Inconsistent Prompt Formatting

**Where**: `PlannerPromptTemplate.kt:44-48`, `ExecutorPromptTemplate.kt:31-56`  
**Issue**: Planner template uses numbered lists for "Writing Good Executor Queries" section, executor uses `###` headers for query types. Minor inconsistency.

**Recommendation**: Standardize format across templates for cognitive consistency when reading prompts.

---

### 7. Doc Update Could Be More Detailed

**Where**: `doc/main/infra/tools.md`  
**Issue**: Only change is removing `reason` from `complete_task`. The broader architectural changes (removing `PromptVariant`, simplifying assembly) aren't reflected in docs.

**Recommendation**: Update architecture docs to reflect the new simplified prompt flow.

---

## Android-Specific Checks

- [x] Coroutines scoped correctly? — No changes to coroutine handling
- [x] No Context leaks? — Pure Kotlin refactoring, no Context references
- [x] Main thread safe? — Prompt building is already string manipulation, no concern
- [x] Permissions checked? — N/A
- [x] A11y service best practices? — N/A (no accessibility service changes)

---

## Recommendation

**CHANGES_REQUESTED**

The simplification is directionally good (less abstraction = easier debugging), but:

1. **Consider structured failure data** — Even if the schema is simple, `reason` as optional helps observability
2. **Add tests for new prompt assembly logic** — Current test coverage is thinner than before
3. **Document the architectural change** — The removal of `PromptVariant` and modular rules is a significant shift

If the team is comfortable with these tradeoffs and the goal is minimizing prompt engineering complexity for rapid iteration, this can be approved with minor test additions.
