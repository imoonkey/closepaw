# UX Feedback 0422 — Implementation Summary

Branch: `main` @ `7bede637..f34e6d21` (10 commits, 2026-04-22).

## Decisions (locked before /implement)

- **D1** — Thought preserved end-to-end at full length. Capsule shows compact slice via marquee; chat + history keep full text.
- **D2** — Dedicated `CollapsePill` owns disclosure click + `Role.Button` semantics. Row root no longer clickable so selection/copy on the final answer doesn't fight the toggle.
- **D3** — "Final answer" defined by `Turn.kt:205-209` agent stop criteria: either `complete_task.answer`, or the last text block emitted with no tool calls. Earlier text blocks remain `Text` (mid-stream prose), rendered inside the trace.

## Commits

| Commit | Title |
|---|---|
| `7bede637` | uxfb-1 · preserve full thought through data pipeline |
| `257acbe5` | uxfb-2 · capsule marquee with reduced-motion fallback |
| `2c2fd9e8` | uxfb-2 · 220dp width cap in reduced-motion island branch |
| `ecf599dd` | uxfb-3 · FinalText concept + CollapsePill + MessageBubble split |
| `1eb01daf` | uxfb-3 · no fabricated FinalText + legacy history migration |
| `677fd8dc` | uxfb-4 · ThoughtGroup trace hierarchy + AgentTrace extraction |
| `a6f4e9fe` | uxfb-3 · recorder writes FinalText directly, mirrors live reducer |
| `08ae6378` | uxfb-followup · drop complete_task truncation + anchor header on IME |
| `5a113d8f` | uxfb-fu2 · scope IME handling to onboarding + settings |
| `f34e6d21` | uxfb-fu2 · force onboarding scroll whenever IME is visible |

## State after milestone

- `MessageBubble.kt` slimmed 463 → 89 lines, dispatcher only. New files: `AgentRow.kt`, `AgentTrace.kt`, `AgentSummary.kt`, `CollapsePill.kt`, `model/ContentBlock.kt`.
- `ContentBlock.FinalText` is the structural source of truth for the closing answer; `AgentRow` renders it in a separate, always-visible region below the pill (collapsed default for completed rows; trace folds, conclusion does not).
- Capsule (StatusIsland + SmartCapsule) marquees the full thought; reduced-motion and 220dp width cap protect the island overlay from off-screen growth.
- Recorder (`AgentMessageBuffer.recordFinalAnswer`) writes `FinalText` directly so reload mirrors the live reducer; `MessageConverter.migrateLegacyFinalText` covers pre-uxfb-3 rows.
- Activity-wide `windowSoftInputMode="adjustNothing"` plus targeted `Modifier.imePadding()` on chat bottomBar, OnboardingShell, and SettingsSheet — header anchored, only the input dock floats above the keyboard. OnboardingShell falls back to scrollable layout when `WindowInsets.isImeVisible` is true so portrait API-key step CTAs stay reachable.

## Verification

- `./gradlew :app:assembleDebug :app:test` green; 1185+ tests pass.
- 5 codex `/code-review` rounds → 5 HIGHs surfaced and resolved; final pass clean.
- Real-device QA on EP0110MZ0BC101266W: 4 chat scenarios PASS (`qa-report.md`), IME header anchoring PASS (`qa-screenshots/ime_header_*.png`), Settings IME PASS (`qa-followup2.md`).

## Open follow-ups

- **P3 (deferred):** in expanded state the final answer appears twice — once as the `complete_task` action's `resultSummary` inside the trace, and again as `FinalText` below the pill. Decide whether to drop the duplicate or keep both for redundancy.
