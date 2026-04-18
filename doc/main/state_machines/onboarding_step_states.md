# Onboarding Step State Hierarchies

Three sealed interfaces in `OnboardingState.kt` model the transient UI state for onboarding steps:

- `PermissionStepState` — Accessibility / Overlay / Battery (6 states)
- `ApiKeyStepState` — manual API-key entry + OAuth flow (10 states)
- `DemoStepState` — preflight + agent demo run (7 states)

All three implement the marker `OnboardingStepState`, which is what the ViewModel exposes via a single `stepState` field.

## Why three hierarchies, not one

**Disjoint domains.** Permission states model OS settings round-trips. API-key states model network credential validation and OAuth. Demo states model an agent run with credential-error fallback. The states do not share semantics; merging them produces a 23-case sealed type whose members are mutually exclusive by construction.

**Per-step UI dispatch.** `OnboardingScreen.kt` routes each `WizardStep` to a dedicated composable (`PermissionStepContent`, `ApiKeyStepContent`, `DemoStepContent`). Each renderer's `when` is exhaustive over its own sealed type — the compiler proves every state is handled.

**Flattening loses exhaustiveness.** A unified `OnboardingStepState` with 23 members would make every renderer's `when` non-exhaustive over the parent type. Each renderer would then have to encode subset-membership ("am I a permission state?") in code — duplicated across all three call sites and unverifiable by the compiler. The current sealed split lets the type system carry that invariant.

**Narrowing cost is local.** The `stepState as? X ?: defaultX` casts at `OnboardingScreen.kt:64,81,98,114,137` are the only price (one per step instance). They contain the dynamic check at the dispatch boundary so renderers stay statically typed.

**Test isolation.** `PermissionStepStateTest`, `ApiKeyStepStateTest`, and `DemoStepStateTest` characterize each FSM independently. Splitting the type aligns with how the behavior is actually verified.

## When to revisit

Flatten only if a future change introduces shared transitions across step domains (e.g. a unified retry/error state used by all three). No such transition exists today.
