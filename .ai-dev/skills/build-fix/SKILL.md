---
name: build-fix
description: Fix Gradle/Kotlin build errors incrementally. No refactoring - just get the build green. Use when verify fails.
---

# Build Fix

Fix Android build errors quickly with minimal diffs.

## When to Use

- After `/verify` fails with build errors
- During development when build breaks
- Resolving merge conflicts that break build

## Workflow

### 1. Collect Errors

```bash
./gradlew assembleDebug 2>&1 | grep -E "^e:|error:|FAILURE" | head -30
```

### 2. Categorize by Type

- Gradle sync errors
- Kotlin compilation errors
- Resource errors
- Manifest merge conflicts
- Dependency conflicts

### 3. Fix One Error at a Time

- Understand the error
- Apply minimal fix
- Re-run build
- Verify no new errors

If the break is in test compilation or unit tests, use:

```bash
./gradlew test 2>&1 | tail -50
```

### 4. Stop If

- Fix introduces new errors
- Same error after 3 attempts
- User requests pause

## Common Android Errors

### Gradle Sync

```kotlin
// ERROR: Could not resolve dependency
// FIX: Check repositories, version compatibility
```

### Kotlin Type Errors

```kotlin
// ERROR: Type mismatch: inferred type is X but Y was expected
// FIX: Add explicit type, use safe cast
```

### Unresolved Reference

```kotlin
// ERROR: Unresolved reference: SomeClass
// FIX: Add import, check dependency
```

### Null Safety

```kotlin
// ERROR: Only safe (?.) or non-null asserted (!!.) calls allowed
// FIX: Add ?., ?:, or null check
```

### Resource Errors

```kotlin
// ERROR: resource not found
// FIX: Check R imports, resource naming
```

### Manifest Merge

```xml
<!-- ERROR: Manifest merger failed -->
<!-- FIX: Add tools:replace or tools:node="remove" -->
```

### Dependency Conflicts

```kotlin
// ERROR: Duplicate class found
// FIX: Exclude transitive dependency
implementation("lib") {
    exclude(group = "conflicting-group")
}
```

## Minimal Diff Rules

**DO:**
- Add missing imports
- Fix type annotations
- Add null checks
- Fix manifest attributes
- Add `override` keyword

**DON'T:**
- Refactor unrelated code
- Rename things
- Change architecture
- Optimize performance

## Output Format

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
