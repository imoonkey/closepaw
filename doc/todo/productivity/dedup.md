# Deduplication: .claude/ folder optimization

**Status**: ✅ Implemented  
**Date**: 2025-01-25

## Summary

Merged duplicate commands into skills following Claude Code best practices. Commands folder removed; skills now serve as the single source of truth for all workflows.

## Changes Made

### Before (18 files, duplicated)
```
.claude/
  commands/           # 6 files - REMOVED
    plan.md, verify.md, update-docs.md, tdd.md, visual-fix.md, build-fix.md
  skills/             # 8 folders with verbose names
    planning-workflow/, verification-loop/, doc-update/, tdd-workflow/,
    visual-debug-workflow/, code-review-workflow/, strategic-compact/, coding-standards/
  agents/             # 4 files - kept as-is
```

### After (14 files, deduplicated, tool-agnostic)
```
.ai-dev/                      # Primary location (tool-agnostic name)
  skills/                     # 10 folders with short command names
    plan/                     # Structured planning
    verify/                   # Pre-commit quality gates
    update-docs/              # Sync docs with code
    tdd/                      # Test-driven development
    visual-fix/               # Debug with screenshots
    build-fix/                # Fix Gradle errors
    code-review/              # Systematic review
    strategic-compact/        # Context compaction (renamed from compact)
    orchestrate/              # NEW - chain skills/agents together
    coding-standards/         # Reference skill (auto-applied)
  agents/                     # 4 files (thin persona + tool restrictions)

# Symlinks for cross-tool compatibility
.claude -> .ai-dev
.cursor -> .ai-dev
.codex -> .ai-dev
CLAUDE.md -> AIDEV.md
AGENTS.md -> AIDEV.md
```

### Available Commands (via skills)
- `/plan` - Structured planning before implementation
- `/verify` - Pre-commit quality gates (build, lint, test)
- `/update-docs` - Sync documentation with code changes
- `/tdd` - Test-driven development workflow
- `/visual-fix` - Debug agent with visual inspection
- `/build-fix` - Fix Gradle/Kotlin errors incrementally
- `/code-review` - Systematic code review
- `/strategic-compact` - Strategic context compaction
- `/orchestrate` - Chain skills/agents for complex workflows

---

# Original Research Question
我的.claude文件夹配置， agents/ commands/ skills/ 下有很多重合，比如update-docs相关的，或者code-review相关的，可能类似的内容会在两三个文件夹下出现。有什么best practice来reduce duplicates吗？帮我搜搜总结一下。

# Answer I got

你这个痛点**非常典型**：同一个“能力”（比如 `update-docs` / `code-review`）既写成了 **agent**，又写成了 **command**，又写成了 **skill**，最后维护成本爆炸，还容易 drift。

我刚搜了下 Claude Code 官方文档 + 社区实践，总结一套**最有效的去重套路**（偏 DRY / single source of truth）。

---

## 0) 先讲一个关键变化：`commands` 其实已经“并入 skills”了

官方现在明确说：

* `.claude/commands/review.md` 和 `.claude/skills/review/SKILL.md` **都会生成同一个 `/review`**，效果一样
* **skills 是推荐方式**（支持 supporting files、自动触发控制等），老的 `commands/` 只是兼容还在继续支持
* 如果同名，**skill 会优先生效**（命令会被覆盖） ([Claude Code][1])

✅ **Best practice #1：把重复的 command 迁移成 skill，然后删掉 command。**
这一步通常就能干掉你一半重复内容。

---

## 1) 定义清楚三类东西各自“应该放哪里”

一个很好用的分工方式（也是官方推荐思路的自然推导）：

### A) `CLAUDE.md`：项目长期稳定的“宪法/常识”

Anthropic 的 best practices 明确把它定位成：代码风格、测试命令、仓库约定、常用脚本、踩坑提示等应该长期存在的内容 ([Anthropic][2])
✅ **把所有 skill/agent 都会重复写的“项目事实”搬到这里**，去重立竿见影。

---

### B) `skills/`：可复用能力（知识 + 流程）的**唯一真源**

skills 是官方“正统扩展点”：带 frontmatter、支持 `$ARGUMENTS`、支持 supporting files、还能控制是否允许模型自动触发 ([Claude Code][1])

✅ **Best practice #2：所有 workflow（code review / doc update / release / refactor）都只写成 skill。**
不要再写成 agent+command 两套。

---

### C) `agents/`：只放“执行环境/人格/权限边界”，不放业务流程

Subagent 的核心价值是：

* 有自己的 system prompt（偏 persona / role）
* 能限制工具、权限模式
* **能 preload skills，把 skill 内容直接注入它的上下文** ([Claude Code][3])

✅ **Best practice #3：Agent 变薄，只负责“怎么做事的风格/边界”，步骤都放 skill。**

---

## 2) 用“Skill 做入口 + Agent 做运行时”彻底消掉 skill/agent 重复

官方 skills 支持：

* `context: fork`：让 skill 在一个 fork 出来的子上下文跑
* `agent:`：指定用哪个 subagent 配置执行 ([Claude Code][1])

这就给了你一个**干净到极致的结构**：

### ✅ 推荐结构（以 code-review 为例）

```
.claude/
  skills/
    review/
      SKILL.md          # 这里写“步骤/产出格式”
      checklist.md      # supporting file，放检查清单（避免别处重复贴）
  agents/
    code-reviewer.md    # 只写角色、工具限制、预加载哪些 skills
```

#### `skills/review/SKILL.md`（入口）

* 写 review 的流程
* 用 `context: fork` + `agent: code-reviewer` 跑到专门 subagent 上
  （frontmatter 字段就是官方这套） ([Claude Code][1])

#### `agents/code-reviewer.md`（环境）

* 只写 “你是资深 reviewer，关注安全/性能/可读性”
* 用 `skills:` preload 你要的规则类 skills（比如 `security-guide` / `style-guide`） ([Claude Code][3])

**这样同一个“能力”永远只在 skill 里维护一次**，agent 永远不抄流程。

---

## 3) 把重复内容从“复制粘贴”改成 “supporting files”

skills 官方明确建议：把大块参考资料拆成 supporting files，让 `SKILL.md` 保持短小，并通过链接引导加载 ([Claude Code][1])

✅ **Best practice #4：所有 checklist / 模板 / 示例输出都放 supporting files**
比如：

* `review/checklist.md`
* `update-docs/template.md`
* `update-docs/examples/*`
* `release/scripts/*`

这样你不会在 3 个 skill/agent 里反复贴同一段 checklist。

---

## 4) “谁触发”也会导致重复：用 frontmatter 控制触发方式

skills 有两个特别去重友好的开关： ([Claude Code][1])

* `disable-model-invocation: true`
  👉 只允许你手动 `/skill` 触发（适合 deploy / 改文档 / 可能有副作用的任务）
* `user-invocable: false`
  👉 背景知识类 skill，不想作为命令出现，但希望模型需要时自动加载

✅ **Best practice #5：**

* “流程型动作”统一设成手动触发（防止 Claude 自己乱跑）
* “规则/知识型”设成不可手动触发（避免命令列表污染）

---

## 5) 跨多个项目重复：打包成 plugin，而不是到处复制 `.claude/`

如果你重复是“在 N 个 repo 里都有一份同样的 review/update-docs”，官方推荐用 **plugins** 来复用并版本化：plugins 可以包含 skills + agents + hooks + MCP ([Claude Code][4])

✅ **Best practice #6：把通用能力做成 plugin**（比如 `my-team-code-quality`）

* 每个 repo 只保留极少量 project-specific 的 `.claude/`
* 通用的东西集中升级

---

## 6) 进阶社区套路：搞一个“单一真源目录”，其余都是 loader

社区里有人专门解决“agents/commands/skills 多处重复”的问题：建立一个工具无关的 `ai-dev/` 目录做唯一真源，然后 `.claude/` 只引用/映射它 ([Medium][5])

✅ **Best practice #7（工程化上限更高）：**

* 把所有通用规则、prompt、模板放 `ai-dev/`
* `.claude/` 用脚本/生成器输出（或 symlink）
  这样你甚至能同时喂给 Copilot / Cursor / Claude，彻底杜绝 drift。

---

## 7) 一个你可能忽略的“隐形成本”：重复会吃掉技能描述预算

skills 的 **description 会被加载进上下文让 Claude 知道有哪些能力**，太多/太长会超预算（默认 15000 chars），官方甚至给了环境变量去调 ([Claude Code][1])
所以去重不仅是维护成本，也是在省上下文预算。

---

# 最推荐的一句话策略

> **用 skills 做唯一真源（流程+知识），用 agents 只定义执行环境与权限，用 CLAUDE.md 放项目常识；commands 逐步清空或全部迁移成 skills。** ([Claude Code][1])

---

如果你愿意，我也可以按你现有 `.claude/` 的结构，给一个“迁移方案模板”（比如把 `update-docs` / `code-review` 合并成上面这种 skill+agent 架构），并给你一套推荐的目录命名规范，后续不会再长重复。

[1]: https://code.claude.com/docs/en/skills "Extend Claude with skills - Claude Code Docs"
[2]: https://www.anthropic.com/engineering/claude-code-best-practices "Claude Code Best Practices \ Anthropic"
[3]: https://code.claude.com/docs/en/sub-agents "Create custom subagents - Claude Code Docs"
[4]: https://code.claude.com/docs/en/plugins "Create plugins - Claude Code Docs"
[5]: https://medium.com/%40genyklemberg/one-prompt-to-rule-them-all-how-to-reuse-the-same-markdown-instructions-across-copilot-claude-42693df4df00 "One Prompt to Rule Them All: How to Reuse the Same Markdown Instructions Across Copilot, Claude, Cursor & Codex | by Yevheniy Potupa | Medium"
