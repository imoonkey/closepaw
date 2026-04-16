# Review: UI/UX Quality (`d9be858a..HEAD`)

**Reviewer**: Codex
**Date**: 2026-04-16
**Baseline**: `317ae8f8`
**Scope**: `app/src/main/kotlin/com/moonkey/androidagent/ui/` and `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt`
**Method**: code review only; no runtime verification performed

## Summary

Most of the requested cleanup is correct at `HEAD`: capsule composition no longer writes state during composition, settings state is keyed/saveable in the right places, destructive dialogs no longer write Compose state from `Dispatchers.IO`, the targeted a11y fixes landed, and overlay nav state now reads from `CapsuleStateHolder` instead of duplicate host flows.

Two scroll-behavior issues remain in `ChatScreen.kt`.

## HIGH

1. `MessageList` still treats "last item is visible" as "user is at the bottom", which breaks the new stickiness policy for tall last bubbles. In `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatScreen.kt:210-217`, `isNearBottom` is derived only from the last visible item index. If the final message is taller than the viewport, the user can scroll up within that same message while `lastVisible` still equals the last index. In that state, streaming updates keep `isNearBottom == true`, so `LaunchedEffect(scrollKey)` at `:235-238` continues auto-scrolling and the FAB at `:261-285` never appears. This still violates the requirement that users who scroll up during a long response should not be yanked back down.

Fix: derive follow state from pixel distance to the real bottom of the list, not item index. For example, compare `viewportEndOffset` against `lastVisible.offset + lastVisible.size` with a threshold, or use `canScrollForward` plus a bottom-distance threshold.

## MEDIUM

1. `scrollKey` does not track all last-message growth, so "follow while streaming" still misses some action-card updates. In `app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatScreen.kt:223-230`, the key only includes `contentBlocks.size` and `last.content.length`. But `last.content` comes from text blocks only (`app/src/main/kotlin/com/moonkey/androidagent/ui/chat/model/ChatMessage.kt:39-42`), while action execution updates can grow the rendered last item by changing an existing action card's `state` and `resultSummary` without changing block count (`app/src/main/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModel.kt:60-86`). `ActionCard` renders that `resultSummary` directly into the bubble height (`app/src/main/kotlin/com/moonkey/androidagent/ui/chat/components/ActionCard.kt:108-114`). When that happens, the last item gets taller but `scrollKey` does not change, so the follow effect never runs.

Fix: derive the key from all height-affecting last-message data, not just text length and block count. A simple option is to fold each block into a small signal that includes block type, text length, action state, and `resultSummary` length.

## Checked, No Findings

- Capsule composition correctness: `previousMode` is now caller-plumbed, the old composition-time write is gone, and input clearing moved into an effect.
- Settings state: `rememberSaveable` usage and tab/provider mutation decoupling look correct after `b0753bf6`.
- Destructive action dialogs: dialog state cleanup is correct and Compose state writes now happen back on Main after IO work.
- A11y targets: onboarding back uses `IconButton`, capsule nav buttons now expose descriptions, and status island colors moved to theme tokens.
- Overlay state unification: duplicate nav-state flows were removed from `CapsuleOverlayHost`, and `ServiceOverlayController` no longer writes the same nav inputs into both the holder and the host.

## Recommendation

**CHANGES_REQUESTED** until the two scroll issues above are fixed.
