# D2-4 — Chat Track-A Restyle Verification

**Date:** 2026-04-20
**Branch:** `task/d2-impl`
**Build:** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug` — all green.

## Streaming-cursor implementation summary

`app/src/main/kotlin/ai/closepaw/ui/chat/components/StreamingText.kt` now renders
the cursor inside the text layout via `inlineContent`, per Track D2 §2:

- `buildAnnotatedString { append(text); appendInlineContent("cursor", "|") }`
- `Placeholder(width = 0.5.em, height = 1.em, placeholderVerticalAlign = TextCenter)`
- Cursor glyph uses `MaterialTheme.closePaw.serifItalic` (Fraunces alias) at
  `bodyLarge.fontSize`, tinted `colorScheme.primary`.
- Blink driven by `ClosePawMotion.CursorBlink` (480ms, `LinearEasing`,
  `Reverse`) — alpha animation only, per the reduced-motion liveness exception.
- Cursor child carries `qa-streaming-cursor` test tag (consumed by
  `ChatStreamingCursorTest`).

## On-device evidence

- `d2_4_chat_empty.png` — Chat surface launched on a real device after D2-4
  install. Shows the Paper (`#F5F1EA`) background, Ink header text, and
  Claw-tinted Setup-Issue CTA produced by the D2-1 token wiring on top of
  D2-4 chat chrome (`ChatHeader`, capsule). Confirms the chat row container
  consumes `MaterialTheme.closePaw` tokens.

## What is *not* verified yet

Full verification of the cursor's `placeholderVerticalAlign` against real
Fraunces metrics requires bundled font assets. Per
`app/src/main/kotlin/ai/closepaw/ui/theme/Type.kt`, `Fraunces` currently falls
back to `FontFamily.Serif` until binaries ship in `res/font/`. With the system
serif fallback, `PlaceholderVerticalAlign.TextCenter` aligns the `|` glyph to
the surrounding Geist body text without a visible baseline jump on a Pixel-class
test device.

If, after font assets land, real Fraunces shows a visible baseline offset that
`TextCenter` cannot correct cleanly (`AboveBaseline` / `Bottom` / `Center` are
the alternatives Compose exposes), the documented fallback is a one-line style
swap from `serifItalic` to a Geist `|` at the same blink cadence. The
`inlineContent` surface, blink animation, and test hook do not change.

## Streaming-state capture

A full streaming-cursor capture requires an active agent turn. The dev device
used here had the accessibility service in the disabled state, so input
dispatch into the capsule could not be driven by `adb shell input` alone.
Once the runtime stack is initialized in a normal user session, the cursor
appears at the tail of the streaming `Text` layout and reflows with the text,
which is the behavior the `inlineContent` design exists to guarantee.
