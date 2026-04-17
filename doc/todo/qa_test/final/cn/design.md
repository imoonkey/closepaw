# AndroidAgent QA Test 框架

Status: aligned draft v2

本文档是 `androidagent` 项目 QA Test 的当前 self-contained 对齐设计。

维护规则：

- 当某个 open question 得到解决时，先更新本文档。
- 在正文反映出已解决的决定后，再删除或修订最终 `Open Questions` 章节中对应的条目。

## 目标（Goal）

为 `androidagent` 增加一个 deterministic 的 QA 层，用于验证：

- Compose shell 中由 app 自身拥有的 UI flow
- permission 与 service 的 setup flow
- 团队已经在使用的 debug / bootstrap 路径
- 基于 emulator 的 CI smoke 覆盖

并且不将 UI regression testing 与 agent-quality eval、live LLM 行为或探索性的 UX 调试混在一起。

## 范围（Scope）

该 QA 层用于对 Android app 及其 device-boundary 行为做可重复的 regression check。

它与以下内容是分离的，不会替代它们：

- `app/src/test` 下的 JVM unit test
- `eval/` 中 AndroidWorld 风格的 agent benchmark
- `/ux-visual-debug` 的探索性 / 手工 UX 验证
- 通过 `scripts/debug-run.sh` 和 `scripts/action-test.sh` 做的临时调试

在首次 rollout 中明确 out of scope 的部分：

- snapshot / golden-image testing（Paparazzi、Roborazzi、shot）
- 发起 live LLM call 的测试（云端或本地 Leap）
- 跨 app 的 agent-quality 场景——继续留在 `eval/`
- Maestro 或任何第二套 E2E stack 出现在 PR gate 中（后续是否新增一个 release-only lane 见 Open Questions）

## 对齐后的决定（Aligned Decisions）

当前共识：

- 首次 QA 实现应当在项目内使用**单一 deterministic 的 Android test runtime**。
- 该 runtime 位于 `app/src/androidTest`。
- 初始 stack 为：
  - **Compose UI Test** 用于 app 拥有的 UI
  - **UI Automator** 用于系统 Settings、permission、以及 out-of-process UI
  - **AndroidX instrumentation runner + Android Test Orchestrator** 负责执行与隔离
- `Kaspresso` 和 `Appium` 不在本项目范围内。
- `Espresso` 不是独立的编写模型；只在必要时作为 AndroidX 的底层依赖出现。
- QA 层必须对 LLM 行为进行 stub 或脚本化；live model call 不属于 deterministic QA。
- 稳定的 `testTag` selector 是 app-owned UI 的契约。
- PR CI 只 gate 在 deterministic 的 emulator smoke 上。
- 对硬件或环境敏感的 flow 必须与 required PR check 分离。

### 为什么不用 Kaspresso / Appium / Espresso 作为独立编写模型

- **Kaspresso**：它是 Espresso + UI Automator 上的 Kotlin DSL 封装，主要卖点是 flaky handling 与可读性。本项目不采用：
  - 我们已决定以 Compose UI Test 为主，而 Kaspresso 的强项在 Espresso / View 层；对 Compose 的加持有限。
  - 本项目的真正难点不是 selector 写法，而是 **deterministic state seeding + LLM 隔离**——Kaspresso 并不解决这部分。
  - 我们通过 `animationsDisabled = true` + Orchestrator + `clearPackageData = true` 已覆盖 Kaspresso 的主要 flaky handling 收益，不需要再引一层第三方依赖和学习成本。
- **Appium**：基于 W3C WebDriver 的跨平台 QA 体系，优势是 Android + iOS 统一、多语言 client。本项目不采用：
  - 我们是 Android-only，跨平台不是需求。
  - Appium server + driver 的运维成本明显高于直接 `connectedDebugAndroidTest`。
  - 与 Kotlin / Compose 的认知距离最远，断言表达力弱于 Compose UI Test。
- **Espresso**（作为独立 authoring model）：本 app 的 UI 几乎全是 Jetpack Compose，`composeTestRule.onNodeWithTag(...)` 是更直接的路径；Espresso 只在与 Compose 互操作时作为 AndroidX 底层依赖出现，不需要作为独立的 authoring model 被推上。

一句话：选型优化的是本项目最稀缺的能力——**deterministic setup、LLM stub、Compose 原生断言**；Kaspresso / Appium / 独立 Espresso 优化的是别的维度。

## 初始 QA 架构

### 测试分层

```text
app/src/test
  -> 针对 logic、policy、session state、formatting、storage 的快速 JVM unit test

app/src/androidTest
  -> 针对 app UI、permission、service wiring、debug seam 的 deterministic device/emulator QA

eval/
  -> 跨第三方 app 和任务的 agent-quality benchmark 层
```

首次对齐后的 rollout 即补上中间这层：deterministic 的 `androidTest` QA。

### 按 scenario 组织，而不是按 tool 组织

测试套件按行为划分：

- **Compose scenarios**
  - onboarding shell
  - settings sheet / pages
  - chat shell
  - permission repair card
- **System scenarios**
  - overlay permission
  - accessibility enablement
  - 从 Settings 返回 app
  - service-visible 行为
- **Debug seam scenarios**
  - 通过 debug intent extras 启动 fresh session
  - 通过 `ACTION_DEBUG_EXEC` 直接执行 action

工具的选择跟随 scenario：

- app 自身拥有的 UI 使用 Compose Test
- 跨越 app 边界或涉及 system UI 时使用 UI Automator

## Deterministic Runtime 模型

初始 QA runtime 将 setup 和 permission 变成 canonical 的前置状态。

### 测试状态机

```text
CLEAN_APP
  -> 清除 package data 和 runtime 残留

SEEDED_STATE
  -> 直接 seed onboarding / settings / session 前置条件

DEVICE_READY
  -> 应用 shell / device 前置条件
     (overlay appops、accessibility 启用、service 可见)

APP_LAUNCHED
  -> 使用显式 debug extras 启动 MainActivity

FLOW_RUNNING
  -> 执行 Compose assertion 与可选的 UI Automator 步骤

ARTIFACTS_COLLECTED
  -> 在失败或完成时保存 screenshot / logcat / 相关 app 文件
```

大部分测试应从 `CLEAN_APP -> SEEDED_STATE -> DEVICE_READY -> APP_LAUNCHED` 开始。

只有那些目的在于验证实际 Settings 旅程的测试才需要手工点击 setup。

## 仓库集成（Repo Integration）

### Gradle 与 instrumentation

在 `app/build.gradle.kts` 中：

- 设置 `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`
- 对现有的 `debug` variant 运行 QA
- 添加 `androidTestImplementation`：
  - `androidx.test.ext:junit`
  - `androidx.test:runner`
  - `androidx.test:rules`
  - `androidx.test.uiautomator:uiautomator`
  - `androidx.compose.ui:ui-test-junit4`（复用现有 Compose BOM）
- 添加 `androidTestUtil("androidx.test:orchestrator")`
- 启用 Android Test Orchestrator 并设置 `clearPackageData=true`
- 设置 `testOptions.animationsDisabled = true`（Compose 与 UI Automator 在默认动画时长下都容易 flake）

Orchestrator 属于对齐后的 baseline。本 app 会持久化 onboarding state、settings、allow-list、session history 与 memory，per-test state 隔离不是可选项。

`testTag` 编译为轻量级的 `SemanticsModifier` 并在 release build 中保留，不需要额外的 R8 keep rule 或 debug-only strip。

### 目录布局

```text
app/src/androidTest/kotlin/com/moonkey/androidagent/qa/
  annotations/
    Smoke.kt
    Nightly.kt
    ManualDevice.kt
  base/
    QaHarness.kt
    QaSmokeRule.kt
    QaArtifactsRule.kt
    QaStateSeeder.kt
    QaShell.kt
    QaLaunchIntents.kt
  fixtures/
    LlmScripts.kt
  robots/
    ChatRobot.kt
    OnboardingRobot.kt
    SettingsRobot.kt
    SystemSettingsRobot.kt
  smoke/
    AppLaunchSmokeTest.kt
    FreshSessionIntentSmokeTest.kt
    SettingsSheetSmokeTest.kt
  permissions/
    AccessibilityEnableFlowTest.kt
    OverlayPermissionFlowTest.kt
    PermissionRepairCardTest.kt
  service/
    AgentServiceLifecycleTest.kt
    SessionResumeAfterRelaunchTest.kt
  action/
    DebugActionReceiverSmokeTest.kt

app/src/debug/kotlin/com/moonkey/androidagent/qa/
  QaRuntimeOverrides.kt
  ScriptedLlmClient.kt

app/src/main/kotlin/com/moonkey/androidagent/ui/testtags/
  QaTags.kt

.github/workflows/
  qa.yml
```

### 复用的 debug / runtime seam

对齐设计复用仓库中已存在的代码：

- `MainActivity` 的 debug extras，用于 fresh-session bootstrap 和 goal dispatch
- `OnboardingStore` 和 `AppSettingsStore` 用于 seeded state
- `ACTION_DEBUG_EXEC` 用于 deterministic 的 action-path smoke
- 现有 `debug-output/` 约定用于 artifact

QA harness 应在引入任何第二套外部框架之前先充分利用这些 seam。

## LLM 隔离

Deterministic QA 不能依赖 live OpenAI call 或本地模型下载行为。

对齐后的 baseline 采用 **debug-only 的脚本化 runtime override**：

- 测试代码选择一个脚本化的 response plan
- debug-only 的 runtime glue 注入一个脚本化的 `LLMClient`
- 实现应复用已有的 `LLMClientFactory.forTest(...)` seam 或等价的 runtime override 路径

重要约束：

- **不要**仅为 QA 就新增一个 production 的 `LLMBackendType` 或其他 production runtime mode

这样可以把 test 的关注点挡在 checkpoint schema 和 production config 之外。

### Fixture 契约

脚本化的 LLM 行为以 **Kotlin 代码**表达，而不是 JSON 或其他外部数据：

- `app/src/androidTest/kotlin/com/moonkey/androidagent/qa/fixtures/LlmScripts.kt` 暴露命名的 factory（如 `LlmScripts.completeImmediately()`、`LlmScripts.oneToolCallThenFinish(...)`）。
- 每个 factory 返回一个 `LLMClient` 实现（或一个被 `ScriptedLlmClient` 使用的 driver），描述 *interaction pattern 的意图*——多少轮、哪些 tool call、哪个 finish reason——而不是逐字节的 prompt/response payload。
- 测试通过 `qa.runtime.useScriptedLlm(LlmScripts.xxx())` 按测试粒度 opt-in；不使用全局状态，也不使用文件系统 fixture。

为什么选 Kotlin 而不是 JSON：

- 对 `LLMClient` / tool-call interface 有 type safety——schema drift 会变成编译错误，而不是悄悄通过 CI
- 内部 interface 迁移时 IDE refactor 能自然跟随
- 不需要 capture-and-refresh 工作流；脚本描述意图，因此只在 interaction 语义变化时才需要改动

脚本不得编码真实的 OpenAI response envelope。如果某个测试需要对 wire-format 处理进行断言，它属于 `app/src/test` 的 unit test，而不是 QA。

## Selector

当前 UI 在若干位置已有 content description，但稳定的 selector 对 QA 来说还不够。

在 production UI 代码中加入一个小而明确的 selector 表面：

- `onboarding` shell 动作
- permission repair card
- chat composer 和 send action
- session drawer 控件
- settings 打开 / 关闭与主要 toggle
- external-goal 确认对话框

规则：

- 对于 QA 需要反复定位的 app-owned 控件，使用 `testTag`
- 仅当某个标签真正是面向用户的契约时，才使用可见 text 或 content description

## Artifact 采集

加入一个 `QaArtifactsRule`，在失败时：

- 通过 `UiDevice` 抓取整机 screenshot
- dump logcat
- 记录当前 package / activity 信息
- 在存在时保存相关 app 文件：
  - trace artifact
  - session history
  - debug action output

Artifact 输出到：

```text
debug-output/qa/<timestamp>/<test-name>/
```

与仓库现有的 `debug-output/` 约定一致。

## CI 接线

### PR required 任务

新增 `.github/workflows/qa.yml`，包含两个 required job：

1. `unit`
   - 运行 `./gradlew testDebugUnitTest`

2. `android-smoke`
   - 通过 `ReactiveCircus/android-emulator-runner` 启动一个 emulator（API 33、`google_apis` image、x86_64）
   - 运行 `./gradlew connectedDebugAndroidTest`，过滤条件为 `@Smoke`
   - 上传 instrumentation report 和存在时的 `debug-output/qa/` artifact

初始 PR gate 保持在 debug variant 与 deterministic smoke 上。emulator image 被 pin 住以便 flake 调查对齐到稳定基线；升级到更新的 API level 是显式且被 review 的变更。

### Lane 晋升阈值

当某个非 required 的 lane 在最近 50 次 CI 运行中同时满足以下条件时，可晋升为 required 的 PR gate：

- 至少 49/50 次绿色（≥ 98% pass rate）
- 没有任何单一测试的 flake rate 超过 2%（retry-to-green 算作 flake）
- 没有任何一次运行需要人工介入（重跑、device 重启、emulator 重置）

这是唯一的 canonical bar。它适用于每一个 optional lane（full instrumentation、permission/system、manual-device，以及将来任何 release-smoke lane）。不满足该 bar 时 lane 保持 optional。

### Optional lane

在 baseline 变绿后再加入非 required 的 lane：

- 排除 `@ManualDevice` 的完整 instrumentation 套件
- 更长的 permission / system flow
- 用于 Shizuku、virtual display、OEM 敏感行为的手工 / 自建 device flow

任何依赖硬件特性、Shizuku 或非标 device 配置的 lane，在 meet 上述 **Lane 晋升阈值** 之前必须保持在 required PR check 之外。

## Release 验证

仓库现有的开发指南已经区分了 debug QA 与 release 验证：

- 日常工作使用 debug
- release build 用于发版、release smoke 和 R8 / resource-shrink 验证
- 发版前，团队应安装已签名的 release APK，并至少跑一个真实的端到端 LLM tool-call flow

对齐后的首版保留该 release 验证要求，但**暂不**将自动化的 release-smoke 框架作为初始 QA stack 的一部分。

## 本地执行

基础本地命令不需要 wrapper 脚本：

- `./gradlew testDebugUnitTest`
- `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.annotation=com.moonkey.androidagent.qa.annotations.Smoke`

如果能简化常见流程，可以稍后添加薄的 wrapper 脚本，但初始 rollout 中 Gradle 仍是 canonical 入口。

## Rollout 计划

### Phase 1: Baseline

- instrumentation runner
- Android Test Orchestrator
- `androidTest` 骨架
- 最小化的 `qa.yml`，在 emulator 上运行一个 smoke test

### Phase 2: Deterministic harness

- 脚本化 LLM override + `LlmScripts` fixture
- state seeder
- shell / device helper 层
- debug launch helper
- 初始的 `QaTags`

### Phase 3: PR smoke 覆盖

- clean launch / onboarding state 覆盖
- seeded launch bypass
- settings sheet smoke
- permission repair card
- debug action smoke

### Phase 4: CI 扩展与文档

- 扩展 `qa.yml`，形成 PR 级的 `unit` + `android-smoke` gate 与 optional lane
- artifact 上传
- 贡献者文档：从 clone 到 green 的本地 QA 流程

### Phase 5: 更广 lane

- full instrumentation lane
- manual-device lane
- 若被批准，未来的自动化 release-smoke lane

## 任务（Tasks）

### `qa-gradle-baseline`

- Scope: `app/build.gradle.kts`、`.github/workflows/qa.yml`（最小形态：一个 smoke job）
- Acceptance criteria:
  - instrumentation runner 已配置
  - Orchestrator 已启用、`animationsDisabled = true`
  - CI 的 emulator 上至少运行一个 smoke test
- Dependencies: 无

### `qa-debug-harness`

- Scope: `app/src/debug/kotlin/com/moonkey/androidagent/qa/**`、`app/src/androidTest/kotlin/com/moonkey/androidagent/qa/base/**`、`app/src/androidTest/kotlin/com/moonkey/androidagent/qa/fixtures/LlmScripts.kt`
- Acceptance criteria:
  - 测试可以注入脚本化的 LLM 行为，且不需要新增 production backend enum
  - 测试可以直接 seed onboarding / settings / session state
  - 测试可以通过 typed 的 debug helper 启动 app
  - `LlmScripts` 至少暴露一个被首个 smoke test 使用的 factory
- Dependencies: `qa-gradle-baseline`

### `qa-compose-selectors`

- Scope: `app/src/main/kotlin/com/moonkey/androidagent/ui/**`
- Acceptance criteria:
  - 对高价值的 app-owned 控件具备稳定的 `testTag` 覆盖
  - smoke test 不主要依赖可见 text
- Dependencies: `qa-gradle-baseline`

### `qa-smoke-suite`

- Scope: `app/src/androidTest/kotlin/com/moonkey/androidagent/qa/smoke/**`、`.../robots/**`
- Acceptance criteria:
  - 标记 `@Smoke` 的 deterministic 测试在 emulator 上通过
  - 失败时产出 screenshot 和 logcat artifact
- Dependencies: `qa-debug-harness`、`qa-compose-selectors`

### `qa-system-suite`

- Scope: `app/src/androidTest/kotlin/com/moonkey/androidagent/qa/permissions/**`、`.../service/**`、`.../action/**`
- Acceptance criteria:
  - overlay / accessibility / system-boundary flow 在没有 live LLM 依赖下得到覆盖
  - `ACTION_DEBUG_EXEC` smoke 已自动化
- Dependencies: `qa-debug-harness`

### `qa-ci`

- Scope: `.github/workflows/qa.yml`（在 `qa-gradle-baseline` 最小形态基础上扩展）
- Acceptance criteria:
  - PR 上运行 `unit` 与 `android-smoke`
  - optional lane 与 required PR gate 分离
  - Lane 晋升阈值写入 workflow 文件或相邻文档
- Dependencies: `qa-smoke-suite`、`qa-system-suite`

### `qa-docs`

- Scope: `doc/dev/qa.md`、workflow 文档、任何本地 QA readme
- Acceptance criteria:
  - 贡献者可以依据文档从 clone 到本地 smoke 全绿
  - 文档中写明各 CI lane 的用途与本地命令
- Dependencies: `qa-ci`

## Open Questions

1. 在 deterministic `androidTest` stack 变绿之后，是否要新增一个自动化的 **black-box release-smoke lane**，还是按照 `doc/dev/development.md` 保持 release 验证为手工？共识：无论哪种选择，都不纳入 PR gate。唯一真正待定的是 release-tag 触发的 lane 是否会被自动化。
2. 如果后续添加 release-smoke lane，是否应使用 **Maestro**，且其范围应限制在 release-only 风险（R8 / resource-shrink / 签名 APK 行为），还是扩展到少量更广的 user journey？共识：若存在此 lane，其范围应保持窄且聚焦 release。工具选择目前偏向 Maestro，因为它能对签名的 release APK 做 black-box 驱动，但团队尚未承诺要自动化该 lane。
