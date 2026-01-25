---
name: coding-standards
description: Android/Kotlin coding conventions and patterns. Auto-applied as reference during code changes.
---

# Coding Standards

Android/Kotlin coding conventions and patterns.

## Core Principles

1. **Readability First** - Clear > clever
2. **KISS** - Simplest solution that works
3. **DRY** - Extract common logic
4. **YAGNI** - Don't build unneeded features

## Kotlin Idioms

### Naming

```kotlin
// Classes: PascalCase
class SessionManager

// Functions/variables: camelCase  
fun processEvent()
val isRunning: Boolean

// Constants: SCREAMING_SNAKE
const val MAX_RETRIES = 3
```

### Null Safety

```kotlin
// ✅ Prefer safe calls
user?.name?.uppercase()

// ✅ Elvis for defaults
val name = user?.name ?: "Unknown"

// ❌ Avoid force unwrap
user!!.name  // BAD
```

### Immutability

```kotlin
// ✅ Prefer val
val state: SessionState

// ✅ Use copy() for modifications
val newState = state.copy(status = Running)

// ❌ Avoid var unless necessary
var mutableState  // BAD if avoidable
```

### Sealed Classes for State

```kotlin
sealed class Result<T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error<T>(val error: Throwable) : Result<T>()
}
```

## Android Patterns

### Coroutines

```kotlin
// ✅ Structured concurrency
viewModelScope.launch {
    // Cancelled when ViewModel cleared
}

// ✅ Main-safe
suspend fun fetchData() = withContext(Dispatchers.IO) {
    // Heavy work
}

// ❌ Avoid GlobalScope
GlobalScope.launch { }  // BAD
```

### Lifecycle

```kotlin
// ✅ Scope to lifecycle
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.state.collect { }
    }
}

// ❌ Avoid static Context refs
companion object {
    var context: Context  // LEAK!
}
```

### State Management

```kotlin
// ViewModel
private val _state = MutableStateFlow(initialState)
val state: StateFlow<UiState> = _state.asStateFlow()

fun updateState(action: Action) {
    _state.update { it.reduce(action) }
}
```

## Code Smells

| Smell | Threshold | Fix |
|-------|-----------|-----|
| Large file | >400 lines | Extract class/functions |
| Deep nesting | >4 levels | Early returns, extract |
| Long function | >50 lines | Break into smaller |
| God class | Does everything | Single responsibility |
| Magic numbers | Unexplained | Named constants |

## Error Handling

```kotlin
// ✅ Comprehensive
try {
    val result = riskyOperation()
    onSuccess(result)
} catch (e: SpecificException) {
    log.error("Context: ${e.message}")
    onError(AgentError.from(e))
}

// ❌ Swallowing
try { riskyOperation() } catch (e: Exception) { }  // BAD
```
