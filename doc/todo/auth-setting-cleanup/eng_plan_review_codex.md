# Eng Plan Review — Auth Setting Cleanup

这份设计的大方向是对的，也和已确认的决策一致：把 `mode × provider` flatten 到 client 层、把 credential 收敛到单一 `AuthStore`，确实是修掉 onboarding OAuth demo bug 和清理架构债的最小正确方向。问题不在方向，而在当前 plan 还没有覆盖真实代码里的接线范围、缓存语义和迁移边界，所以现在直接 `/implement` 风险偏高。

## Plan summary

- 目标是让“选中的 model/provider”成为唯一真相，决定加载哪种 credential、走哪个 client、在 onboarding 和主流程里都不再依赖 `authMethod`/magic key/fallback chain。
- 成功标准不该只看 onboarding demo 跑通，还要看主流程 session 创建、executor model、settings UI、debug intent 覆盖、旧 tester 状态迁移一起成立。

## What already exists

- 当前 OAuth 持久化和加密降级回退已经有可复用实现，不需要从零造一套。[OAuthCredentialStore.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/auth/OAuthCredentialStore.kt:18)
- 当前 settings auth 页已经有两条重要行为契约并且有 androidTest 覆盖：tab 切换本身不 commit；mode/provider 变化时会 canonicalize model。新方案应该保留这些行为，而不是重写交互语义。[LlmAuthSettingsPage.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt:76) [SettingsLlmAuthTest.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/androidTest/kotlin/ai/closepaw/qa/SettingsLlmAuthTest.kt:26)
- 当前主流程的 cloud preflight、startup failure banner、onboarding/demo/session boot 都已经存在，可重用或改写，不需要另起新机制。[MainActivityModelValidation.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/app/MainActivityModelValidation.kt:7) [ChatViewModel.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/chat/ChatViewModel.kt:290) [OnboardingDemoController.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/onboarding/OnboardingDemoController.kt:31)
- 真实 session 启动链路现在是 `MainActivity -> AgentSession -> SessionServices -> SessionLlmBootstrapper -> LLMClientFactory`，auth 改造必须一次打穿这条链，而不是只改 onboarding 页面和 factory。[MainActivity.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/app/MainActivity.kt:498) [AgentSession.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:55) [SessionServices.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/session/SessionServices.kt:86) [SessionLlmBootstrapper.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/session/SessionLlmBootstrapper.kt:31)

## Findings

1. **High — task scope低估了真实改动面，当前 tasks 覆盖不了运行时主链路。**
   
   设计里把删除 `buildApiKeys()`、删除 `authMethod`、让 factory 直接读 `AuthStore` 作为核心改动，但当前运行时代码还在多个入口直接依赖这些旧状态：[MainActivity.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/app/MainActivity.kt:503), [MainActivityModelValidation.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/app/MainActivityModelValidation.kt:13), [SessionLlmBootstrapper.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/session/SessionLlmBootstrapper.kt:34), [SessionServices.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/session/SessionServices.kt:89), [AgentSession.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:59), [SettingsSheet.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/settings/SettingsSheet.kt:30), [SettingsHomePage.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/settings/SettingsHomePage.kt:77), [MainActivityIntentApplier.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/app/MainActivityIntentApplier.kt:29)。
   
   当前 plan 的 “Changed” 和 tasks 没把这些文件列进来，[design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:169) [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:185)。如果按现有拆分执行，结果要么编不过，要么 session 主流程仍旧保留旧的 env-var / map 路径，onboarding 修了但主流程还是脆的。
   
   建议新增一个明确的 `runtime-wiring` task，覆盖 `MainActivity`、`MainActivityModelValidation`、`AgentSession`、`SessionServices`、`SessionLlmBootstrapper`、`SettingsSheet`、`SettingsHomePage`，并把它放在 UI cleanup 前。

2. **High — 这份 plan 只清了 OpenAI，没有把所有 cloud provider 收拢进单一 `AuthStore`。**
   
   用户已确认的方向是“单一 `AuthStore`，keyed by flat provider id，每个 provider 一个 `AuthCredential`”。但当前设计只明确替换了 `openAiOAuthAccessToken/openAiManualApiKey/apiKey`，没有把 `openRouterApiKey` 和 `novitaApiKey` 一起迁过去。[design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:57) [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:173)
   
   现状里 `OPENROUTER`/`NOVITA` 仍然是 `AppSettingsState` + `AppSettingsStore` 的一等字段，[AppSettingsState.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/app/AppSettingsState.kt:23) [AppSettingsStore.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/app/AppSettingsStore.kt:172)，settings UI 也直接编辑这些字段。[LlmAuthSettingsPage.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt:229)
   
   如果这一轮只把 OpenAI 挪走，最后会留下“两套 credential storage”：OpenAI 在 `AuthStore`，OpenRouter/Novita 在 settings store。那和这次要清理的架构债是同一种问题，只是换了个名字。
   
   建议把 `OPENAI_API`、`OPENAI_CODEX`、`OPENROUTER`、`NOVITA` 一次性都迁进 `AuthStore`，包含 migration、UI getter/setter、preflight validation 和 debug 覆盖路径。

3. **High — factory 内做 OAuth refresh 但不处理 client cache，会导致刷新后的 token 根本用不上。**
   
   当前 `LLMClientFactory` 会缓存 client 实例。[LLMClientFactory.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/llm/LLMClientFactory.kt:34) 当前 `CodexResponseClient` 又把 `accessToken` 固化在构造参数里，后续每次请求都直接复用这个值。[CodexResponseClient.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/llm/CodexResponseClient.kt:35) [CodexResponseClient.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/llm/CodexResponseClient.kt:233)
   
   设计文档把 refresh 挪进了 factory，但没有定义 cache 策略。[design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:62) [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:123) 按现有语义实现的话，store 里的 token 刷新成功后，已经缓存下来的 `CodexResponseClient` 仍会继续发旧 token。
   
   这是 implementation 前必须定掉的设计点。可选方案只有三类：
   
   - `OPENAI_CODEX` 不缓存 client。
   - cache key 带 credential version/fingerprint，credential 变化就换 client。
   - `CodexResponseClient` 改成每次请求从 provider/credential supplier 读取最新 token。
   
   另外，“single-process 所以不需要 refresh locking”这个结论也不够严谨；单进程不等于没有协程并发。[design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:62)

4. **High — migration 方案现在拿错了 source of truth，而且顺序放晚了。**
   
   migration 伪码用了 `old.openAiOAuthAccessToken` 作为 OAuth 是否存在的判断条件。[design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:145) 但这在现有代码里是 transient in-memory state，不持久化。[AppSettingsState.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/app/AppSettingsState.kt:58) 真正持久化的 OAuth 来源是 `OAuthCredentialStore.load()`，以及 onboarding 存下来的 `auth_method`。[MainActivity.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/app/MainActivity.kt:131) [OnboardingStore.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/onboarding/OnboardingStore.kt:105)
   
   另外，当前 task 顺序把 migration 放在 `settings-state-shrink` / UI rewrite 之后。[design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:193) [design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:199) 这会让你在删掉旧字段/旧 store 之后，才去写依赖旧状态的迁移逻辑，顺序上不稳。
   
   这部分还缺两个关键迁移面：
   
   - `executorModel` 也要 remap，不只是 `selectedModel`。[AppSettingsStore.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/app/AppSettingsStore.kt:171)
   - 旧 session checkpoint/history 里也持有 model key；如果升级前的 `gpt-5.4` 代表 OAuth OpenAI，升级后 catalog split 后它会默认落到 `OPENAI_API`，除非你显式 remap 或明确宣布旧 checkpoint 不支持 reload。[AgentSession.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/session/AgentSession.kt:108)
   
   建议把 migration scaffolding 前置到 `AuthStore` 落地后立刻做，并保证：
   
   - 从 `OAuthCredentialStore` 读旧 OAuth。
   - 从 `OnboardingStore` 读旧 auth-method only as migration input。
   - remap `selectedModel` 和 `executorModel`。
   - 迁移必须 idempotent；只有全部 copy/remap 成功后才写 migration sentinel、删旧 prefs。
   - 明确 old checkpoint reload 是支持还是直接失效。

5. **Medium — settings / onboarding 的状态机还没有根据 flatten 后的 provider 模型重写，现有 UI 逻辑会出现 out-of-domain 状态。**
   
   现在 `LlmAuthSettingsPage` 的 tab 默认值来自 `authMethod` 和 `llmBackend`，[LlmAuthSettingsPage.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt:76) API Key tab 的 `selectedProvider` 又直接从当前 `selectedModel.provider` 派生。[LlmAuthSettingsPage.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt:237)
   
   provider flatten 后，如果当前 model 是 `OPENAI_CODEX`，用户切到 API Key tab，而 tab-local provider 仍然直接吃 `selectedModel.provider`，就会落入一个 API Key tab 里选中了 OAuth provider 的非法状态。当前设计只写了“tab switch is view-only”，但没有定义 tab entry canonicalization 规则。[design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:118)
   
   onboarding 也类似。`OnboardingViewModel` 当前在 step resume 时靠 `OnboardingStore.loadAuthMethod()` 重建 UI 成功态。[OnboardingViewModel.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/onboarding/OnboardingViewModel.kt:323) 如果 `authMethod` 从系统真相中移除，这里必须改成基于 `selectedModel.provider.mode + AuthStore.has(provider)` 推导，而不是继续保留一个过期的 side-channel。

6. **Medium — 主流程 error UX 与 debug/eval 覆盖路径还没落到真实代码约束上。**
   
   设计里要求主流程出现 credential 错误时 “toast + banner + deep-link 到正确 tab”。[design_claude.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/todo/auth-setting-cleanup/design_claude.md:135) 但当前 `SettingsSheet` 的 page state 是内部 `rememberSaveable`，没有外部 seed；`LlmAuthSettingsPage` 的 `selectedTab` 也没有 deep-link 入参。[SettingsSheet.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/settings/SettingsSheet.kt:70) [LlmAuthSettingsPage.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt:76)
   
   同时，debug build 现在还能通过 intent 注入 `api_key/openrouter_api_key/novita_api_key/openai_base_url`，[MainActivityIntentPayload.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/app/MainActivityIntentPayload.kt:28) 并通过 `MainActivityIntentApplier` 写入 settings state。[MainActivityIntentApplier.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/ai/closepaw/app/MainActivityIntentApplier.kt:29) 设计删了 `buildApiKeys()`，但没有定义这些 ephemeral override 之后放哪。
   
   建议在 plan 里明确：
   
   - `SettingsSheet` 接受 `initialPage` / `initialAuthTab` 之类的路由入参。
   - 主流程尽量复用现有 `ChatViewModel.reportStartupFailure()` banner，而不是再发明一套 banner。
   - debug/eval intent 覆盖要么落到 `AuthStore` + 独立 base-url override，要么保留一个仅 debug 使用的 session override layer，但不能留空白。

## Test plan

- `AuthStore` JVM tests：`OPENAI_API` / `OPENAI_CODEX` / `OPENROUTER` / `NOVITA` 的 `get/set/clear/has`，错误 credential type 清理，encryption-degraded memory fallback。
- migration tests：OAuth 从 `OAuthCredentialStore` 搬运成功；OpenAI manual key、OpenRouter、Novita 搬运成功；`selectedModel`/`executorModel` remap；fresh install no-op；partial failure 不写 sentinel；重复运行 idempotent。
- factory tests：`OPENAI_API` response/chat client routing；`OPENAI_CODEX` routing；missing credential / refresh failure typed errors；refresh 后 cache 行为正确；credential 清除后不会继续复用旧 client。
- session/bootstrap tests：`MainActivityModelValidation` 或其替代物对 main/executor model 都按 provider 校验；`SessionLlmBootstrapper` 不再按 env-var 误判 `OPENAI_CODEX`；debug intent/base-url override 仍能生效。
- onboarding tests：OAuth success 写 `AuthStore.OPENAI_CODEX`；process death 后 resume 能从 `AuthStore` 恢复正确 step state；demo 缺 credential / refresh 失败时显示 inline error card。
- compose/android tests：settings tab switch 仍 inert；tab 默认值来自 `selectedModel.provider.mode`；从 `OPENAI_CODEX` 进入 API Key tab 时会 canonicalize 到合法 provider；settings deep-link 可以直接打开到 LLM/Auth 页的正确 tab。
- 设备 QA：fresh install OAuth → Run Demo 成功；fresh install API key → Run Demo 成功；settings sign-in / sign-out / tab 切换保留凭据；OAuth near-expiry refresh；如果支持旧 checkpoint reload，要覆盖 upgrade 后 reload。

## Not in scope

- 不要在这一轮把 local backend 整体重构进 cloud `selectedModel` 流程；保持现有 `LLMBackendType.LOCAL` 和本地模型下载路径，只做必要的 enum / UI 对齐。
- 不要引入 fallback chain、catalog inheritance、provider alias 等兼容层。
- 不要重做 onboarding funnel 或 settings 信息架构；保留现有 mode → provider → model 的 UI 层次和“tab switch 不 commit”的交互契约。

## Next step

- 先改 design，再进 `/implement`。当前最需要补的是：
  1. 增加 `runtime-wiring` task，覆盖 `MainActivity`、`MainActivityModelValidation`、`AgentSession`、`SessionServices`、`SessionLlmBootstrapper`、`SettingsSheet/HomePage`、`MainActivityIntentApplier`。
  2. 明确 **所有 cloud providers** 都迁入 `AuthStore`，不是只迁 OpenAI。
  3. 明确 `OPENAI_CODEX` 的 client cache / refresh 策略。
  4. 重写 migration 伪码，基于真实持久化来源，并前置到 legacy cleanup 前。
  5. 明确 old checkpoint reload 是否支持；如果不支持，在 plan 里写清楚并在升级时 fail closed。

做完这些补充后，这份设计就可以交给 `/implement`，不需要再回 `/scope-review`。
