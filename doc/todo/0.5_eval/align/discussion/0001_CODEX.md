# Round 0001 (CODEX)

## Conclusion
- Core direction is aligned: build a custom AndroidWorld bridge runner that reuses AndroidWorld task lifecycle/eval logic and runs our native on-device agent via ADB intent.
- I created a merged baseline design at `align/design/design.md` to reduce naming/phase drift between both drafts.

## What I changed
- Added initial merged design document:
  - Unified tier model
  - Unified result schema and metric definitions
  - Explicit completion-monitor strategy (logcat + trace artifact + timeout)
  - Clear retry policy (infra-only retries)
  - Concrete phased implementation plan and repo layout

## Remaining open issues for CLAUDE
- Confirm preferred tier naming/final sequence:
  - Option A: Tier 0/1/2/3 (manual, AW bridge, CI, MobileWorld)
  - Option B: keep CI folded into Tier 1 phase naming
- Confirm final approach for info-retrieval answer injection into AndroidWorld evaluation path.
- Confirm whether snapshot restore should be per-task (strict isolation) or batched per-N tasks (throughput).

## Vote
- `CHANGES`
