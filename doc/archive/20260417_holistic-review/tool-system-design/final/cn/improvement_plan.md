# Tool System 改进计划 — 最终版

**日期:** 2026-04-08
**共识方:** Claude + Codex（双重设计对齐）
**基础:** 以 Codex 的架构方向为主，融合 Claude 的实现层面发现

---

## 指导原则

- KISS 优先于框架膨胀
- 能力来自 tool 定义，而非并行 enum
- 每次屏幕截取都经过同一个 gate
- 明确指定的目标应当明确失败
- 取消在所有工具中语义一致

---

## Phase 0: 保障 Observation 边界（Critical）

### 目标

使"blocked 应用被遮罩和拒绝"在每个 tool 层截取点都为真。

### 变更

1. 在 `tool/` 下创建 `ScreenCaptureGate`（或 `SnapshotGate`）：
   - 封装 `platform.captureScreen()`
   - 在截取时读取当前前台 package
   - 应用 `AppClassifier.maskIfBlocked()`
   - 返回净化后的 snapshot
   - 不持有重试逻辑或 observation 构建逻辑

2. 将 `tool/` 中所有直接调用 `captureScreen()` 的地方替换为通过该 gate 调用：
   - `OpenAppTool` 启动后截取
   - `UIActionInvocation` action 后截取
   - `PostActionAnalysis` 重试截取（PostActionAnalysis 每次重试调用 gate）
   - `ToolRouter` post-approval 刷新

3. 使 `open_app` 具备目标感知能力：
   - 在执行前解析目标 package
   - 根据目标分级重新检查 policy
   - 如果解析只能在执行内部完成，在 `launchApp()` 之前进行内部 policy 重新检查

### 验收测试

- 从 NORMAL 到 BLOCKED 的 `open_app` 在启动前被拒绝
- 任何落在 BLOCKED 应用上的 action 返回遮罩后的 observation
- `tool/` 中 gate 之外没有原始 `captureScreen()` 调用

### 涉及文件

`ToolRouter.kt`, `OpenAppTool.kt`, `UIActionInvocation.kt`, `PostActionAnalysis.kt`, 新增 `ScreenCaptureGate.kt`

---

## Phase 1: 将能力元数据迁移到 ToolSpec 上（High）

### Phase 1a: 即时止血

为 `ask_user` 和 `shell` 补充 `ToolName`：

```kotlin
// ToolName.kt
data object AskUser : ToolName(raw = "ask_user", canonical = "ask_user", displayName = "Ask user")
data object Shell : ToolName(raw = "shell", canonical = "shell", displayName = "Shell")

// isScreenChanging:
CompleteTask, WriteTodos, Scratchpad, RememberExperience, AskUser, Shell -> false
```

**工作量:** 15 分钟。立即消除错误的审批提示。

### Phase 1b: 元数据迁移

在 `ToolSpec` 上添加最小元数据：

```kotlin
interface ToolSpec {
    // existing members...
    val isScreenChanging: Boolean get() = true   // safe default
    val capturesScreen: Boolean get() = false
    val mayLaunchApp: Boolean get() = false
}
```

每个工具声明自身的元数据。

在 session 启动时从已注册工具构建 `ToolCapabilitiesResolver`。注入到：
- `PolicyEngine`（替换 `ToolName.isScreenChanging`）
- `TurnToolPolicy`（替换 `ToolName` 查询）
- `ActionSignature`（替换 `ToolName` 查询）

暂时保留 `ToolName` 仅用于显示/UI。移除其上的行为查询。

**为何保持最小:** 只有 3 个布尔值有实际消费者。等到有真实调用者需要时再添加更多字段。

### 涉及文件

`ToolName.kt`, `ToolSpec.kt`, `PolicyEngine.kt`, `TurnToolPolicy.kt`, `ActionSignature.kt`, 所有 `tool/impl/*.kt`, 新增 `ToolCapabilitiesResolver.kt`

---

## Phase 2: 规范化 Action 运行时（Medium）

### 变更

1. **取消语义一致性:**
   - `SwipeExecutor`: 将平台取消映射为 `Cancelled`，而非 `Failed`
   - `TypeExecutor`: 在 direct-set、tap-to-focus 和 focused-set 路径中保持 `Cancelled` 传播

2. **明确目标的 scroll:**
   - 当调用方指定了 `element_index` 或 `text` 但解析失败时，返回错误
   - 只有无目标的 scroll 才可使用全屏边界

3. **重定向可观测性:**
   - 当 `refinePointActionTarget()` 提升到容器或附近子元素时，在 attempt trail 中包含备注
   - 默认保持重定向启用（解决真实 Android UI 模式）

### 涉及文件

`SwipeExecutor.kt`, `TypeExecutor.kt`, `ScrollExecutor.kt`, `PointActionExecutorCore.kt`

---

## Phase 3: Shell 加固（Medium）

Shell 已确认在生产中使用（`StandaloneAgentDef.allowedTools`，standalone prompt rule 9）。

### 变更

1. **加固 blocklist:** 将 `env`、`xargs`、`find`（因 `-exec`）添加到被阻止的命令中
2. **截断指示器:** 当输出超过 `MAX_OUTPUT_CHARS` 时，追加 `\n[output truncated at N chars]`
3. **度量使用情况:** 在 eval/debug 运行中跟踪 shell 调用及命令

### 未来方向（不在本计划内）

- 构建有类型的替代工具（`read_file`、`list_dir`、`stat_path`）
- 在替代工具验证后对 `shell` 进行 feature-gate
- 此后才考虑移除

### 涉及文件

`ShellTool.kt`

---

## Phase 4: Router 契约收紧（Low）

### 变更

方案 A（推荐）: 持有 per-call 取消令牌，驱动它们贯穿执行流程。
方案 B（更简单）: 将 `cancel()`/`cancelAll()` 重命名为 `abortPendingApproval()`/`abortAllPendingApprovals()`。

添加测试：
- 取消一个正在执行的工具
- 取消在 type/swipe 中的传播
- Approval 中止 vs 执行中止

### 涉及文件

`ToolRouter.kt`, `SimpleToolRouterContext.kt`, 测试文件

---

## Phase 5: 批量清理（Low）

1. 移除死代码 `UIActionInvocation.detectScrollBoundary()` 和 `UiChangeDetector.detectScrollBoundary()`
2. 移除 `PolicyEngine.isEscape()` 中死掉的 `mobile_action(back/home)` escape 路径。评估移除残留的 `MobileActionName` 条目（Back, Home, Wait, SystemButton）
3. 移除 `OpenAppTool` companion 中的重复常量（`UI_SETTLE_DELAY_MS`, `SUGGESTION_LIMIT`）
4. 将 `SystemButtonTool` 的不可达分支改为 `error("Unreachable: validated in validate()")`
5. 如无调用者，移除 `DataQueryInvocation`
6. 移除 `ActionPriorityOrder` 代码注释中的 `doc/todo/...` 引用

### 涉及文件

`UIActionInvocation.kt`, `UiChangeDetector.kt`, `PolicyEngine.kt`, `ToolName.kt`, `OpenAppTool.kt`, `SystemButtonTool.kt`, `DataQueryInvocation.kt`, `ActionPriorityOrder.kt`

---

## 总结

| Phase | 优先级 | 范围 | 关键指标 |
|-------|--------|------|----------|
| 0 | Critical | 安全边界 | BLOCKED 截取零泄露 |
| 1a | High | 止血 | ask_user/shell 分类正确 |
| 1b | High | 元数据 | ToolName 行为查询归零 |
| 2 | Medium | Action 运行时 | 取消语义一致 + 明确 scroll 失败 |
| 3 | Medium | Shell | 加固 blocklist + 截断指示器 |
| 4 | Low | Router | Cancel API 匹配实际作用域 |
| 5 | Low | 清理 | 约 60 行死代码移除 |

**每个 phase 可独立交付。** Phase 0 是最高优先级 — 它解决了唯一的关键设计缺陷。
