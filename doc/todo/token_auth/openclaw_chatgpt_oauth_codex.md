# OpenClaw ChatGPT OAuth / Codex auth note

## TL;DR

- From the current OpenClaw repo, `openai-codex` is not a thin shell wrapper around the `codex` CLI for normal auth or inference.
- It also does not go through `OPENAI_API_KEY` on the main ChatGPT subscription path.
- The main path is: browser OAuth -> access/refresh token -> OpenClaw's own auth store -> refresh via library code -> OpenClaw provider calls.
- In OpenClaw itself, the OAuth flow is delegated to `@mariozechner/pi-ai`, not to a spawned `codex` process.
- There is code to read existing Codex CLI credentials from `~/.codex/auth.json` and macOS Keychain, but in the current tree that reader is not wired into the normal external CLI sync path, so it does not look like the primary mechanism.

## Short answer to the original question

If the question is:

- "Is OpenClaw just wrapping Codex CLI?" -> mostly no for the `openai-codex` auth/model path.
- "Did OpenClaw hack Codex auth?" -> not inside OpenClaw code directly; it uses a dedicated OAuth implementation from `@mariozechner/pi-ai`.

The most accurate wording is:

- OpenClaw directly integrates a Codex/ChatGPT OAuth flow through `pi-ai`.
- It stores and refreshes OAuth tokens itself inside OpenClaw state.
- It does not appear to shell out to `codex` for this path.
- Whether `pi-ai` internally reimplements the same protocol as Codex CLI cannot be proven from this repo alone.

## Evidence in the repo

### 1. Login entry point is built-in OpenAI Codex OAuth, not a CLI subprocess

`src/commands/models/auth.ts`

- `modelsAuthLoginCommand()` special-cases `openai-codex`.
- It calls `runBuiltInOpenAICodexLogin(...)`.
- That function calls `loginOpenAICodexOAuth(...)`.

`src/commands/openai-codex-oauth.ts`

- `loginOpenAICodexOAuth()` imports `loginOpenAICodex` from `@mariozechner/pi-ai`.
- It sets up browser OAuth handlers and passes them into `loginOpenAICodex(...)`.
- There is no shell-out to `codex`, `npx codex`, or similar in this flow.

This is the strongest signal that the main `openai-codex` path is not "wrap Codex CLI and scrape its login".

### 2. OpenClaw persists the OAuth credentials into its own auth store

`src/commands/onboard-auth.credentials.ts`

- `writeOAuthCredentials(provider, creds, ...)` writes an OAuth credential into OpenClaw's auth profile store.
- The profile id is built as `${provider}:${email}`.

`src/agents/auth-profiles/paths.ts`

- Auth profiles are stored in `auth-profiles.json` under the resolved OpenClaw agent dir.

`src/agents/agent-paths.ts`

- The default agent dir resolves under `~/.openclaw/agents/<agent>/agent`.

So the working storage is OpenClaw-owned state, not the Codex CLI home directory.

### 3. Token refresh is also handled in library code, not by asking Codex CLI

`src/agents/auth-profiles/oauth.ts`

- `resolveApiKeyForProfile()` handles OAuth credentials.
- When expired, it calls `refreshOAuthTokenWithLock(...)`.
- That eventually calls `getOAuthApiKey(...)` from `@mariozechner/pi-ai`.

Again, this is a direct token refresh path through a library, not a subprocess wrapper around the Codex CLI.

### 4. Runtime model path is OpenClaw provider code, not Codex CLI execution

`docs/providers/openai.md`

- OpenClaw documents two separate OpenAI paths:
  - `openai/*` for API key usage.
  - `openai-codex/*` for ChatGPT/Codex OAuth usage.
- The same doc says OpenClaw uses `pi-ai` for model streaming.

`src/agents/model-forward-compat.ts`

- `openai-codex` maps to model API `openai-codex-responses`.

This points to direct provider-level integration, not "spawn Codex CLI and proxy stdin/stdout".

### 5. `codex-cli` exists as a separate concept, but it is deprecated for onboarding auth

`src/commands/onboard.ts`

- If the old auth choice is `codex-cli`, OpenClaw logs:
  - `Auth choice "codex-cli" is deprecated; using OpenAI Codex OAuth instead.`

That is important because it separates two ideas:

- `codex-cli` as a CLI backend concept.
- `openai-codex` as the current OAuth model/auth path.

They are not treated as the same thing anymore.

## What about existing Codex CLI login state?

This is the subtle part.

### 1. There is code to read Codex CLI credentials

`src/agents/cli-credentials.ts`

- `readCodexCliCredentials()` checks:
  - macOS Keychain service `Codex Auth`
  - `~/.codex/auth.json` or `$CODEX_HOME/auth.json`
- It extracts:
  - `access_token`
  - `refresh_token`
  - optional `account_id`

So OpenClaw clearly knows the Codex CLI credential layout.

### 2. But that reader is not wired into the current external CLI sync path

`src/agents/auth-profiles/external-cli-sync.ts`

- Current automatic external CLI sync only covers:
  - Qwen CLI
  - MiniMax CLI
- There is no Codex branch here.

Repo-wide search in the current tree shows `readCodexCliCredentials*` only in:

- `src/agents/cli-credentials.ts`
- its tests

That means the Codex reader currently looks unused in the mainline auth loading path.

### 3. OpenClaw does merge a generic OAuth file

`src/agents/auth-profiles/store.ts`

- `mergeOAuthFileIntoStore()` loads `oauth.json` and merges provider creds into the auth store.

`src/config/paths.ts`

- `resolveOAuthPath()` points to `~/.openclaw/credentials/oauth.json` by default.

So there are two OpenClaw-owned persistence layers worth separating:

- `~/.openclaw/credentials/oauth.json`
- `~/.openclaw/agents/.../agent/auth-profiles.json`

Neither of these is the same as `~/.codex/auth.json`.

## Practical conclusion

Based on the current repo only:

1. OpenClaw is not primarily "packaging the Codex CLI" for `openai-codex` auth/inference.
2. OpenClaw is using a direct OAuth/token flow through `@mariozechner/pi-ai`.
3. OpenClaw stores and refreshes those tokens in its own state.
4. OpenClaw does understand the Codex CLI credential format, but that reuse path does not appear to be the main active path today.
5. So the closest answer is: "not a Codex CLI wrapper; closer to a direct ChatGPT/Codex OAuth integration implemented via `pi-ai`."

## Extra signals

`src/infra/provider-usage.fetch.codex.ts`

- Codex usage is fetched from `https://chatgpt.com/backend-api/wham/usage`.
- It sends `Authorization: Bearer <token>`.
- It can also send `ChatGPT-Account-Id`.

This further supports that the subscription path is using ChatGPT/Codex-style bearer tokens directly, not API keys.

`src/agents/auth-profiles/oauth.ts`

- There is a special fallback for `openai-codex` refresh failures where OpenClaw can temporarily keep using the cached access token.

That kind of logic is another sign that OpenClaw treats Codex OAuth tokens as first-class credentials in its own runtime.

## Bottom line

The strongest repo-backed answer is:

- OpenClaw did not simply "wrap Codex CLI" for this.
- OpenClaw also does not use the normal OpenAI API key path for `openai-codex`.
- OpenClaw integrates a direct ChatGPT/Codex OAuth token flow via `@mariozechner/pi-ai`, then persists and refreshes the tokens itself.
- There is some knowledge of Codex CLI credential storage, but in the current code it does not look like the main path.
