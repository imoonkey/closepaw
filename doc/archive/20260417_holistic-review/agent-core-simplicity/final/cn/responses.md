# Comment Responses — Agent Core Simplicity

---

## H-1 / P0: 每轮一个屏幕操作的不变量

> 我记得之前有讨论填表之类的，每轮一个太慢了，所以允许一轮多个。我们是不是这里不用卡得那么死，依赖prompt让agent自己选择？你可以看standalone agent def里system prompt怎么写的。

**你说得对。Codex 和 Claude 在这里都搞错了 framing。**

查看了 StandaloneAgentDef 的 system prompt，原文是这么写的：

```
You may batch multiple actions in one turn (e.g. filling several form fields).
However, navigation actions that change the screen (click a link/button that
opens a new page, back, open_app) must be the only screen action in that turn
— observe the result before acting further.
```

这里做了明确区分：
- **表单操作（click field, type, click field, type）**→ 可以一轮批量执行
- **导航操作（click link/button that opens new page, back, open_app）**→ 必须单独执行，然后观察

TurnToolPolicy 的代码注释也写得很清楚：

```kotlin
// Navigation isolation (click-to-navigate, back, open_app should be alone)
// is enforced at the prompt layer, not here.
```

**所以 runtime 允许多个屏幕操作是 intentional design，不是 bug。** prompt 层已经在做区分（导航 vs 表单填充），runtime 不应该硬卡。

**但 Codex 发现的那个 correctness bug 仍然成立：** `actionForNextTurn` 在执行前预计算，如果前面的 tool 失败了，next-turn state 会记录一个从未执行的操作。这个 bug 跟"一轮几个操作"无关，是 action signature 计算时机的问题。

**修正后的 P0 应该是：**

1. ~~硬性强制每轮一个屏幕操作~~ → 保持 prompt-level 的导航隔离策略，不动 runtime
2. **修复 actionForNextTurn 的预计算 bug** → 从实际执行的最后一个操作派生签名，而非预计算
3. 如果多个 tool 中某个失败了，清理 next-turn state，确保 loop detection 只看到真正执行的操作

这大幅缩小了 P0 的范围——从"重写 TurnToolPolicy 和 TurnExecutionPhaseRunner"变成了"修复 action signature 派生逻辑"。

---

## P2: appTier 是该用没用，还是 security migration 移走了？

> appTier是该用没用，还是之前security migration把appTier的check从这里移走了，确实不需要了？

**查了 git history，是后者：security migration 期间 design 演化了，appTier 成了残留。**

具体历程：

- Commit `08ab249` ("feat: agent security — KISS 4+1 layer model") 引入了 AppTier 和 PreTurnContext.appTier
- **原始意图**是在 `capturePreTurnSnapshot()` 中预先捕获 tier，然后在整个 turn pipeline 中传递同一个值
- **实际实现**中，各安全层改为按需调用 `appClassifier.classify()`：
  - Layer 2 (Perception Gate): `capturePreTurnSnapshot()` 中本地用 `tier` 变量做 BLOCKED 遮蔽，但不从 PreTurnContext 读回
  - Layer 3 (Execution Gate): `PolicyEngine.check()` 在执行时重新调用 `appClassifier.classify()`
  - Layer 4 (Memory Gate): `RememberExperienceTool` 也是按需分类

这种按需分类其实更 robust——如果 turn 中间 app 切换了，每层都能独立判断。PreTurnContext.appTier 从引入之日起就没有被 `executeTurn()` 读取过。

**结论：确实不需要了，安全删除。**

---

## Open Questions: What is DTO?

> What is DTO?

DTO = Data Transfer Object（数据传输对象）。

就是只携带数据、不包含业务逻辑的薄壳对象。在这个上下文里指 `PreTurnContext`、`PreparedTurn`、`PlanningPhaseOutput` 这类——只是在函数之间搬运几个字段的 data class。

这里的争论点是：有些 DTO 只被一个调用者用了一次（是纯粹的"信封"），但它们给阶段之间的契约起了名字（比如 `PlanningPhaseOutput` 让你一眼看出 planning phase 输出了什么）。是保留作为文档化手段，还是 inline 掉减少类型数量？对齐后的建议是：先删死字段（`appTier`），整体 DTO 留到 turn loop 稳定后再裁剪。

---

## P1 ExecutorStepPolicy 拆分

> 很好，这是相当于做了个简化，我喜欢KISS方向的修剪。

收到，这条保持不变。
