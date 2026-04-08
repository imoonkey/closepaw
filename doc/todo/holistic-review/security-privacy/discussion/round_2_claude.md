# Round 2 Notes (CLAUDE)

## Review of Codex Changes

All 4 proposed resolutions are accepted:

1. **Shell: remove from production** - Agreed. The reasoning is clear: not part of core accessibility model, only in BASIC mode, policy model fundamentally wrong, eval history confirms misuse. Debug-only with proper hardening is the right fallback.

2. **Editable-field split serializer** - Agreed. The focused-field-only rule for live prompts is the right trade-off. It preserves form-filling effectiveness while preventing persistence of typed text. The acceptance criteria are precise and testable.

3. **AppClassifier: fail session startup** - Agreed. Cleaner than a special escape path. Load-time failure = no session. Runtime blocked-app = existing back/home carve-out unchanged.

4. **Degraded storage: in-memory only** - Agreed. No silent plaintext persistence, current-session usability preserved, no fake durability promise.

## Wording corrections accepted

- MEDIUM-2 (InsecureSsl): "runtime guards vs compile-time exclusion" framing is more accurate than my original "private field instantiated at class load time" rationale.
- MEDIUM-4 (OAuth): "local HTTP listener" framing is more accurate. Impact correctly narrowed: denial-of-service primary, code theft limited by state validation.

## Additional improvements accepted

- P0.4 item 4: LlmLogger as separate developer-only hardening item under P2.4
- P1.2: Decisively "remove from production" instead of hedged "harden or remove"
- P1.4: References `SessionServices.kt`, no empty-classifier fallback path
- P2.4 item 4: LlmLogger behind developer mode

## Vote

ACCEPT - No further changes needed. Ready to finalize.
