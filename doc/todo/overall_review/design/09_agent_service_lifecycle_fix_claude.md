# Design: AgentService Lifecycle Fix

**Priority**: P0 — Critical Bug
**Files affected**: `app/AgentService.kt`

---

## Bug Description

`AgentService.onDestroy()` has a race condition that loses the shutdown operation:

```kotlin
// AgentService.kt — current onDestroy() (approximately)
override fun onDestroy() {
    super.onDestroy()
    instance = null
    serviceLifecycleOwner.handleDestroy()
    overlayController?.removeOverlay()

    // BUG: This submits Op.Shutdown to the session...
    scope.launch {
        session?.submit(Op.Shutdown)
    }

    // ...but then immediately kills the scope, cancelling the launch above
    scope.cancel()
}
```

The `scope.launch { session?.submit(Op.Shutdown) }` creates a coroutine, but `scope.cancel()` on the very next line cancels the scope before that coroutine can run. The `Op.Shutdown` is never delivered, so the session never cleans up its resources (LLM connections, platform, traces, etc.).

### Secondary issue: Event collector lacks error boundary

The event collector coroutine in `collectEvents()` has no `try/catch`. If any event processing throws, the collector dies silently, and the capsule stops receiving updates.

## Fix

### 1. Fix the shutdown race

Use `runBlocking` for the shutdown sequence in `onDestroy()`, since this must complete synchronously before the service is destroyed:

```kotlin
override fun onDestroy() {
    super.onDestroy()
    instance = null
    serviceLifecycleOwner.handleDestroy()
    overlayController?.removeOverlay()

    // Shutdown session synchronously — must complete before service dies
    runBlocking {
        try {
            session?.submit(Op.Shutdown)
        } catch (e: Exception) {
            Log.e(TAG, "Session shutdown failed", e)
        }
    }

    scope.cancel()
}
```

Alternative (preferred if `submit` is fast): cancel the running agent first, then clean up:

```kotlin
override fun onDestroy() {
    super.onDestroy()
    instance = null
    serviceLifecycleOwner.handleDestroy()
    overlayController?.removeOverlay()

    // Cancel event collector first
    eventCollectorJob?.cancel()

    // Shutdown session — use runBlocking since we must complete before service dies
    runBlocking(Dispatchers.Default) {
        withTimeout(5_000) {
            try {
                session?.submit(Op.Shutdown)
            } catch (_: Exception) {}
        }
    }

    scope.cancel()
}
```

### 2. Add error boundary to event collector

```kotlin
private fun collectEvents(session: AgentSession) {
    eventCollectorJob?.cancel()
    eventCollectorJob = scope.launch {
        try {
            session.events.collect { event ->
                try {
                    handleEvent(event)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling event: ${event::class.simpleName}", e)
                }
            }
        } catch (e: CancellationException) {
            throw e  // Don't swallow cancellation
        } catch (e: Exception) {
            Log.e(TAG, "Event collector crashed", e)
        }
    }
}
```

## Steps

1. Fix `onDestroy()` shutdown ordering — `runBlocking` with timeout
2. Add per-event `try/catch` in event collector
3. Add top-level `try/catch` around collector flow
4. Test: verify session cleanup completes when service is destroyed
5. Test: verify event collector survives individual event handler exceptions

## Risks

- **Low**: `runBlocking` in `onDestroy()` is acceptable — Android docs allow blocking in `onDestroy()`, and the timeout prevents hangs
- **Low**: 5-second timeout is generous for `Op.Shutdown` processing
- **None**: Error boundary is strictly additive
