# App Skill 重写框架（中文摘要）

> 原文：`doc/todo/appskill_topdown/final/framework.md`

## 目标

将 17 个 app skill 重写为统一的 top-down 格式：只保留 app 本地知识，去掉 solver 逻辑和 eval overfit，保持足够紧凑以每个 foreground turn 加载。

## 核心原则

App skill 编码的是 app 的**数据模型和交互模型**：app 本地的"什么"、"在哪里"、"哪里会出错"。**不编码** agent 的推理策略。

判断标准：
- 保留：像写给操作员的简洁笔记
- 删除：像写给 benchmark runner 的解题指南

## 内容所有权

### 保留在 app skill 中
- 隐藏数据位置（real source of truth 在哪里）
- 非显而易见的导航路径
- Accessibility 缺陷（缺失的 cell、隐藏 scroll 轴）
- 静默失败模式
- 交互陷阱（控件行为与表象不符）
- 平台怪癖（first-run prompt、extension field、picker 行为）

### 从 app skill 中移除
- Solver 算法（counting procedure、dedup strategy、batching）
- Scratchpad 格式规定（`unchecked:`、`checked:` 等 schema）
- Hardcoded 数字/阈值
- Eval 特定 pattern
- Turn 优化语言（"save turns"、"do this quickly"）
- Core prompt 中已有的通用规则

## 分解规则

当 skill 混合了 app 知识和 solver 过程时，提取 app fact，丢弃 procedure：

| 混合写法 | 纯 fact |
|---|---|
| "Scroll list, 写 tracks 到 scratchpad, open Edit for every track..." | "Track name 不代表 activity type。Type 只在 Edit → Activity type 里。" |
| "Add 8-10 songs, check total, remove if over..." | "Playlist detail view 显示 total duration。" |

## 结构

按**失败代价**排序：防止最严重错误的规则排第一。

- **CRITICAL**（顶部，每个 skill 最多 1 个）：仅当显而易见的路径经常导致错误结果，且正确替代方案从屏幕上看不出来
- 然后：canonical route、pitfall、操作笔记
- 只有一行内容的 section 不需要 header

## Token 预算

| Tier | 行数 | 适用场景 |
|---|---|---|
| Minimal | 2-5 | 只有一两个强 quirk |
| Standard | 6-12 | 大部分 app |
| Complex | 13-18 | 有真正的 hidden state、多个强陷阱 |
| Hard cap | 20 | 绝不超过 |

## CRITICAL Protocol

仅当**同时满足**以下 5 条时使用：
1. 默认路径经常导致错误结果
2. 正确替代方案从屏幕上看不出来
3. 规则是 app 特定的，不是通用 agent 纪律
4. 规则适用于该 app 的多个 task
5. 规则可以用 1-3 个短 bullet 说明

## 重写结果总览

**366 → 126 lines（-65.6%）**

| App | 旧行数 | 新行数 | Tier |
|---|---|---|---|
| Audio Recorder | 13 | 2 | T1 |
| Documents UI | 13 | 2 | T1 |
| Google Calendar | 11 | 2 | T1 |
| Google Photos | 15 | 3 | T1 |
| Chrome | 19 | 2 | T1 |
| Google Files | 20 | 4 | T1 |
| Simple Gallery Pro | 17 | 4 | T1 |
| Pro Expense | 26 | 6 | T2 |
| Broccoli | 29 | 7 | T2 |
| Settings | 16 | 8 | T2 |
| Retro Music | 27 | 9 | T2 |
| OpenTracks | 29 | 11 | T2 |
| Simple Calendar Pro | 24 | 12 | T2 |
| OsmAnd | 24 | 12 | T2 |
| VLC | 25 | 12 | T2 |
| Markor | 27 | 15 | T3 |
| Tasks.org | 31 | 15 | T3 |

## 配套代码改动

- `AppSkillRepository.kt`：添加 frontmatter stripping，加载时自动去掉 `---` 块，不注入到 agent prompt context
- `/prompt-tune` skill：新增 `references/app_skill.md` reference，编写 app skill 时遵循
