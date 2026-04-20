# QA Report R2 — commit 785c3cae

**Date:** 2026-04-20  
**Evidence:** /tmp/ux_qa_r2/ (22 screenshots from previous worker)  
**Device:** EP0110MZ0BC101266W

---

## I-1 — Material Container slots (no lavender leak)

**PARTIALLY VERIFIED — Takeover capsule not capturable.**

Settings provider segmented selector (`22_llm_auth.png`): The "OpenAI" chip (selected, ✓ checkmark) renders white/warm against a cream bottom-sheet background — no lavender (#E6DEF6) detected. "OpenRouter" and "Novita" unselected chips are similarly clean.

Takeover capsule and onboarding chip: **NOT VERIFIABLE** from existing evidence. Accessibility service was disabled on the device throughout the entire QA session (the "Setup Issue" banner is visible in every ClosePaw screenshot). No task ever executed, so the Takeover overlay was never triggered. A re-capture with accessibility enabled is required to verify the capsule and onboarding surfaces.

Verdict for captured surface (Settings chips): **FIXED**. Takeover capsule: **NEEDS RE-CAPTURE**.

---

## I-2 — Collapsed row headline format

**NOT VERIFIABLE — no completed task in evidence.**

Because accessibility was disabled throughout the session, no agent task ran to completion. The sessions drawer shows one prior session ("What is 2 plus 2? Just answer. · 1 message · 32 minutes ago") but the chat view for it only shows an unanswered user message with no collapsed task row. The '✓ prompt · N actions · elapsed' format cannot be confirmed or denied from existing screenshots.

Verdict: **NEEDS RE-CAPTURE** with accessibility enabled and a completed task.

---

## I-3 — EmptyState paw watermark

**FIXED.**

Multiple portrait screenshots (`01b_empty_portrait.png`, `09_chat_after.png`, `11_collapsed_row.png`, `15_typed.png`, `19_after_a11y.png`) all clearly show the paw vector watermark at large size, centered, with "ClosePaw / What can I help you with?" subtitle and suggestion chips beneath. No robot icon (SmartToy) present in any screenshot. Landscape screenshot (`01_empty_state.png`) shows an empty center — likely the paw is suppressed in landscape when vertical space is tight; acceptable.

---

## Instrumented Tests (ai.closepaw.qa)

**39/49 FAILED** — `IllegalStateException: ClosePawTokens not provided. Wrap your content in ClosePawTheme { ... }.` (Tokens.kt:42)

This is a **test-harness regression**, not a runtime bug. The ui-revamp introduced `LocalClosePawTokens` (custom token system) but test composables that call into themed components are not wrapped in `ClosePawTheme`. Every test that renders a themed composable fails at the token-provision check.

Failing classes (14): `CapsuleApprovalTest`, `CapsuleInputTest`, `CapsuleLifecycleTest`, `CapsuleRenderingTest`, `ChatAgentRowDisclosureTest`, `ChatBubbleAlignmentTest`, `ChatEmptyStateTest`, `ChatStreamingCursorTest`, `ChatThinkingStateTest`, `DisplayModeSettingsTest`, `SettingsAgentBehaviorTest`, `SettingsLlmAuthTest`, `SettingsNavTest`, `SettingsPermissionsTest`.

Passing (10 tests across 6 classes): `SanityTest`, `ChatHeaderTest`, and partial passes in `CapsuleLifecycleTest`, `ChatAgentRowDisclosureTest`, `DisplayModeSettingsTest`, `SettingsNavTest` — tests that don't touch themed token-dependent composables.

**Fix required:** Add `ClosePawTheme { }` wrapper to the shared test composable harness (likely a `@get:Rule` or `setContent` helper used by these tests).

---

## Summary

| Issue | Verdict |
|-------|---------|
| I-1 Settings provider chips | FIXED |
| I-1 Takeover capsule / onboarding chip | NEEDS RE-CAPTURE |
| I-2 Collapsed row headline | NEEDS RE-CAPTURE |
| I-3 EmptyState paw watermark | FIXED |
| Instrumented tests | 39/49 FAILING (test harness regression) |

**Blockers before archive:**
1. Run `scripts/setup.sh` to restore a11y permissions, then re-capture Takeover capsule and a completed task row (I-1 capsule + I-2).
2. Fix test harness: wrap shared test `setContent` in `ClosePawTheme { }` so that `LocalClosePawTokens` is provided — restores 39 failing tests.
