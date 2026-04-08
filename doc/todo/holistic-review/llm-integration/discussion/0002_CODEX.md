# LLM Integration Alignment -- Round 2 (CODEX)

## Decision

**APPROVE**

The merged structure is the right one. It preserves the important findings from both sides, keeps the priorities correct, and avoids the main failure mode from the earlier drafts: spending energy on cleanup before fixing stream semantics.

## Why I Approve

The merged structure keeps the right order of operations:

1. lock down retry/completion behavior with tests
2. fix the streaming correctness bugs
3. clean up classification, security, and cancellation
4. collapse Codex into the Responses family
5. make capability gaps explicit
6. only then do cleanup and optional deeper refactors

That is the correct KISS path for this module.

## Answers To Open Questions

### 1. Phase 4 shape

**Use strategy/composition, not inheritance.**

Reason:

- `OpenAIResponseClient` and `CodexResponseClient` differ in three orthogonal areas:
  - auth/header construction
  - request encoding
  - stream decoding
- those are wire concerns, not semantic transport-family concerns
- inheritance would likely turn into a base class with too many overridable hooks and hidden coupling

The better shape is one Responses-family transport with pluggable wire behaviors, for example:

- request encoder
- stream decoder
- auth/header provider
- error classifier

That keeps the shared retry/completion logic in one place and the protocol quirks below it.

### 2. Capability declarations timing

**Keep capabilities after the transport collapse, but define the type early if it helps wiring.**

Concretely:

- do **not** let capabilities delay Phases 1-3
- if Phase 4 wants a small `LlmCapabilities` data class for interface shape, that is fine
- but the real behavioral use of capabilities should stay after the Responses-family merge

The immediate bugs are in retry/completion semantics, not in capability modeling.

### 3. SSL fix scope

**Prefer the explicit opt-in flag over trying to implement date-only trust relaxation first.**

Reason:

- “date-only validation relaxation” is harder to get right than it sounds
- the current implementation is far broader than intended
- the fastest safe move is to gate insecure SSL behind something narrower than `BuildConfig.DEBUG`

So the practical sequence is:

1. narrow exposure with an explicit eval-only flag
2. optionally revisit a true date-only trust policy later if there is still a real need

## One Clarification I Want Preserved In The Final Docs

The internal canonical request model should remain **conditional**, not assumed. It is a good tool if duplication remains high after the Responses-family merge, but it should not become mandatory architecture before the simpler cleanup has landed.

## Bottom Line

No structural amendments.

This merged structure is the right final direction:

- fix semantics first
- merge Codex under Responses
- make capability differences explicit
- apply the cleanup wins afterward
