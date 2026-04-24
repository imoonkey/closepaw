# Path A Investigation — Token Exchange for Platform API Key

Date: 2026-04-02
Status: Investigation complete — Path A is a dead end for our use case

---

## 1. Problem Statement

When we do OAuth login and get an `id_token`, the token exchange endpoint
(`urn:ietf:params:oauth:grant-type:token-exchange` → `openai-api-key`) rejects
it with: `Invalid ID token: missing organization_id`.

Our `id_token` has organizations nested inside
`https://api.openai.com/auth.organizations[0].id`, but the token exchange
expects a top-level `organization_id` claim directly inside the
`https://api.openai.com/auth` object.

**Question**: How does Codex CLI get `organization_id` at the top level?

---

## 2. Investigation Findings

### 2.1 Codex RS `obtain_api_key` is identical to our code

The `obtain_api_key` function in `server.rs` sends the **exact same parameters**
we do:

```
POST {issuer}/oauth/token
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:token-exchange
client_id={client_id}
requested_token=openai-api-key
subject_token={id_token}        ← sent as-is, no manipulation
subject_token_type=urn:ietf:params:oauth:token-type:id_token
```

**No JWT manipulation. No organization_id injection. No extra headers.** The
id_token is passed through verbatim.

### 2.2 The authorize URL parameters are identical

Codex RS `build_authorize_url` uses the same parameters we use:

| Parameter | Codex RS | Our code |
|---|---|---|
| `response_type` | `code` | `code` |
| `client_id` | same | same |
| `scope` | `openid profile email offline_access api.connectors.read api.connectors.invoke` | same |
| `code_challenge_method` | `S256` | `S256` |
| `id_token_add_organizations` | `true` | `true` |
| `codex_cli_simplified_flow` | `true` | `true` |
| `originator` | `codex_cli_rs` | `codex_cli_rs` |

The only extra parameter Codex RS supports is `allowed_workspace_id` (for
enterprise workspace restrictions), which is `None` in normal use.

### 2.3 The token exchange is OPTIONAL in Codex CLI

**This is the critical finding.** In `server.rs`, the API key exchange is called
with `.ok()`:

```rust
// server.rs — process_request, /auth/callback handler
let api_key = obtain_api_key(&opts.issuer, &opts.client_id, &tokens.id_token)
    .await
    .ok();  // ← OPTIONAL! If it fails, login still succeeds
```

The `api_key` is stored in `AuthDotJson.openai_api_key` as an optional field.
Login succeeds regardless.

### 2.4 Codex CLI uses `access_token` with chatgpt.com, NOT the exchanged API key

The auth mode is always `AuthMode::Chatgpt` after OAuth login:

```rust
// persist_tokens_async in server.rs
let auth = AuthDotJson {
    auth_mode: Some(AuthMode::Chatgpt),   // ← Always Chatgpt mode
    openai_api_key: api_key,              // ← Optional, might be None
    tokens: Some(tokens),
    ...
};
```

And `get_token()` for `Chatgpt` mode returns the `access_token`:

```rust
pub fn get_token(&self) -> Result<String, std::io::Error> {
    match self {
        Self::ApiKey(auth) => Ok(auth.api_key.clone()),
        Self::Chatgpt(_) | Self::ChatgptAuthTokens(_) => {
            let access_token = self.get_token_data()?.access_token;
            Ok(access_token)  // ← Uses access_token, NOT openai_api_key
        }
    }
}
```

Codex CLI hits `chatgpt.com/backend-api/codex/responses` with:
- `Authorization: Bearer {access_token}`
- `chatgpt-account-id: {from JWT claims}`
- `originator: codex_cli_rs`

This is the same endpoint and approach as pi-mono (OpenClaw). The exchanged API
key, even when it succeeds, is NOT used for the primary API calls.

### 2.5 The device code flow skips token exchange entirely

`device_code_auth.rs` calls `persist_tokens_async` with `api_key = None`:

```rust
crate::server::persist_tokens_async(
    &opts.codex_home,
    /*api_key*/ None,  // ← No token exchange attempted
    tokens.id_token,
    tokens.access_token,
    tokens.refresh_token,
    ...
)
```

### 2.6 The `organization_id` claim is account-dependent

The `compose_success_url` function in `server.rs` reads `organization_id` from
the id_token claims:

```rust
let org_id = token_claims.get("organization_id").and_then(|v| v.as_str()).unwrap_or("");
let completed_onboarding = token_claims.get("completed_platform_onboarding")
    .and_then(JsonValue::as_bool).unwrap_or(false);
```

This reveals the claim structure:
- `organization_id` — present only for users with a **platform/API account**
- `completed_platform_onboarding` — whether they've finished API setup
- `organizations` array — present for all users when
  `id_token_add_organizations=true`

**The token exchange endpoint requires `organization_id` as a direct claim in
the id_token. This claim only exists for users who have completed OpenAI
platform onboarding (created an API billing account with an org).** ChatGPT-only
subscribers don't have it.

### 2.7 Pi-mono (OpenClaw) doesn't do token exchange at all

Pi-mono's `openai-codex.ts` has no token exchange step. After getting the
`access_token` from the authorization code exchange, it uses it directly with
`chatgpt.com/backend-api`. The scope is `openid profile email offline_access`
(no `api.connectors.*`).

### 2.8 Token refresh does NOT re-do the API key exchange

`request_chatgpt_token_refresh` in `manager.rs` sends a standard
`refresh_token` grant and persists the new `id_token`, `access_token`, and
`refresh_token`. No API key exchange on refresh.

---

## 3. Root Cause

**Path A fails because it targets users who don't need it, and doesn't work for
users who do need it.**

| User type | Has `organization_id`? | Token exchange works? | Could just paste API key? |
|---|---|---|---|
| ChatGPT Plus/Pro subscriber (no API account) | No | No | No (no API key to paste) |
| API platform user (has billing account) | Yes | Yes | Yes |

- ChatGPT-only users: Token exchange fails because they don't have a platform
  org. These are the users OAuth is most valuable for (they can't get an API key
  otherwise).
- Platform users: Token exchange works, but they already have API keys.
  OAuth doesn't solve a real problem for them.

**Codex CLI recognizes this by making token exchange optional.** It always falls
through to using `access_token` with `chatgpt.com/backend-api`.

---

## 4. What `organization_id` actually is

It's a platform API organization identifier (e.g., `org-xxxx`), created when a
user sets up API billing on `platform.openai.com`. It's fundamentally different
from:
- `chatgpt_account_id` — ChatGPT subscription account (always present)
- `organizations` array — list of workspaces/orgs the user belongs to (added by
  `id_token_add_organizations=true`)

There is no way to get `organization_id` into the id_token for ChatGPT-only
users because they literally don't have a platform organization.

---

## 5. Paths we tried (confirmed dead ends)

| Attempt | Result | Why it can't work |
|---|---|---|
| `id_token_add_organizations=true` | Organizations added as array, not top-level `organization_id` | Different claim, different purpose |
| `originator=codex_cli_rs` | No effect on id_token structure | Originator is for tracking, not claim shape |
| `audience=https://platform.api.openai.org` | Rejected: "not whitelisted by OAuth 2.0 Client" | Client ID is for ChatGPT, not platform |
| Adding `api.responses.write` scope | Rejected by auth page | Scope not allowed for this client |
| Different browser (Android Chrome vs desktop) | N/A — irrelevant | Token structure is server-side, per account |
| JWT manipulation before sending | N/A — won't work | Server validates JWT signature |
| Separate API call for organization_id | N/A — no such claim for ChatGPT users | Can't inject into signed JWT |

---

## 6. Conclusion: Abandon Path A, commit to Path B

**Path A (token exchange → platform API key) is not viable** for our target use
case (ChatGPT subscribers without API accounts).

This isn't a bug we can fix or a parameter we're missing. It's a fundamental
constraint: the token exchange produces API platform keys, and ChatGPT-only
users don't have API platform accounts.

**Path B (access_token → chatgpt.com/backend-api) is the correct approach.**
This is what both Codex CLI and OpenClaw actually use. Our access_token already
works — we validated it with HTTP 200 + SSE stream from
`chatgpt.com/backend-api/codex/responses`.

---

## 7. Recommended Implementation

### 7.1 Keep the token exchange as opportunistic

Match Codex CLI's behavior: try token exchange, ignore failure.

```kotlin
// In OAuthTokenExchange.exchange():
val idToken = tokenResult.tokens.idToken
var platformApiKey: String? = null
if (idToken != null) {
    platformApiKey = tryExchangeForApiKey(idToken)  // returns null on failure
}
```

If it succeeds (platform user), store the API key and use the existing
`OpenAIResponseClient` pipeline with `api.openai.com`. If it fails
(ChatGPT-only user), proceed with Path B.

### 7.2 Primary path: CodexResponseClient for chatgpt.com/backend-api

Build a `CodexResponseClient` that:
- Hits `chatgpt.com/backend-api/codex/responses`
- Sets `Authorization: Bearer {access_token}`
- Sets `chatgpt-account-id: {from JWT}`
- Sets `originator: android_agent`
- Sets `OpenAI-Beta: responses=experimental`
- Enforces `stream: true`, includes `instructions`
- Does NOT send `max_output_tokens`

### 7.3 LLMClientFactory routing

```kotlin
fun createClient(authMethod: AuthMethod): LLMClient {
    return when (authMethod) {
        is AuthMethod.OAuth -> {
            if (authMethod.platformApiKey != null) {
                // Platform user — use standard pipeline
                OpenAIResponseClient(apiKey = authMethod.platformApiKey)
            } else {
                // ChatGPT user — use Codex backend
                CodexResponseClient(
                    accessToken = authMethod.accessToken,
                    accountId = authMethod.accountId
                )
            }
        }
        is AuthMethod.ManualApiKey -> OpenAIResponseClient(apiKey = authMethod.key)
    }
}
```

### 7.4 Updated OpenAIOAuth.kt changes

Simplify `exchangeForApiKey` to be non-blocking:

```kotlin
/** Try token exchange for platform API key. Returns null on failure (expected for ChatGPT-only users). */
private fun tryExchangeForApiKey(idToken: String): String? {
    return try {
        // ... existing exchange code ...
        apiKey
    } catch (e: Exception) {
        Log.d(TAG, "API key exchange not available (ChatGPT-only account): ${e.message}")
        null
    }
}
```

Remove the current fallback warning log — failure is the expected path for most
users.

### 7.5 OAuthTokens structure update

```kotlin
data class OAuthTokens(
    val accessToken: String,        // Always present — used for chatgpt.com/backend-api
    val refreshToken: String,
    val expiresAt: Long,
    val email: String?,
    val accountId: String?,         // chatgpt_account_id from JWT
    val platformApiKey: String?,    // From token exchange, null for ChatGPT-only users
)
```

---

## 8. Next Steps

1. Build `CodexResponseClient` for `chatgpt.com/backend-api/codex/responses`
2. Update `LLMClientFactory` to route OAuth users to the correct client
3. Make token exchange in `OAuthTokenExchange` non-fatal (log at debug, not warn)
4. Wire the demo step to use `CodexResponseClient` for OAuth users
5. Handle SSE response format differences (if any) between standard API and
   chatgpt.com/backend-api
