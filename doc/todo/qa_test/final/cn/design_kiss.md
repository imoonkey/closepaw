# QA Test — KISS 设计

## 目标

给 app behavior 加 **regression guard**——把"应该是这样"的行为用可执行的 test 锁住，未来任何改坏它的 PR 都立刻知道。

不是 test 基建工程，不是 CI 治理项目，也不是"出 bug 才写 test"的被动模式。

Bug 只是触发**新增** guard 的一种契机。bootstrap 阶段就该按 behavior 主动 guard 高价值行为，bug 来了再补。

## 原则

- **behavior-first**：每个 test guard 一条具体行为（"X 状态下显示 Y"、"点 A 触发 B"），名字直接写清楚（`Header_NewChatButtonHiddenWhenEmpty`、`WaitingForInput_SendEnabledOnlyWhenNonBlank`）。
- **新增的两种契机**：(a) bootstrap 时按 area 把高价值 behavior inventory 一次性 guard 住；(b) bug 出现时为该 behavior 加新 guard。两种都正当。
- **扁平目录**：所有 test 都放 `app/src/androidTest/kotlin/ai/closepaw/qa/`。不分 smoke/permission/service 子目录。
- **重复 3 次再抽象**：helper、base class、Robot、annotation 都等到第 3 个 test 真的需要时再提。
- **不纳入 CI**（第一阶段）：本地 `./gradlew connectedDebugAndroidTest` 能跑就够了。CI 以后再说。

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
app/src/androidTest/kotlin/ai/closepaw/qa/
  ChatHeaderTest.kt
  ChatEmptyStateTest.kt
  CapsuleRenderingTest.kt
  CapsuleInputTest.kt
  SettingsNavTest.kt
  ...
```

文件按 area + 主题分组（`ChatXxx`、`CapsuleXxx`、`SettingsXxx`），不分子目录、没有 `base/`、`robots/`、`fixtures/`、`annotations/`。

第一个 helper（例如 `launchAppWithSeededOnboarding()`）写成顶层函数放在 `QaHelpers.kt`，只有在被 3 个 test 用到后才升级为 class。

## 每个 test 的模板

```kotlin
@RunWith(AndroidJUnit4::class)
class ChatHeaderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun new_chat_button_hidden_when_showNewChatButton_false() {
        compose.setContent {
            ChatHeader(onMenuClick = {}, onNewChatClick = {}, showNewChatButton = false)
        }
        compose.onAllNodesWithContentDescription("New conversation").assertCountEquals(0)
    }
}
```

关键点：

- **assert 的是"正确行为"**——既适用于 bootstrap guard（一直绿，破时变红），也适用于 bug fix（先红后绿）。
- **seed state 直接走 existing store/debug extras 或直接传参**——不为 QA 新增 production 代码路径。
- **使用 `org.junit.Assert`**——NEVER kotlin built-in `assert(...)`，没有 `-ea` 时它是 no-op，会让坏 test 静默通过。
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

**Bootstrap 阶段（一次性，按 area 主动 guard 高价值 behavior）**：

1. 选一个 area（Chat / Capsule / Settings / ...）。
2. 列出该 area 用户可见的 behavior inventory（参见 `bootstrap_plan.md`）。
3. 按 inventory 写 test，每条 behavior 一个 `@Test`。test 直接 pass = guard 已就位。
4. commit。

**Bug 阶段（事件驱动，guard 出错过的 behavior）**：

1. 用户报 bug → 确认能复现。
2. 写一个 test，assertion 写"正确行为"。跑一次 **确认它是红的**（红才证明 bug 存在 + test 写对了）。
3. 改代码让它变绿。
4. commit。Regression guard 达成。

两种模式产出的 test 在仓库里没有区别——都是 behavior guard。Bug 阶段的 test 也不要在文件名/类名里加 `Bug123` 之类的标签，behavior 名字本身就够了。

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

## Next step

参见 `bootstrap_plan.md` 的 area-by-area 列表。bootstrap 完成后，guard 持续靠两条路径增长：

- 新增 UI behavior 时，开发者顺手补一条 test（review 时把关）。
- 出 bug 时按 bug 阶段工作流补 test。

**不要被动等 bug**——已经识别但还没 guard 的高价值 behavior，主动补上比等它出问题再补便宜得多。
