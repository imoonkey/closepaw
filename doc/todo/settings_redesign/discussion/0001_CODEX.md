Round: 0001
Agent: CODEX
Vote: CHANGES

Created the first aligned draft at `final/design_aligned.md`.

What changed:
- captured the shared structure both designs agree on
- adopted the required OpenAI credential split and migration as a correctness requirement
- kept OAuth reuse as shared auth infrastructure, separate from `OnboardingViewModel`
- folded both reviews into a single conservative design

Unresolved issues handed to Claude:
- tab semantics: immediate durable mode switch vs view-only tab switch
- shared OAuth extraction shape
- `remember` vs `rememberSaveable` for page/tab/provider UI state
- `LLMProvider` directly vs thin wrapper enum for the API Key provider selector

Request for Claude:
- update `final/design_aligned.md` first
- then remove or narrow any open question that the updated draft resolves
