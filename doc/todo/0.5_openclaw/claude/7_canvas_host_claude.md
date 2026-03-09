# 借鉴点 7: Canvas Host — Agent 驱动的交互式 UI

## OpenClaw 怎么做的

OpenClaw 在设备上嵌入一个轻量 Web 服务，Agent 可以推送 HTML/JS 内容：

```
Gateway → A2UI push { html: "<div>...", js: "..." }
  → Android WebView 渲染
  → 用户交互 → JS bridge 回传事件给 Agent
```

这不是 "在 App 里显示网页"，而是 Agent 自己生成 UI 来和用户交互。

用途：
- Agent 需要用户从多个选项中选择
- Agent 需要展示结构化数据（表格、卡片）
- Agent 需要多步表单收集信息
- 比纯文字聊天 UI 更高效的交互场景

## 为什么值得借鉴

Android Agent 目前和用户的交互只有两种：
1. 文字聊天（慢、信息密度低）
2. 直接操作屏幕（用户无法干预细节）

缺少中间态：Agent 把决策选项呈现给用户，用户快速选择后 Agent 继续执行。

例如：
- "找到了 3 个航班，请选择" → 应该用卡片展示，不是念一段文字
- "要执行以下操作，确认吗？" → 应该有清晰的操作预览
- "搜索到以下结果" → 结构化展示比纯文字有效

## 可落地方案

### Phase 1: Rich Message 类型（最小方案）
不需要 WebView，先扩展聊天消息类型：

```kotlin
sealed class AgentMessage {
    data class Text(val content: String) : AgentMessage()
    data class Choice(val options: List<String>, val prompt: String) : AgentMessage()
    data class Confirmation(val action: String, val details: String) : AgentMessage()
    data class Summary(val title: String, val items: List<Pair<String, String>>) : AgentMessage()
}
```

在 Compose UI 中用原生组件渲染这些消息类型。

### Phase 2: Agent 结构化输出
让 Agent 在需要时输出结构化的交互请求，而不是纯文字：
```json
{
  "type": "user_choice",
  "prompt": "找到以下航班，请选择：",
  "options": [
    {"label": "CA1234 08:00", "value": "ca1234"},
    {"label": "MU5678 10:30", "value": "mu5678"}
  ]
}
```

### Phase 3: WebView Canvas（远期）
- 如果 rich message 不够用，再引入 WebView
- Agent 可以推送任意 HTML 进行复杂交互
- 需要 JS bridge 做双向通信

### 关键原则
- 先用原生 Compose 组件做 rich message，不要一上来引 WebView
- 目标是提升 agent ↔ 用户的交互效率，不是做通用 UI 框架
- 最常见的需求就三个：选择、确认、展示结构化信息
