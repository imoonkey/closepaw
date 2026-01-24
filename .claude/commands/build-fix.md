# Build Fix

Fix Gradle/Kotlin errors incrementally.

## Instructions

1. **Run build, capture errors**
   ```bash
   ./gradlew assembleDebug 2>&1 | grep -E "^e:|error:|FAILURE"
   ```

2. **For each error:**
   - Show error context
   - Explain issue
   - Apply minimal fix
   - Re-run build
   - Verify resolved

3. **Stop if:**
   - Fix introduces new errors
   - Same error after 3 attempts
   - User requests pause

## Common Fixes

| Error | Fix |
|-------|-----|
| Unresolved reference | Add import, check dependency |
| Type mismatch | Explicit type, safe cast |
| Null check | Add `?.`, `?:`, or null check |
| Missing override | Add `override` keyword |
| Resource not found | Check R import, resource name |

## Output

```
BUILD FIX SESSION

Initial errors: X

Fix 1: [file:line]
  Error: ...
  Applied: ...
  Result: [OK/NEW_ERROR]

...

Final status: [PASS/FAIL]
Errors fixed: Y
Remaining: Z
```

## Rules

- One error at a time
- Minimal changes only
- No refactoring
- Re-verify after each fix
