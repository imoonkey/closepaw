# Codebase Review Plan

> **Reviewer**: Claude (Legendary Engineer Consultant)
> **Date**: January 19, 2026
> **Standard**: Linux-kernel level code quality

## Executive Summary

This review examines the Android Agent codebase with the highest standards for code design, implementation correctness, and robustness. The goal is to identify all logic holes, design principle violations, risks, bugs, and redundancies.

## Review Scope

### Codebase Statistics
- **Total Kotlin files**: ~35 source files
- **Architecture**: Single ReAct Agent with Op/Event protocol
- **Key Patterns**: State machine, dependency injection, sealed classes, coroutines

### Reference Documents
- `doc/agent_infra/infra_summary.md` - Architecture overview
- `doc/agent_infra/protocol.md` - Op/Event protocol specification
- `doc/ui/stack.md` - UI design and implementation

## Review Modules

### 1. Agent Core Review (`agent_core_review.md`)
**Files:**
- `agent/Agent.kt` - ReAct loop executor
- `agent/Turn.kt` - Single LLM turn handler
- `agent/AgentConfig.kt` - Configuration
- `agent/AgentSource.kt` - Agent source enum (future)

**Focus Areas:**
- ReAct loop correctness and edge cases
- Cancellation handling
- Tool call parsing robustness
- History recording accuracy
- Error recovery logic

### 2. Session & Protocol Review (`session_protocol_review.md`)
**Files:**
- `session/AgentSession.kt` - Session lifecycle
- `session/SessionServices.kt` - Dependency injection
- `protocol/Op.kt` - Operations
- `protocol/AgentEvent.kt` - Events
- `protocol/SessionState.kt` - State machine
- `protocol/ApprovalTypes.kt` - Approval types
- `protocol/AgentError.kt` - Error types
- `protocol/SessionId.kt` - ID generation

**Focus Areas:**
- State machine correctness
- Op handling completeness
- Event emission consistency
- Thread safety
- Resource cleanup

### 3. Tool System Review (`tool_system_review.md`)
**Files:**
- `infra/tools/ToolRouter.kt` - Execution state machine
- `infra/tools/ToolSpec.kt` - Tool interface
- `infra/tools/ToolCallState.kt` - State definitions
- `infra/tools/ToolCallResult.kt` - Result types
- `infra/registry/ToolRegistry.kt` - Tool discovery
- `tools/base/BaseTool.kt` - Abstract base
- `tools/impl/*.kt` - Individual tools

**Focus Areas:**
- State machine transitions
- Approval flow correctness
- Validation completeness
- Tool invocation robustness
- Cancellation propagation

### 4. Platform & Perception Review (`platform_perception_review.md`)
**Files:**
- `platform/AndroidPlatform.kt` - Platform interface
- `platform/AccessibilityPlatform.kt` - Implementation
- `platform/UIAction.kt` - Action types
- `platform/ActionResult.kt` - Result types
- `data/perception/Perceptor.kt` - Screen capture
- `domain/models/Models.kt` - Data models

**Focus Areas:**
- Accessibility API usage
- Gesture dispatch reliability
- Memory safety (AccessibilityNodeInfo lifecycle)
- Screen capture accuracy
- Bounds calculation correctness

### 5. Data & Infrastructure Review (`data_infra_review.md`)
**Files:**
- `data/llm/LLMClient.kt` - OpenAI API wrapper
- `data/llm/ChatMessage.kt` - Message types
- `infra/history/HistoryManager.kt` - Conversation history
- `infra/policy/PolicyEngine.kt` - Approval policy

**Focus Areas:**
- Rate limit handling
- Retry logic correctness
- Token budget management
- History normalization
- Policy decision consistency

### 6. UI Layer Review (`ui_layer_review.md`)
**Files:**
- `MainActivity.kt` - Compose entry point
- `AgentService.kt` - AccessibilityService
- `service/OverlayManager.kt` - Floating overlay
- `ui/screen/AgentScreen.kt` - Main UI
- `util/StatusUtils.kt` - Status utilities

**Focus Areas:**
- Lifecycle management
- State synchronization
- Memory leaks
- Thread safety
- UI responsiveness

## Review Criteria

### High-Risk Issues (Must Fix)
- Bugs that cause crashes or data loss
- Security vulnerabilities
- Race conditions / deadlocks
- Memory leaks
- Incorrect state machine transitions
- API contract violations

### Medium Issues (Should Fix)
- Missing error handling
- Inefficient algorithms
- Code duplication
- Unclear abstractions
- Missing validation
- Inconsistent patterns

### Low-Risk Suggestions (Nice to Have)
- Code style improvements
- Documentation gaps
- Performance optimizations
- Refactoring opportunities
- Test coverage gaps

## Review Output Format

Each review document will contain:
1. **Summary** - What the module does
2. **High-Risk Issues** - Must-fix problems with code location and fix proposal
3. **Medium Issues** - Should-fix problems with code location and fix proposal
4. **Low-Risk Suggestions** - Nice-to-have improvements
5. **Questions** - Areas of uncertainty requiring clarification

## Review Schedule

| Order | Module | Priority | Complexity |
|-------|--------|----------|------------|
| 1 | Agent Core | Critical | High |
| 2 | Session & Protocol | Critical | High |
| 3 | Tool System | Critical | High |
| 4 | Platform & Perception | High | Medium |
| 5 | Data & Infrastructure | High | Medium |
| 6 | UI Layer | Medium | Medium |

## Final Deliverable

After all module reviews:
- `overall_code_review.md` - Executive summary with prioritized issues
- Recommendations for immediate fixes
- Design improvement suggestions for larger refactoring
