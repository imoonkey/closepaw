# 借鉴点 4: Agent Identity & Personality 模板

## OpenClaw 怎么做的

OpenClaw 用一组 `.dev.md` 模板定义 agent 的完整人格：

### IDENTITY.dev.md — 身份定义
- 名字、性别、语言风格
- 背景设定和角色定位
- 对不同用户的态度差异

### SOUL.dev.md — 行为灵魂
- 核心价值观和做事原则
- 遇到冲突时的优先级
- 什么该做什么不该做

### TOOLS.dev.md — 工具使用规范
- 每个工具的使用场景和限制
- 工具选择的优先级逻辑
- 常见误用和正确做法

### USER.dev.md — 用户画像
- 用户是谁、技术水平、使用习惯
- 用户的期望和容忍度
- 与用户沟通的基调

### AGENTS.dev.md — 协作规范
- 多 agent 之间的分工
- 任务委派规则
- 状态同步机制

## 为什么值得借鉴

Android Agent 现在的 system prompt 是写在代码里的硬编码字符串。问题：
- 修改需要改代码 + 重新编译
- 没有结构化，所有规则混在一起
- 用户无法自定义 agent 行为风格
- 不同任务类型无法用不同的 persona

## 可落地方案

### Phase 1: 提取 system prompt 到文件
```
config/
├── identity.md     # agent 的基本身份和沟通风格
├── tools.md        # 工具使用规范（可从现有代码提取）
└── rules.md        # 行为准则和安全边界
```

好处：
- 不改代码就能调 prompt
- eval 时可以方便地 A/B 测试不同 prompt 版本
- 用户可以覆盖默认配置

### Phase 2: 用户自定义 Persona
- 提供几个预设: "高效执行"、"详细解释"、"谨慎确认"
- 用户可以在 App 设置里选择或自写
- 对应不同的 system prompt 组合

### Phase 3: 任务特化 Persona
- 购物任务 → 注重价格比较、确认金额
- 社交任务 → 注重隐私、确认发送内容
- 系统设置 → 注重安全、避免误操作

### 关键原则
- 最小改动是把 system prompt 从代码移到资源文件
- 不要过度设计 persona 系统，先做到"可配置"就够了
- TOOLS.dev.md 的思路最有实操价值 — 工具使用规范单独维护，独立于人格设定
