# Extra20 Recommendation Rollout Plan (Codex)

## Decisions captured from notes
- Accessibility strategy: keep both Android Agent service and AndroidWorld forwarder enabled always.
- Markor bucket weighting: skipped for now.
- Headline KPI: use unique-task metric (`1/20` style), not attempts.

## Rollout order
1. P0 foundations
- open_app alias fix
- eval clean profile (`ask_user`/`write_todos` off)
- a11y dual-service coexistence checks

2. P1 reliability
- explicit completion gate
- completion preflight checks
- dynamic turn budget

3. P3 diagnostics
- scoring probe artifacts/logs

4. P2 capability expansion
- hybrid perception for visual-hard tasks
- optional shell/read_file tool (scoped)

## Why this order
- P0/P1 directly unblock most failed tasks and reduce wasted turns.
- P3 speeds up debugging of residual failures.
- P2 adds structural capability after baseline stability is restored.

## Eval checkpoints
- Checkpoint A: after P0 (expect major reduction in zero-progress failures)
- Checkpoint B: after P1+P3 (expect fewer premature completions and cleaner failure attribution)
- Checkpoint C: after P2 (expect BrowserDraw/BrowserMaze class improvements)
