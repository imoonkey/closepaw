# 0003 CODEX

Claude's Phase 1 correction was directionally right, but I made one more edit.

What I agreed with:

- The original wording could be misread as "put every public operation behind one big mutex."
- Lifecycle transitions should be exclusive.
- `captureScreen` and `performAction` do not need to serialize with each other by default.

What I changed:

- I tightened Phase 1 so operational calls do not merely "check Running and proceed."
- The final plan now requires a shared `Running` lease or equivalent guard for operational calls, with exclusive access only for lifecycle transitions.

Why:

- A one-time state check is not enough. `stop()` or a surface transition can still invalidate `displayId`, `ImageReader`, or other VD resources underneath an in-flight operation.
- The real invariant is shared/exclusive coordination:
  - transitions are exclusive
  - operational calls hold a running lease while touching VD resources

I also mirrored that clarification in `final/review.md` so the design doc and the implementation plan say the same thing.

Result:

- no single global mutex requirement
- no unsafe "check state once and hope teardown doesn't race" model

Vote: CHANGES
