# Session Review — Aligned Fix List (`f4a699b9..HEAD`)

Consolidates `session_review_claude.md` (Claude) and `session_review_codex.md` (Codex). Each finding was verified against source. Findings are listed severity-first.

**Confidence legend:** HIGH = both reviewers caught it · MEDIUM = one caught + other agreed in alignment · LOW = one caught + other no-opinion.

---

## Critical

### C1. Terminal task text/result not persisted; `SessionError` not recorded
- **Severity:** Critical · **Confidence:** MEDIUM (Codex caught; Claude agreed in alignment)
- **File:** `app/src/main/kotlin/ai/closepaw/app/AgentServiceEventHandler.kt:76-86,127-131`; `app/src/main/kotlin/ai/closepaw/history/SessionRecordingService.kt:226-228`
- **What + why:** `TaskCompleted` calls `completeAgentMessage()` then `recordTaskOutcome(outcome)` — but `recordTaskOutcome` only stashes the outcome enum in memory; the `event.result` text (success summary, `⚠ ...` error string) is never written to the buffer. `SessionError` updates UI status only, no recorder call. Reload of a turn that ended with no prior MessageDelta loses the entire agent row; error turns lose the error text.
- **Source ref:** Codex §1.
- **Fix sketch:** Before `completeAgentMessage()`, append a terminal `ContentBlock.Text(event.result ?: defaultFor(outcome))` (or equivalent) to the buffer; in `SessionError`, record an error block to the active agent message before letting it finalize.

---

## Important

### C2. `rowState` migration bypassed by production writer
- **Severity:** Important · **Confidence:** MEDIUM (Codex caught; Claude agreed in alignment)
- **File:** `app/src/main/kotlin/ai/closepaw/history/SessionRecordMessageMerger.kt:14-21`; `app/src/main/kotlin/ai/closepaw/history/model/MessageConverter.kt:80-81,122-129`
- **What + why:** `MessageRecord.Agent.rowState` and `MessageConverter` round-trip the field, but `mergeAgentSnapshot()` constructs `MessageRecord.Agent` without `rowState`. At `HEAD`, the concrete user-facing break is resumed non-terminal semantics: any persisted row that should stay `Waiting` reloads as `Live`, and any future persisted `Error` row would fall back to `Complete`.
- **Source ref:** Codex §2; relates to Claude "Good-call: Persistence round-trip" — the helper is correct, the production writer bypasses it.
- **Fix sketch:** Pass `rowState` (and `completedTimestamp` if not already) from the snapshot/agent state into `mergeAgentSnapshot`; collapse the duplicated mapping path so writer and converter share one builder.

### I2. Capsule `TaskOutcome.ERROR` discards the actual error message
- **Severity:** Important · **Confidence:** MEDIUM (Codex caught; Claude agreed in alignment)
- **File:** `app/src/main/kotlin/ai/closepaw/ui/overlay/CapsuleStateHolder.kt:243-251`
- **What + why:** `onTaskCompleted(ERROR, message)` always emits `CapsuleMode.Error("Error occurred")`, ignoring `message`. Chat row shows `⚠ Permission denied`; capsule shows generic text — surfaces diverge on the same failure.
- **Source ref:** Codex §3.
- **Fix sketch:** `CapsuleMode.Error(message?.takeIf { it.isNotBlank() }?.let(::sanitize) ?: "Error occurred")` (mirror the sanitization used in `onError()`).

### I3. Reduced-motion contract specified but unwired at every motion site
- **Severity:** Important · **Confidence:** MEDIUM (Codex caught; Claude agreed in alignment)
- **File:** `app/src/main/kotlin/ai/closepaw/ui/theme/Motion.kt:37-56`; `app/src/main/kotlin/ai/closepaw/ui/chat/components/ThinkingIndicator.kt:47-56`; `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/GlowOverlayHost.kt:66-85`; `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsSheet.kt:80-95`
- **What + why:** `ClosePawMotion.reducedMotion()` is implemented but no call site reads it. With animator scale = 0, the thinking pulse, glow breath, and settings page slides keep animating, violating D1 §8.
- **Source ref:** Codex §5; D1 §8 (design_aligned.md), called out as required in `eng-design/track-d2/initial/design_review_claude.md:36`.
- **Fix sketch:** At each motion call site, read `ClosePawMotion.reducedMotion()` and switch to the documented fallback (instant or 120ms fade, no looping decoration; preserve cursor blink as liveness signal).

### I4. Capsule/island status surfaces still miss both the paw glyph and the running-state breath behavior
- **Severity:** Important · **Confidence:** MEDIUM (Codex caught; Claude agreed in alignment)
- **File:** `app/src/main/kotlin/ai/closepaw/ui/overlay/model/CapsuleRenderSpec.kt:23,59`; `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/SmartCapsuleSurface.kt:243-255`; `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/StatusIslandCompose.kt:57-62`
- **What + why:** D1 makes the paw glyph the identity replacement for generic status dots, and the render spec already carries `DotSpec.pulsing = true` for `Running`. At `HEAD`, both surfaces still render a plain colored circle, and `SmartCapsuleSurface` ignores the `pulsing` flag entirely. So the status mark is wrong in both shape and motion: no paw glyph, no running-state breath.
- **Source ref:** Codex §4; Claude logic review N2; design_aligned.md §6.2; README/motion-spec capsule rules.
- **Fix sketch:** Render `R.drawable.ic_paw` at spec size, tint it from semantic status color, and thread the running-state pulse into both surfaces so `DotSpec.pulsing` actually drives the shared breath animation. Keep text label/semantics so status is never color-only.

---

## Nit

### N1. Live-scroll affordance is a generic FAB, not the spec'd `↓ live` pill
- **Severity:** Nit · **Confidence:** MEDIUM (Codex caught; Claude agreed in alignment)
- **File:** `app/src/main/kotlin/ai/closepaw/ui/chat/ChatScreen.kt:291-316`
- **What + why:** Track A §5.1 specifies a bottom-right `↓ live` pill; we render an unlabeled `SmallFloatingActionButton` with default fade/scale.
- **Source ref:** Codex §6.
- **Fix sketch:** Replace with a compact pill using ClosePaw tokens/motion + explicit `live` label.

### N2. `outcomeFooter` emits two spaces after the check glyph
- **Severity:** Nit · **Confidence:** MEDIUM (Claude caught; Codex agreed)
- **File:** `app/src/main/kotlin/ai/closepaw/ui/chat/components/MessageBubble.kt:410`
- **What + why:** `"✓  ${parts.joinToString(...)}"` renders `✓  2 actions · 8.3s` (double space). Track A §4.5 uses single-space separation.
- **Fix sketch:** Single space.

### N3. `truncateWords` allocates `Regex("\\s+")` per recomposition
- **Severity:** Nit · **Confidence:** MEDIUM (Claude caught; Codex agreed)
- **File:** `app/src/main/kotlin/ai/closepaw/ui/chat/components/MessageBubble.kt:452-456`
- **What + why:** Hits every recomposition of every collapsed header. Trivially hoistable.
- **Fix sketch:** Top-level `private val WHITESPACE = Regex("\\s+")`.

### N4. `ThinkingIndicator` lacks `liveRegion` / `contentDescription` semantics
- **Severity:** Nit · **Confidence:** MEDIUM (Claude caught; Codex agreed)
- **File:** `app/src/main/kotlin/ai/closepaw/ui/chat/components/ThinkingIndicator.kt:32-43`
- **What + why:** TalkBack users get no announcement when the agent enters Thinking. `qa-thinking-indicator` testTag is present but no a11y semantics.
- **Fix sketch:** Add `Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "Thinking" }` (Polite, not Assertive, to avoid thrashing during long thinks).

### N7. `formatElapsed` pins `Locale.US` for the decimal `s` format
- **Severity:** Nit · **Confidence:** LOW (Claude caught; Codex no-opinion)
- **File:** `app/src/main/kotlin/ai/closepaw/ui/chat/components/MessageBubble.kt:420`
- **What + why:** Timestamp formatter was de-pinned in `784b8b2e`, but the elapsed string still uses `Locale.US` — on locales using `,` as decimal separator the row mixes formatting policies.
- **Fix sketch:** Drop the `Locale.US` arg (defaults to `Locale.getDefault()`).

### N9. Coverage gaps on the runtime fixes from this session
- **Severity:** Nit (test debt, not a bug) · **Confidence:** LOW (Claude caught; Codex no-opinion)
- **What + why:** No tests pin (a) `d23537e8` chat→capsule done bridge calls `CapsuleStateHolder.onUserResponseSent(callId)` from `ChatScreen.onUserResponse`; (b) `4d0e1168` ThinkingIndicator 4-phase cumulative reveal + Ink tint.
- **Fix sketch:** One small test per fix.

---

## Dropped from the source reviews

- **Claude I1 (`MainActivity` stale booleans)** — dropped. `repairModel` is Compose state; the `ON_RESUME` write recomposes the host call, so `isAccessibilityEnabled` / `isOverlayEnabled` are re-evaluated on the same pass. `deriveRepairModel()` is derived from those same permission reads, so the inconsistent-pair claim does not hold at `HEAD`.
- **Claude N5 (`ActionState.Executing` dead branch)** — dropped. The live reducer does not emit `Executing`, but the persisted schema, converter, tests, and Track A design all still model it. Keeping the render branch is consistent with the declared state surface.
- **Claude N6 (blank-thought divergence)** — dropped. The only current `ThoughtUpdate` emitter trims and returns on empty before dispatch, so there is no live divergence at `HEAD`.
- **Claude N8 (`FivePawProgress` hypothetical off-by-one)** — dropped. Current callers hard-code `totalSteps = 5`, which matches the spec; this is a cleanup idea, not a bug in the reviewed range.
- **Claude "Critical: None new"** — kept as-is (status note, not a finding).
- **Claude "Good-calls"** — preserved as context but not actionable; the `951c82f5` round-trip praise stands but is qualified by C2 above.
- **Codex "Good-calls" §7, §8** — preserved as context, not actionable.

## Suggested merge order

1. **C1** — real recorder data loss.
2. **C2, I2** — persistence semantics and error-surface divergence.
3. **I3, I4** — the remaining motion/identity contract gaps on live status surfaces.
4. **N1, N2, N4** — visible chat affordance / text / a11y polish.
5. **N3, N7, N9** — low-risk cleanup and test debt.
