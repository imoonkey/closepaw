# Scope Review：Android Agent 下一步做什么？

*日期：2026-03-24。Autotune R58 结束后。*

## 现状

| 维度 | 状态 |
|------|------|
| **Agent 质量** | AndroidWorld 全集约 81%（hard subset 33/51，easy set 约 61/65）。58 轮 autotune。 |
| **架构** | 成熟。ReAct loop、multi-agent delegation、11 tools、streaming LLM、hybrid perception、memory V2。 |
| **App skills** | 17 个 app——全是 AndroidWorld eval 专用（Markor、Tasks.org、Broccoli、OpenTracks 等） |
| **安全** | **未达生产标准**。cleartext traffic 开启、API key 明文存 SharedPreferences、insecure SSL config、allowBackup=true。 |
| **分发** | 零。没有任何人能安装和使用这个产品。 |
| **Onboarding** | 无。手动配置流程复杂：accessibility service → overlay permission → battery optimization → API key 输入。 |
| **设计文档** | 大量：10 个 OpenClaw alignment 主题、Task API 设计、proactive UX 设计、publish gap 分析、app skill framework。 |
| **主动能力** | 无。没有 scheduled tasks，没有 notification handling。产品完全是被动的（用户说 → agent 做）。 |

## 核心问题

产品花了 58 轮在一个没有真实用户看到的 benchmark 上打磨性能。

**设计与交付的比例失衡：**
- 10 份 OpenClaw alignment 文档 → 0 个功能落地
- Task API 完整设计 → 0 行代码
- Proactive agent UX 完整设计 → 0 行代码
- Publish gap 评估完成（2026-03-05）→ 0 项修复
- Memory V1 设计 → V2 落地（唯一一个真正 ship 了的）

**Eval 陷阱：** 继续跑 R59，每轮收益约 0.2%。剩余 18 个失败任务的构成：
- 6 个 chronic（≤1/15 通过率）——结构性问题，prompt 调不动
- 7 个 stochastic——无论怎么改都会波动
- 2 个 parked infra——platform/a11y 层面限制
- 3 个 chronic cognitive——turn budget 不够

继续 tune 的边际收益已经很低。Eval 应该变成 **regression CI gate**，不再是优化目标。

**覆盖度缺口：** 17 个 app skill 全是 AndroidWorld eval 用的 app（Markor、Broccoli、OpenTracks、SimpleCalendar……）。真实用户装的是微信、WhatsApp、Gmail、YouTube、Google Maps、Spotify、Instagram、Uber、支付宝、美团……Agent 对用户日常用的 app 一个都不认识。

## 逐项挑战 priority.md

| priority.md 项目 | 判断 |
|------------------|------|
| 0. Prompt generalization | **停**。58 轮了。Diminishing returns。Eval 转 CI 用。 |
| 1. Memory V2 | **已完成**。继续前进。 |
| 2. Session management | **推迟**。Session 能用（hot idle、checkpoint、resume）。用户可浏览只是 polish，不创造价值。 |
| 3. Security / permissions | **现在做**。所有分发路径的前置条件。 |
| 4. Auth（OpenClaw 式） | **推迟**。还没有外部调用者。等 Task API 时再做。 |
| 5. Release readiness | **现在做**。通向用户的路。 |
| 6. 60-app coverage | **高价值**。先收敛到 top 30 常用 app。这才是让产品有用的东西。 |
| 7. Scheduled tasks | **高价值**。把一次性工具变成每天都用的 assistant，带来留存。 |
| 8. Notification handling | **高价值**。和 scheduled tasks 搭配，构成 proactive 能力。 |

**关于 OpenClaw P1-P9 roadmap**（dynamic tool exposure、policy externalization、prompt assets 等）：这些是内部工程质量的工作。对长期代码健康很重要，但对用户价值为零。碰到相关代码时顺手做，不要专门开一个 phase。

## 三条路径

### Path A：先发布，再迭代

最小 cleanup 后开源到 GitHub。让真实使用来指导后续开发。

```
第 1 周：Security hardening（EncryptedSharedPrefs、关 cleartext/insecure SSL）
        README + LICENSE + CONTRIBUTING
第 2 周：Onboarding wizard（a11y → overlay → battery → API key → demo）
        最小 CI（build + test + lint）
        GitHub release v0.1-alpha
第 3 周+：根据真实反馈迭代
```

| 优点 | 缺点 |
|------|------|
| 最快触达真实用户 | 首次印象只有 17 个冷门 eval app |
| 反馈驱动迭代 | 安全修复时间压力大 |
| 逼自己面对真实问题 | 可能因缺乏 app 覆盖而反响平平 |

### Path B：先扩覆盖再发布（推荐）

扩展到 30 个常用 app，修好安全，加上 onboarding，再发布。用户第一天就能得到真实价值。

```
Phase 1 — 打通分发（2 周）
├── Security hardening（publish gap P0 项）
│   ├── API key 迁移到 EncryptedSharedPreferences
│   ├── 关闭 cleartext traffic
│   ├── 移除/门控 insecure SSL config
│   └── allowBackup=false + data extraction rules
├── Onboarding wizard（顺序引导，不是 settings 页面）
│   ├── Accessibility service 权限
│   ├── Overlay 权限
│   ├── Battery optimization 豁免
│   ├── API key 输入 + 验证
│   └── Demo task（"打开设置，开启 Wi-Fi"）
├── README + LICENSE + CONTRIBUTING + SECURITY.md
└── 最小 CI（GitHub Actions: PR 触发 build + test + lint）

Phase 2 — 真实实用性（3-4 周）
├── Top 20-30 常用 app skills
│   ├── 通讯：微信、WhatsApp、Telegram、Signal
│   ├── 社交：Instagram、Twitter/X、TikTok、YouTube
│   ├── 效率：Gmail、Google Calendar、Google Maps、Notion
│   ├── 消费：支付宝、美团、饿了么、Uber、Amazon
│   ├── 媒体：Spotify、Apple Music、Netflix
│   └── 系统：Phone、Contacts、Camera、Clock、Calculator
├── Prompt externalization（P3 Phase 1）
│   └── System prompt 移到 assets——迭代 skill 不需要重新打包 APK
├── Eval 转 regression CI
│   └── PR 跑 full AndroidWorld set，regression 才 fail，不再主动优化
└── GitHub release v0.1-alpha

Phase 3 — 日常助手（3-4 周）
├── Scheduled tasks（cron 式定时执行）
├── Notification handling（通知触发 → agent 响应）
└── v0.2 release

Phase 4 — 平台化（未来，用户验证之后）
├── Task API（HTTP endpoint，供外部编排器调用）
├── Voice input（Smart Capsule 内 push-to-talk）
├── Play Store 提交（如果 accessibility compliance 可行）
└── OpenClaw 通过 Task API 集成
```

| 优点 | 缺点 |
|------|------|
| 用户第一天就有 30 个常用 app 可用 | 首个外部用户要等 5-6 周 |
| Demo 和 pitch 有说服力 | 在真空中构建 app skill，可能偏离真实使用模式 |
| 安全在分发之前妥善修复 | App skill 的工作量不小 |
| 自然递进：实用性 → 留存 → 平台 | |

### Path C：先做平台

先建 scheduled tasks + notification handling + Task API。定位为"全天候个人助手"而非"一次性工具"。

```
第 1-2 周：Scheduled tasks（cron 式任务执行引擎）
第 2-3 周：Notification handling（通知流 → reactive trigger）
第 3-4 周：Task API（HTTP endpoint、bearer auth、localhost-only 默认）
第 4 周+：以"自主助手"定位发布
```

| 优点 | 缺点 |
|------|------|
| 差异化定位，不是"又一个 agent demo" | 最复杂的路径 |
| Proactive 能力带来日活留存 | 离发布最远 |
| Task API 打开生态 | 仍然有 17-app 覆盖度的问题 |

## 推荐

**Path B**，理由：

1. **Security + onboarding** 是任何分发路径的必要条件，不可跳过。
2. **App coverage** 是"酷 demo"和"有用产品"的分界线。Agent 能力已经足够好——只是需要认识用户真正在用的 app。
3. **Prompt externalization**（P3 Phase 1）解锁 app skill 的快速迭代，不需要每次打 APK。战术性收益高。
4. **Eval 转 regression CI** 保留 58 轮的成果作为质量门槛，同时停止磨 benchmark。
5. **Scheduled tasks + notifications** 在 Phase 3 构建留存和日常价值，建立在覆盖度之上。

核心洞察：**security + onboarding + app coverage 就是 MVP。** 其他所有东西——session UX、policy externalization、rich messages、voice、Task API——要么是内部质量，要么是平台功能，要么是 polish，都可以等用户验证之后再做。

## 不该做的事

- **不要跑 R59。** Eval 作为 CI 用，不是优化 loop。
- **不要现在建 Task API。** 没有外部调用者。有人想集成的时候再建。
- **不要专门做 OpenClaw P1-P4 roadmap 的重构。** 内部工程质量工作，用户看不到。碰到时顺手做。
- **不要现在冲 Play Store。** AccessibilityService 做"自主 agent"的合规不确定性很高。先开源，Play Store 等真实验证后再说。
- **不要现在做 voice。** 好的 UX 但不扩展 agent 的能力边界。核心实用性验证后再做。

## 待讨论的问题

1. **App skill 规模化方法：** 能否从 app 文档/爬取自动生成 skill，还是每个 app 都必须手动从 trace 分析编写？到 30-60 个 app 时，手动编写是瓶颈。
> 这个我们建个skill，然后agent在手机上自己discover，总之不会是手动的。

>> **[回复]** 这意味着 Phase 2 的核心不是"写 30 个 SKILL.md"，而是"建一个 app skill discovery 系统"。Agent 自己跑目标 app → 探索 UI → 提炼 skill → 存到 `app_skills/<package>/SKILL.md`。这比手动写 skill 更 scalable，但也更重（需要 discovery loop + quality gate + 人工 review 机制）。Phase 2 的 note.md 会以此为核心。

2. **中文市场 vs 英文市场：** App skill 优先级完全不同。中文用户需要微信/支付宝/美团/饿了么，英文用户需要 WhatsApp/Uber/Spotify。先做哪个？
> 做英文用户优先。

>> **[回复]** 收到。Phase 2 app list 聚焦英文市场 top apps：WhatsApp、Gmail、YouTube、Google Maps、Spotify、Instagram、Uber、Amazon、Twitter/X、Telegram 等。中文 app（微信、支付宝等）留到 Phase 4+。

3. **Accessibility compliance：** Publish gap 评估指出 AccessibilityService 是 Play Store 最大的不确定性。有没有一种产品定位能通过审核？还是说开源 sideload 是主要分发路径？
> a11y几乎是必须需要的，所以先ignore这个。不能上play store就sideload。我看有类似app上google play store的，所以可能也还行。

>> **[回复]** 同意。Phase 1 先做 GitHub release（sideload APK）。Play Store 放到 Phase 4。先有用户、有反馈，再去解决分发渠道问题。类似产品（MacroDroid、Tasker、AutoInput）确实在 Play Store 上架了，说明路径存在，但不是现在的优先级。

4. **LLM 成本模型：** 目前依赖 cloud API（OpenAI、OpenRouter）。如果做 scheduled tasks 每天跑，per-user 月成本是多少？On-device LLM（LFM）能否支撑日常任务？
> 所以要做openclaw类似的openai codex OAuth登陆，这样用户可以复用openai subscription。我的商业化以后再搞。on-device LLM太慢太笨，现在还不行。on-device path目前只是个demo, doesn't really work for real user's use case yet。

>> **[回复]** 明确了。这意味着 onboarding 中"API key 输入"要扩展为"OpenAI OAuth 登录 OR 手动 API key"。OAuth 降低新用户门槛（不需要懂 API key 是什么），但实现更重。建议：Phase 1 先做 API key 手动输入（快），OAuth 登录作为 Phase 1.5 或 Phase 2 的增强。这个在 Phase 1 note.md 里讨论。

---

## 决定

> Plan B我同意。我觉得你说的整体是对的。你给你说的Phase 1/2/3 每个建个doc/todo folder。里面写个note.md，用几个bullet point描述要干嘛。这是我跟你讨论每个project scope的起点。每个note.md聊差不多了，会以它们为起点开始具体设计和实现。

**Path B 确认。** 三个 Phase 分别建 folder + note.md 作为 scope 讨论起点。

关键调整（基于你的回复）：
- Phase 2 核心变成 **app skill discovery 系统**，不是手动写 30 个 skill
- 英文市场优先
- Play Store 推迟到 Phase 4+
- OpenAI OAuth 登录在 Phase 1 或 Phase 2 落地（取决于 scope 讨论）
- On-device LLM 暂不投入

各独立设计/实现单元（按建议执行顺序）：

**Phase 1 — 打通分发：**
- `doc/todo/security_hardening/note.md`
- `doc/todo/onboarding_wizard/note.md`
- `doc/todo/open_source_release/note.md`

**Phase 2 — 真实实用性：**
- `doc/todo/prompt_externalization/note.md`
- `doc/todo/app_skill_discovery/note.md`
- `doc/todo/eval_regression_ci/note.md`

**Phase 3 — 日常助手：**
- `doc/todo/scheduled_tasks/note.md`
- `doc/todo/notification_handling/note.md`
- `doc/todo/openai_oauth/note.md`