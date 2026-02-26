# SSL Bypass for Frozen System Time

## Problem

AndroidWorld sets the emulator system time to a fixed past date (Oct 2023) via `freeze_datetime: true` for reproducible task scoring. This is critical because tasks like `SimpleCalendarAddOneEvent` and `ExpenseAddMultipleFromMarkor` are scored by checking database rows with expected timestamps — if the system time is "now" instead of Oct 2023, the timestamps won't match and the task scores 0 regardless of whether the agent completed the workflow correctly.

However, freezing the clock to Oct 2023 causes HTTPS certificate validation to fail. SSL certificates issued after Oct 2023 appear "not yet valid" from the emulator's perspective, and the OpenAI SDK's OkHttp client rejects the connection. This made LLM API calls fail entirely, forcing `freeze_datetime: false` as a workaround — which then caused the date-dependent scoring mismatches observed in the round 7 post-fix eval (run 183513).

**Failure chain** (two layers):
```
Layer 1 — SSL:
  freeze_datetime: true
    → emulator clock = Oct 2023
    → SSL cert "notBefore" > system time
    → javax.net.ssl.SSLHandshakeException
    → all LLM calls fail
    → agent cannot operate

Layer 2 — Bridge workaround (compounded the problem):
  _ensure_device_time_is_sane() in native_agent_bridge.py
    → re-enables auto_time=1 (NTP sync)
    → validates clock skew against host time
    → even with freeze_datetime: true, emulator time reverts to real time
    → scoring timestamp mismatches persist

Previous workaround:
  freeze_datetime: false
    → emulator clock = real time (Feb 2026)
    → SSL works, agent operates normally
    → but calendar/expense timestamps = Feb 2026, not Oct 2023
    → scoring expects Oct 2023 timestamps → score = 0
```

## Solution

Inject a trust-all SSL configuration into the OpenAI SDK client that skips certificate date validation. This is applied only in debug builds (`BuildConfig.DEBUG`), so release builds retain full certificate validation.

### Files Changed

#### 1. `app/src/main/kotlin/com/moonkey/androidagent/llm/InsecureSslConfig.kt` (new)

Singleton providing two components:
- **`trustManager`**: An `X509TrustManager` with no-op `checkClientTrusted` / `checkServerTrusted` methods — accepts all certificates regardless of date, issuer, or chain.
- **`sslSocketFactory`**: An `SSLSocketFactory` from an `SSLContext` initialized with the trust-all manager.

```kotlin
object InsecureSslConfig {
    val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    val sslSocketFactory: SSLSocketFactory by lazy {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())
        sslContext.socketFactory
    }
}
```

#### 2. `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIResponseClient.kt` (modified)

Added conditional SSL bypass to the `OpenAIOkHttpClient.builder()` chain in `init`:

```kotlin
client = OpenAIOkHttpClient.builder()
    .apiKey(apiKey)
    .apply { baseUrl?.let { baseUrl(it) } }
    .apply {
        if (BuildConfig.DEBUG) {
            sslSocketFactory(InsecureSslConfig.sslSocketFactory)
            trustManager(InsecureSslConfig.trustManager)
        }
    }
    .build()
```

#### 3. `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt` (modified)

Same pattern applied to the `client` property initializer.

#### 4. `eval/config/default.yaml` (modified)

Changed `freeze_datetime: false` to `freeze_datetime: true` with explanatory comment:
```yaml
freeze_datetime: true  # AndroidWorld sets system time to Oct 2023; app uses InsecureSslConfig to bypass cert date checks
```

#### 5. `eval/aw_bridge/native_agent_bridge.py` (modified)

Removed `_ensure_device_time_is_sane()` method and its call from `_start_agent()`. This method was an earlier workaround that re-enabled `auto_time=1` (NTP sync) before each task run, which undid AndroidWorld's frozen time. Also removed the `_MAX_ALLOWED_TIME_SKEW_SEC` constant. With `InsecureSslConfig` handling SSL, this workaround is no longer needed and was actively preventing `freeze_datetime` from working.

## How It Was Found

The OpenAI Java SDK (v4.14.0) uses `OpenAIOkHttpClient` internally. Decompiling the SDK jar confirmed that `OpenAIOkHttpClient.Builder` exposes `.sslSocketFactory()`, `.trustManager()`, and `.hostnameVerifier()` methods — the same customization points available on raw OkHttp clients. This made it a straightforward 3-file change without needing to fork or wrap the SDK.

## Verified Impact

With `freeze_datetime: true` and the bridge fix, the emulator system time is Oct 2023 during eval. Verified with `SimpleCalendarAddOneEvent`:

- **Run 183513** (pre-fix, `freeze_datetime: false`): score=0.0, 30 turns, agent completed task but timestamps mismatched
- **Run 210502** (SSL fix only, bridge still overriding time): score=0.0, 30 turns, emulator still showed Feb 2026
- **Run 222122** (SSL fix + bridge fix): **score=1.0**, ~22 turns, 197s. Calendar opened to Oct 2023 directly, no date navigation needed

Additional benefits:
- **Fewer turns**: Calendar opens to Oct 2023 so the agent doesn't waste 14+ turns navigating the date picker from Feb 2026
- **Correct timestamps**: DB rows match scorer expectations for `start_ts`, `end_ts`, `time_zone`
- **ExpenseAddMultipleFromMarkor**: `created_date` should now match expected value (needs re-verification)

## Security Note

The trust-all TrustManager disables all certificate validation (not just date checks). This is acceptable because:
1. It is gated behind `BuildConfig.DEBUG` — release builds are unaffected.
2. The eval environment is a local emulator connecting to known LLM API endpoints.
3. This is a standard Android debug pattern (used in development/test builds across the ecosystem).

A more surgical fix (custom TrustManager that validates everything except date) would be possible but adds complexity with no practical security benefit in this context.
