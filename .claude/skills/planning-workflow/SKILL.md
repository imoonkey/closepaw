# Planning Workflow

Structured planning before implementation.

## When to Use

- New features
- Complex changes
- Multiple files affected
- Unclear requirements

## Planning Steps

### 1. Understand Requirements
- What problem are we solving?
- What are the constraints?
- What's the success criteria?

### 2. Analyze Impact
```bash
# Find related code
grep -rn "RelatedClass" app/src/ --include="*.kt"
```

Questions:
- What components are affected?
- What dependencies exist?
- What could break?

### 3. Design Approach
- How does this fit existing architecture?
- What patterns should we follow?
- What are the alternatives?

### 4. Break Into Phases

```markdown
## Phase 1: [Foundation]
- [Task 1]: path/to/file.kt
- [Task 2]: path/to/file.kt
Risk: Low

## Phase 2: [Core Logic]
- [Task 3]: ...
Risk: Medium
Dependencies: Phase 1

## Phase 3: [Integration]
- [Task 4]: ...
Risk: High
```

### 5. Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| [Risk 1] | M | H | [How to handle] |

### 6. Testing Strategy

- Unit: [what to test]
- Integration: [what flows]
- Manual: [what to verify]

## Output Format

```markdown
# Plan: [Feature]

## Summary
[2-3 sentences]

## Requirements
- [R1]
- [R2]

## Affected Components
- [Component]: [what changes]

## Phases
[Phase breakdown]

## Risks
[Risk table]

## Testing
[Strategy]

**Waiting for confirmation**
```

## Principles

- Be specific with file paths
- Consider Android-specific concerns (lifecycle, threading)
- Enable incremental verification
- Document decisions
