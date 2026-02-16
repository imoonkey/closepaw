# AppSkill 设计（Codex）

> 目标：基于 `.reference/mobile_agent/droidrun` 的 AppCard 机制，结合本项目现有 Kotlin 架构，设计一套可落地的 AppSkill 系统。  
> 方法：先从第一性原理定义问题，再反推系统边界、数据结构、注入点和演进路线。

## 1. 第一性原理：我们在解决什么

Android Agent 每一轮都在做同一件事：  
在不完整观察（a11y 树可能缺失、文本截断、UI 动态变化）下，选择“下一步最可能推进目标”的动作。

没有 AppSkill 时，LLM 只能依赖：
- 通用预训练先验（泛化强，但不针对具体 App）
- 当前屏幕瞬时信息（真实但局部）
- 短期历史（有限）

这会导致三个基本成本：
- 探索成本高：同一个 App 每次都重新试错。
- 误动作成本高：把“看起来像”当成“就是”。
- token 成本高：反复在对话里重建同类操作知识。

所以 AppSkill 的本质不是“加更多 prompt”，而是：
- 给决策器提供“按包名绑定的稳定先验”，降低搜索空间。
- 让 agent 在同等观察下更快收敛到高概率动作。

## 2. 参考实现提炼（DroidRun AppCard）

参考代码核心点（已核对源码）：
- 抽象接口：`AppCardProvider.load_app_card(package_name, instruction)`.
- Provider 模式：`local` / `server` / `composite(server-first, local-fallback)`.
- Manager 回合读取当前 package，加载 app_card，注入 system prompt。
- 失败降级为空字符串，不阻断主流程。

优点：
- 解耦干净，扩展性强。
- 离线可用（local）+ 在线动态（server）。
- 接入点明确（planning 前）。

局限：
- 数据主要是自由文本 Markdown，缺少结构化字段，不利于精确裁剪。
- 缓存策略较粗（包含无效键；缺少清晰 TTL/失效策略）。
- 主要注入 Manager，Executor 侧价值未完全释放。

## 3. 本项目现状与接入约束

当前项目关键事实：
- 已有 `platform.getCurrentPackageName()`，每回合可拿到前台包名。
- 所有角色（Standalone/Planner/Sub-agent Executor）最终都走 `AgentTurnRunner -> PromptBuilder`。
- `AgentDef.systemPrompt` 目前是静态常量字符串。
- 动态上下文主要在 `PromptBuilder` 里拼装（history/memory/observation）。

结论：
- **最佳接入点是 `PromptBuilder` 的 observation 上下文**，而不是仅改静态 system prompt。
- 这样可以一次接入，覆盖主 Agent 和被委托的 Executor 子 Agent。

## 4. 设计目标与非目标

设计目标：
- 按包名自动注入 AppSkill，默认零配置可关闭。
- 命中快、失败可降级、严格 token 上限。
- 支持 local/server/composite，先本地落地。
- 不改变工具协议，不破坏现有 turn/trace/approval 流程。

非目标（首版不做）：
- 不做自动学习写回（self-edit skill）。
- 不做复杂多维检索（OCR 向量召回等）。
- 不把 AppSkill 当“真相源”，永远以当前屏幕证据优先。

## 5. 方案总览

### 5.1 核心组件

1. `AppSkillProvider`（接口）
- 输入：`packageName` + `goal` + `agentRole`
- 输出：`AppSkillPayload?`（可空，表示无技能）

2. Provider 实现
- `LocalAppSkillProvider`：assets/文件映射 + 读取缓存
- `ServerAppSkillProvider`：HTTP 拉取动态技能
- `CompositeAppSkillProvider`：server-first + local fallback
- `DisabledAppSkillProvider`：统一空实现

3. `AppSkillService`
- 负责缓存、TTL、限长裁剪、渲染成 prompt 片段。
- 对上暴露：`suspend fun resolve(...): AppSkillSnippet?`

4. `PromptBuilder` 注入
- 在 observation 文本前加入 `## App Skill` 段。
- 明确声明“技能是提示，不是事实”。

### 5.2 数据模型（建议）

```kotlin
data class AppSkillPayload(
    val packageName: String,
    val title: String,
    val bodyMarkdown: String,
    val version: String? = null,
    val updatedAtMs: Long? = null,
    val confidence: Float = 1.0f
)

data class AppSkillSnippet(
    val packageName: String,
    val source: String, // local/server/composite
    val renderedText: String
)
```

说明：
- 首版允许 `bodyMarkdown`，但通过 `AppSkillService` 做统一裁剪和头部模板化。
- 后续可升级为结构化字段（navigationHints/actionPatterns/antiPatterns）。

## 6. Prompt 注入策略

注入模板建议：

```text
## App Skill (for com.xxx.yyy)
Use as guidance only. If it conflicts with current screen evidence, trust the screen.
[skill content...]
```

注入位置建议：
- 在当前 `warnings` 之后，`Screen state` 之前。
- 理由：先给策略先验，再看实时状态，有助于“带先验读图”。

角色差异化预算：
- Standalone/Planner：`maxChars = 1200`
- Executor：`maxChars = 500`（避免原子执行被过量策略噪声污染）

## 7. 配置设计

在 `SessionConfig` 新增：

```kotlin
data class AppSkillConfig(
    val enabled: Boolean = false,
    val mode: AppSkillMode = AppSkillMode.LOCAL,
    val localDir: String = "appskills",
    val serverUrl: String? = null,
    val timeoutMs: Long = 1200,
    val maxRetries: Int = 1,
    val positiveTtlMs: Long = 30 * 60_000L,
    val negativeTtlMs: Long = 5 * 60_000L
)
```

并在 `SessionServices` 中创建 `appSkillService` 单例（session scope）。

## 8. 本地文件布局（首版）

建议放在 assets（便于打包）：

```text
app/src/main/assets/appskills/
├── index.json
└── cards/
    ├── com.google.android.gm.md
    └── com.android.settings.md
```

`index.json` 最小结构：

```json
{
  "version": 1,
  "mapping": {
    "com.google.android.gm": "cards/com.google.android.gm.md",
    "com.android.settings": "cards/com.android.settings.md"
  }
}
```

## 9. 执行流程（单回合）

1. `AgentTurnRunner` 获取当前 package。  
2. 调用 `appSkillService.resolve(package, goal, role)`。  
3. `PromptBuilder.buildInputItems(..., appSkillSnippet)`。  
4. LLM 在同一轮看到 AppSkill + 当前屏幕 + 历史。  
5. 未命中/出错时返回空，不影响主流程。  

## 10. 缓存与失效策略

缓存键建议：
- `packageName + role + goalHashPrefix`

策略：
- 正命中缓存：30 分钟（可配）。
- 负缓存（未找到/404）：5 分钟（避免频繁 IO/HTTP）。
- `clearCache()` 暴露给调试和测试。

注意：
- local provider 不应把完整 `goal` 作为强缓存维度（命中率太低）。
- server provider 可保留 goal 维度，用于动态技能生成。

## 11. 质量与安全约束

必须保证：
- 任何 provider 异常都吞掉并降级为空。
- 限长裁剪必须在注入前完成，防止 context 爆炸。
- 明确提示“screen evidence 优先”，避免 stale skill 误导。
- server mode 不记录敏感 goal 到长期日志（至少默认脱敏/截断）。

## 12. 观测与评估

新增 trace 字段（建议）：
- `app_skill_hit` (bool)
- `app_skill_source` (local/server/none)
- `app_skill_package`
- `app_skill_chars`
- `app_skill_latency_ms`

核心评估指标：
- 任务成功率
- 平均完成 turn 数
- 重复动作率（loop proxy）
- 首次有效动作命中率（前 2 turn）

## 13. 分阶段落地

Phase 1（最小可用）：
- Local provider + assets 映射
- PromptBuilder 注入 + 限长 + trace 字段
- 2~3 个高频 App 样例卡片

Phase 2（增强）：
- Server/composite provider
- TTL/负缓存/超时重试完善
- 角色差异化注入预算

Phase 3（进阶）：
- 结构化 skill schema（从自由文本升级）
- 基于失败历史的 skill 片段重排
- 半自动维护工具链（lint + schema 校验）

## 14. 关键取舍结论

- 取舍 1：首版用 Markdown，快速落地；通过 service 层限长与模板约束控制风险。  
- 取舍 2：注入 observation，而非改静态 system prompt；一次覆盖所有 agent role。  
- 取舍 3：先 local 再 server；先解决稳定收益，再做动态生成。  

---

这个设计保持了 DroidRun 的优点（Provider 抽象、可降级、可扩展），同时贴合当前代码结构（`AgentTurnRunner + PromptBuilder`）以最小侵入实现 AppSkill 能力。
