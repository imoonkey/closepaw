# Tooling and Platform Review

## Summary
The `tools/` and `platform/` packages implement the actual capabilities of the agent. The `ToolRouter` provides a robust state machine for execution and policy enforcement. `AccessibilityPlatform` maps semantic actions to Android accessibility calls.

## High-Risk Issues (Must-Fix)

### 1. Observation Data Loss
**Location**: `infra/tools/ToolRouter.kt` line 194
**Issue**: The `execute` method returns `ToolCallResult.Success` but *drops* the `observation` field from the internal `ToolExecutionResult`.
**Code**: `ToolCallResult.Success(callId, executionResult.output, executionResult.data)`
**Impact**: The screen capture performed by `BaseTool` is lost. This forces `Agent.kt` to re-capture the screen, causing the "Double Observation" performance issue.
**Fix**: Add `observation` field to `ToolCallResult.Success` and propagate it.

## Medium Issues (Should-Fix)

### 2. Double Screen Capture
**Location**: `tools/base/BaseTool.kt` lines 193-209
**Issue**: Every tool execution waits for UI settle and captures screen. This is correct for V2 "Tools with Observation", but since `Agent.kt` does it too, it's redundant.
**Fix**: Once Issue #1 is fixed, remove the manual capture in `Agent.kt` and rely on the tool's observation.

### 3. Weak Permission Check
**Location**: `platform/AccessibilityPlatform.kt` line 53
**Code**: `return service.serviceInfo != null`
**Issue**: Checking `serviceInfo` might not be sufficient to guarantee the service is actually connected and able to dispatch gestures.
**Fix**: Use `AccessibilityManager` to verify the service is enabled and running, or rely on `onServiceConnected` state in `AgentService`.

## Low-Risk Suggestions (Nice-to-Have)

### 4. Hardcoded Gestures
**Location**: `platform/AccessibilityPlatform.kt`
**Suggestion**: Scroll and swipe gestures use hardcoded durations (300ms) and path logic. These might fail on different device sizes or UI frameworks (e.g. Flutter vs Native). Consider making these configurable or adaptive.

### 5. Element Lookup Safety
**Location**: `platform/AccessibilityPlatform.kt` line 82
**Suggestion**: `snapshot.rawMap[action.elementIndex]` assumes the map is valid. If the screen has changed significantly between perception and action, this index might be stale or point to a wrong element. `Perceptor` logic limits elements to 80, but indexes are simple integers. Using resource IDs or more robust selectors would be safer, though harder with Accessibility.
