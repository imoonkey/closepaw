Initialized the alignment workspace and wrote the first self-contained aligned draft at `final/design.md`.

This draft uses the CODEX structure as the base because both cross-reviews selected it, but it pulls in CLAUDE's useful detail where that does not make the design less conservative.

Key choices in this round:

- Kotlin-native runtime inside `androidagent`
- build-time exported site bundles instead of raw YAML on device
- session-scoped `OpenWebRuntime` separate from `AndroidPlatform`
- browser-backend abstraction with managed backend as the base and Chrome CDP as optional later backend
- split discovery vs execution tool surface
- curated MVP allowlist
- explicit final `Open Questions` section as the last section of the design

Vote: CHANGES
