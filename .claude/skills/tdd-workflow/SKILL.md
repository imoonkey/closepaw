# TDD Workflow

Test-driven development for Android/Kotlin.

## When to Activate

**Use for:**
- Core agent logic (state machines, orchestration)
- Data transformations
- Protocol implementations
- Pure utility functions

**Skip for:**
- UI components
- Android system integration
- Simple CRUD

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
