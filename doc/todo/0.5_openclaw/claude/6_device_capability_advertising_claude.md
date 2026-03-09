# 借鉴点 6: 设备能力广播 (node.describe)

## OpenClaw 怎么做的

每个设备（node）连接 Gateway 时，主动广播自己的能力清单：

```typescript
node.describe → {
  commands: ["camera.snap", "location.get", "sms.send", ...],
  caps: ["a11y", "screen_capture", "mic", ...],
  platform: "android",
  version: "1.2.0"
}
```

Gateway 根据设备实际能力决定：
- Agent 可以使用哪些工具
- 哪些命令需要路由到特定设备
- 设备离线时哪些能力不可用

### InvokeDispatcher 模式
Android 端用一个统一的 dispatcher 路由所有工具调用：
```
Gateway → node.invoke(command, params)
  → InvokeDispatcher
    → CameraHandler / LocationHandler / SmsHandler / ...
    → 权限检查 → 前台检查 → 执行 → 返回结果
```

## 为什么值得借鉴

Android Agent 目前的 tool 定义是静态的 — 代码里写了什么就有什么。

但实际情况是：
- 不是所有设备都开启了无障碍服务
- 不是所有设备都授予了截图权限
- 部分设备可能没有摄像头
- 未来可能支持不同的 tool 插件

如果 agent 不知道当前设备的真实能力，就会尝试调用不可用的工具，白白浪费步骤。

## 可落地方案

### Phase 1: 动态 Tool 注册
```kotlin
interface ToolProvider {
    fun availableTools(): List<ToolDefinition>
    fun isAvailable(): Boolean  // 当前是否可用
}

// 启动时收集所有可用工具
val tools = toolProviders
    .filter { it.isAvailable() }
    .flatMap { it.availableTools() }
```

好处：
- Agent 的 tool 列表反映真实能力，不会出现 "工具存在但执行失败" 的情况
- 新增工具只需实现 ToolProvider 接口，不改核心代码
- 为 skill/plugin 系统打基础

### Phase 2: 能力变化通知
- 用户运行时授予/撤销权限 → 动态更新可用 tool 列表
- 无障碍服务断开 → 移除依赖 a11y 的工具
- 前台/后台切换 → 部分工具可用性变化

### Phase 3: 能力广播给外部控制面
- Web 控制台显示当前设备的实际能力
- 远程入口根据设备能力过滤可用命令

### 关键原则
- Tool 注册应该是动态的、运行时的，不是编译时静态的
- 每个 Tool 自己负责报告可用性，不由中心逻辑判断
- 这是 plugin/skill 系统的地基 — 先做好这个，后面扩展自然
