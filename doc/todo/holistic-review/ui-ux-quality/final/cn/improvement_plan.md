# UI/UX 质量改进计划 — 最终版 (Claude + Codex 对齐)

**基准**: Codex 分阶段方案加 Claude 战术补充
**原则**: 先修复 state 所有权和 composition 正确性，再做打磨。做减法而非加法。

---

## Phase 1: 共享 Surface 的 Compose 正确性
**优先级**: P0

**目标文件**:
- `capsule/surface/SmartCapsuleSurface.kt`
- `onboarding/OnboardingScreen.kt`

**任务**:
- 移除 SmartCapsuleSurface composition 中的所有 state 写入
- 停止在 `remember` 内部更新 `previousModeState`
- 将 composition 时的输入清除替换为 effect 或由调用方拥有的 state 转换
- 将 onboarding effect 收集器的 key 改为 `effects`，而非 `Unit`

**验证**:
- 针对 capsule 转换的 Compose 测试：Hidden->WaitingForInput 恰好清除一次输入；WaitingForInput->Running 不会重复清除无关的草稿文本
- 手动验证：overlay 输入模式下无重复的焦点/输入闪烁

---

## Phase 2: 提升 Settings 导航和 Auth State
**优先级**: P0

**目标文件**:
- `settings/SettingsSheet.kt`
- `settings/LlmAuthSettingsPage.kt`

**任务**:
- 引入显式的 settings 导航/auth state（提升或正确使用 `rememberSaveable` 作为 key）
- 移除 `selectedTab` 和 `selectedProvider` 的"从外部 state 初始化一次"模式
- 将探索性的 tab/provider 选择与已提交的 backend/auth mutation 分离（点击 tab 不应立即触发 `onBackendChange`/`onAuthMethodChange`）

**验证**:
- 在 OAuth/API key/local 之间切换
- 外部更改已选模型后重新打开 settings — 显示的 tab/provider 正确
- 在嵌套 settings 页面旋转屏幕 — state 保留
- 从 OAuth 返回 — 显示正确的 tab

---

## Phase 3: 修复聊天滚动行为并简化聊天 State
**优先级**: P1

**目标文件**:
- `chat/ChatScreen.kt`
- `chat/ChatViewModel.kt`
- `chat/ChatEventReducer.kt`
- `chat/components/MessageBubble.kt`
- `chat/components/ActionCard.kt`

**任务**:
- 将"messages.size 变化时滚动"替换为显式的吸底策略
- 在自动滚动前跟踪用户是否在底部附近
- 当用户正在跟读对话时，在最后一条消息增长时滚动
- 当用户已向上滚动且有新内容到达时，显示"滚动到底部"FAB
- 将 MessageBubble 中的 `SimpleDateFormat` 替换为顶层的 `DateTimeFormatter`（线程安全）
- 移除 ActionCard 中 `CircularProgressIndicator` 外层冗余的 `infiniteTransition` 旋转

**验证**:
- 超过一屏的长流式响应 — 视口跟随
- 流式传输中途添加 action card — 视口跟随
- agent 继续运行时用户向上滚动 — 不会被拉回底部，FAB 出现
- 围绕滚动触发条件的 reducer/screen 测试

---

## Phase 3.5: Session 删除确认
**优先级**: P1

**目标文件**: `navigation/NavigationDrawer.kt`

**任务**:
- 在 session 删除前添加确认对话框
- AlertDialog："删除此会话？此操作不可撤销。" 配合 [取消] [删除] 按钮

**验证**:
- 点击删除 -> 对话框出现 -> 取消返回列表
- 点击删除 -> 对话框出现 -> 删除移除 session
- 没有因误触导致的意外删除

**预估工作量**: 新增约 15 行。

---

## Phase 4: 统一 Capsule/Overlay State 所有权
**优先级**: P1

**目标文件**:
- `overlay/CapsuleStateHolder.kt`
- `overlay/compose/CapsuleOverlayHost.kt`
- `overlay/compose/IslandOverlayHost.kt`

**任务**:
- 定义一个权威的 capsule UI state 契约
- 保持 CapsuleStateHolder 作为 mode/context/platform/island state 的唯一所有者
- 移除 CapsuleOverlayHost 中重复的 render-input flow
- Host 仅负责：显示/隐藏窗口、focusability、touchability、pass-through

**验证**:
- 手动矩阵测试：主 app、accessibility overlay、viewer 打开/关闭、background/island 转换、所有 capsule mode（waiting-for-input、waiting-for-approval、running、done、error）

---

## Phase 5: Accessibility 加固
**优先级**: P1

**目标文件**:
- `onboarding/OnboardingShell.kt`
- `capsule/surface/SmartCapsuleSurfaceParts.kt`
- `overlay/compose/StatusIslandCompose.kt`
- Settings/navigation/chat 的可点击行

**任务**:
- 将 icon+`clickable` 模式替换为合适的 button 原语（如 `IconButton`）
- 为每个仅图标的操作添加 `contentDescription`
- 为自定义 clickable surface 添加 semantics/role
- 审查返回、导航、删除和 overlay 控件的触摸目标

**验证**:
- TalkBack 通过测试：onboarding、settings、chat header/drawer、capsule overlay 和 island

---

## Phase 6: Theme 和 Token 清理
**优先级**: P2

**目标文件**:
- `theme/Color.kt`
- `theme/Shape.kt`
- `overlay/compose/StatusIslandCompose.kt`

**任务**:
- 移除未使用的"通用"color token 集合（Color.kt 中约第 12-59 行）。`Chat*` 变体为权威版本。
- 删除未被引用的 shape token
- 使 island/overlay surface 消费权威的 theme token，而非硬编码颜色
- 标准化哪些 token 是 public 的，哪些是实现细节

**验证**:
- 在 light 和 dark 主题下对 chat、settings、onboarding 和 overlay 进行截图对比

---

## Phase 7: 拆分大型 UI 文件
**优先级**: P2

**目标文件**: `onboarding/OnboardingSteps.kt` (735 行)

**任务**:
- 拆分为：PermissionStepContent、ApiKeyStepContent、DemoStepContent、CompleteStepContent、共享的 onboarding 原语/文案
- 每个文件保持在 400 行以内

**验证**:
- onboarding 流程无功能变化
- 每个步骤可以有更简单的 preview/test

---

## Phase 8: State 保留和资源规范化
**优先级**: P3

**目标文件**: Settings、onboarding、capsule input、chat 叶子 state

**任务**:
- 在本地 state 需要在配置更改后保留时添加 `rememberSaveable`
- 将 `PerceptionMode` 的原始字符串转换为 typed enum
- 移除重复的版本显示（仅保留 SettingsHomePage 上的）
- 开始将面向用户的字符串提取到 resource 中
- 标准化时间格式化工具并移除临时的 `SimpleDateFormat` 使用

**验证**:
- 在 settings、onboarding 和 chat 上进行旋转/重建检查

---

## 非建议事项

以下内容被明确排除在本改进计划之外：

| 事项 | 原因 |
|------|------|
| 不要将 overlay 专属的 dark-mode 工作作为优先事项引入 | 保持 overlay 以对比度为先；通过 Phase 6 将其迁移到权威 token |
| 不要移除 AppWindowInsets wrapper | 零运行时开销下增加了文档价值 |
| 不要将 ActionCard 的 expand state 提升到 ViewModel | 临时的 UI state 正确地限定在 composition 范围内 |
| 在简化 chat state 所有权之前不要为拆分而拆分 ChatViewModel | 类边界是合理的；问题在于分裂的 state 表示（StateFlow + SnapshotStateList + StringBuilder + lock），在 Phase 3 中解决 |
| 不要添加更多 settings 动画 | AnimatedContent slide 过渡已存在且足够 |

---

## 执行总结

```
Phase 1 (P0) ─── 共享 surface 的 Compose 正确性
Phase 2 (P0) ─── Settings state 所有权
                    |
Phase 3 (P1) ─── 聊天滚动 + 聊天清理
Phase 3.5 (P1) ── Session 删除确认
Phase 4 (P1) ─── Overlay state 统一
Phase 5 (P1) ─── Accessibility 加固
                    |
Phase 6 (P2) ─── Theme token 清理
Phase 7 (P2) ─── 文件拆分
                    |
Phase 8 (P3) ─── State 保留 + 资源规范化
```

**关键路径**: Phase 1 和 Phase 2 应在任何新的 capsule 或 settings 功能开发之前完成。

**最小可行改进**: 如果必须最小化实施工作量，Phase 1 + Phase 2 能带来最大的即时质量提升。
