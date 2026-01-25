---
description: Systematic code review with severity-based findings.
agent: code-reviewer
---

# Code Review

Systematic review process with high standards.

## Review Mindset

High standards like kernel code. Find:
- Logic holes
- Design principle violations
- Risks and bugs
- Redundancies

## Process

1. **Get context**
   ```bash
   git diff --stat
   git log -3 --oneline
   ```

2. **Review changes** against checklist

3. **Document findings** by severity

4. **Fix small issues** inline

5. **Create design docs** for big issues

## Severity Levels

### Critical (Must Fix)
- Security: secrets, injection, leaks
- Crashes: null pointers, unhandled exceptions
- Data loss: state corruption, race conditions

### High (Should Fix)
- Memory leaks
- Threading violations
- Missing validation
- Lifecycle issues

### Medium (Consider)
- Code duplication
- Missing tests
- Large files/functions
- Poor naming

### Low (Nice-to-Have)
- Style consistency
- Documentation gaps
- Minor optimizations

## Android-Specific Checks

- [ ] Coroutines scoped correctly?
- [ ] No Context leaks?
- [ ] Main thread safe?
- [ ] Permissions checked?
- [ ] A11y service best practices?

## Output Format

```markdown
# Review: [scope]

## Summary
[What changed]

## Critical
1. [Issue]: [why + where + fix]

## High
1. [Issue]: [why + where + fix]

## Medium
...

## Recommendation
[APPROVE / CHANGES_REQUESTED]
```

## Reference

- Treat `doc/main/` as context (code is source-of-truth)
- Update docs after fixing issues
