# Holistic Review 阅读导读

本次 review 覆盖 12 个维度、24 份文档（每个维度 review.md + improvement_plan.md），由 Claude 和 Codex 独立审查后 cross-review 对齐产出。以下按优先级排序，帮助你高效消化。

---

## 总览

| 优先级 | 维度 | P0 | P1 | P2 | 预估工作量 | 状态 | 一句话 |
|--------|------|----|----|----|-----------|----|------|
| 1 | [Security & Privacy](#1-security--privacy) | 4 | 4 | 4 | 3-4 周 | **DONE** | 控制面漏洞 + privacy gate 位置错误，阻塞发布 |
| 2 | [Agent Core Simplicity](#2-agent-core-simplicity) | 1 | 2 | 1 | 2-3 周 | **DONE** | runtime 违反 one-screen-action invariant |
| 3 | [Platform Robustness](#3-platform-robustness) | 2 | 5 | 5 | 4-5 周 | **DONE** | 无界 callback hang + VD lifecycle 竞态 |
| 4 | [Tool System Design](#4-tool-system-design) | 1 | 3 | 1 | 2 周 | **DONE** | security gate 未端到端 + shell 注入 |
| 5 | [LLM Integration](#5-llm-integration) | 5 | 3 | 6 | 3-4 周 | **DONE** | streaming 正确性 bug：静默截断、retry 丢失 |
| 6 | [State & Concurrency](#6-state--concurrency) | 1 | 4 | 2 | 3 周 | **DONE** | 持久化写入乱序导致数据丢失风险 |
| 7 | [UI/UX Quality](#7-uiux-quality) | 2 | 4 | 2 | 2-3 周 | **DONE** | Compose composition 阶段副作用 |
| 8 | [Performance & Resources](#8-performance--resources) | 2 | 2 | 3 | 2 周 | R8 未启用 + O(n^2) 压缩/截断 |
| 9 | [Error & Resilience](#9-error--resilience) | 4 | 8 | 5 | 3-4 周 | 完成判定不依赖执行结果 + approval 吞错 |
| 10 | [Test Architecture](#10-test-architecture) | 0 | 6 | 3 | 3 周 | LLM wire format/retry 零测试覆盖 |
| 11 | [Protocol & Communication](#11-protocol--communication) | 0 | 2 | 3 | 1-2 周 | ~257 行 dead event 类型可删 |
| 12 | [Dead Code & Over-Abstraction](#12-dead-code--over-abstraction) | 0 | 3 | 2 | 1-2 周 | ~600 行确认 dead code 可删 |

**P0 总计: 22 项 | P1 总计: 46 项 | 总工作量估算: 4-5 个月**

---

## 推荐阅读路线

### 第一梯队：必须立即读（影响正确性和安全性）

#### 1. Security & Privacy
**路径**: `security-privacy/final/` | [中文版](security-privacy/final/cn/)

最高优先级。4 个 P0 发现：
- **Intent 注入**: exported launcher Activity 接受未验证的 API key / base URL / mode extras，任何 app 可篡改
- **Privacy gate 位置错误**: blocked app 的屏幕数据在 capture 之后才过滤，raw artifacts 和 observation 已泄露到 cloud LLM
- **加密存储 fail-open**: EncryptedSharedPreferences 失败时静默降级为明文 SharedPreferences
- **Accessibility 数据未脱敏**: 密码、OTP、联系人、金额等字段直接发送给 cloud LLM

**核心主题**: boundary 放在 capture 之前而非之后；fail-closed 而非 fail-open。

#### 2. Agent Core Simplicity
**路径**: `agent-core-simplicity/final/` | [中文版](agent-core-simplicity/final/cn/)

核心循环正确性问题：
- **P0**: runtime 实际允许多个 screen-changing action per turn，违反了代码文档声明的 one-action invariant，导致 tool arbitration、snapshot chaining、loop detection 级联 bug
- AgentDef 和 AgentDefinition 双重角色定义系统并存
- ExecutorStepPolicy 混合三种无关关注点 + 包含 dead WarnApproaching state

**核心主题**: 在 runtime boundary 强制 invariant；统一角色定义。

#### 3. Platform Robustness
**路径**: `platform-robustness/final/` | [中文版](platform-robustness/final/cn/)

会话可靠性基础：
- **P0**: callback-driven capture 路径无超时上限——framework callback 丢失将永久阻塞 session
- **P0**: VD lifecycle 状态分散在 display/reader/surface 三处，无统一序列化 owner
- Binder death 仅 log 不清理 proxy，Shizuku 重启后 dead wrapper 残留
- Window 选择偏向最低层而非最顶层，dialog/popup 导致 screenshot 与 tree 不一致

**核心主题**: 统一 lifecycle arbiter；bounded callback helper；显式 window 选择。

---

### 第二梯队：尽快阅读（影响可靠性）

#### 4. Tool System Design
**路径**: `tool-system-design/final/` | [中文版](tool-system-design/final/cn/)

- **P0**: blocked app boundary 未端到端执行——tool observation 路径中 raw capture 仍可泄露（依赖 Security P0.2）
- `ask_user` 和 `shell` 不在 ToolName enum 中，默认为 Unknown（isScreenChanging = true），导致误触 approval
- Shell validator 只检查第一个 token，`; | &` 可绕过
- Explicit-target scroll 在 resolution 失败时静默降级为全屏滚动

#### 5. LLM Integration
**路径**: `llm-integration/final/` | [中文版](llm-integration/final/cn/)

5 个 P0 streaming 正确性 bug：
- Streaming completion 逻辑分散在四个 client + retry runner + policy + parser + classifier 中
- Domain exception (RateLimitException) 在 retry path 中被降级为 RuntimeException
- Created event 过早阻断 retry；metadata event 不应阻止恢复
- Codex `response.incomplete` 映射为 Completed 而非 Failed
- ChatCompletionClient 在没有 terminal finish_reason 时也 emit Completed

#### 6. State & Concurrency
**路径**: `state-concurrency/final/` | [中文版](state-concurrency/final/cn/)

- **P0**: SessionRecordingService 写入可乱序完成，旧 snapshot 覆盖新 snapshot——数据丢失
- Takeover/pause state machine 违反合约：SessionResumed 可能在 SessionTakeover 之前 emit
- AgentSession lifecycle 跨 suspend point 未序列化——completion 可与 shutdown 竞态
- ToolRouter cancel/cancelAll 只清理 bookkeeping，不实际 signal 执行中的 tool

---

### 第三梯队：按需阅读（质量与效率提升）

#### 7. UI/UX Quality
**路径**: `ui-ux-quality/final/` | [中文版](ui-ux-quality/final/cn/)

- **P0**: SmartCapsuleSurface 在 composition 阶段写入 state（previousModeState、inputText）
- **P0**: Settings tab/provider state 与 app state 脱节，初始化仅一次，config 变更后不同步
- Chat auto-scroll 只监听 message count 变化，streaming 增长时用户看不到新内容
- Capsule 状态控制权分散在 CapsuleStateHolder 和 CapsuleOverlayHost 之间

#### 8. Performance & Resources
**路径**: `performance-resources/final/` | [中文版](performance-resources/final/cn/)

高 ROI 快速修复：
- **P0**: Release build 未启用 R8 minification——20-40% APK 体积浪费
- **P0**: Perceptor 两次完整 DFS 遍历 accessibility tree（INTERACTIVE_ONLY + ALL）
- History compression O(n^2)：每次 item 移除都清空 token cache 触发全量重算
- Truncation 中 `indexOf` 做线性扫描，data-class `equals()` 开销大

#### 9. Error & Resilience
**路径**: `error-resilience/final/` | [中文版](error-resilience/final/cn/)

4 个 P0 核心循环 bug：
- Task completion 判定不依赖 complete_task 是否实际执行——tool failure 后仍声明完成
- Approval notification 失败被吞掉，重新标记为 user timeout（60s）
- AgentError protocol（11 variant）定义了但完全 dead——所有 error 在到达 session 层之前降级为 string
- `ask_user` 被分类为 screen-changing，在 blocked app 中请求帮助也需要 approval

---

### 第四梯队：收尾清理（低风险高收益）

#### 10. Test Architecture
**路径**: `test-architecture/final/` | [中文版](test-architecture/final/cn/)

- LLM wire-format、parser、retry stack 零测试——最高风险外部边界
- Safety tool（shell、ask_user）零直接测试覆盖
- Service/session 编排层（AgentService、SessionCoordinator、SessionAgentRunner）无测试
- ~530 行重复 test helper 可整合为 shared fixtures

#### 11. Protocol & Communication
**路径**: `protocol-communication/final/` | [中文版](protocol-communication/final/cn/)

协议层 dead weight 清理：
- AgentEventDomains（12 个 marker interface）无 consumer 使用，纯 overhead
- AgentError.kt（11 variant，~170 行）从未实例化
- CompletionReason 混合 task outcome 和 session shutdown reason，存在 impossible state
- SessionConfig 将 execution/model/platform/observability/eval 混在一个 flat object 中

#### 12. Dead Code & Over-Abstraction
**路径**: `dead-code-overabstraction/final/` | [中文版](dead-code-overabstraction/final/cn/)

安全的减法操作：
- 4 个完整 dead file 可删除（StatusUtils、DataQueryInvocation、SessionServicesSummaryFormatter 等）
- 17+ 个 dead method/field/parameter
- 2 个 single-implementation interface 可折叠（OnboardingDemoController、LlmCredentialValidator）
- Sub-agent catalog 只注册一个 executor，但维持了完整 marketplace 机制
- 预计删除 ~600 行，零行为变更

---

## 交叉发现（Cross-Cutting Themes）

这些主题在多个维度反复出现：

1. **Boundary 位置**: Privacy gate、security gate、error classification 都应在数据进入管道之前执行，而非之后补救（Security、Tool、Error 共同发现）

2. **Dead AgentError**: Security、Protocol、Dead Code、Error 四个维度独立确认 `AgentError.kt` 170 行完全 dead——最高置信度删除目标

3. **ToolCallState 废弃**: Dead Code 和 Tool System 均确认 `ToolCallState.kt`（115 行 7-state 状态机）被 ToolRouter 完全绕过

4. **Fail-closed vs fail-open**: 加密存储、approval、app classification 都存在 fail-open 倾向，应统一为 fail-closed

5. **单写者原则**: SessionRecordingService、HistoryManager、VD lifecycle 都需要序列化写入，避免竞态

---

## 执行建议

### Phase 1: 安全与正确性（2-3 周）
- Security P0.1-P0.4（intent 验证、privacy gate 前置、fail-closed 存储、数据脱敏）
- Dead Code Phase 1（AgentError.kt、ToolCallState.kt、其他确认 dead file 删除——安全、快速、减轻后续 review 负担）

### Phase 2: 核心循环稳定（2-3 周）
- Agent Core P0（one-screen-action invariant 强制执行）
- LLM Integration P0（streaming completion 正确性修复）
- Error Resilience P0（completion 判定修复、approval 错误处理）

### Phase 3: 平台可靠性（3-4 周）
- Platform Robustness P0-P1（VD lifecycle 统一、bounded callback、window 选择）
- State & Concurrency P0（SessionRecordingService 写入序列化）

### Phase 4: 质量提升（2-3 周）
- Performance 快速修复（R8 启用、O(n^2) 修复）
- UI/UX P0（composition side-effect 修复）
- Test 基础设施整合 + 关键 boundary 测试补充

### Phase 5: 协议清理（1-2 周）
- Protocol dead type 删除（~257 行）
- Tool/Agent 剩余 dead code 清理
- SessionConfig 拆分

---

## 文件索引

```
doc/todo/holistic-review/
  read-guide.md                          <-- 你在这里
  security-privacy/final/                 review.md | improvement_plan.md | cn/
  agent-core-simplicity/final/            review.md | improvement_plan.md | cn/
  platform-robustness/final/              review.md | improvement_plan.md | cn/
  tool-system-design/final/               review.md | improvement_plan.md | cn/
  llm-integration/final/                  review.md | improvement_plan.md | cn/
  state-concurrency/final/                review.md | improvement_plan.md | cn/
  ui-ux-quality/final/                    review.md | improvement_plan.md | cn/
  performance-resources/final/            review.md | improvement_plan.md | cn/
  error-resilience/final/                 review.md | improvement_plan.md | cn/
  test-architecture/final/                review.md | improvement_plan.md | cn/
  protocol-communication/final/           review.md | improvement_plan.md | cn/
  dead-code-overabstraction/final/        review.md | improvement_plan.md | cn/
```

每个 aspect 下还有 `initial/`（Claude + Codex 独立审查）和 `discussion/`（cross-review 对齐过程），供追溯审查逻辑。
