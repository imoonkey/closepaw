# App Skill Discovery

- 核心：建一个系统让 agent 自己探索 app 并提炼 SKILL.md，不是手动写
- Discovery loop：给定 target app → agent 启动 → 系统性探索 UI 路径 → 记录 a11y tree 特征、navigation pattern、pitfall → 生成 SKILL.md draft
- Quality gate：discovery 产出需要验证（跑 test task / 人工 review）
- 英文市场 top 30 app 优先（WhatsApp, Gmail, YouTube, Maps, Spotify, Instagram, Uber, Amazon, Twitter/X, Telegram...）
- 依赖 prompt externalization（skill 迭代不需要重新打包 APK）
