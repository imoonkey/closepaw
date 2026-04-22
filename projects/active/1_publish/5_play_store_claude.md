# 5. Play Store - 商店上架与审核准备

## 现状

- 无 Play Store 截图/feature graphic
- 无应用描述文案
- 无隐私政策 URL（Play Store 要求填写 URL）
- 无 Accessibility Service 审核材料
- 无 release signing（见 2_release_build_claude.md）

## 前置依赖

- 1_legal_claude.md — LICENSE 确定
- 2_release_build_claude.md — 签名 + R8
- 3_privacy_claude.md — 隐私政策

## 任务

### 5.1 Play Console 开发者账号

- Google Play Console 开发者账号（$25 一次性注册费）
- 如果以公司身份发布需要 D-U-N-S 号（个人开发者不需要）

### 5.2 商店素材准备

**必需**:
- App icon: 512x512 PNG（已有 adaptive icon，导出高分辨率版即可）
- Feature graphic: 1024x500 PNG（商店顶部大图）
- 截图: 至少 2 张，推荐 4-8 张
  - 手机截图: 16:9 或 9:16
  - 建议截图内容：主界面、任务执行中、Smart Capsule overlay、设置页面
- Short description: 最多 80 字符
- Full description: 最多 4000 字符

**推荐**:
- Demo 视频（YouTube link）— 对 Accessibility Service 审核特别有帮助

### 5.3 应用描述文案

**Short description** (80 chars):
> AI agent that automates Android tasks with natural language instructions.

**Full description** 大纲:
```
Android Agent lets you automate any task on your phone using plain English.

HOW IT WORKS
- Type what you want to do: "Open Settings and turn on dark mode"
- The AI agent reads the screen, plans the steps, and executes them
- Watch as it navigates apps, taps buttons, and types text — all autonomously

FEATURES
- Natural language task execution
- Multiple AI backends: OpenAI, OpenRouter, or local inference
- Smart Capsule overlay — interact without leaving your current app
- Real-time action visualization
- Full control — stop any task at any time

PRIVACY
- Your API key stays on device
- Screen data is only sent to your chosen AI provider during active tasks
- No telemetry, no analytics, no cloud storage
- Option for fully local inference (no data leaves your device)

REQUIREMENTS
- Android 12+ (API 31)
- Accessibility Service permission (for screen reading & interaction)
- Overlay permission (for Smart Capsule)
- API key from OpenAI, OpenRouter, or Novita (or use local inference)

This app uses Android's Accessibility Service to read screen content and perform
actions on your behalf. The service is ONLY active during explicit task execution
and does NOT monitor your activity in the background.
```

### 5.4 Accessibility Service 审核准备（最关键）

Google 的 Accessibility Service 审核流程：

1. **Declaration Form**: 在 Play Console 的 "App content" 中填写 Accessibility Service 用途声明
2. **Demo Video**: 录制 2-3 分钟视频，展示：
   - 用户如何启动任务
   - Agent 如何读取屏幕并执行操作
   - 用户如何停止任务
   - 证明服务不在后台偷偷运行
3. **Core functionality justification**: 说明 Accessibility Service 是 app 核心功能（不是附加功能）

**关键措辞策略**:
- 强调 app 是 "assistive technology"（辅助技术）
- 强调用户主动触发、用户完全控制
- 强调没有后台数据收集
- 类比：类似 Voice Access、Switch Access 等 Google 自己的无障碍 app

**高风险点**:
- Google 可能认为 "AI 操控手机" 不是合法的无障碍用途
- 准备好被 reject 后的申诉策略
- 考虑 app 描述中加入 "accessibility" 和 "assistive" 关键词

### 5.5 Content Rating

Play Store 要求填写内容评级问卷（IARC）：
- 这个 app 不含暴力/色情/赌博内容
- 但需要说明 "user-generated content"（用户可以让 agent 做任何事）
- 可能被标记为需要 parental controls

### 5.6 Data Safety Section

Play Store 的 Data Safety 表格，需要填写：

| 数据类型 | 是否收集 | 是否共享 | 用途 |
|----------|----------|----------|------|
| 屏幕内容 | 是（临时） | 是（发到 LLM） | App functionality |
| API keys | 是（本地存储） | 否 | Authentication |
| 任务文本 | 是（本地存储） | 是（发到 LLM） | App functionality |

### 5.7 替代发布策略

如果 Play Store 审核不通过，备选方案：

1. **GitHub Releases + APK 直接下载** — 最简单，无审核
2. **F-Droid** — 开源 app 商店，但要求完全开源（Leap SDK 可能是问题）
3. **自建更新渠道** — app 内检查 GitHub releases 更新
4. **Play Store 内测轨道** — 先发 internal/closed testing，审核宽松些

**推荐路径**: 先 GitHub Releases，同时申请 Play Store，被 reject 了不影响分发。

## 验收标准

- [ ] Play Console 开发者账号已注册
- [ ] 512x512 icon 已导出
- [ ] Feature graphic 已设计
- [ ] 至少 4 张截图已准备
- [ ] Short/Full description 已撰写
- [ ] Accessibility Service 声明已填写
- [ ] Demo 视频已录制
- [ ] Data Safety 表格已填写
- [ ] 已提交审核（或已发布到 GitHub Releases 作为替代）
