# 借鉴点 2: Voice-First 交互

## OpenClaw 怎么做的

语音不是 OpenClaw 的附加功能，而是核心交互模态：

### Talk Mode（对话模式）
- 完整的语音循环: 监听 → STT 转写 → 发送给 agent → agent 回复 → TTS 播放
- ElevenLabs 流式 TTS（主），系统 TTS（备）
- Agent 可以通过 JSON 指令控制语音参数（voice_id、speed、stability）
- 支持打断：用户说话时自动中断 TTS 播放

### Wake Word（唤醒词）
- Swabble 组件：纯本地语音唤醒，基于 Apple Speech.framework
- 零网络开销，隐私友好
- 默认唤醒词 "clawd"，可自定义
- 三种模式: Off / Foreground / Always

### Voice Directives
Agent 回复可以嵌入 TTS 控制指令：
```json
{"voice_id": "abc", "speed": 1.2, "stability": 0.5}
```
让 agent 可以根据上下文调整语音风格（比如读新闻用正式语调，闲聊用轻松语调）。

## 为什么值得借鉴

Android Agent 目前完全依赖打字输入 + 屏幕阅读。但手机的核心使用场景里，很多时候用户不方便打字：
- 开车时
- 做饭时
- 走路时
- 手上拿着东西

语音输入 → agent 执行 → 语音反馈，这个闭环一旦跑通，产品可用性质变。

## 可落地方案

### Phase 1: 语音输入（最小可用）
- 接入 Android SpeechRecognizer API（系统自带，免费）
- 用户按住说话 → 转文字 → 发给 agent
- 零额外成本，Android 原生支持

### Phase 2: 语音反馈
- agent 完成任务后用 Android TextToSpeech 播报结果
- 关键信息简短总结（"已发送"、"找到 3 个结果"），不念完整回复
- 可选接入 ElevenLabs 等服务提升质量

### Phase 3: 唤醒词（远期）
- 利用 Android Vosk/Porcupine 做本地唤醒
- "Hey Agent, 打开微信" → 直接执行

### 关键原则
- Phase 1 零成本可做，应该尽快补上
- 语音反馈要克制 — 只说关键信息，不做话痨
- 唤醒词是远期，不急
