# 借鉴点 5: 三轴安全模型

## OpenClaw 怎么做的

OpenClaw 不是一个 "permissions" 开关，而是三个正交维度：

### Axis 1: Sandbox — 在哪里执行
- `off` — 直接在宿主环境执行
- `non-main` — 非主会话走沙箱（Docker）
- `all` — 全部走沙箱

附加控制：
- scope: session / agent / shared（容器生命周期）
- workspace access: ro / rw（工作区读写权限）

### Axis 2: Tool Policy — 允许哪些工具
- 按工具名或工具组 allow/deny
- 工具组简写: `group:runtime`, `group:fs`, `group:sessions`
- 可以按 agent、按 session 单独配置

### Axis 3: Elevated — 如何逃逸
- 沙箱模式下的 "host exec" 应急通道
- 仅限高信任工作流
- 在 UI 上显式标记 "已提升权限"

三个维度独立配置，互不耦合。

## 为什么值得借鉴

Android Agent 目前的安全模型很简单：无障碍服务开了就全开了。

但随着能力扩展（shell tool、远程控制、消息入口），需要分层控制：
- 有些工具对任何任务都安全（截屏、读取界面）
- 有些工具需要确认（点击购买按钮、发送消息）
- 有些工具需要显式授权（shell 执行、文件删除）

## 可落地方案

### Phase 1: Tool 风险分级
```kotlin
enum class ToolRiskLevel {
    SAFE,       // 读取类：截屏、获取界面树、读取文本
    MODERATE,   // 操作类：点击、滑动、输入文字
    HIGH        // 副作用类：发送消息、支付确认、删除内容
}
```

- SAFE: 自动执行，无需确认
- MODERATE: 默认自动，用户可开启确认
- HIGH: 默认需要确认，用户可关闭（自担风险）

### Phase 2: 按任务上下文调整
- "打开设置" → 全部 SAFE/MODERATE，无需确认
- "给 xxx 发微信" → 发送动作提升为 HIGH，需确认内容
- "帮我付款" → 支付相关全部 HIGH

### Phase 3: 远程入口额外约束
- 本机操作: 使用用户配置的风险等级
- 远程入口: HIGH 工具强制需要确认，不可关闭
- 新设备: 首次连接需要在手机端批准

### 关键原则
- 三轴模型的核心不是 "更多开关"，而是 "正交解耦"
- 每个轴解决一个问题：在哪执行 / 能用什么 / 能否升级
- 对 Android Agent，Phase 1 的 tool 风险分级最实用，其余是远期
- 安全默认值要保守，宁可多确认一次，不要出事后才加限制
