# OpenClaw 产品借鉴点

## 结论

OpenClaw 最值得借鉴的不是“支持很多渠道”本身，而是它把产品组织成了一个很清晰的结构：

1. 一个稳定的控制平面（Gateway / Web UI / Session / Config）。
2. 多个轻量入口（聊天渠道、Web、移动端、语音）。
3. 多种可插拔能力（skills / plugins / nodes）。

对 Android Agent 来说，这个思路比单纯抄功能更有价值。我们现在更像“一个会操作手机的 agent”，而 OpenClaw 做得更像“一个有统一控制面的个人助理系统”。

## 值得重点借鉴的点

### 1. 把 Android 端从“唯一产品入口”降级为“执行节点”

OpenClaw 对 Android 的定位很克制：Android app 不是主控台，而是 node。真正的主控是 Gateway + Control UI + 各种聊天入口。

这点很值得借鉴，因为 Android Agent 现在天然会滑向“所有事情都塞进手机 App 里”，最后容易出现：

- 配置重
- 调试难
- 远程控制弱
- 自动化不可观察

更好的产品结构是：

- Android 负责执行设备侧动作：a11y、截图、点击、输入、读取当前界面。
- 外部控制面负责发任务、看日志、看运行态、改配置。
- 同一个 agent 可以从 App 内、Web、消息入口被唤起。

这会直接提升“可远程使用”和“可调试性”。

### 2. 补一个轻量 Web Control UI，而不是继续堆原生设置页

OpenClaw 的浏览器控制台很强，不只是聊天，还覆盖：

- session 管理
- 节点状态
- 配置编辑
- 技能开关
- cron / webhook
- 日志和调试

对 Android Agent，这里最值得抄的是“运维面板”思路，而不是 UI 样式。我们当前非常需要一个低成本的控制面，至少应覆盖：

- 当前设备是否在线
- 最近一次任务在做什么
- 当前屏幕截图 / UI tree 摘要
- 最近 action 流
- 失败原因
- 手动重试 / 中断
- prompt / tool 配置切换

这类能力放在 Web 上，迭代速度会远高于继续在 Android App 里堆复杂页面。

### 3. onboarding 做成向导，而不是文档拼图

OpenClaw 的 `openclaw onboard` 很像产品级 setup funnel，不只是“填写配置”，而是按顺序完成：

- model / auth
- workspace
- gateway
- channel
- daemon
- health check
- skills

Android Agent 也应该有自己的 onboarding 向导，至少覆盖：

- 无障碍权限是否开好
- 截图 / 前台服务 / 电池白名单是否齐
- LLM 配置是否有效
- 设备连接方式是否正常
- 第一个 demo task 是否跑通

关键不是“向导形式”，而是把首次成功率当成产品能力来设计。

### 4. 配对、批准、allowlist 这些安全默认值值得直接借鉴

OpenClaw 在产品层面很强调：

- 新设备接入要审批
- 新浏览器连接要配对
- 私信默认不直接放开
- 高风险能力默认受限

这对 Android Agent 很重要，尤其如果后面加：

- shell / terminal tool
- 远程控制
- Telegram / WhatsApp 等消息入口

建议直接借鉴成产品原则：

- 默认只允许本机 / 已配对控制端发任务。
- 高危工具单独开关，并且按会话显示“已提升权限”状态。
- 所有远程入口都必须有设备绑定与显式批准。

这个不是“锦上添花”，是能力扩张前必须补的地基。

### 5. skills / plugins 不是技术细节，而是产品分层

OpenClaw 的技能体系做对了一件事：把“长尾能力”从 core 里剥离出去。

对 Android Agent，这很有启发。未来很多需求不应该继续写死在核心 agent 里，比如：

- 电商下单流程
- 某个特定 App 的自动化脚本
- 日报、记账、打卡等任务模板
- 领域化 prompt + tool 组合

更合理的形态是：

- core 只负责通用观察、规划、执行、校验
- app-specific 能力走 skill / plugin
- 用户工作区可覆盖默认技能

这能减少核心复杂度，也更利于社区扩展。

### 6. 多入口一致会话，比“多端 UI”更重要

OpenClaw 很强的一点是，不同入口共用同一套 session / history / routing 语义。

这意味着：

- 手机里发起的任务
- Web 里查看的状态
- 聊天渠道里追问的上下文

都可以落到同一个会话上。

对 Android Agent，后续如果要做 Web 控制台、消息入口、桌面端，这一点应该尽早定下来：

- 会话是产品主对象
- 任务、截图、动作流、错误都挂在会话下
- UI 只是会话的不同视图

这样扩产品时不会每个入口各有一套状态。

### 7. 用“真实 showcase”讲产品，而不是只讲架构

OpenClaw 的 Showcase 页面很重要，因为它让用户看到“这个东西到底能拿来干嘛”。

Android Agent 也应该尽早整理自己的 showcase，哪怕先只做 5 个高频案例：

- 打开 App 完成一次明确操作
- 处理一个通知并回复
- 跨 App 复制信息
- 根据截图/页面状态继续操作
- 定时完成重复事务

这既是对外表达，也是对内的产品边界约束。没有 showcase，产品很容易一直停留在“技术看起来很强”。

## 可以吸收但不要照搬的点

### 1. 不要一开始追多渠道大而全

OpenClaw 的广渠道覆盖是它的产品特色，但对 Android Agent 来说，短期不值得学这个“广度”。

更合理的是只抓 1 到 2 个高价值入口：

- Web 控制台
- Telegram 或类似远程消息入口

先把单设备远程操控闭环跑顺，比接十几个渠道更重要。

### 2. 不要过早平台化到插件市场

OpenClaw 的 plugin / community 生态已经有体量支撑。Android Agent 现在更适合先做：

- 本地 workspace skill
- 内部 manifest
- 最小安装/启用机制

先把结构做对，不要一开始就做市场、注册表、分发中心。

### 3. 不要把“Node + Gateway”拆得过重

OpenClaw 的架构有明显“控制平面 / 执行节点”分离，这个思路值得借鉴；但 Android Agent 不必一上来复制出一个很重的分布式系统。

更实际的做法是：

- 先定义协议和对象模型
- 先有一个最小控制面
- 后面再决定是否独立部署 gateway

先拿到产品收益，再做架构重化。

## 对现有想法的直接回应

`qi_note.md` 里已有两点，我认为都值得保留，但要稍微改写成产品语言。

### 1. terminal / shell tool

这确实值得做，而且是高杠杆能力。但它不应只是“多一个 tool”，而应该是：

- 受控能力
- 默认关闭
- allowlist / approval 驱动
- 在 UI 上清楚显示风险等级

如果做对，它能把 Android Agent 从“只会点屏幕”升级成“能联合手机与宿主环境完成任务”的 agent。

### 2. 兼容 OpenClaw 风格的 skills / integration

也值得做，但建议拆成两层：

- 第一层：兼容类似 skill folder / `SKILL.md` 的轻量规范。
- 第二层：再考虑 Telegram / WhatsApp 这类外部入口接入。

先把 skill 规范定出来，后续接入更多入口时复用同一套能力层，会简单很多。

## 建议优先级

如果只选最值得借鉴的 4 件事，我建议顺序是：

1. Web Control UI，先补观察性和远程控制。
2. 安全配对模型，给远程入口和高危 tool 打地基。
3. skill 结构，把长尾能力从 core 拆出去。
4. onboarding 向导，提高首跑成功率。

terminal tool 和消息渠道接入也重要，但它们更适合建立在前面 4 件事之后，不然产品会先变强，再变乱。

## 参考来源

- `.reference/other/openclaw/README.md`
- `.reference/other/openclaw/VISION.md`
- `.reference/other/openclaw/docs/index.md`
- `.reference/other/openclaw/docs/platforms/android.md`
- `.reference/other/openclaw/docs/web/control-ui.md`
- `.reference/other/openclaw/docs/start/wizard.md`
- `.reference/other/openclaw/docs/tools/skills.md`
- `.reference/other/openclaw/docs/plugins/community.md`
- `.reference/other/openclaw/docs/start/showcase.md`
