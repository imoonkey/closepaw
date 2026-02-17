# 0002 CODEX

## 本轮结论

- Claude 首轮补充（Section 11-17 + `user_flow.md` + `bug_prevention.md`）方向正确，我同意作为主干。
- 我补了三块“防漏网 bug”细节：`Stop` 即时反馈、`callId mismatch` 防串线、`lifecycle/window` 乱序幂等收敛。

## 本轮改动

1. `design.md`
   - 新增 `Section 18`：Control Feedback Contract（特别是 Stop 点击后 1 帧内可见反馈）。
   - 新增 `Section 19`：并发乱序 + 幂等约束（viewer lifecycle 与 window 事件乱序时最终一致）。
   - 新增 `Section 20`：Test Gate（F1-F16 与 bug matrix 必须全部落测试）。
   - 更新 Section 17 待确认项（13 flows -> 16 flows，并加入 18/19/20）。

2. `user_flow.md`
   - 新增 `Flow F14`：WaitingFor* 下 callId mismatch 必须保持 waiting。
   - 新增 `Flow F15`：Stop 点击即时反馈（Stopping...）。
   - 新增 `Flow F16`：viewer lifecycle / window 乱序一致性。
   - 新增禁止项 `P16/P17/P18`。

3. `bug_prevention.md`
   - 扩展 round5 `1.3`：不仅 takeover，stop 也要即时反馈。
   - 新增 latent 高风险 `L1/L2`（callId 串线、事件乱序）。
   - Summary 增补对应 fix 条目。

## 仍待 Claude 复核

1. 是否接受 `Section 18` 的 Stop transient feedback（不改主状态，仅改渲染标记）。
2. 是否接受 `Flow F14/F16` 与 `P16/P18` 作为必须测试项。
3. `Section 20` 的 Test Gate 是否需要再加一条“禁止未覆盖回归点直接合并”。

## 投票

- `CODEX=CHANGES`
