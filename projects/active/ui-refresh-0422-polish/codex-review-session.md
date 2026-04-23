# Review: ui-refresh-0422 + polish

Scope: `git diff 63bd9776..HEAD`, excluding doc-only commits `151fcd10` and `0266617c`. I skipped doc/task metadata except where repo-wide grep was needed for the `rightSlot` / `todayLabel` / `paperGrain()` checks.

## Per-file verdict table

| file | classification | confidence | evidence |
|---|---|---:|---|
| `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleControlBar.kt` | pure-appearance | high | Only the stop `OutlinedButton` border changed; click routing and mode switch are unchanged (`CapsuleControlBar.kt:140-167`). |
| `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/CapsuleInputBar.kt` | logic-touch | high | The draft/effect/focus logic stayed in place (`CapsuleInputBar.kt:69-110`), but the send control moved into `TextField.trailingIcon`, got new animation/tag wiring, and `maxLines` changed `2 -> 4` (`CapsuleInputBar.kt:112-187`). |
| `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/SmartCapsuleSurface.kt` | logic-touch | high | New local elapsed-time latch and 1 Hz producer were added (`SmartCapsuleSurface.kt:105-115`, `SmartCapsuleSurface.kt:298-355`). The sibling ledger / marquee split is correct (`SmartCapsuleSurface.kt:298-334`). |
| `app/src/main/kotlin/ai/closepaw/ui/chat/ChatScreen.kt` | pure-appearance | high | `ModalNavigationDrawer` only gained `Modifier.paperGrain()` and the live pill was restyled (`ChatScreen.kt:106-133`, `ChatScreen.kt:304-345`); response bridge logic is unchanged and still pinned by `ChatDoneBridgeTest.kt:29-49` (`ChatScreen.kt:356-364`). |
| `app/src/main/kotlin/ai/closepaw/ui/chat/components/AgentTrace.kt` | pure-appearance | high | Changed only the left rule width/color and action-row text color (`AgentTrace.kt:100-110`, `AgentTrace.kt:193-205`). Trace grouping logic is elsewhere and remained pinned by `ThoughtGroupTest.kt:15-115`. |
| `app/src/main/kotlin/ai/closepaw/ui/chat/components/ChatHeader.kt` | pure-appearance | high | Swaps the center title for `PageMasthead` without touching menu/new-chat callbacks (`ChatHeader.kt:54-88`). `ChatHeaderTest.kt:20-43` still passes. |
| `app/src/main/kotlin/ai/closepaw/ui/chat/components/CollapsePill.kt` | pure-appearance | high | Summary text style only (`CollapsePill.kt:64-73`). No state/semantics/callback changes. |
| `app/src/main/kotlin/ai/closepaw/ui/chat/components/EmptyState.kt` | logic-touch | medium | Suggestion payloads are reconstructed from `verb + gloss` and still match legacy strings (`EmptyState.kt:44-52`, `EmptyState.kt:115-120`), but `StaggeredReveal` adds delayed composition/clickability and reduced-motion branching (`EmptyState.kt:165-192`). |
| `app/src/main/kotlin/ai/closepaw/ui/chat/components/MessageBubble.kt` | pure-appearance | high | `SelectionContainer` is still wrapped only around the user text (`MessageBubble.kt:88-94`), `qa-user-bubble` is still present (`MessageBubble.kt:72-74`), and only layout/surface styling changed (`MessageBubble.kt:67-100`). |
| `app/src/main/kotlin/ai/closepaw/ui/chat/components/ThinkingIndicator.kt` | logic-touch | medium | The cadence/tint path is unchanged (`ThinkingIndicator.kt:49`, `ThinkingIndicator.kt:58-68`, `ThinkingIndicator.kt:72-129`), and polite live-region semantics are still present (`ThinkingIndicator.kt:39-45`), but the new visible `Text("Thinking…")` adds an extra semantics node (`ThinkingIndicator.kt:50-54`). |
| `app/src/main/kotlin/ai/closepaw/ui/navigation/NavigationDrawer.kt` | pure-appearance | high | Header text was replaced by `PageMasthead`; close callback and drawer actions are unchanged (`NavigationDrawer.kt:166-184`). |
| `app/src/main/kotlin/ai/closepaw/ui/onboarding/OnboardingShell.kt` | pure-appearance | high | Progress paw tint only (`OnboardingShell.kt:135-143`). |
| `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsHomePage.kt` | pure-appearance | high | Adds masthead/section headers/fleuron around existing settings rows; `onDismiss` and `onNavigate` wiring is unchanged (`SettingsHomePage.kt:49-107`). `SettingsNavTest.kt:27-90` still passes. |
| `app/src/main/kotlin/ai/closepaw/ui/theme/Ornaments.kt` | pure-appearance | high | Pure composable primitives only (`Ornaments.kt:29-101`). Repo-wide grep found no code call sites for `rightSlot` or `todayLabel`; current code callers only use `title` / `leadingPaw` (`ChatHeader.kt:68-72`, `NavigationDrawer.kt:172-176`, `SettingsHomePage.kt:56-60`). |
| `app/src/main/kotlin/ai/closepaw/ui/theme/PaperGrain.kt` | pure-appearance | high | Draw-only modifier (`PaperGrain.kt:41-55`), no layout or pointer logic. Default strength is now `0.04f` (`PaperGrain.kt:41`); code callers are no-arg only (`ChatScreen.kt:108`, `EmptyState.kt:70`). |
| `app/src/main/kotlin/ai/closepaw/ui/theme/Type.kt` | pure-appearance | high | Comment-only change (`Type.kt:17-20`); no runtime behavior changed. |

## Logic changes inventory

1. `SmartCapsuleSurface.kt` (`LOGIC-TOUCH`) — `CONCERN`

   The hot-path part is correct inside one composition lifetime:
   - `Hidden -> Running`: `LaunchedEffect(isRunning)` sets `runningStartedAtMs` when `isRunning` flips true (`SmartCapsuleSurface.kt:110-114`).
   - `Running -> Running` thought churn: the effect key is the boolean, not the full `mode`, so `CapsuleStateHolder.onThoughtUpdate()` recreating `CapsuleMode.Running(thought)` does **not** reset the latch inside the same composition (`CapsuleStateHolder.kt:162-165`).
   - `Running -> Done/Hidden`: the same effect clears the latch when `isRunning` flips false (`SmartCapsuleSurface.kt:112-114`).
   - The ledger `Text` is a sibling of the thought `Text`, and `basicMarquee` stays attached only to the thought node (`SmartCapsuleSurface.kt:298-334`).

   The weak point is that the start time is UI-local, not state-machine-local:
   - The only source of truth is `remember { mutableStateOf<Long?>(null) }` (`SmartCapsuleSurface.kt:111`).
   - `CapsuleMode.Running` carries only `thought`, not a start timestamp (`CapsuleMode.kt:12-14`).
   - `ChatScreen` and `CapsuleOverlayHost` both instantiate `SmartCapsuleSurface` from the *current* mode value, so a fresh composition while already running will start the ledger at "now", not at task start (`ChatScreen.kt:153-190`, `CapsuleOverlayHost.kt:116-156`).
   - `MainActivity` does not opt into `configChanges` handling, so configuration change is a real activity-recreation path for the in-app surface (`AndroidManifest.xml:29-45`).

   Test pinning is missing here. `CapsuleRenderingTest.kt:54-70` only proves the running thought/buttons render; nothing covers elapsed-label continuity across initial mid-run attach, config change, or surface re-creation.

2. `CapsuleInputBar.kt` (`LOGIC-TOUCH`) — `CONCERN`

   The core state machine wiring is still intact:
   - Local draft owner unchanged (`CapsuleInputBar.kt:69`).
   - `pendingInputText` seeding effect unchanged (`CapsuleInputBar.kt:73-78`).
   - `clearDraft` effect unchanged (`CapsuleInputBar.kt:79-83`).
   - `inputEnabled` rule unchanged (`CapsuleInputBar.kt:85-90`).
   - `autoFocus` + IME show path unchanged (`CapsuleInputBar.kt:92-101`).
   - `submit()` still trims, forwards, then clears (`CapsuleInputBar.kt:104-110`).
   - The surface-level submit routing is unchanged: `Hidden -> onSend`, `WaitingForInput -> onUserResponse`, else `onSupplement` (`SmartCapsuleSurface.kt:164-180`).
   - IME send is still wired through `ImeAction.Send` + `KeyboardActions(onSend = { submit() })` (`CapsuleInputBar.kt:183-184`).

   The regression is in the inline-send restructure itself:
   - The send control moved from a sibling `FilledIconButton` into `TextField.trailingIcon` (`CapsuleInputBar.kt:146-173`).
   - An isolated connected run of `CapsuleRenderingTest + CapsuleInputTest` showed all 4 `CapsuleRenderingTest` cases pass, but 3 of 4 `CapsuleInputTest` cases fail because the send control is no longer discoverable by the existing content-description contract (`CapsuleInputTest.kt:35-82`).
   - `maxLines` also changed from `2` to `4` (`CapsuleInputBar.kt:185`; base was `2`), so this is not behavior-neutral even aside from the semantics regression.

   Additional coverage gaps remain:
   - No test pins IME-send execution.
   - No test pins `pendingInputText` restore, `clearDraft` runtime behavior, autofocus/keyboard-show, or the accessibility-mode disable rule.
   - Repo grep found no test that depends on `qa-capsule-send`; the break is on the older accessible-button contract, not on the new tag.

3. `EmptyState.kt` (`LOGIC-TOUCH`) — payload is solid; animation timing is not appearance-only

   The suggestion payloads are preserved exactly:
   - `Suggestion.full` is string concatenation with a single space (`EmptyState.kt:44-46`).
   - The three entries still resolve to `"Check my unread emails"`, `"Turn on Do Not Disturb"`, and `"Search for nearby restaurants"` (`EmptyState.kt:48-52`).
   - Clicks still forward `s.full` (`EmptyState.kt:115-120`).
   - `ChatScreen` still routes suggestion clicks straight to `viewModel::sendMessage` (`ChatScreen.kt:199-203`), and `ChatViewModel.sendMessage()` forwards that text unchanged to `Op.UserInput` / session bootstrap (`ChatViewModel.kt:289-296`).

   The non-appearance part is `StaggeredReveal`:
   - Suggestions are not composed immediately under normal motion; each row waits `index * 80 ms` before becoming visible/clickable (`EmptyState.kt:165-192`).
   - Reduced-motion gets a different branch (`EmptyState.kt:173-176`).

   I did not find any other current test depending on those prompt strings besides the already-known stale `ChatEmptyStateTest.kt`. So the payload path is safe; the reveal timing is simply unpinned behavior.

4. `ThinkingIndicator.kt` (`LOGIC-TOUCH`) — mostly safe, but not strictly appearance-only

   What stayed solid:
   - `liveRegion = Polite` and `contentDescription = "Thinking"` are still applied on the root node (`ThinkingIndicator.kt:39-45`).
   - Tint still resolves through `THINKING_INDICATOR_TINT.resolve()` (`ThinkingIndicator.kt:49`, `ThinkingIndicator.kt:58-68`).
   - Paw-toe cadence logic is unchanged (`ThinkingIndicator.kt:72-129`).
   - `ThinkingIndicatorCadenceTest.kt:13-50` passed; that file now also contains the tint-role pin at `ThinkingIndicatorCadenceTest.kt:45-50`. There is no separate `ThinkingIndicatorTintTest` file.

   The appearance-only claim still does not fully hold here:
   - The old indicator was a single `Surface` + canvas subtree; the new version adds a visible child text node (`ThinkingIndicator.kt:50-54`), so the accessibility tree is different even though the explicit live-region semantics were preserved.
   - No existing test pins merged announcement behavior for the new tree shape.

## Test coverage

Executed during this review:

- `./gradlew testDebugUnitTest --tests ai.closepaw.ui.chat.ChatDoneBridgeTest --tests ai.closepaw.ui.chat.components.ThinkingIndicatorCadenceTest --tests ai.closepaw.ui.overlay.model.CapsuleRenderSpecTest --tests ai.closepaw.ui.overlay.CapsuleStateHolderTest --tests ai.closepaw.ui.overlay.CapsuleApprovalTransitionTest`
  - PASS.
- `./gradlew testDebugUnitTest --tests ai.closepaw.ui.chat.components.ThoughtGroupTest`
  - PASS.
- `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.closepaw.qa.ChatHeaderTest,ai.closepaw.qa.SettingsNavTest`
  - PASS.
- `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ai.closepaw.qa.CapsuleRenderingTest,ai.closepaw.qa.CapsuleInputTest`
  - FAIL: all 4 `CapsuleRenderingTest` cases passed; 3 of 4 `CapsuleInputTest` cases failed on the send-control semantics contract.

What the existing tests do pin:

- `ChatDoneBridgeTest.kt:29-49` pins the chat-to-capsule response bridge in `ChatScreen.forwardUserResponse()`.
- `CapsuleRenderSpecTest.kt:36-52`, `CapsuleStateHolderTest.kt:145-186`, and `CapsuleApprovalTransitionTest.kt:33-103` pin the waiting/approval state machine transitions that `CapsuleInputBar` and `SmartCapsuleSurface` sit on top of.
- `ThinkingIndicatorCadenceTest.kt:13-50` pins cadence and the tint role, but not the combined accessibility tree.
- `ThoughtGroupTest.kt:15-115` pins `AgentTrace` grouping logic, not styling.
- `ChatHeaderTest.kt:20-43` and `SettingsNavTest.kt:27-90` pin the top-level callbacks after the masthead/sectioning changes.

What is still uncovered:

- `SmartCapsuleSurface` elapsed-label continuity across first composition while already running, config change, and any surface re-attachment.
- `CapsuleInputBar` IME-send behavior, autofocus/IME show, `pendingInputText` restore, `clearDraft` runtime behavior, and accessibility-mode disable path.
- `EmptyState` reduced-motion and stagger timing.
- `ThinkingIndicator` merged accessibility announcement behavior after adding the visible `Thinking…` text.
- `ChatScreen` drawer gesture/scrim/insets behavior under `paperGrain()`. Static reasoning says safe because `paperGrain()` is draw-only (`PaperGrain.kt:41-55`), but there is no direct test pin.

## Final verdict

**NO-GO** — the round is not appearance-only: it introduces new runtime behavior in the capsule timer and empty-state reveal, and the inline-send refactor regresses the existing capsule input accessibility/test contract.
