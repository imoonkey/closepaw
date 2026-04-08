# Round 1 Notes (CODEX)

## Review Outcome

Claude's first aligned draft is close. I updated the final docs for three reasons:

1. Resolve the 4 open questions with concrete design choices
2. Correct the `InsecureSsl` wording so it describes the real problem: runtime guards instead of compile-time exclusion
3. Correct the OAuth callback wording so it describes the actual HTTP listener pattern and its primary risk

## Responses to Open Questions

### 1. Shell disposition

Remove `shell` from production agent modes in the first hardened draft.

Reasoning:
- It is not part of the core accessibility automation model
- It is only exposed through `AgentMode.BASIC`, not the default `PRO` path
- The current policy model is fundamentally wrong for a file/system capability
- The repo's own eval history shows repeated shell misuse and low-value detours

If eval/dev still needs shell, keep a separate debug-only tool with argv-only execution, binary/path allowlists, and a separate data-access policy.

### 2. Editable-field suppression

Do not use one blanket rule.

Proposed default:
- Password fields: always redact everywhere
- Non-password editable fields: always redact from history, checkpoints, and traces
- Live prompt serializer: allow raw text only for the currently focused editable field
- All other editable fields: expose structure, focus state, and non-empty/length markers instead of raw text

Reasoning:
- This keeps note-editing and form-filling workable
- It stops the persistence layer from becoming a copy of typed screen text
- It uses state the code already has (`isEditable`, `isFocused`) without requiring a much more complex first draft

### 3. AppClassifier fail-closed behavior

Fail session startup if `app_tiers.json` does not load.

No special back/home carve-out is needed for this load-time failure path. The classifier is built during session creation, so the clean design is: no classifier, no agent session. The existing runtime back/home carve-out remains correct when the classifier is available and the foreground app is blocked.

### 4. Degraded encrypted storage

Allow credentials entered after failure to live in memory for the current process only. Never persist them to plaintext. Restart requires re-entry or re-authentication.

That is the cleanest security/UX trade-off:
- no silent plaintext persistence
- current-session usability preserved
- no fake durability promise after process death

## Corrections Applied to Final Docs

1. `final/review.md`
- Replaced the `InsecureSsl` finding wording with the accurate compile-time-vs-runtime framing
- Replaced the OAuth callback finding wording with the accurate local HTTP-listener framing
- Converted the 4 open questions into proposed Round 2 resolutions

2. `final/improvement_plan.md`
- Converted the shell question into a concrete plan: remove from production, debug-only if retained
- Converted the editable-field question into a concrete serializer policy
- Converted the AppClassifier question into a concrete fail-session-start decision
- Clarified degraded-storage behavior for in-memory current-session credentials
- Added `LlmLogger` follow-up to the debug artifact hardening work

## Vote

CHANGES

Claude should review the focused-editable-field prompt rule carefully, but I think this is the simplest defensible default for the first aligned draft.
