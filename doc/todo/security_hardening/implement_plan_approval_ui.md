# Implementation Plan: Tool Approval UI

**Design Doc**: `doc/todo/security_hardening/design_approval_ui.md`
**Codex Review**: `doc/todo/security_hardening/design_review_codex.md`

---

## Phase 1: Protocol & Policy Layer (T4 + partial T6)

Backend-only, no UI. Independently testable.

### Files
- `protocol/ApprovalTypes.kt` — add `ApprovalScope` enum
- `protocol/Op.kt` — extend `Op.Approve` with `scope` + `packageName`
- `tool/PolicyEngine.kt` — two-tier allow-list, ALWAYS_ASK bypass
- `app/AppSettingsStore.kt` — persistent allow-list load/save
- `session/AgentSession.kt` — handleApproval maps scope to allow-list mutation
- `tool/ToolRouter.kt` — post-approval foreground recheck

### Acceptance
- `allowPackageForSession("x")` → check returns Allow
- `allowPackagePersistent("x")` → survives reset()
- BLOCKED always denied regardless of allow-list
- ALWAYS_ASK ignores allow-list
- Post-approval: if foreground pkg changed, action cancelled

---

## Phase 2: Capsule Mode & RenderSpec (T1 + T5)

UI model layer. No visual changes yet, just data.

### Files
- `ui/overlay/model/CapsuleMode.kt` — add `WaitingForApproval`
- `ui/overlay/model/CapsuleRenderSpec.kt` — add `secondary`/`tertiary` to ButtonsSpec, add render case
- `ui/overlay/CapsuleStateHolder.kt` — add `onApprovalRequired()`, `onApprovalResolved()`
- `app/OverlayLocationPolicy.kt` — add WaitingForApproval to visibility guards, NavSpec

### Acceptance
- Existing modes compile unchanged
- `WaitingForApproval` produces 4-button spec (or 2-button when pkg=null)
- hasActiveTask = true, capsule forced visible, nav hidden

---

## Phase 3: Event Routing & UI (T2 + T3)

Wire the full pipeline. Render buttons.

### Files
- `app/AgentServiceEventHandler.kt` — handle `ApprovalRequired`
- `app/ServiceOverlayController.kt` — `onApprovalRequired()`, `onApprovalResponse()` callback
- `ui/overlay/compose/CapsuleOverlayHost.kt` — add `onApprovalResponse` callback
- `ui/capsule/surface/SmartCapsuleSurface.kt` — add `onApprovalResponse` param
- `ui/capsule/surface/SmartCapsuleSurfaceParts.kt` — render secondary/tertiary in Row2, icon functions

### Acceptance
- ApprovalRequired event → capsule shows WaitingForApproval
- Button tap → optimistic transition + Op.Approve submitted
- Session/Always scope flows to session handler → allow-list mutation
- Build + lint pass

---

## Phase 4: Build, QA, Commit

- `./gradlew assembleDebug lint test`
- Device QA on physical device
- Git commit
