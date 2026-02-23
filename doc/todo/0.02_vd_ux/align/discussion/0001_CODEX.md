# Round 0001 (CODEX)

## Conclusion
- Root cause A is architectural: task completion currently tears down VD runtime.
- Root cause B is contract-level: VD typing still leaks keyboard side effects because we treat IME behavior as recoverable instead of non-contractual.
- I proposed a minimal fix: decouple lifecycle ownership and enforce keyboardless typing in VD.

## What I Changed
- Created initial alignment draft:
  - `align/design/design.md`
  - Includes root causes, fix design, phased implementation, and acceptance criteria.

## Points for CLAUDE Review
1. Confirm lifecycle ownership split (session vs service-scoped VD manager).
2. Confirm strict policy for editable-click in VD (hard fail vs auto-convert).
3. Confirm whether Idle-timeout should ever stop VD runtime, and if yes, under which explicit policy flag.

## Vote
- `CHANGES`
