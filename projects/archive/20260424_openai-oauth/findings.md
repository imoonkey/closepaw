# OpenAI OAuth — Implementation Findings

Date: 2026-04-03
Status: Full E2E validated — OAuth login → CodexResponseClient → demo task (Open Settings) succeeds

---

## 1. What Works

- **OAuth login flow**: PKCE + localhost:1455 callback server + token exchange → access_token + refresh_token
- **Client ID**: `app_EMoamEEZ73f0CkXaXp7hrann` (Codex CLI's, reused by OpenClaw and us)
- **Scopes**: `openid profile email offline_access api.connectors.read api.connectors.invoke`
- **Redirect URI**: Only `http://localhost:*` is whitelisted. Custom schemes (`androidagent://`) and external domains (`closepaw.ai`) are rejected
- **Codex validation**: access_token works against `chatgpt.com/backend-api/codex/responses` (HTTP 200, SSE stream)

## 2. Two API Paths — Codex CLI vs OpenClaw (pi-mono)

### Path A: Codex CLI (token exchange → platform API key)

**Flow**: OAuth → id_token → `urn:ietf:params:oauth:grant-type:token-exchange` → `openai-api-key` → use with `api.openai.com`

**What Codex CLI does**:
```
POST auth.openai.com/oauth/token
  grant_type = urn:ietf:params:oauth:grant-type:token-exchange
  client_id = app_EMoamEEZ73f0CkXaXp7hrann
  requested_token = openai-api-key
  subject_token = {id_token}
  subject_token_type = urn:ietf:params:oauth:token-type:id_token
```
Returns an `access_token` that works as a platform API key with full Responses API scope.

**Our blocker**: Token exchange fails with `Invalid ID token: missing organization_id`. The id_token we receive has `organizations` nested inside `https://api.openai.com/auth.organizations[0].id` but the exchange endpoint expects a top-level `organization_id` claim. Tried:
- `id_token_add_organizations=true` in authorize URL → organizations added but nested, not top-level
- `originator=codex_cli_rs` → no effect on id_token structure
- `audience=https://platform.api.openai.org` → rejected ("not whitelisted by OAuth 2.0 Client")
- Adding `api.responses.write` to scopes → rejected by auth page

**Open question**: How does Codex CLI get `organization_id` at the top level? Possibilities:
- Desktop browser flow behaves differently than Android Chrome
- Some undocumented parameter triggers it
- Account-level configuration (platform onboarding completion?) affects id_token structure
- The Codex RS source has additional obfuscated logic we can't see

**If this path is unblocked**: access_token works directly with existing `OpenAIResponseClient` and `ChatCompletionClient`. Zero pipeline changes needed. Most desirable path.

### Path B: OpenClaw / pi-mono (access_token → ChatGPT backend-api)

**Flow**: OAuth → access_token → use directly with `chatgpt.com/backend-api/codex/responses`

**How OpenClaw does it** (from `pi-mono/packages/ai/src/providers/openai-codex-responses.ts`):
```
POST https://chatgpt.com/backend-api/codex/responses
Headers:
  Authorization: Bearer {access_token}
  chatgpt-account-id: {extracted from JWT}
  originator: pi
  OpenAI-Beta: responses=experimental
  Accept: text/event-stream
  Content-Type: application/json
Body:
  { model, stream: true, store: false, instructions, input: [{role, content}], ... }
```

**Key differences from standard OpenAI API**:
- **Endpoint**: `chatgpt.com/backend-api/codex/responses` (not `api.openai.com/v1/responses`)
- **Extra header**: `chatgpt-account-id` (extracted from access_token JWT claims `https://api.openai.com/auth.chatgpt_account_id`)
- **Required**: `stream: true` (non-streaming rejected), `instructions` field (required, not optional)
- **Not supported**: `max_output_tokens` parameter
- **API format**: Same as Responses API but with some field restrictions
- **Billing**: Uses ChatGPT subscription credits, not platform API billing

**Validated on device**: HTTP 200 with SSE stream confirmed.

**To fully implement**: Need a new `CodexResponseClient` that handles the chatgpt.com endpoint, custom headers, and SSE format differences. Changes to `LLMClientFactory` to create this client type for OAuth users.

## 3. What access_token CANNOT do (tested)

| Endpoint | Error |
|---|---|
| `api.openai.com` Responses API | `Missing scopes: api.responses.write` |
| `api.openai.com` Chat Completions API | `Missing scopes: model.request` |

The OAuth access_token has NO direct platform API access. It only works with `chatgpt.com/backend-api`.

## 4. Request Format Gotchas (chatgpt.com/backend-api)

Discovered through trial and error:
- `input` must be an array of message objects, not a string
- Each message content must be `[{"type": "input_text", "text": "..."}]`, not a bare string
- `stream` must be `true` (400 error if false)
- `instructions` is required (400 if missing)
- `max_output_tokens` is not supported (400 if present)

## 5. Current Implementation State

### Done
- `auth/OpenAIOAuth.kt` — PKCE, localhost callback server, token exchange, JWT parsing, Codex validation
- `auth/OAuthCredentialStore.kt` — encrypted storage for OAuth credentials
- `onboarding/OnboardingState.kt` — OAuth states (OAuthReady, OAuthInProgress, OAuthSuccess, OAuthError)
- `onboarding/OnboardingStore.kt` — auth_method persistence
- `onboarding/OnboardingViewModel.kt` — full OAuth flow with Codex validation
- `ui/onboarding/OnboardingSteps.kt` — OAuth UI (sign-in button, progress, success, error, back navigation)
- `ui/onboarding/OnboardingScreen.kt` — wired OAuth callbacks
- `app/MainActivity.kt` — token refresh before session creation, launchMode=singleTask
- `llm/CodexResponseClient.kt` — raw OkHttp + SSE streaming against `chatgpt.com/backend-api/codex/responses`
- `llm/CodexRequestBuilder.kt` — ResponseInputItem/FunctionTool → JSON serialization
- `llm/CodexSseParser.kt` — SSE parsing with ToolCallAccumulator
- `llm/LLMClientFactory.kt` — OAuth routing via `__AUTH_METHOD_OPENAI` signal
- `app/AppSettingsState.kt` — `buildApiKeys()` includes auth method signal

### Not Done
- **Settings sheet** — No "Signed in with OpenAI" display or sign-out option
