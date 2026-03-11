# Agent 身份与人格：实现计划

## Phase 1：抽取与组合

目标：在不改变默认 profile 实际行为的前提下，先把新架构上线。

步骤：

1. 新增 `agent_contracts/` assets，把当前 standalone / planner / executor prompt 文本拆成有序文件。
2. 增加一个内建 identity profile：`balanced`。
3. 引入：
   - `AgentContractRepository`
   - `AgentIdentityRepository`
   - `AgentInstructionComposer`
4. 用 `promptRole` 替换 `AgentDef.systemPrompt`。
5. 更新 `SessionAgentRunner`，让它通过 role + identity + device environment 组合 instructions。
6. 更新 delegated executor 启动逻辑，让 executor instructions 也从相同的 `identityProfileId` 解析。

验收标准：

- 默认 `balanced` profile 生成的 prompt 与现有行为基本等价
- tool list ownership 不变
- app skill injection 不变

## Phase 2：设置与可观测性

目标：让 identity selection 成为真正的 session 功能，而不是隐藏默认值。

步骤：

1. 在 `AppSettingsStore` 中加入 `identityProfileId`。
2. 在 `SessionConfig` 中加入 `identityProfileId`。
3. 增加 preset 选择的 settings UI。
4. 在 trace / debug 输出中记录所选 identity profile id。
5. 非法 profile id 请求时，显式记录 fallback。

验收标准：

- profile 选择能跨 app 重启持久化
- 新 session 会使用选中的 profile
- traces 能显示实际使用的 identity

## Phase 3：校验与测试

目标：让文件化 prompt 加载保持可预测、可审计。

步骤：

1. 为 repository loading 与排序写单测。
2. 为 identity profile 校验写单测：
   - 必需文件
   - 可选 role addenda
   - 长度边界
   - 非法 profile fallback
3. 为 planner / executor inheritance 写单测。
4. 对 `balanced` profile 下每个 role 的 composed prompt 做 snapshot test。

验收标准：

- 非法或不完整的 profile 会干净失败
- prompt composition 是确定性的
- executor 收到与 planner 相同的 session identity

## Phase 4：可选的产品后续工作

目标：在不破坏 ownership model 的前提下扩展 personality 选项。

可选工作：

1. 增加 `efficient` 与 `careful` presets。
2. 支持带校验的自定义 identity 导入。
3. 增加用于比较不同 identity preset 的 eval 支持。

护栏：

- 自定义 identity 只能改 persona，不能改 contract
- 除非有独立证据支持，否则不要做任务 / app 级自动 persona 切换

## 需要关注的风险

1. 抽取过程中的 prompt drift。
   - 缓解：对默认 profile 做旧 prompt 与新组合 prompt 的 snapshot 对比。

2. contract 与 identity 之间的边界侵蚀。
   - 缓解：保持独立 asset 根目录与明确校验规则。

3. planner / executor 不一致。
   - 缓解：两者统一从 `SessionConfig.identityProfileId` 解析。

4. 静默 fallback bug。
   - 缓解：对 fallback 行为做显式日志与 trace 记录。
