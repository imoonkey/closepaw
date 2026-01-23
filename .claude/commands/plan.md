---
description: Create implementation plan. WAIT for confirmation before coding.
---

# Plan

Invoke planner agent for structured planning.

## When to Use

- Starting new feature
- Complex bugfix
- Architectural change
- Multiple files affected
- Requirements unclear

## Process

The planner agent will:

1. Restate requirements
2. Identify affected components
3. Break into phases with:
   - Specific file paths
   - Risk assessment
   - Dependencies
4. **WAIT for your confirmation**

## Example

```
User: /plan Add retry logic to LLM calls

Agent:
# Plan: LLM Retry Logic

## Summary
Add exponential backoff retry for transient LLM failures.

## Affected Components
- `llm/LLMClient.kt`: Add retry wrapper
- `protocol/AgentError.kt`: Add RetryableError type

## Phases

### Phase 1: Error Classification
1. Add `RetryableError` sealed class variant
   Risk: Low

### Phase 2: Retry Logic  
1. Create `withRetry()` extension
2. Integrate with LLM calls
   Risk: Medium (timeout handling)

## Risks
- Timeout stacking: cap total retry time

**Proceed? (yes/modify/no)**
```

## After Confirmation

- Use `/tdd` for core logic
- Use `/verify` before commit
