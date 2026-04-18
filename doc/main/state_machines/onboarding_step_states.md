# Onboarding Step State Hierarchies

Three sealed interfaces in `OnboardingState.kt` model the transient UI state for onboarding steps:

- `PermissionStepState` — Accessibility / Overlay / Battery (6 states)
- `ApiKeyStepState` — manual API-key entry + OAuth flow (10 states)
- `DemoStepState` — preflight + agent demo run (7 states)

All three implement the marker `OnboardingStepState`, which is what the ViewModel exposes via a single `stepState` field.

## Why three hierarchies, not one

**Disjoint domains.** Permission states model OS settings round-trips. API-key states model network credential validation and OAuth. Demo states model an agent run with credential-error fallback. The states do not share semantics; merging them produces a 23-case sealed type whose members are mutually exclusive by construction.

**Per-step UI dispatch.** `OnboardingScreen.kt` routes each `WizardStep` to a dedicated composable (`PermissionStep`, `ApiKeyStep`, `DemoStep`). Each renderer pattern-matches only its own state set. A flat `OnboardingStepState` would force every renderer to either handle all 23 cases or fall back to a default — exactly the opposite of what sealed types are for.

**Cast cost is local.** The `stepState as? X ?: defaultX` casts in `OnboardingScreen.kt` (5 sites) are the only price. Flattening would push branching into every renderer instead of containing it at the dispatch boundary, and would not reduce line count.

**Test isolation.** `PermissionStepStateTest`, `ApiKeyStepStateTest`, and `DemoStepStateTest` characterize each FSM independently. Splitting the type aligns with how the behavior is actually verified.

## When to revisit

Flatten only if a future change introduces shared transitions across step domains (e.g. a unified retry/error state used by all three). No such transition exists today.
