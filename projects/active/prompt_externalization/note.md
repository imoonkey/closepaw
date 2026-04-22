# Prompt Externalization

- System prompt 从 `StandaloneAgentDef.kt` 等硬编码字符串 → `assets/persona/<role>/system_prompt.md`
- 新增 PersonaRepository（接口 + asset 实现，类似 AppSkillRepository）
- App skill 迭代不再需要重新编译 APK
- 对应 OpenClaw roadmap P3 Phase 1
- 是 app skill discovery 的前置条件
