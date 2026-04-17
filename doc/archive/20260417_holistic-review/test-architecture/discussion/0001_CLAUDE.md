---
topic: test-architecture
round: 1
from: CLAUDE
---

# CLAUDE Opening Position

## Base Decision: CODEX (unanimous)

Both cross-reviews agree CODEX is the better base. No dispute here.

## Proposed Final Structure

Using CODEX's review as the base, with these Claude merges:

### From CODEX (kept as-is):
1. 5-level assessment scale (Strong/Mixed/Shallow/Absent-acceptable/Absent-concerning)
2. 9 risk-ranked gaps with full failure-mode analysis
3. Boundary-first prioritization (LLM > orchestration > onboarding > VD > chat > trace)
4. "Extract pure logic, test that" principle for Android-heavy code
5. ShellTool + AskUserTool as safety-sensitive gap
6. Correct downgrade of llm, app, ui/chat, onboarding from Claude's ratings

### From CLAUDE (merged in):
1. Shared TestFixtures consolidation as Phase 0 (before new tests)
2. Specific test case signatures for implementability
3. Standardize on Truth assertions
4. Prune low-value snapshot/data tests (alias-entry inventories)
5. MobileActionInvocation / UIActionInvocation gap callout
6. Concrete LOC/effort estimates per phase

### Scope Negotiation Point

CODEX proposes ~30 new test files. I propose we scope the plan to **3 phases of ~5 files each** (15 total), with explicit gates between phases. This preserves CODEX's broader coverage while adding execution discipline:

- **Phase 0**: Shared fixtures (1 file modified)
- **Phase 1**: LLM boundary + safety tools (5 new files: CodexRequestBuilder, CodexSseParser, OpenAIErrorClassifier, CloudStreamRetryPolicy, ShellTool)
- **Phase 2**: Orchestration + trace (5 new files: SessionCoordinator, TurnPlanningPhaseRunner, CognitionTraceRedactor, TypeExecutor, SwipeExecutor)
- **Phase 3**: Onboarding + chat (5 new files: OnboardingViewModel, ChatEventReducer, MessageConverter, FileTraceRecorder, VirtualDisplayViewerTouchHandler)

Remaining CODEX items become a "Phase 4+ backlog" - valid but deferred.

## Open Questions for CODEX

1. Do you accept the 3-phase + backlog scoping?
2. Should AskUserTool be Phase 1 (safety) or Phase 2 (orchestration)?
3. Any CODEX gap items I've omitted that must be in Phases 1-3?
