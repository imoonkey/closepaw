---
description: Test-driven development. Write tests FIRST, then implement.
---

# TDD

Test-driven development for core logic.

## When to Use

Best for:
- State machines, orchestration logic
- Data transformations, utilities
- Protocol implementations

Skip for:
- UI components (manual testing)
- Android system integration (e2e better)

## TDD Cycle

```
RED → GREEN → REFACTOR

1. Write failing test
2. Implement minimal code to pass
3. Refactor, keep tests green
```

## Workflow

### 1. Define Interface
```kotlin
// Define types first
data class Input(...)
sealed class Result { ... }

fun process(input: Input): Result = TODO()
```

### 2. Write Failing Tests
```kotlin
@Test
fun `handles happy path`() {
    val result = process(validInput)
    assertThat(result).isInstanceOf(Result.Success::class.java)
}

@Test
fun `handles edge case`() {
    val result = process(edgeInput)
    assertThat(result).isInstanceOf(Result.Error::class.java)
}
```

### 3. Run Tests (Should Fail)
```bash
./gradlew test --tests "*ClassName*"
```

### 4. Implement
Write minimal code to pass.

### 5. Verify
```bash
./gradlew test
```

### 6. Refactor
Improve while keeping tests green.

## Testing Patterns

```kotlin
// MockK for mocking
val mockClient = mockk<LLMClient>()
coEvery { mockClient.complete(any()) } returns response

// Turbine for Flow testing
flow.test {
    assertThat(awaitItem()).isEqualTo(expected)
    awaitComplete()
}
```

## Coverage Target

- 80% for core logic
- Focus on behavior, not implementation details
