# Eng Plan Review v2 — Auth Setting Cleanup

## Findings

1. **High — old checkpoint fail-closed does not actually fail closed.**

   `§8` says old checkpoints are rejected only when the saved model key no longer resolves. But `§4` keeps legacy OpenAI keys like `gpt-5.4` and `gpt-5.2` alive on the new API-key provider, so a pre-split OAuth checkpoint still resolves instead of being rejected. Current reload also copies checkpointed `mainModel` and `executorModel` straight back into `SessionConfig` before catalog resolution, with no auth-era or catalog-version gate. That means the high-risk case is silent semantic drift, not honest fail-closed. See [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:105), [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:224), [SessionCheckpointCoordinator.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/session/SessionCheckpointCoordinator.kt:86), [AgentSession.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:119), [ModelCatalog.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/llm/ModelCatalog.kt:100).

   This needs an explicit pre/post-split snapshot gate, or a naming scheme where every pre-split cloud key becomes unresolvable after the split. As written, `unknown model key => reject` is too weak.

2. **High — migration remap can still rewrite non-OpenAI users onto OpenAI.**

   The pseudocode gives OAuth global precedence whenever a valid OAuth record exists, then remaps `selectedModel` and `executorModel` toward `OPENAI_CODEX`. That is safe only if the user was already on legacy OpenAI. It is not safe for installs that currently use `OPENROUTER` or `NOVITA` but still have old OpenAI OAuth tokens on disk. In that case, the pseudocode can drag the active provider back to OpenAI unless `catalog.remapToProvider()` is explicitly defined to preserve non-OpenAI keys. See [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:185).

   The design needs a tighter rule: migration only disambiguates legacy OpenAI selections between `OPENAI_API` and `OPENAI_CODEX`; non-OpenAI and local selections stay as-is. Add tests for `OPENROUTER`/`NOVITA` selected model plus stale OpenAI OAuth.

3. **High — `partial-failure safe` is not true yet unless migration writes are made durable/atomic.**

   The doc currently argues that putting the sentinel at the end makes migration retry-safe. That is not enough if the writes before the sentinel still use async `apply()` semantics like the current settings and onboarding stores do. A process death after `migrated_v1=true` is committed but before remapped model keys or new credentials are durably flushed leaves a half-migrated device that will never retry. See [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:203), [AppSettingsStore.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/app/AppSettingsStore.kt:204), [AppSettingsStore.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/app/AppSettingsStore.kt:254), [OnboardingStore.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/onboarding/OnboardingStore.kt:100).

   To claim idempotent + partial-failure safe, the design needs explicit blocking commit helpers or a single verified transaction boundary. I would require crash-point tests around every pre-sentinel mutation, not just a happy-path rerun test.

4. **Medium — `§5` settings canonicalization drops `executorModel` rules.**

   Current settings code already has the right safety contract: provider/auth canonicalization touches both main and executor models, and clears the executor when it falls out of the valid domain. The v2 state machine only says tab interaction mutates `selectedModel`. In `PRO` mode that leaves `executorModel` stranded on the old provider/mode after a tab or provider switch, which can surface as missing-credential startup failures. See [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:127), [LlmAuthSettingsPage.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt:343).

   The state machine should explicitly say: on provider/mode commit, if `executorModel` is outside the new provider domain, reset or remap it.

5. **Medium — `§3` token supplier fixes stale access tokens, but not all cached OAuth identity state.**

   Today `CodexResponseClient` caches both the access token and the derived `chatgpt-account-id`. If v2 only replaces the token with a supplier, a cached client still carries stale account identity after sign-out/sign-in or account switch. Refresh within the same account is fine; identity change is not. See [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:85), [CodexResponseClient.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/llm/CodexResponseClient.kt:39), [CodexResponseClient.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/llm/CodexResponseClient.kt:233).

   Either the supplier must provide the full OAuth header material, or OAuth writes and clears need their own generation invalidation for Codex clients too.

6. **Medium — H1/M6 are only partially closed in file scope and task ordering.**

   The design says the startup failure banner opens `SettingsSheet(initialPage, initialAuthTab)`, but the current banner is dismiss-only inside `SmartCapsuleSurface`, and the settings route owner lives in `ChatScreen` and `MainActivityContent`. Those files are not in the changed-file list, so the current scope still undercounts the UI wiring needed for the promised deep-link. Also, the dependency graph should make `8 settings-state-shrink` depend on `6 settings-ui-align` and `7 onboarding-rewrite`, not run in parallel, because those are still live consumers of the legacy auth fields today. See [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:135), [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:240), [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:268), [SmartCapsuleSurface.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/capsule/surface/SmartCapsuleSurface.kt:154), [ChatScreen.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/chat/ChatScreen.kt:146), [MainActivityContent.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/app/MainActivityContent.kt:57), [OnboardingViewModel.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/onboarding/OnboardingViewModel.kt:459), [LlmAuthSettingsPage.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt:47).

## First-Round Closure Check

- `H1 runtime wiring`: mostly solved. `§5` and task 5 finally pull the real session boot path into scope. Remaining gap is the banner deep-link wiring above; `ChatViewModel` alone cannot open the sheet.
- `H2 all cloud providers in AuthStore`: mostly solved. `§2` and `§8` now migrate `OPENROUTER` and `NOVITA`, which v1 missed. Residual scope gap: task 7 and the changed-file list still do not name `OnboardingState.kt` or `OnboardingScreen.kt`, even though task 9 now promises a 3-provider onboarding QA path and the current onboarding enum only exposes OpenAI/OpenRouter. See [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:123), [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:280), [OnboardingState.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/onboarding/OnboardingState.kt:10).
- `H3 refresh/cache`: partially solved. Token supplier plus mutex is the right direction and fixes the original stale-token problem. Remaining gap is cached OAuth identity state and invalidation on re-sign-in.
- `H4 migration source/order`: partially solved. Source-of-truth and ordering are much better in `§8`, and `executorModel` remap was added. Remaining gaps are provider-preserving remap, durable commit semantics, and the ineffective checkpoint fail-closed rule.
- `M5 settings canonicalization`: partially solved. The illegal “OAuth provider inside API-key tab” state is closed. Remaining gap is `executorModel` canonicalization.
- `M6 main-flow error UX + debug overrides`: partially solved. Debug intent routing is much better in `§7`. Remaining gap is the actual tappable banner-to-settings route.

## Plan Summary

v2 keeps the agreed architecture and is much closer to implementation than v1. I would still send it back for one more design pass before `/implement`, because the remaining issues are not stylistic; they sit on upgrade safety, checkpoint behavior, and session state consistency.

Success here means three things at once:

- tester credentials migrate once without forced re-auth,
- pre-upgrade checkpoints are rejected deliberately instead of silently reinterpreted,
- settings and onboarding transitions cannot leave a mixed-provider main/executor state.

## What Already Exists

- Reloaded sessions reuse checkpointed model keys verbatim; there is no auth-era or catalog-version guard today. See [SessionCheckpointCoordinator.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/session/SessionCheckpointCoordinator.kt:86), [AgentSession.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:119).
- The current settings page already has the right executor safety contract: provider/auth canonicalization touches both main and executor models. See [LlmAuthSettingsPage.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt:343).
- The current startup banner is dismiss-only; it has no action channel back to settings routing. See [SmartCapsuleSurface.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/capsule/surface/SmartCapsuleSurface.kt:154), [ChatViewModel.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/chat/ChatViewModel.kt:290).
- The current onboarding surface only exposes two cloud providers, so “3 providers in onboarding QA” is not free. See [OnboardingState.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/onboarding/OnboardingState.kt:10).

## Test Plan

- Migration JVM tests for mixed-credential installs: `OPENROUTER` selected plus stale OpenAI OAuth, `NOVITA` selected plus stale OpenAI OAuth, OpenAI manual plus OAuth both present, and executor model set separately from main model.
- Crash-point migration tests: fail after first `AuthStore.set`, after model remap, after sentinel write, and verify retry plus consistency semantics.
- Checkpoint tests with real pre-split snapshots whose `mainModel` is still `gpt-5.4` or `gpt-5.2`; these must reject explicitly, not reload as `OPENAI_API`.
- Settings tests in `PRO` mode: switching `OAuth -> API Key` and `API Key -> OAuth` canonicalizes or clears `executorModel` when invalid.
- Codex client tests: refresh within the same account keeps working; sign-out/sign-in with a different account rebuilds or invalidates the cached client.
- UI tests: startup failure banner tap opens Settings on `LLM_AUTH` with the correct auth tab.

## Not In Scope

- No rollback to `authMethod` side-channels, fallback chains, or split credential stores.
- No redesign of onboarding funnel or settings information architecture.
- No backward-compat remap heuristics for old checkpoints beyond an explicit fail-closed policy.

## Next Step

- Revise `§8` so migration only disambiguates legacy OpenAI selections, leaves non-OpenAI and local selections untouched, and defines a durable commit boundary.
- Replace the current “unknown key only” checkpoint policy with an explicit pre/post-split snapshot gate.
- Add `executorModel` canonicalization to `§5`, and add OAuth identity invalidation details to `§3`.
- Expand task and file scope for the banner deep-link path, and change the dependency graph to `5 -> {6,7} -> 8 -> 9`, not `5 -> {6,7,8} -> 9`.
