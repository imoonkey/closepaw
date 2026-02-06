Prompt 重构建议（强烈建议做）

1. 建一个集中模块：app/src/main/kotlin/com/moonkey/androidagent/agent/prompt/，把所有 prompt 文本从 AgentRuntime.kt、Turn.kt、ExecutorAgent.kt 移走。
2. 文件拆分建议：
PlannerSystemPrompt.kt、ExecutorSystemPrompt.kt、TurnRulesPlanner.kt、TurnRulesExecutor.kt、PromptAssembler.kt。
3. 统一入口只保留一个：PromptAssembler.build(role, context, visibleTools)，输出“最终完整 prompt”。
4. 每轮把“最终 prompt 全文”落 trace（你现在只看到碎片很难 debug）。
建议 artifact：{turn}_full_prompt.txt。
5. 模板形态优先 Kotlin 字符串模板（先别上 jinja），因为：
运行时简单、类型安全、IDE 跳转/重构友好、Android 打包更稳。
6. 之后再升级到 assets 模板（如 .md），由 PromptTemplateLoader + PromptAssembler 渲染。

如果你同意，我下一步可以直接把 prompt 集中模块重构出来（不改行为，只做“结构整理 + trace可观测性增强”）。