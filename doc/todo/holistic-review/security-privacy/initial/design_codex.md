# Security and Privacy Design Review (Codex)

**Date:** 2026-04-08  
**Scope:** `app/src/main/kotlin/com/moonkey/androidagent/` plus `AndroidManifest.xml` and relevant XML configs  
**Focus areas:** API key handling, accessibility data sanitization, credential storage, network security, permission model, and data leakage risks

## Overall Assessment

The codebase already has several good primitives in place: `allowBackup="false"`, release cleartext traffic disabled, encrypted preferences as the default credential store, a blocked/cautious/normal app classifier, and an approval system for screen-changing actions. The main problems are not missing primitives; they are boundary placement and privilege composition.

The highest-risk issues are:

1. The exported launcher activity also acts as an unauthenticated control plane for security-sensitive configuration and goal dispatch.
2. The blocked-app privacy gate runs after screen capture, so trace/debug artifacts can still be written for blocked apps.
3. Accessibility data is normalized for model usability, not sanitized for privacy, then persisted into history/checkpoints and sent to remote LLM endpoints.
4. Secret storage silently fails open to plaintext preferences.
5. BASIC mode exposes a shell tool whose risk model does not match the foreground-app approval model.

I would treat the current design as development-grade, not hardened for broader distribution, until those boundary issues are fixed.

## Architecture and Trust Boundaries

### 1. Control Plane

- `MainActivity` is exported as the launcher entry point. It also parses and applies sensitive intent extras such as API keys, base URL overrides, agent mode, debug mode, trace settings, and goals.  
  Files:
  - `app/src/main/AndroidManifest.xml:29-37`
  - `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivityIntentPayload.kt:28-147`
  - `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivityIntentApplier.kt:17-86`
  - `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:280-317`

### 2. Privileged Automation Plane

- The accessibility service can retrieve window content, take screenshots, and perform gestures.  
  Files:
  - `app/src/main/res/xml/agent_accessibility_config.xml:2-10`

- Optional virtual-display mode adds Shizuku-mediated display and shell-level capabilities. The privilege boundary is therefore not just accessibility; it can expand into shell-adjacent behavior depending on mode and build.

### 3. Perception and Prompting Plane

- `AccessibilityPlatform.captureScreen()` and the virtual-display capture stack collect accessibility trees and screenshots, then convert them into `ScreenSnapshot` and `Perceptor.toPromptJson(...)`.
- That data feeds:
  - live LLM requests,
  - screen-observation history,
  - post-action observations,
  - trace artifacts,
  - debug artifacts.

### 4. Persistence Plane

- Secrets:
  - `AppSettingsStore` for API keys.
  - `OAuthCredentialStore` for OAuth access/refresh/id tokens.
  - `OnboardingStore` for API-key drafts.
- Session and planning state:
  - session JSON and context snapshots under internal app storage.
  - long-term memory markdown files under internal app storage.
- Trace/debug data:
  - JSONL traces, raw trees, sanitized trees, and screenshots under `getExternalFilesDir(...)`.

### 5. Network Plane

- Release cleartext traffic is disabled.  
  File:
  - `app/src/main/res/xml/network_security_config.xml:1-4`

- Cloud traffic goes to OpenAI/OpenRouter/Novita or a base-URL override.
- OAuth uses a localhost callback flow.
- Debug builds can disable TLS certificate validation for all LLM clients.

## Existing Strengths

- `android:allowBackup="false"` reduces backup-based leakage.  
  File:
  - `app/src/main/AndroidManifest.xml:20-27`

- Release cleartext traffic is disabled.  
  File:
  - `app/src/main/res/xml/network_security_config.xml:1-4`

- The app already attempts to separate blocked/cautious/normal apps via `AppClassifier` and `PolicyEngine`.

- Text trace artifacts pass through `CognitionTraceRedactor`, which already strips obvious emails, bearer tokens, JWTs, and some secret-looking keys.  
  Files:
  - `app/src/main/kotlin/com/moonkey/androidagent/trace/CognitionTraceRedactor.kt:9-75`
  - `app/src/main/kotlin/com/moonkey/androidagent/trace/AgentTraceArtifacts.kt:181-196`

These are meaningful controls. The findings below focus on where they do not compose safely.

## Critical

### 1. Exported Launcher Activity Doubles as an Unauthenticated Security Control Surface

**Files**

- `app/src/main/AndroidManifest.xml:29-37`
- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivityIntentPayload.kt:28-147`
- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivityIntentApplier.kt:17-86`
- `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:280-317`
- `app/src/main/kotlin/com/moonkey/androidagent/protocol/SessionConfig.kt:71-77`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/AgentDefRegistry.kt:5-10`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt:8-20`

**What I found**

`MainActivity` is exported because it is the launcher activity, but it also accepts and applies security-sensitive extras from arbitrary intents:

- API keys
- OpenAI base URL override
- backend/model/mode changes
- debug mode
- trace enablement and trace run id
- excluded tools
- goal text

Those extras are not merely read for one session. Several of them are persisted into settings, and `goalText` can trigger automatic execution through `handleIntent(...)`.

The same path can also switch the app into `AgentMode.BASIC`, which routes to `StandaloneAgentDef`, the only mode that exposes the `shell` tool.

**Why it matters**

This creates an attacker-controlled control plane for any co-installed app that can start the launcher activity. A malicious app does not need to compromise storage or accessibility directly. It can:

- change the base URL to an attacker-controlled endpoint,
- turn on trace/debug features,
- switch into BASIC mode,
- dispatch a goal,
- rely on already-granted accessibility/overlay permissions.

That is a serious privilege-composition failure. The exported component that must exist for launching the app is also trusted as though it were an internal automation API.

**Risk**

Full remote reconfiguration of LLM routing and agent behavior by untrusted local callers, with downstream data exfiltration potential.

**Recommendation**

- Split launcher entry from internal command/config entry.
- Ignore all security-sensitive extras from external callers in production.
- Never persist intent-provided credentials or security configuration.
- Require explicit in-app user confirmation before auto-starting a goal received from outside the app.

### 2. Blocked-App Privacy Gating Happens After Capture and After Artifact Writes

**Files**

- `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt:61-102`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt:157-187`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityScreenshotCapturer.kt:157-196`
- `app/src/main/kotlin/com/moonkey/androidagent/trace/TraceRecorderFactory.kt:12-22`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:143-160`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/OpenAppTool.kt:203-220`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/handlers/UIActionInvocation.kt:74-84`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/ObservationBuilder.kt:13-28`

**What I found**

The privacy model says blocked apps should be masked. In practice, masking is applied too late:

- `AccessibilityPlatform.captureScreen()` captures trees and screenshots and writes raw/sanitized trace artifacts before any blocked-app masking occurs.
- `TraceRecorderFactory` stores traces under `getExternalFilesDir(...)`, not internal files.
- `AgentTurnRunner` masks only the already-captured `ScreenSnapshot`.
- `OpenAppTool` and `UIActionInvocation` capture post-action snapshots directly and build observations from raw snapshots without running them through `AppClassifier.maskIfBlocked(...)`.

**Why it matters**

A blocked banking/password-manager/authentication screen can still leak via:

- raw accessibility-tree artifacts,
- sanitized accessibility-tree artifacts,
- screenshots,
- post-action observations after a transition into a blocked app.

The current architecture treats masking as a consumer-side concern. For privacy, it must be a capture-side concern.

**Risk**

The app can persist or process blocked-app content even though the user-visible policy says that content is hidden.

**Recommendation**

- Move blocked-app gating inside the capture pipeline before any `ScreenSnapshotDebug` or trace artifact is created.
- Centralize post-action capture so every observation path uses the same masked snapshot helper.
- Never write raw/sanitized trees or screenshots for blocked apps.

## High

### 3. Accessibility Data Is Normalized for LLM Utility, Not Sanitized for Privacy

**Files**

- `app/src/main/res/xml/agent_accessibility_config.xml:2-10`
- `app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt:243-317`
- `app/src/main/kotlin/com/moonkey/androidagent/model/Models.kt:108-127`
- `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnPlanningPhaseRunner.kt:181-205`
- `app/src/main/kotlin/com/moonkey/androidagent/history/model/SessionRuntimeSnapshot.kt:6-54`

**What I found**

The capture path keeps:

- `text`
- `contentDescription`
- `hintText`
- `resourceId`
- `editable`
- range values

for all retained elements. `PerceptionElement` does not carry `isPassword` or any equivalent sensitivity flag, even though `AccessibilityNodeInfo` exposes password state elsewhere in the codebase. The resulting prompt JSON is then:

- sent to cloud LLMs,
- recorded as screen-observation history,
- checkpointed into `SessionRuntimeSnapshot`.

**Why it matters**

This is not data sanitization. It is data shaping. The system currently assumes that if a node is useful for grounding, its text is fair game for prompts and persistence.

That is too weak for:

- login flows inside ordinary apps,
- OTP fields,
- typed but unsent messages,
- personal contacts and addresses,
- balances, invoices, and account identifiers in apps not covered by the hardcoded blocklist.

**Risk**

Sensitive screen data can leave the device or be durably persisted without any field-level privacy policy.

**Recommendation**

- Add sensitivity metadata to the capture model.
- Suppress text for password fields and consider suppressing text for editable fields by default.
- Give prompt/history/trace serializers separate privacy levels instead of using the same prompt JSON everywhere.

### 4. Secret Storage Fails Open to Plaintext

**Files**

- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt:75-112`
- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt:133-138`
- `app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt:299-317`
- `app/src/main/kotlin/com/moonkey/androidagent/auth/OAuthCredentialStore.kt:31-66`
- `app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingStore.kt:43-61`

**What I found**

All credential stores attempt encrypted preferences first, then silently fall back to plain `SharedPreferences` when encryption fails. `OAuthCredentialStore` persists:

- access token
- refresh token
- id token
- email

through the same failure path.

`AppSettingsStore` also still tries to bootstrap an API key from shared external storage via `api_key.txt`.

**Why it matters**

Fail-open behavior is the wrong default for long-lived credentials. A KeyStore problem should degrade usability, not silently degrade secrecy.

**Risk**

Secrets remain persistent but no longer protected by the storage primitive the rest of the design assumes.

**Recommendation**

- Fail closed for refresh tokens and long-lived API keys.
- Surface degraded-secret-storage state in the UI.
- Remove the shared-storage API-key bootstrap path.

### 5. BASIC Mode Exposes a Shell Capability That Does Not Fit the Foreground-App Approval Model

**Files**

- `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt:8-20`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/ShellTool.kt:38-120`
- `app/src/main/kotlin/com/moonkey/androidagent/tool/PolicyEngine.kt:43-79`

**What I found**

In BASIC mode, the agent can use `shell`. The implementation:

- validates only the first token,
- then executes the full command through `sh -c`.

The policy engine decides approval from the foreground app tier. That model works for screen actions, but it is the wrong abstraction for a tool that can touch the app’s own files, preferences, traces, and session artifacts regardless of what app is currently visible.

**Why it matters**

Even if the user is in a harmless foreground app, `shell` can target:

- app-private session files,
- memory files,
- shared preferences,
- trace artifacts.

The risk is therefore data scope, not foreground package. The current approval model does not express that.

**Risk**

A model or prompt injection that reaches BASIC mode gets a file/system inspection primitive stronger than the policy model assumes.

**Recommendation**

- Remove `shell` from production agent modes, or keep it debug-only.
- If retained, do not use `sh -c`.
- Enforce a binary allowlist and a filesystem allowlist.

## Medium

### 6. Network Hardening Is Good in Release, but Debug Builds Deliberately Disable TLS Validation

**Files**

- `app/src/main/res/xml/network_security_config.xml:1-4`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/InsecureSslConfig.kt:20-48`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt:42-43`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIResponseClient.kt:46-47`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexResponseClient.kt:229`

**What I found**

Release traffic blocks cleartext, which is correct. However, debug/eval builds can disable certificate validation for all LLM clients through `InsecureSslConfig`.

**Why it matters**

That may be acceptable for emulator/eval workflows, but it means debug builds on real networks should be treated as sensitive test artifacts, not casually shareable builds.

**Risk**

MITM exposure of prompts, screenshots, tokens, and tool outputs in debug/eval environments.

**Recommendation**

- Compile the insecure TLS helper only in debug source sets.
- Require HTTPS and production allowlists for base URL overrides outside debug builds.

### 7. Logging and Debug Artifacts Still Expose PII and Model Context

**Files**

- `app/src/main/kotlin/com/moonkey/androidagent/auth/OpenAIOAuth.kt:198-210`
- `app/src/main/kotlin/com/moonkey/androidagent/auth/OpenAIOAuth.kt:396-436`
- `app/src/main/kotlin/com/moonkey/androidagent/auth/OpenAiSignIn.kt:70-77`
- `app/src/main/kotlin/com/moonkey/androidagent/llm/LlmLogger.kt:12-96`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityScreenshotCapturer.kt:157-196`
- `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayScreenshotProcessor.kt:39-89`
- `app/src/main/kotlin/com/moonkey/androidagent/debug/DebugActionExecutor.kt:300-336`

**What I found**

- OAuth code logs decoded `id_token` claims and account identifiers.
- `OpenAiSignIn` logs user email.
- `LlmLogger` logs prompts, input items, tool-call arguments, and responses in debug builds.
- Debug screenshots and action-debug trees/results are written to external app files.

**Why it matters**

The trace redactor helps text artifacts, but it does not protect screenshots, raw trees, or logcat. For privacy, logs and external debug outputs are separate leakage channels.

**Recommendation**

- Strip token/claims/email logs.
- Treat debug logs as local-developer-only.
- Require an explicit developer switch for external debug artifacts.

### 8. The Permission Model Is Intentionally High-Trust but Broader Than Necessary

**Files**

- `app/src/main/AndroidManifest.xml:4-10`
- `app/src/main/res/xml/agent_accessibility_config.xml:2-10`

**What I found**

The app needs strong privileges to automate Android, but the manifest still carries legacy broad storage permissions even though the app targets modern Android and mostly uses app-private storage. The result is a more confusing and broader permission surface than the current design actually needs.

**Why it matters**

Security posture is easier to reason about when every declared privilege is clearly necessary. Right now:

- accessibility + overlay + internet are core,
- optional Shizuku is advanced,
- legacy storage permissions are mostly residue from old credential/debug paths.

**Recommendation**

- Remove stale storage permissions and the shared-storage key path.
- Consider flavoring dev-only capabilities away from production builds.

## Conclusion

The codebase already contains the right building blocks for a safer design, but the security boundary is drawn in the wrong places:

- too much trust is placed in an exported launcher intent,
- privacy masking happens after capture instead of before capture,
- the same accessibility payload is reused for prompting, history, traces, and debug outputs,
- secret storage downgrades silently,
- a screen-centric approval model is asked to govern a file/system tool.

The fastest path to a materially safer design is:

1. lock down external intent control,
2. move privacy gating into the capture layer,
3. sanitize accessibility data at source,
4. fail closed on secret storage,
5. quarantine `shell` from production workflows.
