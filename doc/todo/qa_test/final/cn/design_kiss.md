# QA Test — KISS 设计

## 目标

两件事，别的都不做：

1. **显化已知的 UX bug**——每个 bug 写一个能稳定复现的 test，失败的 assertion 就是 bug 的定义。
2. **guard regression**——bug 修好后把 assertion 翻过来，这个 test 就永久守住这条行为。

不是 test 基建工程，不是 CI 治理项目。

## 原则

- **bug-driven**：先有 bug，才有 test。不预先造抽象。
- **一个测试 = 一条行为**：名字直接写清楚它在查什么（`ChatInputClearsOnSend_Bug123Test`）。
- **扁平目录**：所有 test 都放 `app/src/androidTest/kotlin/com/closepaw/qa/`。不分 smoke/permission/service 子目录。
- **重复 3 次再抽象**：helper、base class、Robot、annotation 都等到第 3 个 test 真的需要时再提。
- **不纳入 CI**（第一阶段）：本地 `./gradlew connectedDebugAndroidTest` 能跑就够了，先用 test 驱动 fix，CI 以后再说。

## Stack

- Compose UI Test（app 内 UI）
- UI Automator（仅当 bug 真的跨到 system Settings，才引）
- AndroidJUnitRunner
- `animationsDisabled = true`（一次性配置，防 flake）

不要 Orchestrator、不要 annotation 分 lane、不要 Maestro、不要 Kaspresso。

## LLM 隔离

复用已有的 `LLMClientFactory.forTest(...)`。

在 test 里直接：

```kotlin
LLMClientFactory.forTest { messages ->
    // 手写这一个 test 想要的 response
}
```

不要先写 `LlmScripts` DSL、不要 factory-of-factory。**第一个 test 只需要一个 lambda**。等第 3 个 test 发现在重复同样的脚本再抽出命名常量。

## 目录

```
app/src/androidTest/kotlin/com/closepaw/qa/
  OnboardingBackButtonBugTest.kt
  ChatInputClearsOnSendBugTest.kt
  SettingsSheetDismissBugTest.kt
  ...
```

就这样。没有 `base/`、`robots/`、`fixtures/`、`annotations/`。

第一个 helper（例如 `launchAppWithSeededOnboarding()`）写成顶层函数放在 `QaHelpers.kt`，只有在被 3 个 test 用到后才升级为 class。

## 每个 bug test 的模板

```kotlin
@RunWith(AndroidJUnit4::class)
class OnboardingBackButtonBugTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun back_button_on_permission_page_does_not_skip_onboarding() {
        // 1. 直接 seed 到"权限页"状态（用已有的 OnboardingStore / debug intent extras）
        // 2. 按返回键
        // 3. assert 当前仍在 onboarding，不是被踢到主界面

        //  bug 未修复时这里会 fail — 这就是 bug 的可执行定义。
        //  修复后这个 test 会 pass — 从此成为 regression guard。
    }
}
```

关键点：

- **seed state 直接走 existing store/debug extras**——不为 QA 新增 production 代码路径。
- **assert 的是"正确行为"**，不是"当前行为"。test 一开始是红的，fix 之后变绿。这就是 TDD for bugs。
- **失败时抓 screenshot**：用 `UiDevice.getInstance(...).takeScreenshot(...)` 一行调用写进 `@After`。单文件、不抽 rule。

## Gradle baseline（一次配置）

`app/build.gradle.kts`：

```kotlin
defaultConfig {
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
}
testOptions {
    animationsDisabled = true
}
dependencies {
    androidTestImplementation("androidx.test.ext:junit:…")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:…")  // 仅当需要
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
```

配完一次，以后忘掉。

## 工作流

1. 用户报 bug → 确认能人工复现。
2. 写一个 `<BugName>BugTest.kt`，assertion 写"正确行为"。跑 `./gradlew connectedDebugAndroidTest --tests '*<BugName>*'`，**确认它是红的**（红的 test 才证明 bug 存在、且 test 写对了）。
3. 改代码让它变绿。
4. commit。Regression guard 达成。

## 何时扩展

只有在**出现实际摩擦**时才扩展：

| 摩擦 | 触发的扩展 |
|---|---|
| 3 个 test 抄同一段 seed 代码 | 提到 `QaHelpers.kt` 顶层函数 |
| 3 个 test 抄同一段 LLM 脚本 | 给 lambda 起名字放常量 |
| CI 里偶发 flake | 加 Orchestrator + clearPackageData |
| 想按 tag 选跑 | 加 `@Smoke` 一个 annotation（不是三个） |
| 需要跨 app 系统 flow | 引入 UI Automator |

**不要预先做这些**。

## 不做什么

- 不分 smoke/nightly/manual-device lane。
- 不写 lane-promotion 阈值。
- 不加 Maestro。
- 不为 QA 新增 production enum / config。
- 不写 Robot pattern、BDD DSL。
- 不做 5-phase rollout plan。

## Next step（需要你输入）

给我**现在最烦的 5 个 UX bug**（简短复现步骤即可），我按上面模板写出第一批 test。从这 5 个开始，基建自然会从它们的重复中长出来，而不是反过来。
