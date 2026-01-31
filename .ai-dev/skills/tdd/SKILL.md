---
name: tdd
description: Test-driven development. Write tests FIRST, then implement. Use for core logic, state machines, data transformations.
---

# TDD

Test-driven development for Android/Kotlin core logic.

## When to Use

**Best for:**
- State machines, orchestration logic
- Data transformations, utilities
- Protocol implementations
- Pure utility functions

**Skip for:**
- UI components (manual testing)
- Android system integration (e2e better)
- Simple CRUD

## TDD Cycle

```
RED → GREEN → REFACTOR

1. Write failing test
2. Implement minimal code to pass
3. Refactor, keep tests green
```

## Workflow

### 1. Define Types

```kotlin
interface Calculator {
    fun calculate(input: Input): Result
}

sealed class Result {
    data class Success(val value: Int) : Result()
    data class Error(val message: String) : Result()
}
```

### 2. Write Tests (RED)

Some tests matter much more than others, some tests no longer make sense because it mocks too much (e.g., without real llm api response, some parts are not really testable). Your goal is to improve system robustness, stability and scalability, not to just hit a test coverage number. If some parts are not suitable for TDD, then do not forcefully follow this process.

```kotlin
class CalculatorTest {
    private val calculator = CalculatorImpl()
    
    @Test
    fun `returns success for valid input`() {
        val result = calculator.calculate(validInput)
        assertThat(result).isInstanceOf(Result.Success::class.java)
    }
    
    @Test
    fun `returns error for invalid input`() {
        val result = calculator.calculate(invalidInput)
        assertThat(result).isInstanceOf(Result.Error::class.java)
    }
    
    @Test
    fun `handles edge case - empty`() {
        val result = calculator.calculate(emptyInput)
        assertThat(result).isEqualTo(Result.Success(0))
    }
}
```

### 3. Run Tests (Should Fail)

```bash
./gradlew test --tests "*CalculatorTest*"
```

### 4. Implement (GREEN)

Write minimal code to pass.

```kotlin
class CalculatorImpl : Calculator {
    override fun calculate(input: Input): Result {
        if (!input.isValid) return Result.Error("Invalid")
        return Result.Success(input.process())
    }
}
```

### 5. Refactor

Keep tests green while improving code.

### 6. Verify Coverage

```bash
./gradlew jacocoTestReport
# Check build/reports/jacoco/
```

## Testing Tools

```kotlin
// MockK
val mock = mockk<Dependency>()
every { mock.call() } returns value
coEvery { mock.suspend() } returns value

// Turbine for Flows
flow.test {
    assertThat(awaitItem()).isEqualTo(expected)
    awaitComplete()
}

// Coroutines Test
@Test
fun `test suspend`() = runTest {
    val result = suspendFunction()
    assertThat(result).isEqualTo(expected)
}
```

## Test Structure (AAA)

```kotlin
@Test
fun `descriptive test name`() {
    // Arrange
    val input = createInput()
    
    // Act
    val result = systemUnderTest.process(input)
    
    // Assert
    assertThat(result).isEqualTo(expected)
}
```

## Coverage Target

- 80% minimum for core logic
- Test behavior, not implementation
