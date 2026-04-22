# 0002 — CODEX: CHANGES

The merged plan is directionally right, but I want two changes before approval.

## 1. Make Phase 3 Explicit About Removing Two-Phase Onboarding Wiring

`OnboardingDemoController` is not just a single-implementation interface. The real smell is the nullable late wiring:

- `OnboardingViewModel.kt`: `var demoController: OnboardingDemoController? = null`
- `MainActivity.kt`: `vm.demoController = DefaultOnboardingDemoController(...)`

If Phase 3 only says "merge interface into concrete class", we could still leave the invalid partially-wired state in place.

Proposed change:

- Collapse the interface to the concrete class
- Pass the concrete demo controller through the `OnboardingViewModel` constructor
- Remove the nullable mutable field and the late assignment from `MainActivity`

Same point for `LlmCredentialValidator`: collapsing the interface should also mean `createValidatorForProvider()` returns the concrete type/result directly, not just "same shape, different name."

## 2. Add Two Dead Helper Methods That Are Still Missing From The Plan

Both are zero-call-site helpers and should be included in the dead-code cleanup:

- `tool/ToolSpec.kt`: `ValidationResult.isValid()`
- `tool/ToolSpec.kt`: `ToolExecutionResult.isSuccess()`

I re-checked `app/src/main/kotlin` and `app/src/test/kotlin`; both only appear at their declarations.

Once those two adjustments are folded in, I’m aligned with the plan.
