# 0008 CLAUDE

## 本轮结论

Codex 0007 落地干净利落。三项待确认全部拍板，无遗留分歧。

### 逐项验证

1. **Section 2.3 force-CAPSULE state invariant** — 已在 design.md 生效，Section 3 VD visibility rules 加了 force 步骤。`applyVisibility()` 现在双重保护：事件驱动初始触发 + 状态不变量兜底。✓

2. **⊖ hidden in WI/WA/Error** — Codex 选择"隐藏"方案（非 no-op），已落实到：
   - Section 4 NavSpec mode 覆盖规则 ✓
   - user_flow.md B2c.WI/WA/E 和 B3c.WI/WA/E 的 Row2-R 列 ✓
   - Section 20 Test Gate #7 (`⊖ hidden` 断言) ✓
   - bug_prevention.md L3 Prevention rules + Verification ✓

3. **L3 latent risk 覆盖** — 规则 + 测试断言均已补齐。✓

### 最终一致性确认

在 0006 穷举验证的基础上，追加验证本轮改动的影响范围：

| 检查项 | 结果 |
|--------|------|
| B2c.WI/WA/E Row2-R 与 Section 4 mode 覆盖一致 | ✓ (📱 👁, ⊖ hidden) |
| B3c.WI/WA/E Row2-R 与 Section 4 mode 覆盖一致 | ✓ (📱, ⊖ hidden) |
| B2i/B3i Force-CAPSULE 由 Section 2.3 state-invariant 保障 | ✓ |
| Section 20 Test Gate #3 + #7 覆盖 force + ⊖ hidden | ✓ |
| F1 ⊖ toggle 不受影响（Running 下 ⊖ 仍可见） | ✓ |
| 其余 F2-F16 不涉及 ⊖ in WI/WA/E，无影响 | ✓ |

### 总体评估

三文件（design.md / user_flow.md / bug_prevention.md）现在完全一致：

- **22 个 design sections**（含 2.3 新增 + Section 4 mode 覆盖 + Test Gate #7）
- **16 个 flow scenarios**（F1-F16 全部通过 Section 21 合规验证）
- **18 条 prohibited behaviors**（P1-P18，design.md Section 16 与 user_flow.md Part 4 一一对应）
- **12 历史 bug + 3 latent risks**（L1/L2/L3）完整追溯到防护规则
- **16 个 critical fix items** in summary table
- **7 条 test gate items** 全覆盖

无剩余分歧。无新增改动。可以进入 TDD 实现。

## 投票

- `CLAUDE=APPROVE`
