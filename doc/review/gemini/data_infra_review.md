# Data and Infrastructure Review

## Summary
This review covers `data/`, `infra/history`, `infra/policy`, and `infra/registry`. The infrastructure provides necessary support for the agent, including perception (converting screen to JSON), LLM communication, and history management.

## High-Risk Issues (Must-Fix)

### 1. Inconsistent Tool Definitions
**Location**: `infra/registry/ToolRegistry.kt` vs `agent/Turn.kt`
**Issue**: `ToolRegistry` has the capability to generate dynamic function schemas (`generateFunctionSchemas`). However, `Turn.kt` uses a hardcoded string for tool instructions.
**Impact**: Adding a tool requires updates in two places. High risk of divergence.
**Fix**: Refactor `Turn.kt` to use `ToolRegistry` for generating prompt instructions.

### 2. Memory Leak Potential in Perception
**Location**: `data/perception/Perceptor.kt` line 38
**Issue**: `ScreenSnapshot` retains the raw `AccessibilityNodeInfo` root. The comment explicitly warns: "Keeping root might cause memory leaks".
**Impact**: `AccessibilityNodeInfo` objects are recycled. Holding onto them can lead to crashes or stale data if the system recycles them under our feet, or memory leaks if we hold them too long.
**Fix**: Do not store the raw `root` in `ScreenSnapshot` if it's meant to persist beyond the immediate turn. If it's needed for action execution, ensure the lifecycle is strictly bound to the turn and cleared immediately after.

## Medium Issues (Should-Fix)

### 3. Inefficient JSON Generation
**Location**: `data/perception/Perceptor.kt` line 46
**Issue**: `toPromptJson` creates a full object graph (`JSONArray`, `JSONObject`) just to convert to string. This generates significant garbage on every turn.
**Fix**: Use a streaming JSON writer (e.g., `JsonWriter` or `Gson`/`Moshi` streaming) to write directly to a string builder, avoiding intermediate object allocation.

### 4. Hardcoded Policy Risk Levels
**Location**: `infra/policy/PolicyEngine.kt` line 26
**Issue**: Risk levels are hardcoded.
**Fix**: Load risk policy from a configuration file or allowing remote configuration would be more flexible for production.

## Low-Risk Suggestions (Nice-to-Have)

### 5. Token Estimation Accuracy
**Location**: `infra/history/HistoryManager.kt`
**Suggestion**: The estimate `0.25 tokens/char` is a very rough heuristic. It might be significantly off for code or JSON heavy content. Consider using a proper tokenizer (e.g. JTokkit) or a better heuristic.

### 6. Ghost Snapshot Clarity
**Location**: `infra/history/HistoryManager.kt`
**Suggestion**: The concept of "Ghost Snapshot" is a bit internal. Ensure it's well-documented why these exist (truncation placeholders).
