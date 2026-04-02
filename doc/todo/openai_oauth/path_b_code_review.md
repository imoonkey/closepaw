# Path B Code Review

## Findings Addressed

### 1. ToolCallAccumulator parallel call handling (CRITICAL → FIXED)
Changed from single-slot to map-based accumulator keyed by output_index.
Updated mapToStreamEvent to pass output_index to argument deltas.

### 2. Token rotation / stale token (CRITICAL → DEFERRED)
Session teardown clears the factory cache. Token refresh happens before session creation.
For mid-session refresh, a token supplier pattern would be ideal but requires larger changes
across the LLMClient interface. Deferred — the 1-hour OAuth token expiry exceeds typical
session durations. If needed, can be addressed by clearing factory cache on token refresh.

### 3. Double event processing in chatWithTools (HIGH → FIXED)
Removed mapToStreamEvent call from non-streaming path. All events handled explicitly.

### 4. SSE parser trim issue (HIGH → FIXED)
Changed from `.trim()` to `.trimStart()` on data lines to only strip leading space after "data:".

### 5. Missing LlmLogger calls in non-streaming path (MEDIUM → FIXED)
Added logInput and logOutput calls.

### 6. OkHttp cleanup (MEDIUM → FIXED)
Added connection pool eviction and dispatcher shutdown in cleanup().

### 7. Message "type" field (MEDIUM → VERIFIED NOT NEEDED)
The Codex endpoint infers message type from "role" presence. Validated in OAuthCodexValidator
which uses the same format successfully.

### 8. OkHttp version conflict (LOW → VERIFIED OK)
OpenAI SDK uses OkHttp transitively. Gradle resolves to highest compatible version.
