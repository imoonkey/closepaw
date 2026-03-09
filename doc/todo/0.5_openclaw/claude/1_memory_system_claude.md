# 借鉴点 1: Memory 系统 — Retain / Recall / Reflect

## OpenClaw 怎么做的

OpenClaw 的 Memory 不是简单的 "把聊天记录存下来"，而是一套三阶段记忆架构：

1. **Retain（留存）**: 每天自动生成 `memory/YYYY-MM-DD.md` 日志，记录当天交互中的关键事实
2. **Recall（回忆）**: 将事实条目索引到 SQLite FTS（全文搜索），可选嵌入向量
3. **Reflect（反思）**: 从事实中提炼出 bank/entities/*.md — 实体页面、世界知识、经验、观点

关键设计：
- 全部基于 Markdown，人类可读、Git 友好
- 每条事实都有 source 归因（文件 + 行号）
- 观点有置信度标记 + 证据指向
- 同一时间只有一个 memory plugin 活跃（避免冲突）
- 本地优先，不依赖云端向量数据库

## 为什么值得借鉴

Android Agent 目前的 session history 是线性的、一次性的。任务结束了，上下文就丢了。

但真正有用的 agent 需要跨任务积累知识：
- 用户偏好（"我习惯用支付宝付款"、"我不看推送通知"）
- App 操作经验（"微信的发送按钮在 xxx 位置"、"这个 App 需要先同意弹窗"）
- 失败教训（"长按这个元素会触发删除而不是编辑"）
- 设备状态知识（"这台设备的 DPI 是 420"、"暗色模式下文字颜色不同"）

## 可落地的最小方案

不需要完整复制三阶段，先做最小有用的一层：

### Phase 1: App 操作经验存储
```
memory/
├── apps/
│   ├── wechat.md          # 微信的操作经验
│   ├── alipay.md          # 支付宝的操作经验
│   └── settings.md        # 系统设置的操作经验
├── user_prefs.md          # 用户偏好
└── device.md              # 设备特征
```

每次任务成功/失败后，提取关键经验写入对应文件。

### Phase 2: 任务规划时自动 recall
在 agent 开始新任务前，根据目标 App 和任务类型，自动加载相关 memory 到 context。

### 关键原则
- Markdown 存储，不引入额外依赖
- 只存泛化经验，不存具体操作流水
- 由 LLM 判断什么值得记住，不做规则抽取
- 文件按 App 组织，不按时间 — 因为我们的核心实体是 App，不是日期
