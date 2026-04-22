# UI/UX 代码质量评审 — 最终版 (Claude + Codex 对齐)

**基准**: Codex 设计加 Claude 补充
**日期**: 2026-04-08
**范围**: `app/src/main/kotlin/com/moonkey/androidagent/ui/`

---

## 概要

UI 模块具有扎实的视觉基础和若干优秀的架构选择（CapsuleMode 状态机、共享的 SmartCapsuleSurface、模块化的聊天 action 渲染）。主要的质量问题是**状态所有权漂移**：多个重要页面将真值来源 state、`remember` 的本地 state 和纯 UI 副作用混合在一起，导致状态不同步并逐步累积。

**结论**: 在进一步扩展 UI 之前需要 `CHANGES_REQUESTED`。代码库无需重写即可修复。

---

## 运行良好的部分

- **CapsuleMode** 是一个清晰、显式的状态机，避免了布尔值混乱
- **SmartCapsuleSurface** 在应用和 overlay 之间共享 — 方向正确
- **聊天 action 渲染**模块化且可读性强（ChatMessage model、ActionCard）
- **Overlay 窗口设置**被 OverlayComposeHost 干净地封装
- **Material 3 主题**在表面层级视觉一致
- **Settings 导航**具有清晰的三级 hub 结构和动画过渡
- **WaitingForApproval** 提供 4 种不同的审批范围，条件渲染正确
- **Stop 按钮**禁用状态（"Stopping..."）防止重复点击
- **Supplement 确认**根据 agent 轮次状态区分消息内容

---

## 高严重性发现

### H1. SmartCapsuleSurface 在 composition 期间修改 state
**文件**: `capsule/surface/SmartCapsuleSurface.kt:67-74, :79-80`

`previousModeState.value = mode` 写在 `remember(...)` 内部。当 `clearInput` 为 true 时，`inputText = ""` 在 composition 期间被写入。这违反了 Compose 的"渲染应无副作用"模型，使行为依赖于 recomposition 顺序。由于该 surface 被 app 和 overlay 共享，问题被放大。

**修复**: 保持 CapsuleRenderSpec.from() 纯函数。将 previous-mode 跟踪提升到 composition 外部，或通过 LaunchedEffect 更新。通过 effect 而非 composition 期间清除输入。

### H2. Settings 页面/tab/provider state 与实际应用 state 脱节
**文件**: `settings/SettingsSheet.kt:68`, `settings/LlmAuthSettingsPage.kt:72-79, :226-229`

`settingsPage`、`selectedTab`、`selectedProvider` 是本地 `remember` 的，从外部输入初始化一次后便不再跟踪。均未使用 `rememberSaveable`。此外，切换 tab 会立即触发 `onBackendChange`/`onAuthMethodChange` — 浏览 tab 会修改 backend/auth state。

**修复**: 将 settings 导航/auth 选择提升为显式 state。将探索性的 tab/provider 选择与已提交的 backend/auth mutation 分离。在本地 state 需要在配置更改后保留时使用 `rememberSaveable`。

### H3. 聊天自动滚动在流式对话中不正确
**文件**: `chat/ChatScreen.kt:189-195`, `chat/ChatEventReducer.kt:64-72, :95-129`

自动滚动仅在 `messages.size` 变化时触发。流式文本和 action-card 更新修改最后一条消息但不改变列表大小 — 内容增长到可视区域以下而视口不跟随。反之，新消息总是动画滚动到底部，即使用户正在查看历史记录。

**修复**: 实现显式的"吸底"策略。跟踪用户是否在底部附近。当用户正在跟读时，在最后一项增长时滚动。当新内容到达而用户已向上滚动时，显示"滚动到底部"FAB。

### H4. Overlay capsule 所有权分散在多个 state store 中
**文件**: `overlay/CapsuleStateHolder.kt:44-67`, `overlay/compose/CapsuleOverlayHost.kt:73-78, :111-117, :213-220`

CapsuleStateHolder 拥有 `mode`、`context`、`platformMode`。CapsuleOverlayHost 同时拥有 `capsuleContext`、`platformMode`、`hasIsland`、`inputFocused`、`interactionLocked`。注释声称是"single source of truth"，但渲染路径消费的是分裂的 state。

**修复**: 定义一个权威的 capsule UI state 契约。CapsuleStateHolder 作为唯一所有者。Host 仅负责窗口参数、focusability 和 touchability。

### H5. Accessibility 语义不一致
**文件**: `onboarding/OnboardingShell.kt:55-60`, `capsule/surface/SmartCapsuleSurfaceParts.kt:256-265`, `overlay/compose/StatusIslandCompose.kt:30-33`

Onboarding 返回按钮使用 Icon+clickable（不是 IconButton — 缺少 48dp 触摸目标）。Capsule 导航按钮的 `contentDescription = null`。Status island 是一个自定义 clickable，没有 role/semantic 命名。

**修复**: 将临时的 icon clickable 模式替换为 button 原语。为每个仅图标的操作提供显式 label。为自定义 clickable 容器添加 semantics。

---

## 中等严重性发现

### M6. OnboardingSteps.kt 超出文件大小指引
**文件**: `onboarding/OnboardingSteps.kt` — 735 行。包含权限流程、API key 流程、OAuth/手动分支、demo 流程、完成流程、共享按钮、共享卡片和文案。违反项目 400 行的指导。

### M7. 主题一致性不如表面看起来那么好
**文件**: `overlay/compose/StatusIslandCompose.kt:35, :55`, `theme/Color.kt:12-59`, `theme/Shape.kt:58-83`

StatusIslandCompose 硬编码了白色/深色颜色，而非使用 theme token。Color.kt 携带两套并行的 token 词汇表 — 非 `Chat*` 集合看起来未被使用。Shape.kt 导出了未被引用的 shape。

### M8. 整个模块缺少 state 保留
`ui/` 中零处使用 `rememberSaveable`。Settings 的页面/tab/provider、capsule 输入、密码可见性在配置更改时全部重置。

### M9. 生命周期/effect 不匹配
**文件**: `onboarding/OnboardingScreen.kt:46-47`, `chat/ChatScreen.kt:65-71`

`LaunchedEffect(Unit)` 即使 flow 发生变化也会永远收集 effect。ChatScreen 直接访问 `AgentService.instance`。

### M10. SettingsSheet 有 38 个参数
**文件**: `settings/SettingsSheet.kt:30-67`。可维护性天花板 — 每新增一个设置项都需要编辑这个签名以及所有调用点。

### M11. CapsuleOverlayHost 有 12 个 nullable callback 属性
**文件**: `overlay/compose/CapsuleOverlayHost.kt:52-62`。遗漏的赋值会静默变为 no-op。

### M12. Session 删除没有确认
**文件**: `navigation/NavigationDrawer.kt:274-284`。删除按钮直接调用 `onDelete` — 误删不可逆。较小的 (32dp) 触摸目标增加了风险。

### M13. MessageBubble 每次 recomposition 分配 SimpleDateFormat
**文件**: `chat/components/MessageBubble.kt:188`。`SimpleDateFormat` 不是线程安全的，且在流式传输期间产生 GC 压力。

### M14. ActionStatusIcon 存在冗余的双重旋转
**文件**: `chat/components/ActionCard.kt:170-187`。自定义的 `infiniteTransition` 旋转包裹了一个本身已在动画的 `CircularProgressIndicator`。

---

## 低严重性发现

### L15. 字符串和时间格式是面向实现的
session 工具类中使用 `Locale.US`。零处使用 `stringResource()`。面向用户的字符串硬编码。

### L16. PerceptionMode 使用原始字符串
**文件**: `settings/SettingsWidgets.kt:327-331`。多处使用原始字符串匹配，存在拼写错误风险。

### L17. 版本字符串显示两次
**文件**: `settings/SettingsHomePage.kt:64-70`, `settings/PermissionsAdvancedSettingsPage.kt:108-114`。

---

## 两份评审的共同缺口

1. 没有性能分析（没有 layout inspector 或 recomposition 计数数据）
2. 没有 dark mode 视觉测试
3. 没有考虑平板/折叠屏适配
