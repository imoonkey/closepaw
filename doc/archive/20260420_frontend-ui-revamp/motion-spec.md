# Motion Spec — "Breath, not bounce"

Every animation in ClosePaw follows three rules:

1. **Four durations only** — `120ms` (micro), `240ms` (transition), `480ms` (mode), `900ms` (breath).
2. **Two easings only** — `EaseInOutSine` (breath, loop), `EaseOutCubic` (entry, transition). Never `spring`, never overshoot.
3. **One orchestrated entrance per screen** — staggered by 120ms. Everything else after is small and local. Delight is rare by construction.

---

## 1. The Breath — signature motion

The Capsule's paw-print in Running state:

```kotlin
val breath by rememberInfiniteTransition().animateFloat(
    initialValue = 1.0f, targetValue = 1.04f,
    animationSpec = infiniteRepeatable(
        animation  = tween(900, easing = EaseInOutSine),
        repeatMode = RepeatMode.Reverse,
    ),
)
Modifier.graphicsLayer {
    scaleX = breath; scaleY = breath
    // Simultaneous alpha breath, subtler
    alpha = 0.85f + (breath - 1.0f) * 3.75f   // 0.85 ↔ 1.0
}
```

Inhale 900ms, exhale 900ms. Total 1.8s cycle. Calm, considered, alive.

**Do NOT apply breath to:**
- Takeover (paused — paw must *freeze*)
- WaitingForInput/Approval (attention should be on the question, not the paw)
- Done/Error (terminal states — no loop)

---

## 2. Capsule mode transitions

Between any two non-Hidden modes, use `AnimatedContent`:

```kotlin
AnimatedContent(
    targetState = mode,
    transitionSpec = {
        (slideInVertically(tween(240, easing = EaseOutCubic)) { it / 3 } +
         fadeIn(tween(240))) togetherWith
        (slideOutVertically(tween(240, easing = EaseOutCubic)) { -it / 3 } +
         fadeOut(tween(120)))
    },
)
```

**Special cases:**

| Transition | Override |
|---|---|
| Running → Done | Paw blinks once (scale 1.0 → 1.2 → 1.0 over 480ms, claw → moss color crossfade), then the whole capsule `scaleOut(targetScale = 0.96f) + fadeOut` over 480ms after a 900ms hold |
| Any → Error | On enter, shake horizontally: `±3dp, 120ms, 3 cycles`. Paw turns rust, no breath. |
| Running → Takeover | Paw *stops mid-breath* — read current scale, animate to 1.0 in 120ms. Color crossfades claw → amber over 240ms. |
| Hidden → Running | 480ms: capsule slides up from bottom (start offset +24dp), paw stamps on arrival (scale 0 → 1.0, 240ms delayed 240ms into the entrance) |

---

## 3. Action Visualizer

### Tap (click)
```
t=0ms    : claw-filled circle at r=6dp, alpha=1.0
t=0-280  : expands to r=48dp, alpha 1.0 → 0 (EaseOutCubic)
t=80ms   : satellite ring appears, stroke-only, r=12dp
t=80-360 : satellite expands to r=64dp, alpha 0.6 → 0
```

### Long-press (hold)
```
t=0-240  : same tap entry, but ring freezes at r=32dp
t=240+   : inner fill pulses: alpha 0.4 ↔ 0.7 at 900ms EaseInOutSine
t=release: ring collapses to r=0 in 180ms, paw-stamp fades in at tap location
```

### Swipe
```
Path drawn start→end in 360ms (EaseOutCubic).
Stroke is tapered: 4dp at start, 2dp at end.
Perlin-noise offset applied to path: ±1.5px, seeded per-swipe (consistent wobble).
At destination, paw-print stamps in claw, scale 0→1 in 160ms, holds 200ms, fades out 240ms.
Full animation: 760ms — well within one mode-transition budget.
```

### Scroll vs. swipe
Distinguish by color *and* motion:
- **Swipe** (user-like intent): claw, paw-stamp at end.
- **Scroll** (reading intent): ink at 40% alpha, no stamp, no taper.

---

## 4. Chat screen — thinking indicator

Three paw-toes fill in sequence:

```
t=0      : toe₁ ink @ 100%, toe₂ @ 30%, toe₃ @ 30%, pad @ 30%
t=225ms  : toe₂ → 100%
t=450ms  : toe₃ → 100%
t=675ms  : pad  → 100%
t=900ms  : reset — all back to 30%
```

Duration 900ms = one breath-half. Synchronises with the overall motion
language so the app feels *tempo-locked*.

---

## 5. Cold-start entrance (orchestrated)

This is the **one** delight moment in the app. Runs on first composition
of `ChatScreen` per process:

```
t=0      : Paper background fades from Ink → Paper (240ms)
t=120    : Fraunces title "ClosePaw" settles in — translateY(-8dp → 0) + fadeIn, 480ms EaseOutCubic
t=240    : Paw-print stamps in header — scale 0 → 1, claw, 240ms EaseOutCubic
t=360    : Chat area fades in + translateY(8dp → 0), 480ms
t=840    : Capsule slides up from bottom if active, 480ms
```

After `t=1320ms` the app is quiet. No more scheduled motion unless the
user or agent acts.

---

## 6. Edge glow — living halo

Current: static linear gradient on edge.

Proposed: additive drift.

```kotlin
val drift by rememberInfiniteTransition().animateFloat(
    initialValue = -1f, targetValue = 1f,
    animationSpec = infiniteRepeatable(
        tween(8000, easing = EaseInOutSine), RepeatMode.Reverse,
    ),
)
// Apply to radial gradient center:
// center = Offset(edgeAnchor.x + drift * 2.dp.toPx(), edgeAnchor.y)
// Opacity ceiling 0.12 instead of current stronger gradient.
```

The glow appears to breathe with the capsule (same 8s period fits neatly
as ~4.5× breath cycles — offbeat by design, so it feels organic rather
than lock-stepped).

---

## 7. What NOT to animate

Resist the temptation:
- **No button press scales.** Buttons respond with color darken only (pressed state token).
- **No ripple**. Material ripple is off-brand; use a 120ms `Pressed` color instead.
- **No list item enter animations.** Session history renders instantly — it's a ledger, not a stream.
- **No loading skeletons.** Either content is there (paper with text) or the thinking indicator is shown. Skeletons are a web pattern.
- **No parallax.** Nothing in an editorial field journal parallaxes.

Restraint is the point. Every motion you *don't* add makes the motion you
*do* add feel intentional.
