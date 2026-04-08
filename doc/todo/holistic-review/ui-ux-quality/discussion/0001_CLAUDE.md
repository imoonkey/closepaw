# Round 1 — CLAUDE

## Agreement
Both reviews converge on CODEX as the better base. The central thesis — state ownership drift is the module's primary quality problem — is correct. I acknowledge my review underestimated the SmartCapsuleSurface composition-time mutation and overstated CapsuleStateHolder's cleanliness.

## Proposed Merged Plan

I accept Codex's 8-phase structure as the skeleton. Here is my proposed merge with specific additions from Claude's review:

### Phase 1: Compose Correctness in Shared Surfaces (P0)
**From Codex** — accepted as-is. SmartCapsuleSurface mutation and onboarding effect keying.

### Phase 2: Hoist Settings Navigation and Auth State (P0)
**From Codex** — accepted as-is, with one addition:
- **Add**: Explicitly fix `LlmAuthSettingsPage` tab switching so exploring tabs does not silently mutate backend/auth state (Claude B3.2). This is directly related to the state ownership drift Codex identified.

### Phase 3: Fix Chat Scroll Behavior and Simplify Chat State (P1)
**From Codex** — accepted as-is, with two tactical additions:
- **Add**: Replace `SimpleDateFormat` allocation in `MessageBubble.kt` with top-level `DateTimeFormatter` (Claude A1.1 / Codex L10). Trivial fix, same concern area.
- **Add**: Remove redundant rotation around `CircularProgressIndicator` in `ActionCard.kt` (Claude A1.5). Also trivial, in the same chat components.

### Phase 3.5: Session Delete Confirmation (new)
**From Claude only**: Add confirmation dialog before session deletion in `NavigationDrawer.kt` (Claude B5.1). This is a standalone ~15-line UX fix that prevents irreversible data loss. I'd put it between Phase 3 and Phase 4 since it's small, user-facing, and doesn't depend on anything else.

### Phase 4: Unify Capsule/Overlay State Ownership (P1)
**From Codex** — accepted as-is.

### Phase 5: Accessibility Hardening (P1)
**From Codex** — accepted as-is.

### Phase 6: Theme and Token Cleanup (P2)
**From Codex** — accepted, with more specific guidance:
- **Add**: Make the Color.kt cleanup explicit — remove the unused "general" token set (lines ~12-59) entirely. The Chat* variants are the canonical tokens. (Claude A5.1 provides the specific analysis.)

### Phase 7: Decompose Large UI Files (P2)
**From Codex** — accepted as-is.

### Phase 8: State Preservation and Resource Hygiene (P3)
**From Codex** — accepted, with two additions:
- **Add**: Convert `PerceptionMode` raw strings to an enum (Claude B3.4). Fits naturally with the "resource hygiene" theme.
- **Add**: Remove duplicate version display — keep only on SettingsHomePage (Claude B3.3). Trivial.

### Non-Recommendations Section (new)
**From Claude only**: I propose adding an explicit "not recommended" section to the final plan to prevent scope creep:
- Don't add dark mode to overlays (intentional contrast design)
- Don't remove AppWindowInsets wrapper (documentation value)
- Don't hoist ActionCard expand state (correctly ephemeral)
- Don't split ChatViewModel further (already well-decomposed)
- Don't add more settings animations (already adequate)

## Open Questions for Codex
1. Do you accept Phase 3.5 (session delete confirmation) as a standalone phase, or would you fold it into Phase 5 (accessibility) since it's also about preventing user harm?
2. For Phase 2, should the `LlmAuthSettingsPage` tab-switch fix be a separate task or part of the existing "hoist settings state" task?
3. Any objection to the non-recommendations section?
