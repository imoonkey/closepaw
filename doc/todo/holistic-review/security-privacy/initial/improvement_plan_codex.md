# Security and Privacy Improvement Plan (Codex)

**Date:** 2026-04-08  
**Companion review:** `doc/todo/security-privacy/initial/design_codex.md`

## Goal

Reduce the gap between the app’s intended safety model and its actual data/control boundaries, without fighting the core product requirement that this is a high-trust Android automation agent.

The plan is ordered by containment value, not implementation convenience.

## Priority 0: Contain the Current Exposure

### P0.1 Close the Exported-Intent Control Plane

**Why first**

This is the easiest path for another app on the device to reconfigure agent behavior, route traffic to a hostile endpoint, enable traces, switch into BASIC mode, and trigger a goal.

**Changes**

1. Keep `MainActivity` exported only for normal launcher semantics.
2. Reject or ignore all security-sensitive extras from external callers in production:
   - API keys
   - base URL overrides
   - backend/model/agent-mode changes
   - debug/trace flags
   - excluded-tools overrides
   - goal auto-start
3. If internal/test automation still needs those overrides:
   - move them to a separate internal action or activity,
   - protect that entry with a signature permission or debug-only build gate,
   - keep all overrides session-scoped only.
4. Require explicit in-app confirmation before executing any externally supplied goal.

**Acceptance criteria**

- An external `ACTION_MAIN` launch cannot persist API keys or routing changes.
- An external launch cannot force BASIC mode.
- An external launch cannot auto-start the agent without an explicit user confirmation step.

**Likely files**

- `app/src/main/AndroidManifest.xml`
- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivityIntentPayload.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivityIntentApplier.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt`

### P0.2 Move Privacy Gating Into the Capture Layer

**Why first**

Blocked-app masking currently happens after capture. That is too late to protect traces, screenshots, and some post-action observations.

**Changes**

1. Determine current package and tier inside the capture stack, not only after `captureScreen()` returns.
2. If the foreground app is `BLOCKED`:
   - return a masked snapshot immediately,
   - do not write raw tree artifacts,
   - do not write sanitized tree artifacts,
   - do not write screenshots,
   - do not emit post-action observations containing captured content.
3. Remove ad hoc post-action capture sites and replace them with one shared helper that always returns a policy-filtered observation.

**Acceptance criteria**

- With tracing enabled on a blocked app, no screenshot or tree artifact is written.
- `open_app` and `UIActionInvocation` cannot return raw blocked-app observations.
- A blocked-app session produces only masked snapshots and policy warnings.

**Likely files**

- `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayCaptureCoordinator.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityScreenshotCapturer.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayScreenshotProcessor.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/OpenAppTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/handlers/UIActionInvocation.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ObservationBuilder.kt`

### P0.3 Add Source-Level Accessibility Sanitization

**Why first**

Right now the code has one main representation of screen text and reuses it everywhere. That keeps the agent effective, but it also turns prompts, history, traces, and checkpoints into copies of the same sensitive capture.

**Changes**

1. Extend perception models with sensitivity metadata:
   - `isPassword`
   - `isEditable`
   - maybe `sensitivity = NORMAL | EDITABLE | SECRET | BLOCKED`
2. At capture time:
   - suppress text/desc/hint for password fields,
   - strongly consider suppressing text for editable fields by default,
   - optionally keep structural cues needed for action grounding.
3. Split serializers by audience:
   - action-grounding serializer,
   - LLM prompt serializer,
   - history serializer,
   - trace/debug serializer.
4. Do not persist the same high-fidelity prompt JSON into history/checkpoints unless explicitly needed.

**Acceptance criteria**

- Password fields never appear with raw text in prompt/history/trace output.
- Editable text is either redacted or explicitly justified by a policy switch.
- `PerceptionElement` or equivalent carries enough sensitivity context for downstream serializers.

**Likely files**

- `app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/model/Models.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnPlanningPhaseRunner.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ObservationBuilder.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/trace/*`
- `app/src/main/kotlin/com/moonkey/androidagent/history/*`

### P0.4 Fail Closed for Long-Lived Secrets

**Why first**

If encrypted storage is unavailable, the safest fallback for long-lived secrets is not plaintext persistence. It is reduced functionality and explicit user intervention.

**Changes**

1. Remove plaintext fallback for:
   - OAuth refresh tokens
   - OAuth id tokens
   - manual provider API keys
2. If encrypted storage fails:
   - keep access tokens in memory only for the current run if necessary,
   - require re-authentication or re-entry after restart,
   - show a blocking or prominent degraded-security banner.
3. Remove the shared-storage API-key bootstrap path entirely.

**Acceptance criteria**

- No long-lived secret is written to plain `SharedPreferences`.
- Encryption failure is visible to the user.
- There is no production path that reads `api_key.txt` from shared storage.

**Likely files**

- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/auth/OAuthCredentialStore.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingStore.kt`
- `app/src/main/AndroidManifest.xml`

### P0.5 Remove or Quarantine `shell` From Production Agent Modes

**Why first**

`shell` is the largest mismatch between the user-visible approval model and the real capability surface.

**Changes**

Choose one of these, in order of preference:

1. **Best:** remove `shell` from production agent definitions entirely.
2. **Acceptable:** keep it only in debug/test builds or behind an explicit developer mode switch.
3. **If it must remain:** execute only an allowlisted binary+arg vector, never `sh -c`, and restrict readable paths to an allowlisted subset.

Even in option 3, the policy engine should not classify `shell` by foreground app alone. It needs a separate data-access policy.

**Acceptance criteria**

- Production BASIC mode cannot invoke unrestricted shell commands.
- Shell commands cannot use shell metacharacters or command chaining.
- Shell access is denied outside approved binaries and paths.

**Likely files**

- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/ShellTool.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/PolicyEngine.kt`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolName.kt`

## Priority 1: Hardening After Containment

### P1.1 Make Insecure TLS a Compile-Time Debug-Only Feature

**Changes**

1. Move `InsecureSslConfig` behind a debug-only source set or equivalent wrapper.
2. Make release code unable to reference the insecure trust manager at all.
3. Validate base URL overrides:
   - require `https`,
   - optionally require a production allowlist outside debug builds.

**Acceptance criteria**

- Release builds do not compile any insecure TLS helper.
- Production base URL overrides reject non-HTTPS URLs.

### P1.2 Reduce Logging and External Debug Artifact Exposure

**Changes**

1. Remove logs for:
   - decoded `id_token` claims,
   - OAuth account IDs,
   - user emails,
   - externally supplied goal text.
2. Reduce `LlmLogger` to developer-only use, or move verbose logs to a clearly private local file.
3. Require explicit developer mode for screenshot/tree debug artifact persistence.

**Acceptance criteria**

- No token claims or account identifiers appear in normal logs.
- Debug artifact writing is opt-in, obvious, and absent in production flows.

### P1.3 Minimize Manifest Permissions and Split Dev-Only Capabilities

**Changes**

1. Remove `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE`.
2. Audit whether Shizuku-specific capabilities should be hidden behind a dev/advanced flavor or setting.
3. Keep the public privilege story simple:
   - accessibility,
   - overlay,
   - internet,
   - optional advanced/dev capabilities.

**Acceptance criteria**

- Manifest contains no stale storage permissions.
- Production capability set is clearly smaller than the dev/debug set.

### P1.4 Add Retention Controls for History, Memory, and Traces

**Changes**

1. Add user-visible retention settings for:
   - session history,
   - checkpoints,
   - memory files,
   - traces/debug artifacts.
2. Add one-tap secure wipe for traces and stored sessions.
3. Default traces to off, with strong user messaging when enabled.

**Acceptance criteria**

- Users can delete persisted session/trace data without manual filesystem access.
- Trace retention is bounded rather than indefinite.

## Priority 2: Follow-Up Architecture Work

### P2.1 Replace the Localhost OAuth Callback With an Android-Native Redirect

**Why**

The current localhost callback flow works, but it is not the natural Android trust boundary.

**Changes**

1. Prefer a deep-link redirect or AppAuth-style custom tab callback.
2. If localhost remains, bind explicitly to loopback and harden request validation.

**Acceptance criteria**

- OAuth callback does not rely on a wildcard-bound local HTTP server.

### P2.2 Add Security Regression Tests

**Required tests**

1. External intent with sensitive extras is ignored.
2. Blocked apps produce no trace screenshots/trees.
3. Password/editable fields are redacted from prompt/history serializers.
4. Encrypted-storage failure does not persist refresh tokens or API keys in plaintext.
5. Shell validator rejects chaining/metacharacters and forbidden paths.

## Suggested Delivery Sequence

### Milestone A

- P0.1 exported-intent lockdown
- P0.2 capture-layer privacy gate
- P0.4 fail-closed secret storage

### Milestone B

- P0.3 accessibility sanitization
- P0.5 shell quarantine
- P1.1 debug-only insecure TLS

### Milestone C

- P1.2 logging/debug cleanup
- P1.3 permission minimization
- P1.4 retention controls
- P2.1 OAuth callback redesign
- P2.2 regression tests

## Short Version

If only five things get done soon, they should be:

1. stop trusting external launcher intents as an internal automation API,
2. make blocked-app masking happen before any capture artifact exists,
3. redact sensitive accessibility fields at source,
4. stop failing open to plaintext for long-lived secrets,
5. remove `shell` from production agent workflows.
