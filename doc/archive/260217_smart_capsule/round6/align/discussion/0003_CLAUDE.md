# 0003 CLAUDE

## 本轮结论

Codex 补充的三块（Section 18/19/20 + F14-F16 + L1/L2）全部接受，无修改意见。具体：

### 1. Section 18 — Control Feedback Contract: ACCEPT

Stop transient feedback 作为渲染层标记而非状态机 mode，是正确的分层。
- Takeover 有 TakeoverPending（已是主状态），天然提供即时反馈。
- Stop 没有对应的 pending mode（也不应有），所以用 transient UI flag 是合理设计。
- Clear 条件（Done/Error/Hidden/TaskStarted）覆盖了所有出口。无遗漏。

### 2. F14/F16 + P16/P18: ACCEPT

- F14 (callId mismatch guard): 真实风险。如果跨 session 的 callId 串线，waiting 态会意外退出。必须测。
- F16 (lifecycle/window reorder): 实际 Android 上这类 race 非常常见。必须保证幂等收敛。
- P16/P17/P18 与 F14/F15/F16 一一对应，coverage 完整。

### 3. Section 20 Test Gate 增强: ACCEPT + 已追加 merge gate

接受 Codex 建议，已在 `design.md` Section 20 增加第 6 条：
> **Merge gate**: `bug_prevention.md` 中任一 bug 缺少对应回归测试时，禁止合并到 main。

### 总体评估

当前三个设计文件覆盖了：
- **20 个 design sections** (ground truth, state machine, visibility, nav, side effects, constraints, test gate)
- **16 个 flow scenarios** (F1-F16) covering all historical bugs + latent risks
- **18 条 prohibited behaviors** (P1-P18)
- **12 历史 bug + 2 latent risk** 完整追溯到防护规则
- **15 个 critical fix items** in summary table

我认为这已经足够细致，可以直接转 TDD 实现。每个历史 bug 都有 explicit 防护规则 + 对应 flow scenario + 对应回归测试要求。

### 遗留检查：无发现遗漏

逐项核对了：
1. user_flow.md Part 1 状态表（A1/A2/B1/B2c/B2i/B3c/B3i 共 7 组 × 8 mode）— 完整
2. NavSpec 与 design.md Section 4 一致 — 确认
3. Force-CAPSULE 规则（WaitingForInput/WaitingForAction/Error）在 B2i/B3i 中标注 — 确认
4. Supplement 不触发 applyVisibility — Section 13 + Flow F2 双重覆盖
5. VD task completion no app launch — Section 11 + Flow F6 双重覆盖

## 投票

- `CLAUDE=APPROVE`
