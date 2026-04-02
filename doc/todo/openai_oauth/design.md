status: draft

# OpenAI OAuth for Onboarding Step 4 — Engineering Design

Date: 2026-04-02
Ref: `doc/todo/openai_oauth/note.md`, OpenClaw reference (`.reference/claws/openclaw`)
Depends on: `doc/todo/onboarding_wizard/eng_design.md` (implemented)

---

## 1. Goal

Add "Sign in with OpenAI" as a primary option on the onboarding API Key step (Step 4). Users with an existing OpenAI account (ChatGPT Plus, etc.) can authenticate via OAuth instead of manually creating and pasting an API key. This eliminates the highest-friction step in onboarding for non-technical users.

### 1.1 Constraints

- Coexist with manual API key entry — user chooses.
- OAuth is OpenAI-only. OpenRouter stays manual API key.
- OAuth access token is functionally identical to an API key (`Authorization: Bearer <token>`).
- Token refresh must be transparent — user never sees "session expired."
- No new activities — handle everything within `MainActivity`.

---

## 2. OAuth Protocol Summary

Reuses OpenAI's official Codex CLI client_id (`app_EMoamEEZ73f0CkXaXp7hrann`), same as OpenClaw's pi-mono SDK. Verified: OpenAI's auth server does not enforce redirect_uri whitelist at the authorize stage for this client_id.

| Parameter | Value |
|---|---|
| Authorization endpoint | `https://auth.openai.com/oauth/authorize` |
| Token endpoint | `https://auth.openai.com/oauth/token` |
| Scopes | `openid profile email offline_access` |
| PKCE method | `S256` |
| Grant type (login) | `authorization_code` |
| Grant type (refresh) | `refresh_token` |
| Client ID | `app_EMoamEEZ73f0CkXaXp7hrann` (OpenAI Codex CLI, reused) |
| Redirect URI | `androidagent://oauth/callback` |
| Extra params | `codex_cli_simplified_flow=true` |

### 2.1 Client ID Strategy

OpenClaw's pi-mono SDK hardcodes the same client_id as OpenAI's official Codex CLI (`app_EMoamEEZ73f0CkXaXp7hrann`). We reuse it too. This means:
- If OpenAI keeps the Codex CLI OAuth working, we work.
- If OpenAI revokes this client_id, all three (Codex CLI, OpenClaw, us) break simultaneously.
- No separate OAuth app registration needed.

### 2.2 User Experience

The flow opens a Chrome Custom Tab to `auth.openai.com`. This is browser-based:
- If user is already logged into OpenAI in Chrome → may auto-approve without re-login.
- If not → full login page (email+password, Google/Apple SSO).
- Having the ChatGPT app installed does NOT help — app sessions are not shared with Chrome.

### 2.1 Flow

```
1. Generate PKCE verifier (32 random bytes → base64url)
2. Derive challenge = SHA256(verifier) → base64url
3. Generate state (32 random bytes → hex)
4. Start localhost HTTP server on port 1455
5. Open browser →
     auth.openai.com/oauth/authorize?
       client_id=...&response_type=code&redirect_uri=http://localhost:1455/auth/callback
       &scope=openid+profile+email+offline_access&code_challenge=...&code_challenge_method=S256
       &state=...&codex_cli_simplified_flow=true
6. User authenticates in browser
7. Redirect → http://localhost:1455/auth/callback?code=...&state=...
8. Localhost server captures code, validates state, returns "Sign-in complete" HTML
9. POST token endpoint: grant_type=authorization_code, code, code_verifier, client_id, redirect_uri
10. Receive: { access_token, refresh_token, expires_in }
11. Extract email from JWT claims (access token is a JWT)
12. Store credentials to OAuthCredentialStore, save access_token as effective API key
```

### 2.2 Redirect URI Constraint

OpenAI enforces redirect_uri whitelist per client_id. Tested on device:
- `http://localhost:1455/auth/callback` → login page loads (accepted)
- `androidagent://oauth/callback` → "Authentication Error" (rejected)
- `https://closepaw.ai/oauth/callback` → "Authentication Error" (rejected)

Only `http://localhost:*` patterns are allowed for this client_id. The localhost server approach (same as Codex CLI) is the only viable path without registering a custom client_id.

---

## 3. UX Changes to Step 4

### 3.1 Auth Method Picker

When OpenAI provider is selected, show two auth method options above the key field:

```
┌──────────────────────────────────────────┐
│                                          │
│  Step 4 of 5    ████████████████░        │
│                                          │
│        (Key icon)                        │
│                                          │
│     Connect your model                   │
│                                          │
│  ┌─ OpenAI ─┐  ┌─ OpenRouter ─┐        │
│  │  ██████  │  │              │        │  ← Provider chips (existing)
│  └──────────┘  └──────────────┘        │
│                                          │
│  ┌─────────────────────────────────┐    │
│  │  🔑 Sign in with OpenAI        │    │  ← Primary (recommended)
│  └─────────────────────────────────┘    │
│                                          │
│       or enter API key manually          │  ← Secondary link
│                                          │
│  ⓘ Uses your existing OpenAI account.  │
│    No API key needed.                    │
│                                          │
└──────────────────────────────────────────┘
```

**After tapping "or enter API key manually"** → collapse to existing API key text field UI.

**When OpenRouter is selected** → show only the existing API key field (no OAuth option).

### 3.2 OAuth In-Progress

```
┌──────────────────────────────────────────┐
│                                          │
│  Step 4 of 5    ████████████████░        │
│                                          │
│     Waiting for sign-in...               │
│                                          │
│        (Circular progress)               │
│                                          │
│  Complete sign-in in your browser.       │
│  You'll return here automatically.       │
│                                          │
│         [ Cancel ]                       │
│                                          │
└──────────────────────────────────────────┘
```

### 3.3 OAuth Success

```
┌──────────────────────────────────────────┐
│                                          │
│     ✓ Signed in as user@example.com     │
│                                          │
│  ⓘ Using your OpenAI account.           │
│                                          │
└──────────────────────────────────────────┘
```

Brief display → auto-advance to Demo step (same 400ms pattern).

### 3.4 OAuth Error

```
┌──────────────────────────────────────────┐
│                                          │
│  ✕ Sign-in failed                       │
│                                          │
│  {error message}                         │
│                                          │
│    [ Try Again ]                         │
│                                          │
│    or enter API key manually             │
│                                          │
└──────────────────────────────────────────┘
```

---

## 4. State Model Changes

### 4.1 Auth Method Enum

```kotlin
enum class ApiKeyAuthMethod {
    OAUTH,      // Sign in with OpenAI
    MANUAL      // Enter API key
}
```

### 4.2 Extended ApiKeyStepState

Add OAuth-specific states alongside existing manual states:

```kotlin
sealed interface ApiKeyStepState : OnboardingStepState {
    // Existing manual states (unchanged)
    data object Empty : ApiKeyStepState
    data class Editing(val key: String) : ApiKeyStepState
    data class Validating(val key: String) : ApiKeyStepState
    data class Invalid(val key: String, val message: String) : ApiKeyStepState
    data class TransientError(val key: String, val message: String) : ApiKeyStepState
    data class Valid(val key: String) : ApiKeyStepState

    // New OAuth states
    data object OAuthReady : ApiKeyStepState              // Show "Sign in" button
    data object OAuthInProgress : ApiKeyStepState          // Waiting for browser callback
    data class OAuthSuccess(val email: String) : ApiKeyStepState  // Signed in
    data class OAuthError(val message: String) : ApiKeyStepState  // Failed
}
```

### 4.3 Initial State Logic

When entering Step 4:
1. If OAuth credentials exist and are valid → `OAuthSuccess(email)`
2. If manual API key exists → `Editing(key)` (existing behavior)
3. If OpenAI selected → `OAuthReady` (default to OAuth)
4. If OpenRouter selected → `Empty` (manual only)

---

## 5. OAuth State Machine

### 5.1 Transition Table

| Current | Trigger | Guard | Next | Side effects |
|---|---|---|---|---|
| `OAuthReady` | Tap "Sign in with OpenAI" | — | `OAuthInProgress` | Generate PKCE + state; launch Custom Tab |
| `OAuthReady` | Tap "enter API key manually" | — | `Empty` | Switch to manual mode |
| `OAuthInProgress` | Redirect received | State matches | `OAuthSuccess` | Exchange code for tokens; persist; save access_token as API key |
| `OAuthInProgress` | Redirect received | State mismatch | `OAuthError` | "Sign-in was interrupted. Please try again." |
| `OAuthInProgress` | Token exchange failed | Auth error | `OAuthError` | Show error message |
| `OAuthInProgress` | Token exchange failed | Network error | `OAuthError` | "Couldn't complete sign-in. Check your connection." |
| `OAuthInProgress` | Tap "Cancel" | — | `OAuthReady` | Cancel pending exchange |
| `OAuthInProgress` | Custom Tab closed (no redirect) | Timeout 120s | `OAuthReady` | Silent return to ready state |
| `OAuthError` | Tap "Try Again" | — | `OAuthInProgress` | Fresh PKCE + state; relaunch Custom Tab |
| `OAuthError` | Tap "enter API key manually" | — | `Empty` | Switch to manual mode |
| `OAuthSuccess` | Auto-advance delay | — | `DemoStep` | Persist apiKey = done; advance |

### 5.2 Custom Tab Lifecycle

- User may close Custom Tab without completing → detect via `onResume` + no callback within 2s → return to `OAuthReady` silently.
- User may switch apps → keep `OAuthInProgress` alive until timeout (120s).
- Multiple rapid taps → ignore if already `OAuthInProgress`.

---

## 6. Component Architecture

### 6.1 New Files

```
app/src/main/kotlin/com/moonkey/androidagent/
├── auth/
│   ├── OAuthConfig.kt              # Endpoints, client ID, redirect URI constants
│   ├── OAuthPkce.kt                # PKCE verifier/challenge generation
│   ├── OAuthTokenExchange.kt       # Code → token HTTP exchange
│   ├── OAuthCredentialStore.kt     # Encrypted storage for tokens
│   ├── OAuthTokenRefresher.kt      # Refresh logic (called before session creation)
│   └── OAuthJwtParser.kt           # Extract email/account from access token JWT
```

### 6.2 Modified Files

| File | Change |
|---|---|
| `OnboardingState.kt` | Add `ApiKeyAuthMethod`, OAuth states to `ApiKeyStepState` |
| `OnboardingViewModel.kt` | Add OAuth flow methods, auth method switching |
| `OnboardingSteps.kt` | Add OAuth UI to `ApiKeyStepContent` |
| `OnboardingStore.kt` | Add `auth_method` persistence (oauth / manual) |
| `AppSettingsState.kt` | Add `resolveEffectiveApiKey()` that checks OAuth first |
| `MainActivity.kt` | Handle OAuth redirect intent; pass to ViewModel |
| `AndroidManifest.xml` | Add intent filter for `androidagent://oauth/callback` |
| `build.gradle` | Add `OPENAI_OAUTH_CLIENT_ID` build config field |

### 6.3 Unchanged

| File | Why |
|---|---|
| `LLMClientFactory.kt` | Receives API key string — doesn't care if it came from OAuth |
| `ChatCompletionClient.kt` | Bearer token is Bearer token |
| `SessionLlmBootstrapper.kt` | Gets keys from `buildApiKeys()` — source is transparent |
| `LlmCredentialValidator.kt` | Not used for OAuth (token exchange IS validation) |

---

## 7. Token Storage

### 7.1 OAuthCredentialStore

Separate encrypted SharedPreferences file: `"oauth_credentials"`.

```kotlin
data class OAuthCredentials(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,          // epoch millis
    val email: String?,
    val accountId: String?
)
```

| Key | Type | Meaning |
|---|---|---|
| `oauth_access_token` | String | Current access token |
| `oauth_refresh_token` | String | Refresh token (long-lived) |
| `oauth_expires_at` | Long | Token expiry (epoch ms) |
| `oauth_email` | String? | User email from JWT |
| `oauth_account_id` | String? | OpenAI account ID from JWT |

### 7.2 Invariants

- OAuth tokens are stored independently from manual API keys. Both can coexist.
- `OnboardingStore.auth_method` records which method the user chose (for display/resume).
- When OAuth is active, `AppSettingsState.apiKey` is populated from `accessToken` on every session creation (after refresh if needed).
- Clearing OAuth credentials falls back to manual API key if one exists.

---

## 8. Token Refresh

### 8.1 Strategy: Refresh Before Session Creation

Token refresh is NOT done via OkHttp interceptor. Instead:

1. **On app launch** (before session creation): `OAuthTokenRefresher.ensureFreshToken()`.
2. If `expiresAt - now < REFRESH_BUFFER_MS (5 min)` → refresh.
3. Refresh call: `POST auth.openai.com/oauth/token` with `grant_type=refresh_token`.
4. On success → update stored credentials + update `AppSettingsState.apiKey`.
5. On failure → clear OAuth credentials, show re-auth prompt in chat.

### 8.2 Why Not OkHttp Interceptor

- Access tokens typically live 1 hour. Agent sessions rarely exceed this.
- Adding an interceptor requires modifying `LLMClientFactory` and `OpenAIOkHttpClient` builder — high blast radius.
- Pre-session refresh covers 99% of cases with zero pipeline changes.
- If mid-session 401 occurs, existing retry logic fails cleanly with an actionable error.

### 8.3 Refresh Failure Handling

| Condition | Action |
|---|---|
| Refresh 200 | Update tokens, proceed |
| Refresh 401 (revoked) | Clear OAuth credentials, set `authMethod = manual`, show re-auth banner |
| Network error | Retry once after 2s; if still failing, proceed with current token (may 401 later) |

### 8.4 Where Refresh Is Called

```kotlin
// MainActivity.kt — before createFreshSession / createOrReloadSession
suspend fun resolveApiKeys(): Map<String, String> {
    oAuthCredentialStore.credentials?.let { creds ->
        val fresh = oAuthTokenRefresher.ensureFresh(creds)
        if (fresh != null) {
            settingsState.updateApiKey(fresh.accessToken)  // silent update
        } else {
            // Token revoked — fallback to manual key or prompt re-auth
        }
    }
    return settingsState.buildApiKeys()
}
```

---

## 9. Intent Handling — OAuth Redirect

### 9.1 Manifest

```xml
<activity android:name=".app.MainActivity"
    android:launchMode="singleTask">  <!-- required for redirect to same instance -->
    ...
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="androidagent" android:host="oauth" android:path="/callback" />
    </intent-filter>
</activity>
```

### 9.2 Intent Flow

```
Custom Tab redirects → androidagent://oauth/callback?code=xxx&state=yyy
  → MainActivity.onNewIntent()
  → Extract code + state from URI
  → Forward to OnboardingViewModel.handleOAuthCallback(code, state)
  → ViewModel validates state, exchanges code for tokens
  → Transition to OAuthSuccess or OAuthError
```

### 9.3 MainActivity.onNewIntent

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    val uri = intent.data
    if (uri?.scheme == "androidagent" && uri.host == "oauth") {
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        val error = uri.getQueryParameter("error")
        onboardingViewModel?.handleOAuthCallback(code, state, error)
    }
}
```

### 9.4 launchMode

`MainActivity` needs `singleTask` (or `singleTop`) so the OAuth redirect goes back to the existing instance instead of creating a new one. Check current `launchMode` — if already `singleTask`, no change needed.

---

## 10. PKCE Implementation

```kotlin
object OAuthPkce {
    fun generate(): PkceChallenge {
        val verifier = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .toBase64Url()
        val challenge = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
            .toBase64Url()
        return PkceChallenge(verifier = verifier, challenge = challenge)
    }

    private fun ByteArray.toBase64Url(): String =
        Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}

data class PkceChallenge(val verifier: String, val challenge: String)
```

State parameter: 32 random bytes → hex encoded.

Both `verifier` and `state` are held in ViewModel memory during the OAuth flow. Not persisted — if the process dies mid-OAuth, user simply retries.

---

## 11. Token Exchange

### 11.1 OAuthTokenExchange

Direct `HttpURLConnection` (same pattern as `HttpLlmCredentialValidator`).

```kotlin
class OAuthTokenExchange(private val config: OAuthConfig) {

    sealed interface Result {
        data class Success(val credentials: OAuthCredentials) : Result
        data class AuthError(val message: String) : Result
        data class NetworkError(val message: String) : Result
    }

    suspend fun exchange(code: String, verifier: String): Result
    suspend fun refresh(refreshToken: String): Result
}
```

### 11.2 Exchange Request

```http
POST https://auth.openai.com/oauth/token
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&code={code}
&code_verifier={verifier}
&client_id={OPENAI_OAUTH_CLIENT_ID}
&redirect_uri=androidagent://oauth/callback
```

### 11.3 Refresh Request

```http
POST https://auth.openai.com/oauth/token
Content-Type: application/x-www-form-urlencoded

grant_type=refresh_token
&refresh_token={refresh_token}
&client_id={OPENAI_OAUTH_CLIENT_ID}
```

### 11.4 Response Parsing

```json
{
  "access_token": "...",
  "refresh_token": "...",
  "expires_in": 3600,
  "token_type": "Bearer"
}
```

`expires_in` → `expiresAt = System.currentTimeMillis() + (expires_in * 1000)`.

### 11.5 Error Mapping

| Condition | Result |
|---|---|
| HTTP 200 | `Success` — parse tokens, extract JWT claims |
| HTTP 400 (invalid_grant) | `AuthError` — "Sign-in expired. Please try again." |
| HTTP 401 / 403 | `AuthError` — "Authorization was denied." |
| HTTP 5xx / timeout | `NetworkError` — "Couldn't complete sign-in." |
| IOException | `NetworkError` — "Check your internet connection." |

---

## 12. JWT Parsing

### 12.1 Purpose

Extract email and account ID from the access token JWT (no verification needed — the token came directly from OpenAI's token endpoint over HTTPS).

### 12.2 Claims Priority

Per OpenClaw reference:

**Account ID** (first non-null):
1. `https://api.openai.com/auth.chatgpt_account_user_id`
2. `https://api.openai.com/auth.chatgpt_user_id`
3. `https://api.openai.com/auth.user_id`
4. `sub`

**Email**:
1. `https://api.openai.com/profile.email`
2. `email`

### 12.3 Implementation

```kotlin
object OAuthJwtParser {
    data class Claims(val email: String?, val accountId: String?)

    fun parse(accessToken: String): Claims {
        val parts = accessToken.split(".")
        if (parts.size != 3) return Claims(null, null)
        val payload = Base64.decode(parts[1], Base64.URL_SAFE)
        val json = JSONObject(String(payload, Charsets.UTF_8))
        // extract claims per priority
    }
}
```

---

## 13. OnboardingViewModel Changes

### 13.1 New State

```kotlin
var authMethod by mutableStateOf(ApiKeyAuthMethod.OAUTH)
    private set

// PKCE state (in-memory only, not persisted)
private var pendingPkce: PkceChallenge? = null
private var pendingState: String? = null
```

### 13.2 New Methods

```kotlin
fun selectAuthMethod(method: ApiKeyAuthMethod)    // Switch between OAuth and manual
fun startOAuth(): Intent                          // Generate PKCE, return Custom Tab intent
fun handleOAuthCallback(code: String?, state: String?, error: String?)
fun cancelOAuth()
```

### 13.3 Step Entry Logic (revised)

```kotlin
private fun enterApiKeyStep() {
    val oauthCreds = oAuthCredentialStore.load()
    when {
        // OAuth credentials exist and valid
        oauthCreds != null && oauthCreds.expiresAt > System.currentTimeMillis() -> {
            authMethod = ApiKeyAuthMethod.OAUTH
            stepState = ApiKeyStepState.OAuthSuccess(oauthCreds.email ?: "")
            // outcome already Done, will auto-advance
        }
        // Manual key exists
        existingKey != null -> {
            authMethod = ApiKeyAuthMethod.MANUAL
            stepState = ApiKeyStepState.Editing(existingKey)
        }
        // OpenAI selected → default to OAuth
        selectedProvider == OnboardingProvider.OPENAI -> {
            authMethod = ApiKeyAuthMethod.OAUTH
            stepState = ApiKeyStepState.OAuthReady
        }
        // OpenRouter → manual only
        else -> {
            authMethod = ApiKeyAuthMethod.MANUAL
            stepState = ApiKeyStepState.Empty
        }
    }
}
```

### 13.4 OAuth Callback Handling

```kotlin
fun handleOAuthCallback(code: String?, state: String?, error: String?) {
    if (stepState !is ApiKeyStepState.OAuthInProgress) return

    if (error != null) {
        stepState = ApiKeyStepState.OAuthError("Sign-in was cancelled.")
        return
    }
    if (code == null || state != pendingState) {
        stepState = ApiKeyStepState.OAuthError("Sign-in was interrupted. Please try again.")
        return
    }

    scope.launch {
        val result = oAuthTokenExchange.exchange(code, pendingPkce!!.verifier)
        when (result) {
            is Success -> {
                oAuthCredentialStore.save(result.credentials)
                settingsState.updateApiKey(result.credentials.accessToken)
                store.saveAuthMethod("oauth")
                store.saveOutcome(WizardStep.ApiKey, StepOutcome.Done)
                outcomes = outcomes.copy(apiKey = StepOutcome.Done)
                stepState = ApiKeyStepState.OAuthSuccess(result.credentials.email ?: "")
                delay(AUTO_ADVANCE_DELAY_MS)
                advanceToNextStep()
            }
            is AuthError -> stepState = ApiKeyStepState.OAuthError(result.message)
            is NetworkError -> stepState = ApiKeyStepState.OAuthError(result.message)
        }
        pendingPkce = null
        pendingState = null
    }
}
```

---

## 14. Persistence Additions

### 14.1 OnboardingStore

New key:

| Key | Type | Values |
|---|---|---|
| `auth_method` | String | `"oauth"` / `"manual"` |

Used to restore the correct UI mode when resuming onboarding.

### 14.2 OAuthCredentialStore

Separate encrypted SharedPreferences file `"oauth_credentials"` (not mixed with onboarding prefs or API key prefs).

---

## 15. Post-Onboarding: Token Refresh Integration

### 15.1 Session Creation Hook

In `MainActivity`, before `createFreshSession()` or `createOrReloadSession()`:

```kotlin
private suspend fun refreshOAuthIfNeeded() {
    val creds = oAuthCredentialStore.load() ?: return
    if (creds.expiresAt - System.currentTimeMillis() > REFRESH_BUFFER_MS) return  // still fresh

    val result = oAuthTokenRefresher.refresh(creds.refreshToken)
    when (result) {
        is Success -> {
            oAuthCredentialStore.save(result.credentials)
            settingsState.updateApiKey(result.credentials.accessToken)
        }
        is AuthError -> {
            // Refresh token revoked — clear OAuth, show re-auth banner
            oAuthCredentialStore.clear()
            // Fall back to manual key if exists, otherwise show repair card
        }
        is NetworkError -> {
            // Proceed with current token — may 401 later
            Log.w(TAG, "OAuth refresh failed (network), proceeding with existing token")
        }
    }
}
```

### 15.2 Settings Sheet

After onboarding, the settings sheet should show:
- "Signed in with OpenAI (user@example.com)" if OAuth is active
- "Sign out" button to clear OAuth credentials and revert to manual key entry
- This is out of scope for this design but noted as follow-up.

---

## 16. Complete Step Changes

The Complete screen (Step 6) should reflect the auth method:

- OAuth: `✓ Signed in with OpenAI`
- Manual: `✓ API key verified` (existing)

---

## 17. Edge Cases

| Case | Behavior |
|---|---|
| User starts OAuth, kills app before callback | PKCE state lost. Next launch resumes at Step 4 in `OAuthReady`. |
| User starts OAuth, Custom Tab stays open, returns to app | `onResume` + no callback → stay in `OAuthInProgress`. 120s timeout → `OAuthReady`. |
| User completes OAuth, token expires before demo | Demo preflight calls `refreshOAuthIfNeeded()`. |
| User completes OAuth in onboarding, later revokes access on openai.com | Refresh fails with 401 → clear OAuth → show re-auth repair card in chat. |
| User has both OAuth token and manual API key | OAuth takes precedence when active. Manual key preserved as fallback. |
| User picks OpenRouter after starting OpenAI OAuth | Cancel OAuth, switch to manual mode. |
| User completes OAuth, then switches provider to OpenRouter in Settings | OpenRouter key is independent. OAuth credentials preserved but not used. |
| Device has no browser / Custom Tabs unavailable | Detect via `resolveActivity()`. Show manual-only mode with explanation. |
| OAuth callback arrives but app was recreated (process death) | `state` doesn't match (pendingState is null) → `OAuthError`. User retries. |

---

## 18. Security

- **PKCE S256**: Prevents authorization code interception.
- **State parameter**: CSRF protection — verified before token exchange.
- **No client secret**: Public client (mobile app). PKCE replaces secret.
- **Encrypted storage**: All tokens in EncryptedSharedPreferences (AES256-GCM).
- **Token not logged**: Access and refresh tokens excluded from debug logs.
- **HTTPS only**: All OAuth endpoints are HTTPS.
- **JWT not verified**: Acceptable — token came from OpenAI's token endpoint over TLS. We only parse claims for display (email).

---

## 19. Prerequisites

1. **OpenAI OAuth client registration**: Register Android Agent as an OAuth application on OpenAI's platform. Obtain `client_id`. Register `androidagent://oauth/callback` as redirect URI.
2. **Verify Custom Tabs availability**: Test on target devices (Android 12+). All should have Chrome or equivalent.
3. **Confirm API access via OAuth token**: Verify that OAuth access tokens work with `/v1/chat/completions` on `api.openai.com` (same as API keys).

---

## 20. Implementation Tasks

### Task 1: `oauth-core-infra`
**Scope**: `OAuthConfig.kt`, `OAuthPkce.kt`, `OAuthTokenExchange.kt`, `OAuthJwtParser.kt`, `OAuthCredentialStore.kt`
**Criteria**: PKCE generation, token exchange, JWT parsing, encrypted storage. Unit-testable without UI.
**Deps**: none

### Task 2: `oauth-onboarding-ui`
**Scope**: `OnboardingState.kt`, `OnboardingViewModel.kt`, `OnboardingSteps.kt`, `OnboardingStore.kt`
**Criteria**: Auth method picker, OAuth states, callback handling, step transitions. OAuth and manual coexist.
**Deps**: Task 1

### Task 3: `oauth-redirect-handling`
**Scope**: `AndroidManifest.xml`, `MainActivity.kt`
**Criteria**: Intent filter, `onNewIntent` dispatch, `singleTask` launch mode, Custom Tab launch.
**Deps**: Task 1, Task 2

### Task 4: `oauth-token-refresh`
**Scope**: `OAuthTokenRefresher.kt`, `MainActivity.kt`
**Criteria**: Pre-session refresh, 5-min buffer, failure fallback, re-auth repair prompt.
**Deps**: Task 1, Task 3

### Task 5: `oauth-complete-step-and-polish`
**Scope**: `OnboardingSteps.kt` (Complete step), edge case handling
**Criteria**: Complete screen shows auth method, Custom Tabs fallback, timeout handling.
**Deps**: Task 2, Task 3

---

## 21. Trade-offs

- **No OkHttp interceptor for refresh**: Simpler integration, near-zero blast radius. Acceptable because access tokens outlive typical sessions.
- **No token persistence across providers**: OAuth is OpenAI-only. If OpenRouter adds OAuth later, extend `OAuthCredentialStore` to be provider-keyed.
- **No silent background refresh**: Only refresh on session creation. Avoids background work scheduler complexity.
- **No client secret**: Standard for mobile OAuth. PKCE provides equivalent security.
- **Custom scheme over App Links**: `androidagent://` is simpler than verified App Links (no `.well-known/assetlinks.json` hosting needed). Acceptable because the OAuth flow is user-initiated and the redirect is immediate.
- **PKCE state not persisted**: If process dies mid-OAuth, user retries. This is a 10-second flow — acceptable trade-off vs. encrypting and persisting PKCE verifier.
