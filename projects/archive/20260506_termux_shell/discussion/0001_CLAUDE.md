# 0001 — CLAUDE: First Draft

## What This Is

Conservative first draft capturing consensus from both designs and both reviews. All contested decisions are deferred to Open Questions rather than resolved.

## Consensus Captured

Both designs agree on:
- Termux bridge daemon primary, remote secondary, native fallback
- HTTP daemon on localhost (not RUN_COMMAND for runtime — 100KB Binder limit)
- Python stdlib for bridge, no Flask
- Bearer token auth on loopback
- Keep existing ShellTool unchanged
- Coding mode opt-in via SessionConfig
- Conditional tool registration
- System prompt extension when coding enabled
- shell_exec, file_read, file_write as core consensus tools
- Risk-based command classification
- SYSTEM commands (am, pm, su) → deny
- Process tools deferred to stretch

## Cherry-Picked from Codex into Draft

- System invariants section (expanded with shell boundary truth from Codex review)
- Backend state machine with NeedsSetup, Limited, Unavailable states
- Protocol versioning (/v1/ prefix)
- Machine-readable error codes (enumerated)
- output_ref for large outputs (artifact files)
- request_id in bridge protocol
- Bridge-side workspace allow-list validation (not just ClosePaw-side)
- Bootstrap via EXTRA_STDIN (not EXTRA_ARGUMENTS)
- Process-group kill on timeout
- Bridge log redaction, no raw env logging
- Finer-grained task decomposition
- python/node/npm/pip NOT in unconditional safe list (Codex review catch)

## Cherry-Picked from Claude into Draft

- RUN_COMMAND 100KB limit documented as system invariant
- PendingIntent token exchange mechanism
- Bridge idle watchdog (30 min)
- Bridge version checking via /health
- Raw-resource bridge deployment from APK
- Minimal backend interface (exec, readFile, writeFile, healthCheck)

## Open Questions (6)

All substantive disagreements are captured in the Open Questions section of final/design.md:

1. **OQ-1**: Edit primitive — file_edit vs apply_patch
2. **OQ-2**: Tool surface breadth — 5 minimal vs 7+ semantic tools (search, status, list_files, git)
3. **OQ-3**: Command classifier default — allow unknown or ask
4. **OQ-4**: NativeBackend scope — expanded shell or limited/disabled
5. **OQ-5**: Policy integration — extend PolicyEngine vs ToolPolicyMetadata refactor
6. **OQ-6**: Outside-workspace file access — ask user or deny

## Vote

**CHANGES** — First draft, substantive content written. Codex review needed.
