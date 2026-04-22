# Motion deltas

> Every new motion in this round reuses an existing `ClosePawMotion` duration
> and easing. No new primitives. Reduced-motion contract honored at every
> call site.

## EmptyState staggered reveal

| Step | Element | Delay | Motion |
|---|---|---|---|
| 0 | "What can I help you with?" headline | 0ms | `fadeIn(TraceEnter) + slideInVertically(8dp → 0)` |
| 1 | Hairline rule | 80ms | same |
| 2 | Suggestion 1 | 160ms | same |
| 3 | Suggestion 2 | 240ms | same |
| 4 | Suggestion 3 | 320ms | same |

Total perceived load = ~560ms. The top-right paw is rendered immediately at
full alpha (it anchors the composition; the rest enters around it).

Reduced-motion: every step renders visible immediately, no animation.

## Capsule ledger counter

Updates 1Hz while `mode is CapsuleMode.Running`. The counter text itself is
not animated — it just changes value. The `animateColorAsState` on the status
dot (`SmartCapsuleSurface.kt:242`) keeps its existing `StatusFlip` (120ms).

Reduced-motion: ticker keeps running (status signal, not decoration — same
class as the streaming cursor blink per `Motion.kt:42-43`).

## Send-button breath

| Phase | From | To | Duration | Easing |
|---|---|---|---|---|
| 1 | scale 1.0 | scale 1.04 | 120ms (`SurfaceSwap / 2`) | `EaseOutCubic` |
| 2 | scale 1.04 | scale 1.0 | 120ms (`SurfaceSwap / 2`) | `EaseOutCubic` |

Triggered exclusively on the click that produces a non-empty submit. No breath
on empty/disabled clicks.

Reduced-motion: skip the animation entirely; submit immediately.

## Fleuron and PageMasthead

No motion. They are static ornaments. (Resist the urge to animate the
fleuron in. Print fleurons don't fade in — they were always on the page.)

## Paper grain

No motion. The texture is baked once per session.

## What stays untouched

- Capsule breath (`CapsuleBreath = 900ms`, `EaseInOutSine`, alpha 0.6 → 1.0
  reverse loop) — unchanged.
- Paw-toe thinking sequence — unchanged.
- Status dot color flip — unchanged.
- Trace item enter — unchanged.
- Settings page slide — unchanged.
- Streaming cursor blink — unchanged.
- Overlay fade-out — unchanged.
