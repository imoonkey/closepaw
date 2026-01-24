# Verify

Run verification loop before commits.

## Instructions

Execute in order:

1. **Build**
   ```bash
   ./gradlew assembleDebug 2>&1 | tail -20
   ```
   If fails → STOP, report errors

2. **Lint**
   ```bash
   ./gradlew lint 2>&1 | head -30
   ```

3. **Tests**
   ```bash
   ./gradlew test 2>&1 | tail -30
   ```

4. **Security Scan**
   ```bash
   grep -rn "api_key\|apiKey\|sk-" --include="*.kt" app/src/ 2>/dev/null | head -5
   ```

5. **Git Status**
   ```bash
   git diff --stat
   ```

## Output

```
VERIFICATION: [PASS/FAIL]

Build:    [OK/FAIL]
Lint:     [OK/X issues]
Tests:    [OK/X failed]
Security: [OK/X found]

Ready for commit: [YES/NO]

Issues to fix:
1. ...
```

## Arguments

- `quick` - Build + lint only
- `full` - All checks (default)
