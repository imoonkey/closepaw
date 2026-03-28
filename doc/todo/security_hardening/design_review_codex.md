# Review: Tool Approval UI

## Summary

The feature is directionally correct: the missing approval UI is a real product gap, and reusing the capsule flow is the right baseline. The current proposal still has a few design issues that should be fixed before implementation. The biggest problems are security semantics around app changes while waiting for approval, `ALWAYS_ASK` being weakened by allow-lists, and state/ownership being pushed into the overlay layer instead of staying in the session/policy path.

## High

1. Approval is checked against the original app once, but the action can execute later against a different foreground app.

   The design treats approval as "approve this action in this app", but it never requires a second policy check when the user finally taps approve. In the current router flow, policy is evaluated once before the wait, the router blocks on approval, and then execution resumes directly; after the wait it only refreshes the snapshot, and even that masking path still uses the stale `packageName` captured before the wait instead of the current foreground package (`app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:101`, `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:156`, `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:220`). The UX spec only covers "user switches away from capsule" as a visibility issue, not "foreground app changed while approval was pending" (`doc/todo/security_hardening/ux_spec_approval_ui.md:137`). For a security gate, that is too weak.

   Fix: bind approval to the package that was shown in the prompt, then on approval fetch the current package and either re-run policy or cancel/re-prompt if the package changed. The post-wait masking path should also use the current package, not the pre-wait package.

2. The proposed allow-list order weakens `ALWAYS_ASK` and contradicts the user-facing semantics.

   The spec says `ALWAYS_ASK` should still show approval UI, but only hides `Always`; `Session` is still available (`doc/todo/security_hardening/ux_spec_approval_ui.md:143`). The proposed `PolicyEngine` order also puts user allow-lists ahead of approval mode (`doc/todo/security_hardening/ux_spec_approval_ui.md:112`, `doc/todo/security_hardening/design_approval_ui.md:212`). That means a previously remembered app would silently bypass `ALWAYS_ASK`, even though the current engine treats `ALWAYS_ASK` as an unconditional prompt (`app/src/main/kotlin/com/moonkey/androidagent/tool/PolicyEngine.kt:49`).

   Fix: make `ALWAYS_ASK` absolute. In that mode, ignore both session and persistent allow-lists and hide both remember-style affordances, not just `Always`.

3. The design puts policy mutations in the overlay/controller layer, which does not match the current ownership model.

   The proposal says the controller should map `ApprovalResponse` to `ApprovalDecision` and directly call `policyEngine.allowPackageForSession()` / `allowPackagePersistent()` (`doc/todo/security_hardening/design_approval_ui.md:162`). That does not fit the current architecture. User intents are supposed to enter the session as immutable `Op`s, and approval already has a dedicated op type (`app/src/main/kotlin/com/moonkey/androidagent/protocol/Op.kt:89`). `ServiceOverlayController` currently only turns UI actions into ops/callbacks and has no `PolicyEngine` reference (`app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:35`, `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt:137`). `PolicyEngine` itself is created inside session tooling bootstrap, not in the overlay stack (`app/src/main/kotlin/com/moonkey/androidagent/session/SessionToolingBootstrapper.kt:29`).

   Fix: keep approval side effects in the session/policy layer. Extend `Op.Approve` with approval scope, or add a separate session-owned approval command, then mutate the allow-list while handling that op in the session. The overlay should stay a pure input surface.

## Medium

1. Four flat actions are too dense for the current row model and will be awkward on narrow devices.

   The current capsule row is a single horizontal strip with a left button group and right nav group, no wrapping, and fixed button padding (`app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/surface/SmartCapsuleSurfaceParts.kt:66`). `ButtonsSpec` is also intentionally tiny today: `primary` plus `stop` (`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/model/CapsuleRenderSpec.kt:15`). The design first proposes an action list, then backs away, then lands on `primary`/`secondary`/`tertiary`/`stop`, which is a sign the abstraction is being stretched by the UI (`doc/todo/security_hardening/design_approval_ui.md:36`). On phone widths, `[Allow] [Session] [Always] [Deny]` is likely to feel cramped even before accounting for icon/text padding (`doc/todo/security_hardening/ux_spec_approval_ui.md:81`).

   Recommendation: reduce the row to two primary choices and make persistence progressive. For example: `Allow` and `Deny`, plus a compact remember control (`Once / Session / Always`) or a small overflow sheet after `Allow`. That keeps the common case fast and avoids permanently complicating the shared capsule button model for one mode.

2. The null-package and state-resolution edge cases are underspecified.

   The UX spec only hides `Always` when `packageName == null` (`doc/todo/security_hardening/ux_spec_approval_ui.md:63`), but `Session` also cannot remember anything without a package, so the proposed behavior is ambiguous. Separately, the design mentions `onApprovalResolved(callId)` in the state holder (`doc/todo/security_hardening/design_approval_ui.md:248`) but the routing table only wires `ApprovalRequired`; the current event handler would still drop `ApprovalResolved` in its fallback branch (`app/src/main/kotlin/com/moonkey/androidagent/app/AgentServiceEventHandler.kt:162`). By contrast, the existing ask-user path has an explicit immediate transition helper, `onUserResponseSent`, that moves the capsule out of waiting state as soon as the user taps (`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt:168`, `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:85`).

   Fix: if `packageName` is null, collapse the UI to one-shot `Allow` plus `Deny`. Also specify one concrete transition path out of `WaitingForApproval`: either local optimistic state change on tap, or a fully routed `ApprovalResolved` event handled end-to-end. Do not leave both half-specified.

## Recommendation

CHANGES_REQUESTED
