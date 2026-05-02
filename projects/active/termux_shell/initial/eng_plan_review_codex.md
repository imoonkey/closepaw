# Engineering Plan Review: Termux Shell Tool

Reviewer: Codex
Design reviewed: `projects/active/coding/final/cn/design.md`
Date: 2026-05-01

---

## Plan Summary

The design adds a new `termux_shell` tool to ClosePaw. ClosePaw keeps the existing Android UI automation tools and the current restricted `shell` tool, while `termux_shell` talks over localhost HTTP to a Python daemon running inside Termux. The intended v1 success case is one agent session that can combine UI tools with a full bash/toolchain workspace shell.

The plan is feasible in broad direction, but the current implementation plan is not ready to execute. The largest issue is that it treats the Termux shell as a small tool addition. In this codebase, it is a new execution domain with its own storage boundary, setup lifecycle, network security exception, policy model, prompt wiring, checkpoint impact, and Android permissions. Those pieces need to move earlier in the phase order.

## What Already Exists

- `ToolSpec`, `ToolInvocation`, `ToolRegistry`, and `ToolRouter` are already the right tool abstraction. `TermuxShellTool` can follow `ShellTool.kt` structurally.
- `SessionToolingBootstrapper` is the right place to conditionally register the new tool, but its current API has no access to context, settings, token storage, or a bridge manager.
- `PolicyEngine.check()` currently auto-allows every non-screen-changing tool before app-tier checks. A `termux_shell` entry with `isScreenChanging = false` will be unsafe unless this is refactored before registration.
- `ToolName.isScreenChanging` is used by both policy and turn arbitration. Adding `termux_shell` only to `ToolName` is not enough; workspace risk must bypass the current auto-allow branch while still behaving as non-screen-changing for turn arbitration.
- `StandaloneRoleDef`, `PlannerRoleDef`, and `ExecutorRoleDef` have static allowed tool sets and static prompt strings. Conditional prompt/tool exposure requires runtime extension in `SessionAgentRunner` / `TurnPlanningPhaseRunner`, not just a literal string appended to one role file.
- Settings are split correctly today: non-secret preferences in `AppSettingsStore`, credentials in `AuthStore`. A Termux token should not go into `AppSettingsState` or plain prefs.
- The app already has OkHttp, but `network_security_config.xml` sets `cleartextTrafficPermitted="false"` globally. Plain `http://127.0.0.1` will need an explicit localhost cleartext exception on current target devices.
- The current manifest queries only launcher activities. Termux package/service detection and `RUN_COMMAND` service intents need explicit visibility entries.
- There is a broader aligned design in `projects/active/coding/final/design.md` that conflicts with this CN design on critical choices: backend-neutral tools vs direct `termux_shell`, Termux-home workspace vs `/sdcard`, file tools vs shell-only, and coding config gate vs simple settings toggle. Implementation will stall unless one design is declared authoritative.

## Findings

### F1: Policy Must Be Phase 1, Not Phase 4

Severity: high, blocker

The CN design says `termux_shell` is `isScreenChanging = false` but must not use the non-screen-changing auto-allow path. The current code does exactly that auto-allow in `PolicyEngine.check()` before any other relevant decision:

```kotlin
if (!ToolName.from(toolName).isScreenChanging) return PolicyDecision.Allow
```

If implementation follows the proposed phase order, Phase 2 can add a working `TermuxShellTool` before Phase 4 adds workspace policy. That creates a period where full bash commands are auto-allowed in `SMART`, `AUTO_APPROVE`, and even while the foreground app is blocked or masked.

Fix the phase order: add the workspace policy dispatch before exposing or registering the tool. Minimum diff is one domain-aware branch in `ToolRouter`/`PolicyEngine`, not a full policy rewrite. But it must exist before `TermuxShellTool` is usable.

### F2: `/sdcard/closepaw/workspace` Is Not a Safe Default Under Scoped Storage

Severity: high, blocker

The design assumes `/sdcard/closepaw/workspace/` is mutually accessible by ClosePaw, Termux, and the user. That is the riskiest filesystem assumption in the plan.

On Android 11+ scoped storage, ClosePaw targets modern SDKs and cannot rely on `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` for arbitrary public directory file APIs. Termux also needs its own storage setup and permission before it can reliably use shared storage. App-specific external directories are not a solution either, because other apps cannot access each other's `Android/data/...` directories on Android 11+.

The implementation should not start with `/sdcard` as the core workspace root. Safer v1 options:

- Use Termux home as the canonical workspace, e.g. `/data/data/com.termux/files/home/closepaw-workspaces`, and stop claiming the native `shell` can inspect the same files.
- Add a separate import/export path through Downloads or SAF when a user-visible artifact is needed.
- If `/sdcard` sharing is required, make Termux storage setup and ClosePaw storage access an explicit setup phase with failure states and tests. Do not hide it inside the bootstrap script.

This also weakens the plan's "two shell tools can share the workspace" story. Treat native `shell` and `termux_shell` as different filesystems except for explicitly exported shared files.

### F3: The Daemon HTTP Transport Will Fail Without a Localhost Cleartext Exception

Severity: high, implementation surprise

The design chooses `http://127.0.0.1:<port>`, while the app's `network_security_config.xml` has:

```xml
<base-config cleartextTrafficPermitted="false" />
```

Do not wait until Termux integration to discover this. Add a narrow localhost cleartext config in the same phase as the Kotlin bridge client. Keep it narrow; do not set global cleartext to true.

### F4: Bootstrap Token Parsing Is Under-Specified and Brittle

Severity: high

The bootstrap script runs `pkg install -y python git ripgrep` and then prints the generated token. Parsing stdout for the token after package manager output is brittle. It can be localized, noisy, interrupted, or include prompts/errors. It also conflates setup logs with the secret transfer.

Use an explicit machine-readable final line, for example:

```text
CLOSEPAW_SETUP_RESULT={"status":"ok","token":"...","bridge_version":"1"}
```

Then reject missing/duplicate/malformed setup result lines. Do not store any token until health check succeeds with that token. Also cover failure outputs: package install failed, Python missing, storage permission denied, `allow-external-apps` missing, user denied RUN_COMMAND permission, PendingIntent result missing, and Termux version too old for Java result return.

### F5: RUN_COMMAND Cannot Reliably Perform the Entire Setup as Written

Severity: high

The design's bootstrap flow assumes one RUN_COMMAND can install packages, create shared storage dirs, generate a token, deploy the bridge, return the token, then start the bridge. That is optimistic.

Specific missing cases:

- First Termux launch/bootstrap may not have completed yet.
- `pkg install` can take longer than expected and may require repository initialization/update.
- Package installation requires network and can fail independently of bridge setup.
- The user must enable `allow-external-apps = true` in Termux properties.
- ClosePaw must hold `com.termux.permission.RUN_COMMAND`.
- Termux may return only a result bundle for Java callers and only on supported versions.
- Background execution may be killed or delayed by battery policy.

Split setup into idempotent steps with stable states:

1. Detect package and RUN_COMMAND permission.
2. Run a cheap probe command and verify PendingIntent result.
3. Install/check required packages.
4. Deploy bridge script and token.
5. Start bridge.
6. Health check.

Settings can present this as one "Set up" action, but the implementation should not be one opaque script.

### F6: Bridge-Side "DENY Android System Commands" Is Not Enough to Enforce the UI Boundary

Severity: high

Blocking first tokens like `am`, `pm`, `settings`, `input`, `su`, and `uiautomator` is necessary but porous. Bypasses include full paths, shell functions, `command am`, aliases, `toybox input`, scripts created in the workspace, `python -c` invoking subprocesses, package scripts, and binaries installed under different names.

For v1, be honest about the guarantee: this is policy and best-effort command classification, not a hard sandbox. The bridge can block obvious direct Android-control commands and keep cwd bounded, but arbitrary bash can still run arbitrary process logic inside Termux's app sandbox.

The product-level invariant should be "workspace shell is approval-gated and bridge-filtered; it is not a syscall sandbox." If a hard boundary is required, the plan needs a different runtime such as proot/container constraints, SELinux/root integration, or a remote sandbox. Those are out of scope.

### F7: Shell-Only File Operations Will Hurt Reliability More Than the Plan Admits

Severity: medium-high

The plan intentionally defers `file_read/write/edit/search/git` and routes everything through shell. That minimizes tool count, but it creates avoidable risks:

- Multi-line file writes through shell quoting are error-prone.
- Binary files and large outputs need ad hoc shell handling.
- Search/list output must be manually shaped and truncated.
- Policy cannot distinguish read-only file access from mutation without parsing shell commands.
- Artifacts written to `~/.closepaw/artifacts` are only readable via more shell commands unless there is a file read path.

The minimum v1 can still stay small, but consider adding `file_read` and `file_write` earlier than planned, especially because the bridge API already needs structured artifact handling. If shell-only remains the decision, the test plan must include robust quoting, multi-line writes, binary-safe failure behavior, and large-output continuation.

### F8: Prompt Injection Is Not Wired to Runtime Availability Yet

Severity: medium-high

The design says "Termux available -> inject prompt section" and "Termux unavailable -> omit it." The current prompt flow builds `fullSystemPrompt` from `AgentRoleDef.systemPrompt` plus optional skill catalog. There is no existing runtime capability section or workspace status section.

Implementation needs a concrete owner:

- `SessionConfig` or a session-scoped `TermuxBridgeState` says whether Termux shell is enabled and ready.
- `SessionAgentRunner` computes allowed tools based on role plus bridge readiness.
- `TurnPlanningPhaseRunner` appends the workspace prompt section based on the same source of truth used for tool registration.

Do not let prompt availability, tool registration, and settings toggle read three separate states.

### F9: Static Role Allow-Lists Make `PRO` Mode Behavior Ambiguous

Severity: medium-high

The CN design says Standalone gets `termux_shell`; UI Executor never gets it. It does not say what happens to the PRO Planner. Today the default user mode is `AgentMode.PRO`, where the main role is `PlannerRoleDef`, not Standalone. If only Standalone gets the tool, most users will not see `termux_shell` by default.

Decide before implementation:

- Basic-only v1: `termux_shell` works only in `AgentMode.BASIC`; settings should say that or auto-switch.
- Planner-enabled v1: `PlannerRoleDef` gets `termux_shell` for non-UI workspace work, while `ExecutorRoleDef` stays UI-only.

I recommend Planner-enabled v1. Mixed UI+workspace tasks in default PRO mode are part of the success criteria.

### F10: Tool Cancellation Does Not Yet Map to Bridge Process Cancellation

Severity: medium

`ToolRouter.cancel()` signals a `CancellationToken`, but `ShellTool` mostly checks cancellation before execution. A 120-second Termux command needs cooperative cancellation while OkHttp is in flight and must also tell the bridge to kill the subprocess.

The bridge API has no call id or `/cancel` endpoint. If v1 only supports timeout, say so. If user stop/takeover must cancel the process promptly, add one of:

- OkHttp call cancellation plus bridge detecting disconnected client and killing the process.
- A request id and `POST /v1/cancel`.
- One command per bridge worker with server-side timeout as the only kill path.

This affects tests and user trust; long-running commands cannot outlive a stopped session silently.

### F11: Output and Trace Redaction Are Missing

Severity: medium

A general shell can print `.env`, tokens, SSH keys, OAuth files, browser cookies, and command-line secrets. The design says failures are explicit but does not specify redaction before tool output enters history, trace, or UI.

Add v1 rules before implementation:

- Deny or ask before reading obvious secret paths: `.env`, `*.pem`, `id_rsa`, token files, Android app-private paths, Termux `~/.ssh`.
- Redact bearer/API-key-looking strings in bridge logs and Kotlin logs.
- Do not log full command env maps.
- Consider truncating and redacting before `ToolObservation.TextOutput` is persisted.

This is especially important because `HistoryManager` and trace recording persist function outputs.

### F12: Workspace Path Enforcement Needs Precise Semantics

Severity: medium

The design says `cwd` must be under workspace root and bridge enforces workspace boundary. For shell commands, cwd validation does not stop absolute path reads/writes. For file/artifact paths, enforcement must use canonical path containment inside Termux, not app-side `File`.

Bridge tests need to cover:

- `cwd = null` defaults to workspace root.
- Relative `cwd`.
- `..` path escape.
- Prefix sibling (`/workspace2` vs `/workspace`).
- Symlink escape.
- Missing cwd.
- Artifact paths and `output_ref` lookup.
- New-file parent canonicalization.

If shell commands are not sandboxed, state that only `cwd` and bridge-managed artifacts are hard-bounded.

### F13: Port Selection, Token Rotation, and Stale Daemon Handling Are Unspecified

Severity: medium

The design omits how the port is chosen and persisted. A fixed port is simple but can collide with a stale daemon, another app, or a previous token. A dynamic port requires ClosePaw to learn it through setup and persist it with the token.

Minimum v1:

- Pick a fixed port and include a bridge identity/version in `/v1/health`.
- Health must reject wrong token, not merely return "not ready".
- If token mismatch occurs, settings should offer re-pair/redeploy.
- Rotate token on setup reset.
- Stop old bridge or make bridge startup idempotently replace the old process.

### F14: Settings UI State Is More Than Three States

Severity: medium

`Not Installed -> Needs Setup -> Ready` is not enough for user support. Real states include:

- Termux installed but never opened/bootstrap incomplete.
- RUN_COMMAND permission missing.
- `allow-external-apps` missing.
- Termux version unsupported.
- Storage permission missing if `/sdcard` is kept.
- Packages missing/installing/failed.
- Bridge outdated.
- Bridge stopped but restartable.
- Token mismatch.
- Health timeout.

The UI can compress these visually, but the model should keep typed reasons so setup can recover and tests can assert exact behavior.

### F15: The Phase Order Puts User-Facing UI Last, But Setup Blocks Runtime

Severity: medium

The current phases are bridge -> tool -> integration -> agent wiring -> settings/QA. That creates an awkward middle where runtime code exists but there is no reliable way to configure it.

Safer order:

1. Config/state gate: `termuxShellEnabled`, bridge status model, secure token store, no tool exposure yet.
2. Policy split for workspace tools, with tests.
3. Bridge daemon and Kotlin client behind fake/in-memory tests.
4. Termux setup/probe UI, manifest, localhost cleartext exception.
5. Register `termux_shell` conditionally and inject prompt from the same readiness state.
6. End-to-end QA.

Tool exposure should be the last step, not the second.

### F16: The CN Design Conflicts With the English Aligned Design

Severity: medium, coordination risk

`projects/active/coding/final/design.md` says the aligned direction is backend-neutral, includes file tools, avoids `/sdcard` as the default build workspace, and adds a coding config gate. The CN design says direct `termux_shell`, no file tools, `/sdcard/closepaw/workspace`, and a Termux-specific settings toggle.

This is not a translation difference; it changes implementation architecture. Decide which file is authoritative before `/implement`. My recommendation is to carry over these pieces from the English aligned design before coding:

- Do not use `/sdcard` as default build workspace.
- Make policy domain separation explicit before any tool exposure.
- Add a runtime coding/Termux config gate.
- Consider at least structured file read/write instead of shell-only file operations.

## Test Plan

- Coding disabled: existing tool schemas, prompts, and behavior are unchanged; no `termux_shell` appears in any role.
- Tool registration: `termux_shell` appears only when the setting is enabled, the bridge is ready, and the role is allowed. Executor never sees it.
- PRO mode: verify whether Planner gets `termux_shell` according to the chosen decision.
- Policy: `termux_shell` never follows the generic non-screen-changing auto-allow branch. Read-only, mutating, network, package install, destructive, Android-control, and unknown commands produce the expected allow/ask/deny results.
- Blocked foreground app: `termux_shell` behavior is explicitly tested while screen content is masked. If allowed, confirm this is intentional; if denied, confirm the policy blocks it.
- Command classifier cases: `git status`, `git diff`, `python script.py`, `python -c`, `npm install`, `pip install`, `curl`, `git commit`, `git push`, `rm -rf`, `chmod`, `am`, `pm`, `settings`, `input`, `toybox input`, `command am`.
- Bridge auth: missing token, wrong token, stale token, token rotation, and token mismatch after redeploy.
- Bridge request validation: malformed JSON, unknown fields, invalid timeout, huge env, invalid cwd, cwd escape, symlink escape, prefix sibling path.
- Execution: stdout/stderr separation, non-zero exit code, timeout kills process group, output cap, output artifact reference, unicode output, binary-ish output failure mode.
- Cancellation: stop session during a long command; verify process is killed or document timeout-only behavior.
- Setup: Termux missing, installed but unopened, permission missing, `allow-external-apps` missing, unsupported version, package install failure, no network, storage permission failure, bridge outdated, bridge dead/restart.
- Network security: OkHttp can call `http://127.0.0.1:<port>` under release-like network security config without enabling global cleartext.
- Settings UI: state row renders typed reasons; setup action is idempotent; toggle does not expose the tool until health is ready.
- Prompt: prompt section appears exactly when tool schema appears; it disappears when bridge becomes unavailable or setting is off.
- Regression QA: one pure UI task, one pure workspace task, and one mixed UI+workspace task.
- Persistence/reload: session checkpoints preserve enough config to avoid resurrecting a dead or disabled Termux tool after reload.

## Not In Scope

- Shizuku/root shell.
- A hard filesystem or syscall sandbox for arbitrary Termux shell commands.
- Remote backend.
- Long-running process management UX, unless cancellation requirements force a minimal `/cancel`.
- Automatic package installation without explicit approval.
- Full native Android shell expansion.
- Making ClosePaw and Termux share arbitrary `/sdcard` files without a specific storage permission/design decision.

## Next Step

Send this plan back to design before implementation. The bridge-daemon direction is good, but the implementation plan needs four decisions resolved:

1. Authoritative design: CN direct `termux_shell` or English backend-neutral workspace design.
2. Workspace root: Termux home by default, or explicit `/sdcard` setup with storage permission handling.
3. Policy path: exact code-level split that prevents non-screen-changing auto-allow for workspace shell.
4. Phase order: setup/config/policy before tool exposure.

After those are resolved, hand off to `/implement` with a first milestone that does not expose `termux_shell` to the LLM until policy and bridge readiness tests are green.
