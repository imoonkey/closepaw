# ClosePaw 发布前 UI 打磨方案（对齐初稿）

## 目标

在不重新设计产品的前提下，对 ClosePaw Android UI 做发布前打磨。覆盖整个用户可见的应用：

- 主对话：标题栏、空状态、消息列表、流式输出、折叠的执行轨迹、完成交接、启动错误横幅。
- 会话抽屉：空历史、活动会话、损坏会话、删除流程。
- 设置：首页、LLM/鉴权、Agent 行为、权限、工具、记忆、App 访问、开源许可。
- Smart Capsule：主应用与悬浮层下的 `Hidden`、`Running`、`TakeoverPending`、`Takeover`、`Supplement`、`WaitingForInput`、`WaitingForAction`、`WaitingForApproval`、`Done`、`Error`。
- 悬浮层表面：island、glow、可视化器、锁屏触控盾、虚拟显示查看器交接。
- 引导（onboarding）与权限修复状态。

成功标准：

- 不存在阻塞发布的裁切、无法到达的操作或首启布局失败。
- 在 360dp 宽度、字体缩放 1.5 下，必需操作仍然可见且可点。
- 自定义可交互表面正确暴露 role、label、state，且具备 48dp 有效触控目标。
- 用户可见的失败具备显式的 loading/error/empty 状态。
- 继续沿用现有的 ClosePaw 纸张材质、Claw 配色、爪印图形、形状、字体、动效与设置组件。

## 对齐基线

两份独立的交叉评审都选择 CODEX 作为对齐初稿的更佳基线。共识：

- Codex 在「发布阻塞」分级上更准。
- Claude 给出了有价值的设计系统漂移发现，应主要以 P1/P2 形式并入。
- 首版发布计划应优先采用「局部、证据驱动」的修复，而不是大范围重构或纯审美变更。
- 主观或未决的选择必须显式保留，直到截图、可访问性检查或产品决策给出答案。

## 原则

- 保持改动局部化。复用 `ClosePawTheme`、`MaterialTheme.closePaw.spacing`、`MaterialTheme.shapes`、`foldedPaper`、`SettingsCard`、`ToolSettingsCard`、`SettingsAlertCard`、`PageMasthead*`、`ClosePawMotion`，以及现有 capsule 原子组件。
- 必需操作路径优先于审美一致性。
- 把可访问性当作布局而不是装饰：48dp 有效触控目标、清晰的语义、对动态字号安全的布局。
- 在 P0/P1 截图通过前，避免大范围「设计系统统一」式重写。
- 不要修改已文档化的产品结构，除非同一次决策中同时更新文档。
- 触控目标约定：在紧凑控件的可点修饰符上加 `Modifier.minimumInteractiveComponentSize()` 以保证 48dp 有效热区（视觉仍可维持 32–36dp，仅外扩点击 slop）。仅当触控热区必须扩出视觉边界时（如 `TextField` 的前后图标槽位）才用 48dp 的 `Box` 包裹。除非有 3+ 个调用点需要完全相同的外观，否则不引入新的共享按钮 helper —— 目标是局部修复，不是再造一层抽象。
- 内边距约定：页面级容器统一通过 `AppWindowInsets`（`ui/theme/WindowInsets.kt`）来读取窗口内边距。功能代码不应直接引用 `WindowInsets.statusBars` / `statusBarsPadding()` / `systemBarsPadding()`。新代码遵循该约定；已有调用点只在 P0/P1 修复已经触达同一文件时一并迁移，不做独立的清扫式重构。

## P0 发布阻塞项

| ID | 文件位置 | 当前行为 | 修复方案 | 验收标准 | 工作量 |
| --- | --- | --- | --- | --- | --- |
| P0-1 引导完成页绕过共享 Shell | `app/src/main/kotlin/ai/closepaw/ui/onboarding/OnboardingScreen.kt:148`、`app/src/main/kotlin/ai/closepaw/ui/onboarding/OnboardingShell.kt:63`、`app/src/main/kotlin/ai/closepaw/ui/onboarding/OnboardingSteps.kt:653` | `WizardStep.Complete` 直接调用 `CompleteStepContent`，最终页失去了共享的 `systemBarsPadding()`/`imePadding()` shell，首启时「Start Using ClosePaw」CTA 可能贴近或被导航栏遮挡。 | 让 `WizardStep.Complete` 走 `OnboardingShell`（去掉重复的 "You're All Set!" 标题），或新增一个共享同样内边距/全屏布局原子的 `OnboardingCompleteShell`。按上文内边距约定，不要在 `CompleteStepContent` 内部自行 `systemBarsPadding()`。 | 360dp 宽度、字体缩放 1.5 下，CTA 在手势导航与三键导航上方均可见。完成页与引导背景、间距、安全区域一致。 | S |
| P0-2 Smart Capsule 注意状态控件可能拥挤或裁切 | `app/src/main/kotlin/ai/closepaw/ui/overlay/model/CapsuleRenderSpec.kt:120`、`CapsuleRenderSpec.kt:195`、`app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleControlBar.kt:67`、`CapsuleControlBar.kt:97` | `WaitingForApproval` 可能在一行不换行地渲染 `Always`、`Session`、`Reject` 以及 VD 导航操作。在窄宽度或大字体下，必需的审批控件会拥挤或裁切。 | 为 `WaitingForApproval`、`WaitingForInput`、`WaitingForAction`、`Error` 增加注意状态布局：必需操作优先、48dp 有效目标；在空间受限时不把导航操作放在同一行。 | 主应用与悬浮层下，审批/动作/输入/错误状态在 360dp + 字体 1.5 时无横向裁切。必需操作可见、有标签、可点。 | M |

## P1 发布一致性与正确性

| ID | 文件位置 | 当前行为 | 修复方案 | 验收标准 | 工作量 |
| --- | --- | --- | --- | --- | --- |
| P1-1 交互目标与语义清扫 | `CapsuleInputBar.kt:229`、`CapsuleInputBar.kt:306`、`CapsuleControlBar.kt:218`、`StatusIslandCompose.kt:42`、`CompletionHandoffCtaRow.kt:59`、`AppAccessSettingsPage.kt:248`、`AppAccessSettingsPage.kt:290`、`AppAccessSettingsPage.kt:542`、`NavigationDrawer.kt:290`、`PermissionRepairCard.kt:119` | Capsule 麦克风/发送/导航/island/交接、App Access 标签、抽屉删除、修复控件使用了紧凑视觉或自定义表面。部分缺少显式 role、selected 状态、stateDescription 或 48dp 有效目标。 | 按上文触控目标约定执行（默认 `Modifier.minimumInteractiveComponentSize()`；`Box(48.dp)` 仅用于 `TextField` 图标槽位）。补齐缺失的 `Role`、`stateDescription`、稳定的 `contentDescription`。在 Material 默认行为合适时优先沿用。 | 在 360dp / 字体 1.5 下做可访问性检查，每个自定义交互表面均具有合适的 role/label/state 与 48dp 有效热区。 | M（按区域拆 3–4 个 PR：capsule、App Access、drawer/repair/handoff，便于评审） |
| P1-2 Capsule 状态行语义对齐 | `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/SmartCapsuleSurface.kt:276`、`app/src/main/kotlin/ai/closepaw/ui/overlay/compose/StatusIslandCompose.kt:42` | Island 已暴露 button 语义，但可点的 capsule 状态行只加了 `clickable`。 | 当存在 `onStatusClick` 时，补 role/button 语义与稳定标签，例如 "Open current app" 或 "Capsule status: <text>"。 | 屏幕阅读器在 island 与展开的 capsule 状态间行为一致。 | S |
| P1-3 纸张噪点强度与图层顺序 | `doc/main/ui/style.md:252`、`app/src/main/kotlin/ai/closepaw/ui/theme/PaperGrain.kt:41`、`PaperGrain.kt:49`、`app/src/main/kotlin/ai/closepaw/ui/chat/ChatScreen.kt:286`、`app/src/main/kotlin/ai/closepaw/ui/settings/SettingsSheet.kt:103` | 文档规定 `strength = 0.015f`；代码默认 `0.04f`（PaperGrain.kt:41）。该修饰符先 `drawContent()` 再 `drawRect(...)`（49–53 行），无论挂在哪条链上都会覆盖其下子节点 —— `ChatScreen.kt:286` 中噪点是绘制在 Scaffold 之后的兄弟 Box；`SettingsSheet.kt:103` 中噪点位于内容 Column 之上。两处当前都把噪点画在了文字之上。 | 默认强度改为 `0.015f`（一个字面量）。同时选一种「画在内容之下」的实现 —— 见 Open Question Q3。Chat 与 Settings 在一个 PR 内同时修复。 | 浅色模式下的截图显示纸张纹理但不削弱文字/控件对比度。深色仍由 `lanternVignette` 主导。 | S |
| P1-4 App Access 记忆创建失败与全编辑器入口 | `app/src/main/kotlin/ai/closepaw/ui/settings/AppAccessSettingsPage.kt:153`、`AppAccessSettingsPage.kt:193`、`AppAccessSettingsPage.kt:528`、`app/src/main/kotlin/ai/closepaw/ui/settings/MemoryFileEditor.kt:352`、`doc/main/app/settings.md:112` | `+ Memory` 把写入失败映射成 `WriteFailed`，然后什么都不显示。Bounded 的逐应用记忆把 `onOpenFullMemoryEditor` 传成 `null`，与已文档化的 Open 入口不符。 | 显式呈现创建记忆失败，并新增一个针对单 App 的全页记忆编辑器路由。保留 `MemoryFileEditor` 已建模的脏缓冲守卫。 | 创建失败可见。长的单 App 记忆能打开全页。内嵌编辑器仍受限于固定高度。 | M |
| P1-5 不限大小的记忆编辑器没有铺满页面 | `app/src/main/kotlin/ai/closepaw/ui/settings/MemoryFileEditorPage.kt:39`、`MemoryFileEditorPage.kt:41`、`app/src/main/kotlin/ai/closepaw/ui/settings/MemoryFileEditor.kt:303` | 不限大小的页面把编辑器套在滚动 Column 里；空文件显示成一个小输入框而不是铺满编辑器页面。 | 让页面感知高度：masthead 固定、编辑器表面/正文撑满剩余高度、操作行可达、文本字段内部滚动。 | 用户/设备记忆页在空内容与长内容下都像一个编辑器，而不是小表单。 | M |
| P1-6 OTHER provider 字段校验 | `app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt:613`、`LlmAuthSettingsPage.kt:635`、`app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthDiscoveryUi.kt:273` | 已有 Base URL 与 Custom Model Id 的校验器，但字段没有 `isError` / `supportingText`；反馈通过 refresh 是否可用或模型选择行为间接呈现。 | 用 `OtherBaseUrlValidator` 与 `ModelIdValidator` 给出字段级校验；refresh 禁用原因作为次级上下文保留。 | OTHER 字段非法时在 refresh/save 路径间接失败之前就给出说明。 | S |
| P1-7 语音反馈仅记录到日志 | `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleInputBar.kt:134` | 语音识别消息进 `Log.i`，失败时麦克风按钮看起来像死掉了。 | 把语音反馈接入一个主应用与悬浮层都可用的瞬态 UI 通道，如 capsule 横幅或 Android toast。 | 权限被撤销、不可用、语种、网络失败对用户可见。 | S |
| P1-8 会话抽屉忽略 active/corrupted 状态 | `app/src/main/kotlin/ai/closepaw/history/model/SessionInfo.kt:32`、`SessionInfo.kt:38`、`app/src/main/kotlin/ai/closepaw/ui/navigation/NavigationDrawer.kt:235`、`NavigationDrawer.kt:290` | `SessionInfo` 暴露了 `isActive` 与 `isCorrupted`，但抽屉行未使用。损坏会话看起来可选；活动会话无标识；删除是紧凑图标。 | 增加 active 与 corrupted 行处理。禁用或拦截 corrupted 行的正常打开，保留删除；删除按钮给 48dp 有效目标。 | 活动会话可辨识。损坏会话不再表现为普通可打开行。删除依然可达。 | M |
| P1-9 破坏性清理操作可能虚报成功 | `app/src/main/kotlin/ai/closepaw/ui/settings/PermissionsAdvancedSettingsPage.kt:171`、`PermissionsAdvancedSettingsPage.kt:198` | Trace/会话清理在 `deleteRecursively()` 之后无视结果直接置为成功，未呈现 IO 失败。 | 用显式的成功/失败处理包装删除，并显示内联的重试/错误状态。仅在确认成功后才显示「已清除」。 | UI 在删除失败时不会报「已清除」。 | S |

## P2 细节优化

| ID | 文件位置 | 当前行为 | 保守修复 | 工作量 |
| --- | --- | --- | --- | --- |
| P2-1 App Access 与开源许可的 loading/empty/error 状态 | `AppAccessSettingsPage.kt:336`、`AppAccessSettingsPage.kt:374`、`OpenSourceLicensesPage.kt:63`、`OpenSourceLicensesPage.kt:86` | loading/无匹配/许可资源失败要么过于简陋要么直接塌陷成空。 | 增加纸张色调的小型 loading/empty/error 状态，操作文案要明确。 | S |
| P2-2 设置卡片一致性 | `SettingsCard.kt:19`、`SettingsWidgets.kt:162`、`PermissionsAdvancedSettingsPage.kt:90`、`ToolSettingsCard.kt:56` | 部分设置行手写 Surface、padding、shape、switch 颜色或 folded-paper 处理。 | 把 debug/trace 行以及明显匹配的行迁到现有卡片原子上，避免整体重写。 | M |
| P2-3 形状 token 清理 | `PermissionRepairCard.kt:50`、`NavigationDrawer.kt:238`、`NavigationDrawer.kt:241`、`MessageBubble.kt:55` | 少数表面用裸 `RoundedCornerShape(N.dp)`。用户气泡有意非对称但未明确文档。 | 把等价字面量替换为主题形状。把用户气泡作为有意为之的局部例外写入文档。 | S |
| P2-4 引导排版、换行与纸张原子 | `OnboardingShell.kt:113`、`OnboardingSteps.kt:203`、`OnboardingSteps.kt:677`、`PermissionRepairCard.kt:50` | 引导标题可能合成 Fraunces 字重/字形；provider 选择是固定行；部分卡片用默认 Material 风格。 | 在身份化样式处使用 Fraunces 斜体 Regular；大字体下让 provider/鉴权选项换行；层级合适时套用 ClosePaw 纸张表面。 | M |
| P2-5 空状态主视觉爪印响应式检查 | `ChatScreen.kt:180`、`ChatScreen.kt:187`、`doc/main/ui/user_interaction.md:57` | 主视觉爪印使用固定偏移与尺寸。在异常内边距、分屏、平板、大字体下可能裁切或重叠。 | 若截图复现裁切，用约束布局替代魔法定位。除非产品另有决定，保留已文档化的空状态构图。 | S–M |
| P2-6 状态色对比度截图 | `StatusColors.kt:58`、`SettingsWidgets.kt:194` | 已知 Amber 对比度风险；Paused 状态仍映射到 Amber 作为 capsule 字形/光晕。 | 在浅色/深色 capsule 表面上抓取状态色对比度截图。仅在截图不通过时调整 token。 | S |
| P2-7 减弱动效下的可视化器 | `ActionVisualizerCompose.kt:56` | 只要有元素就跑帧动画，未读取 `ClosePawMotion.reducedMotion()`。 | 在减弱动效下渲染静态终态标记或最小淡入，替代帧驱动展开。 | S |
| P2-8 LLM 发现选择器行的目标与换行 | `LlmAuthDiscoveryUi.kt:154`、`LlmAuthDiscoveryUi.kt:195`、`LlmAuthDiscoveryUi.kt:247` | 选择器行与 refresh 元信息可能过于紧凑或拥挤。 | 保证 48dp 目标 / selectable 语义；窄宽度下让 refresh 元信息在按钮下方换行。 | S |
| P2-9 Island/Capsule 跑马灯一致性 | `StatusIslandCompose.kt:66`、`SmartCapsuleSurface.kt:320` | Island 文本宽度固定 220dp，跑马灯/减弱动效逻辑重复。 | 截图核对后让 island 最大宽度自适应；仅当能减少重复又不掩盖简单布局差异时再抽出公共跑马灯/减弱动效文本组件。 | S |
| P2-10 消息与账本边缘场景 | `MessageBubble.kt:75`、`AgentSummary.kt:45` | 长不可断用户文本可能溢出；`String.format("%.1fs")` 使用默认 locale。 | 若复现长 URL/哈希溢出，加换行处理。等宽耗时账本改用 `Locale.US`。 | S |
| P2-11 悬浮层内边距助手 | `IslandOverlayHost.kt:117` | Island host 通过资源查找状态栏高度。 | 优先用 WindowInsets/当前 metrics，保留现有 fallback 仅用于不支持的路径。 | S |
| P2-12 Capsule 与告警细节 | `SmartCapsuleSurface.kt:237`、`SmartCapsuleSurface.kt:133`、`CapsuleDivider.kt:195` | 启动错误横幅与部分 capsule 内边距是内联硬编码。 | 在层级合适时考虑用 `SettingsAlertCard` 承载启动错误；仅在视觉对比后再把 6dp 内边距对齐。 | S |
| P2-13 Local Tab 警示色调（延后） | `app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt:568` | LocalTabContent 用 `errorContainer` 表达本应是 warning 的文案（"本地模型较慢…"）。 | 替换为 `SettingsAlertCard(tone = AlertTone.Warning)`。因 `LOCAL_TAB_ENABLED = false`（LlmAuthSettingsPage.kt:72）本次发布无用户能到达，故延后。Local Tab 上线时再开启。 | S |

## 实施任务

1. `ui-polish-p0-onboarding-shell`
   - 范围：onboarding screen/shell/complete step。
   - 验收：在 edge-to-edge 设备上，360dp + 字体 1.5 时，最终引导 CTA 在导航栏之上可见。

2. `ui-polish-p0-capsule-attention-layout`
   - 范围：`CapsuleControlBar`、`CapsuleRenderSpec`、导航可见性/布局。
   - 验收：approval/action/input/error 状态在 360dp + 字体 1.5 下无横向裁切。

3. `ui-polish-accessibility-targets`
   - 范围：capsule 控件、island、App Access 标签、抽屉删除、修复卡、交接 CTA。
   - 验收：自定义交互表面具备 role/state/label 与 48dp 有效目标。
   - 依赖：任务 2 的 capsule 控件布局。

4. `ui-polish-memory-app-access`
   - 范围：`MemoryFileEditor*`、`AppAccess*`、设置页路由。
   - 验收：记忆创建失败可见；单 App 全编辑器可打开；不限大小编辑器铺满页面。

5. `ui-polish-settings-failure-states`
   - 范围：LLM OTHER 字段、权限高级清理操作、语音反馈通道、license/App Access empty/error 状态。
   - 验收：非法字段与失败的 IO/破坏性操作可见，且不会虚报成功。

6. `ui-polish-session-drawer-state`
   - 范围：会话抽屉行。
   - 验收：active 与 corrupted 会话在视觉与语义上都有区分；删除目标可达。

7. `ui-polish-paper-and-visual-system`
   - 范围：纸张噪点、引导样式、形状 token、设置卡一致性、状态色对比度。
   - 验收：在 P0/P1 用户操作路径绿了之后再落地这些验证过的视觉系统修复。

8. `ui-polish-final-qa`
   - 范围：下方矩阵对应的截图、可访问性检查、定向 UI 检查。
   - 验收：浅/深色、360dp、字体 1.5、减弱动效下都不存在 P0/P1 级别的裁切、缺语义、虚报成功或对比度不达标。

## QA 矩阵

| 表面 | 需要捕获的状态 |
| --- | --- |
| 主对话 | 空状态、单条用户消息、长 URL、流式 agent、已完成且折叠的轨迹、完成交接、启动错误横幅 |
| 会话抽屉 | 空历史、活动会话、损坏会话、删除确认 |
| 设置首页 | 全部已授权、缺少权限、长模型名、深色模式 |
| LLM/鉴权 | Sign In 未登录/已登录/进行中/错误、API Key OpenAI/OpenRouter/OTHER 合法与非法字段、模型 refresh loading/error/empty |
| Agent 行为 | Perception 选择器、VD Shizuku 不可用/权限拒绝/启用、browser_script 探测中/未绑定/未知、Termux 状态、skills 空/已加载/info 弹窗 |
| 记忆 | 锁定/解锁、loading、过大保存错误、IO 错误、全页用户/设备编辑器、限制大小的 App 编辑器、单 App 全编辑器 |
| App Access | loading、无匹配 App、长 App 名/包名、被屏蔽 App 警告、仅 skill 展开、仅 memory 展开、创建记忆失败 |
| Smart Capsule | Hidden 输入、Running、TakeoverPending、Takeover、Supplement note、WaitingForInput、WaitingForAction、WaitingForApproval、Done、Error、启动错误、语音权限/错误 |
| 悬浮层 | 看屏 capsule、island、glow、可视化器、VD viewer、锁屏触控盾、键盘聚焦 |
| 引导 | 各步骤在 360dp 宽度、字体 1.5、API Key 步骤打开 IME、最终完成页（手势导航 + 三键导航） |
| 动效/对比度 | 减弱动效开启、浅/深色纸张噪点或暗角、Paused/Running/Error 状态色 |

## 本次发布打磨不在范围内

- 品牌语言、对话标题栏、导航模型或设置信息架构的重新设计。
- 新增全局按钮抽象 —— 除非实践证明重复的局部修复更糟。
- 在 P0/P1 检查通过前重构每个自定义 `Surface` 或每个裸 inset 调用。
- 在没有显式文档/产品更新的情况下，修改已文档化的 `[menu] + paw + ClosePaw + [+]` 对话标题栏结构。
- 重做 `PageMastheadDrillDown` 的 back/close 语义；本次发布保留两者，因为差异已文档化且有用。
- 让 `Fleuron` 自身对屏幕阅读器可见；除非后续可访问性评审另有要求，否则它是装饰性的。
- `OverlayComposeHost` 窗口 token 清理及类似的生命周期/健壮性审计。不属于视觉打磨范畴；若有缺陷再作为独立的 overlay 加固任务。
- 仅为了满足原则中 inset 或触控目标约定而做的大范围清扫式重构。这些约定随 P0/P1 修复触达每个文件时逐文件落地。

## 已定方案

以下决策解决了原 Q1、Q2、Q4、Q5、Q6、Q7、Q10、Q11、Q12。上方对应的 P 条目都按这些决策来实施。

- **D1（原 Q1）Capsule 注意状态布局** → 在 `WaitingForApproval`、`WaitingForInput`、`WaitingForAction`、`Error` 下**隐藏次级导航**。必需操作占满整行，48dp 有效目标。状态切回 `Running` 时导航恢复。对应 P0-2。
- **D2（原 Q2）纸张噪点 modifier 语义** → 保持 `Modifier.paperGrain` 为 **overlay**（语义不变，无 API 变更）。需要"画在内容下"的调用点应把**兄弟 Box** 放在内容之后绘制，而不是包裹内容。ChatScreen 和 SettingsSheet 在 P1-3 中按此模式迁移并同时修复强度。
- **D3（原 Q4）用户气泡材质** → 保留 `surfaceContainerLow`，给用户气泡加 **1dp outline** 描边（Ink α 0.18）。最小改动，可视分离用户气泡与同色 capsule。P2-3 中与气泡有意非对称形状一起记录。
- **D4（原 Q5 + Q7 + Q12）失败/警示反馈承载** → **内联 `SettingsAlertCard`** 作为设置页失败默认承载（App Access 记忆创建失败、记忆编辑锁定反馈）；语音识别/权限失败在对话 scaffold 上同样用 `SettingsAlertCard`。toast 仅保留给没有明显锚定行的场景。本次发布不引入 capsule 内瞬态横幅与 Snackbar。对应 P1-4、P1-7，以及 P2-13 延后的 local-tab 路径。
- **D5（原 Q6）App Access 筛选器** → 改用 Material **`SingleChoiceSegmentedButtonRow`**，与 `LlmAuthSettingsPage:398` 对齐。这一区原本属于 P1-1 的可访问性工作大部分被消除（segmented 行原生暴露 role/state/48dp 目标）。新增叶子任务 P1-10。
- **D6（原 Q10）Action 可视化器减弱动效范围** → P2-7 **只做减弱动效合规**。item 生命周期/循环清理明确不在范围内，发布后若有缺陷再处理。
- **D7（原 Q11）引导 Shell 滚动模型** → 用**始终纵向滚动**的简单模型替换 `OnboardingShell` 中的 `BoxWithConstraints` + IME 启发式。IME insets 由 `imePadding()` 处理。`OnboardingShell.kt:60-79` 启发式块删除。并入 P0-1。

## 未决问题

剩 3 项 —— 都需要数据（截图或设备实测）才能做有意义的决策。解决方式：先更新上方相应自包含小节，再删除或修订此处问题。

1. 空状态主视觉爪印（P2-5）：在目标设备上（Pixel 6a 手势导航、分屏、平板、字体 1.5）是否真出现裁切/重叠？若是，修复应是约束化定位、更小响应式尺寸、降低 alpha 还是组合方案？
2. VirtualDisplayViewerActivity：黑色/无帧 loading 状态（`ui/viewer/VirtualDisplayViewerActivity.kt`）是否纳入本次发布打磨，还是延后归到 VD pipeline UX 工作？
3. Paused Amber 状态色：若 P2-6 对比度截图在浅/深色 capsule 表面上不通过，修复应是 outline 支持、更深 token 还是不同的语义前景？
