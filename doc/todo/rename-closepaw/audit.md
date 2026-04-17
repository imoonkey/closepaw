# Rename Audit

Source of truth for the ClosePaw rename. Every path listed below is under `git ls-files` in this worktree. Excluded trees are listed but not enumerated. Ambiguous prose sites require phase-5 manual review.

Design: `doc/todo/rename-closepaw/design.md` (sed rules in §"Sed rewrite rules").

## Excluded (frozen — do not touch)
- `doc/archive/**` — 1051 tracked files; ~1600 "android agent" / `com.moonkey.androidagent` mentions left as dated record.
- `doc/autotune/round_*/**` — 467 tracked files; ~220 mentions frozen as round snapshots.
- `doc/todo/**/final/**`, `doc/todo/**/initial/**` — 259 tracked files; 0 mentions (safe to exclude).
- `eval/results/**` — 1 tracked file; 0 mentions.
- `app/build/**`, `app/.cxx/**`, `.gradle/**`, `.worktrees/**`, `build/**` — build artifacts, not in `git ls-files`.
- Third-party packages under `.reference/` and any vendored SDK (`com.openai.*`, `ai.liquid.leap.*`, `dev.rikka.shizuku.*`, `moe.shizuku.*`, `org.lsposed.*`) — 0 tracked files under `.reference/`; vendored packages untouched.

Total excluded tracked files: **1778** (1051 archive + 467 autotune/round + 259 todo final/initial + 1 eval/results). Excluded hit total: **~1820**.

## Phase 1 — Gradle identity
- `settings.gradle.kts` — line 16: `rootProject.name = "AndroidAgent"` → `"ClosePaw"`.
- `app/build.gradle.kts` — line 11: `namespace = "com.moonkey.androidagent"`; line 15: `applicationId = "com.moonkey.androidagent"` → both `ai.closepaw`.
- `app/proguard-rules.pro` — lines 35, 36, 58, 107, 108, 109: `com.moonkey.androidagent.*` keep rules.

## Phase 2 — Kotlin package tree + intent action strings

### Directories to `git mv`
- `app/src/main/kotlin/com/moonkey/androidagent` → `app/src/main/kotlin/ai/closepaw`
- `app/src/test/kotlin/com/moonkey/androidagent` → `app/src/test/kotlin/ai/closepaw`
- `app/src/debug/kotlin/com/moonkey/androidagent` → `app/src/debug/kotlin/ai/closepaw`
- `app/src/release/kotlin/com/moonkey/androidagent` → `app/src/release/kotlin/ai/closepaw`

After move, remove empty `com/moonkey/` parent dirs in each source set.

### .kt files that require `package`/`import` rewrite (full list)
382 Kotlin files total (main: 264, test: 116, debug: 1, release: 1). All files under `app/src/{main,test,debug,release}/kotlin/com/moonkey/androidagent/**`. Enumerated via `git ls-files app/src/{main,test,debug,release}/kotlin/com/moonkey/androidagent | grep '\.kt$'` — written to `/tmp/kt_files.txt` during audit; regenerate on demand.

Sed rewrites on every .kt file:
- `package com.moonkey.androidagent` → `package ai.closepaw`
- `import com.moonkey.androidagent` → `import ai.closepaw`
- any embedded string literal `com.moonkey.androidagent` → `ai.closepaw` (see intent section below; plus the `mockkStatic` / className strings listed below).

### Intent action literal rewrites (file + line + literal)
- `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt:38` — `const val ACTION_STOP_AGENT = "com.moonkey.androidagent.STOP_AGENT"` → `"ai.closepaw.STOP_AGENT"`.
- `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt:381` — class-name literal `"com.moonkey.androidagent.ui.viewer.VirtualDisplayViewerActivity"` → `"ai.closepaw.ui.viewer.VirtualDisplayViewerActivity"`.
- `app/src/main/kotlin/com/moonkey/androidagent/app/AgentServiceReceiverHelpers.kt:50` — `ACTION_DEBUG_EXEC = "com.moonkey.androidagent.ACTION_DEBUG_EXEC"` → `"ai.closepaw.ACTION_DEBUG_EXEC"`.
- `app/src/test/kotlin/com/moonkey/androidagent/app/OverlayLocationPolicyTest.kt:17,27` — className strings `com.moonkey.androidagent.ui.viewer.VirtualDisplayViewerActivity`, `com.moonkey.androidagent.app.MainActivity` → `ai.closepaw.*`.
- `app/src/test/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModelTest.kt:153,187` — `mockkStatic("com.moonkey.androidagent.auth.OpenAiSignInKt")` → `ai.closepaw.auth.OpenAiSignInKt`.
- `scripts/action-test.sh:16` — `ACTION_INTENT="com.moonkey.androidagent.ACTION_DEBUG_EXEC"` → `ai.closepaw.ACTION_DEBUG_EXEC` (also listed in Phase 4).

## Phase 3 — Resources & manifest
- `app/src/main/AndroidManifest.xml:27` — `android:theme="@style/Theme.AndroidAgent"` → `Theme.ClosePaw`.
- `app/src/main/AndroidManifest.xml:41` — `android:theme="@style/Theme.AndroidAgent.FullScreen"` → `Theme.ClosePaw.FullScreen`.
- `app/src/main/res/values/strings.xml:3` — `<string name="app_name">Android Agent</string>` → `ClosePaw`.
- `app/src/main/res/values/themes.xml:4` — `<style name="Theme.AndroidAgent" ...>` → `Theme.ClosePaw`.
- `app/src/main/res/values/themes.xml:14` — `<style name="Theme.AndroidAgent.FullScreen" ...>` → `Theme.ClosePaw.FullScreen`.
- `app/src/main/assets/security/app_tiers.json:39` — key `"com.moonkey.androidagent": "NORMAL"` → `"ai.closepaw"`.

## Phase 4 — Scripts & eval

### scripts/**
- `scripts/setup.sh:27` — `PACKAGE="com.moonkey.androidagent"` → `ai.closepaw`; lines 76, 151 contain product-label strings `"Android Agent"` (banner + accessibility hint) → `ClosePaw`.
- `scripts/debug-run.sh:29` — `PACKAGE="com.moonkey.androidagent"` → `ai.closepaw`.
- `scripts/action-test.sh:15,16` — `PACKAGE` + `ACTION_INTENT` both carry `com.moonkey.androidagent` → `ai.closepaw`.
- `scripts/eval_parallel.sh:176` — tmp log prefix `/tmp/androidagent_eval_parallel_` → `/tmp/closepaw_eval_parallel_`.
- `scripts/token_counts.py:35,36` — `AGENT_DEF` and `TOOL_DIR` paths under `app/src/main/kotlin/com/moonkey/androidagent/...` → `ai/closepaw/...`.
- `scripts/remote/provision.sh:136,145,146,154` — `~/.android-agent-env` path + bashrc guard string → `~/.closepaw-env`.
- `scripts/remote/sync.sh:6` — `REMOTE_DIR="~/androidagent"` → `~/closepaw`.
- `scripts/README.md:1,192` — header `# Android Agent Development Scripts` and UX step `Find "Android Agent"` → `ClosePaw`.

### eval/**
- `eval/config/default.yaml:30,31`, `eval/config/cog_tune_fast_glm5.yaml:29,30`, `eval/config/cog_tune_fast_qwen35.yaml:29,30`, `eval/config/gpt54_never_succeeded.yaml:29,30` — `package_name`/`activity` → `ai.closepaw`.
- `eval/pyproject.toml:2` — `name = "android-agent-eval"` → `"closepaw-eval"`; line 4 description label → `ClosePaw`.
- `eval/README.md:1,232` — header + "Android Agent app" product label → `ClosePaw`.
- `eval/aw_bridge/__init__.py:1` — docstring "native Android Agent eval" — product label → `ClosePaw` (likely).
- `eval/aw_bridge/completion_monitor.py:30,31,96,100` — ANR regex and substring `com.moonkey.androidagent` → `ai.closepaw` (both the regex literal and the "ANR in …" matcher).
- `eval/aw_bridge/native_agent_bridge.py:57,58` — `_A11Y_SERVICE = "com.moonkey.androidagent/com.moonkey.androidagent.app.AgentService"`; `_A11Y_SERVICE_LABEL = "Android Agent"` → `ai.closepaw/ai.closepaw.app.AgentService`, label `ClosePaw`.
- `eval/aw_bridge/runner.py:260,261` — default `package_name` / `activity` fallback strings → `ai.closepaw`.
- `eval/tests/test_completion_monitor.py:82,92,136` — fixture log lines `ANR in com.moonkey.androidagent`.
- `eval/tests/test_native_agent_bridge.py:12,13,47,98,100,112,122,124,134,135,145` — fixtures + dumpsys snippets + `run-as` command.
- `eval/tests/test_runner.py:29,30,340,341`, `eval/tests/test_runner_preflight_policy.py:20,21` — fixture `package_name`/`activity`.

## Phase 5 — Active docs (unambiguous replacements)

### Package-token replacements (`com.moonkey.androidagent` → `ai.closepaw`)
177 hits across 36 active doc files. Top contributors:
- `doc/todo/settings_redesign/code_review_codex.md` (19)
- `doc/main/infra/tool/mobile_action.md` (18)
- `doc/todo/holistic-review/test-architecture/codex_review_v3.md` (13)
- `.claude/skills/prompt-tune/SKILL.md` (11)
- `doc/todo/holistic-review/state-concurrency/codex_review.md` (8), `.../qa_report.md` (7)
- `doc/todo/security_hardening/agent_security_review_round2.md` (6), `doc/todo/holistic-review/llm-integration/review_milestone.md` (6), `doc/todo/2_lock_screen/lock_screen_design_codex.md` (6)
- Full enumeration written in §"Package-token hit list" below.

### Product-label replacements (`"Android Agent"` as a brand name → `"ClosePaw"`)
Header/brand locations in active docs (unambiguous — replace):
- `.cursorrules:1`, `AGENTS.md:1`, `CLAUDE.md:1`, `GEMINI.md:1` — `# Android Agent` (top-of-file banner, product title).
- `doc/main/README.md:1` — `# Android Agent Documentation`.
- `doc/main/agent/overview.md:3` — `> Design principles, architecture, and package structure for the Android Agent.`
- `doc/dev/development.md:5`, `doc/dev/visual_debug_guide.md:1,5` — guide titles.
- `doc/main/ui/user_interaction.md:18` — mock `ChatHeader` shows `Android Agent` — this is literally the in-app header label → `ClosePaw`.
- `scripts/README.md:1`, `eval/README.md:1` — product-as-title.
- `scripts/setup.sh:76,151` — banner + a11y settings instruction.
- `scripts/README.md:192`, `eval/README.md:232` — instruction "Find/launch Android Agent" in settings/launcher → `ClosePaw`.
- `inspection_tool/README.md:1` — `# Android Agent Replay Viewer` → `ClosePaw Replay Viewer`.
- `.claude/skills/cog-tune/SKILL.md:3` — frontmatter description `Analyze Android Agent cognition…` → product label, replace.

### Package-token hit list (full, file:line:snippet)
.claude/skills/prompt-tune/SKILL.md:95:- Core system prompt: `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`
.claude/skills/prompt-tune/SKILL.md:96:- Prompt assembly: `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/PromptBuilder.kt`
.claude/skills/prompt-tune/SKILL.md:100:- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionTool.kt`
.claude/skills/prompt-tune/SKILL.md:101:- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/ShellTool.kt`
.claude/skills/prompt-tune/SKILL.md:102:- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/CompleteTaskTool.kt`
.claude/skills/prompt-tune/SKILL.md:103:- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/OpenAppTool.kt`
.claude/skills/prompt-tune/SKILL.md:104:- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/ScratchpadTool.kt`
.claude/skills/prompt-tune/SKILL.md:105:- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/WriteTodosTool.kt`
.claude/skills/prompt-tune/SKILL.md:106:- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/AskUserTool.kt`
.claude/skills/prompt-tune/SKILL.md:107:- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/SystemButtonTool.kt`
.claude/skills/prompt-tune/SKILL.md:108:- `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/WaitTool.kt`
.claude/skills/ux-visual-debug/SKILL.md:77:adb shell am force-stop com.moonkey.androidagent
.claude/skills/ux-visual-debug/SKILL.md:80:adb shell monkey -p com.moonkey.androidagent -c android.intent.category.LAUNCHER 1
doc/PROGRESS.md:56:**Key files:** `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifier.kt`, `.../session/SessionCoordinator.kt`, `.../onboarding/PermissionStateMonitor.kt`, `.../onboarding/HttpLlmCredentialValidator.kt`, `.../tool/impl/ShellTool.kt`, `app/build.gradle.kts`, 26 new test files under `app/src/test/kotlin/com/moonkey/androidagent/`
doc/PROGRESS.md:194:**Key files:** `app/src/test/kotlin/com/moonkey/androidagent/llm/CloudStreamRetry{Runner,Policy}Test.kt`, `CodexSseParserTest.kt`, `OpenAIErrorClassifierTest.kt`
doc/PROGRESS.md:454:**Key files:** `app/src/main/kotlin/com/moonkey/androidagent/memory/MemorySchema.kt`, `app/src/main/kotlin/com/moonkey/androidagent/memory/MemoryStore.kt`, `app/src/main/kotlin/com/moonkey/androidagent/memory/MemoryRecaller.kt`, `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/RememberExperienceTool.kt`, `app/src/main/kotlin/com/moonkey/androidagent/agent/Agent.kt`, `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`, `doc/main/agent/memory.md`, `doc/todo/0.5_memory/memory_v2_implementation_plan.md`
doc/PROGRESS.md:721:**Key files:** `app/src/main/kotlin/com/moonkey/androidagent/tool/action/PointActionExecutorCore.kt`
doc/PROGRESS.md:744:**Key files:** `app/src/main/kotlin/com/moonkey/androidagent/agent/cognition/prompt/AppSkillRepository.kt`, `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnPlanningPhaseRunner.kt`, `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/StandaloneAgentDef.kt`, `app/src/main/kotlin/com/moonkey/androidagent/agent/definition/PlannerAgentDef.kt`, `app/src/main/assets/app_skills/`
doc/dev/development.md:91:./gradlew test --tests "com.moonkey.androidagent.history.HistoryManagerTest"
doc/main/README.md:78:app/src/main/kotlin/com/moonkey/androidagent/
doc/main/agent/overview.md:57:com.moonkey.androidagent/
doc/main/infra/tool/click_transport_experiment.md:15:- **App**: `com.moonkey.androidagent` debug build, accessibility service enabled
doc/main/infra/tool/mobile_action.md:18:The tool contract is defined in [MobileActionTool.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionTool.kt) and the runtime glue lives in [MobileActionInvocation.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionInvocation.kt).
doc/main/infra/tool/mobile_action.md:58:[MobileActionTool.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionTool.kt) does four things:
doc/main/infra/tool/mobile_action.md:71:- [ClickExecutor.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/ClickExecutor.kt)
doc/main/infra/tool/mobile_action.md:72:- [LongPressExecutor.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/LongPressExecutor.kt)
doc/main/infra/tool/mobile_action.md:73:- [ScrollExecutor.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/ScrollExecutor.kt)
doc/main/infra/tool/mobile_action.md:74:- [TypeExecutor.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/TypeExecutor.kt)
doc/main/infra/tool/mobile_action.md:75:- [SwipeExecutor.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/SwipeExecutor.kt)
doc/main/infra/tool/mobile_action.md:77:  - [PointActionExecutorCore.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/PointActionExecutorCore.kt)
doc/main/infra/tool/mobile_action.md:78:  - [TargetResolver.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/TargetResolver.kt)
doc/main/infra/tool/mobile_action.md:79:  - [PostActionAnalysis.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/PostActionAnalysis.kt) — accepts `appClassifier` for BLOCKED-app observation masking
doc/main/infra/tool/mobile_action.md:80:  - [UiChangeDetector.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/UiChangeDetector.kt)
doc/main/infra/tool/mobile_action.md:81:  - [ActionPriorityOrder.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/ActionPriorityOrder.kt)
doc/main/infra/tool/mobile_action.md:89:- [AccessibilityPlatform.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt)
doc/main/infra/tool/mobile_action.md:90:- [NodeActionPerformer.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/platform/NodeActionPerformer.kt)
doc/main/infra/tool/mobile_action.md:91:- [AccessibilityGestureInjector.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityGestureInjector.kt)
doc/main/infra/tool/mobile_action.md:92:- [UIAction.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/platform/UIAction.kt)
doc/main/infra/tool/mobile_action.md:111:`click` uses [PointActionExecutorCore.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/PointActionExecutorCore.kt) with this channel order:
doc/main/infra/tool/mobile_action.md:224:[UiChangeDetector.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/action/UiChangeDetector.kt) computes a fingerprint from:
doc/todo/0.9_planner_executor_improv/design_gemini.md:176:app/src/main/kotlin/com/moonkey/androidagent/
doc/todo/0.9_planner_executor_improv/design_gemini.md:187:1.  Create `com.moonkey.androidagent.agent.v2` (or just refactor in place if we are bold).
doc/todo/1_publish/2_release_build_claude.md:63:-keep,includedescriptorclasses class com.moonkey.androidagent.**$$serializer { *; }
doc/todo/1_publish/2_release_build_claude.md:64:-keepclassmembers class com.moonkey.androidagent.** {
doc/todo/1_publish/2_release_build_claude.md:67:-keepclasseswithmembers class com.moonkey.androidagent.** {
doc/todo/1_publish/2_release_build_claude.md:84:-keep class com.moonkey.androidagent.platform.accessibility.** { *; }
doc/todo/2_lock_screen/lock_screen_design_codex.md:50:Add a dedicated lockscreen config to `SessionConfig` (`app/src/main/kotlin/com/moonkey/androidagent/protocol/Op.kt`):
doc/todo/2_lock_screen/lock_screen_design_codex.md:73:Add `app/src/main/kotlin/com/moonkey/androidagent/session/lockscreen/LockScreenExecutionController.kt`.
doc/todo/2_lock_screen/lock_screen_design_codex.md:162:File: `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt`
doc/todo/2_lock_screen/lock_screen_design_codex.md:173:File: `app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt`
doc/todo/2_lock_screen/lock_screen_design_codex.md:182:File: `app/src/main/kotlin/com/moonkey/androidagent/session/SessionServices.kt`
doc/todo/2_lock_screen/lock_screen_design_codex.md:189:File: `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/VirtualDisplayPlatform.kt`
doc/todo/2_scroll_bottom/design_codex.md:18:- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/SwipeExecutor.kt`
doc/todo/2_scroll_bottom/design_codex.md:19:- `app/src/main/kotlin/com/moonkey/androidagent/tool/action/UiChangeDetector.kt`
doc/todo/2_scroll_bottom/design_codex.md:20:- `app/src/main/kotlin/com/moonkey/androidagent/platform/AccessibilityPlatform.kt`
doc/todo/2_scroll_bottom/design_codex.md:21:- `app/src/main/kotlin/com/moonkey/androidagent/platform/NodeActionPerformer.kt`
doc/todo/2_scroll_bottom/design_codex.md:22:- `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt`
doc/todo/holistic-review/dead-code-overabstraction/codex_review.md:16:1. `delegate_task` no longer rejects an obsolete `agent_name` at runtime, so this phase is not quite "pure deletion" from a behavior-contract perspective. `ToolRouter` only calls `tool.validate()` before `createInvocation()` and does not enforce `parameterSchema.additionalProperties = false` (`app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:93`). After this refactor, `DelegateTaskTool.validate()` only checks `query`/`important_notes` and ignores unknown keys (`app/src/main/kotlin/com/moonkey/androidagent/tool/impl/DelegateTaskTool.kt:60`), while `createInvocation()` always routes to the single resolved executor role (`app/src/main/kotlin/com/moonkey/androidagent/tool/impl/DelegateTaskTool.kt:75`). That means a stale call like `{"agent_name":"planner","query":"tap login"}` now succeeds and delegates to executor instead of failing. Impact is low because the current schema no longer advertises `agent_name`, but if you want this refactor to stay strictly deletion-only, either reject `agent_name` explicitly in `validate()` or add a regression test that locks in the intentional "ignored if present" behavior.
doc/todo/holistic-review/dead-code-overabstraction/codex_review.md:20:- Onboarding wiring matches the plan and no partially-wired/null state remains: `MainActivity` constructs `OnboardingDemoController` inline and passes it into `OnboardingViewModel` at creation time (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:150`, `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:156`, `app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModel.kt:28`, `app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModel.kt:262`).
doc/todo/holistic-review/dead-code-overabstraction/codex_review.md:21:- `delegate_task` still resolves through the registry path rather than a hardcoded role constant: `SessionAgentRunner` passes `AgentDefRegistry.delegatableRoles()` into the tool (`app/src/main/kotlin/com/moonkey/androidagent/session/SessionAgentRunner.kt:142`), and the registry still resolves to the single executor role (`app/src/main/kotlin/com/moonkey/androidagent/agent/definition/AgentDefRegistry.kt:16`).
doc/todo/holistic-review/dead-code-overabstraction/codex_review.md:23:- Test coverage impact looks clean for the deleted APIs: no dead tests remain under `app/src/test/kotlin`, `DelegateTaskToolTest` was updated for the new contract, and both `./gradlew :app:testDebugUnitTest --tests 'com.moonkey.androidagent.tool.impl.DelegateTaskToolTest'` and full `./gradlew test` passed.
doc/todo/holistic-review/error-resilience/qa_report.md:30:**Code verification** — `app/src/main/kotlin/com/moonkey/androidagent/agent/AgentRuntimeTypes.kt:72-104`:
doc/todo/holistic-review/error-resilience/qa_report.md:90:4. `adb shell am force-stop com.moonkey.androidagent`
doc/todo/holistic-review/error-resilience/qa_report.md:100:**Shutdown trace**: `vendor.qti.hardware.servicetrackeraidl-service: destroyService is called for service: com.moonkey.androidagent/.app.AgentService` — clean teardown. `SessionCoordinator: Session shutdown completed` present in an earlier run.
doc/todo/holistic-review/error-resilience/qa_report.md:143:ToolRouter: Policy decision for open_app: Deny(reason=Blocked: financial/auth app (com.moonkey.androidagent))
doc/todo/holistic-review/error-resilience/qa_report.md:212:- Session files pulled via `adb shell run-as com.moonkey.androidagent cat files/sessions/<file>`.
doc/todo/holistic-review/llm-integration/review_milestone.md:4:**Local verification:** `./gradlew testDebugUnitTest --tests 'com.moonkey.androidagent.llm.CloudStreamRetryPolicyTest' --tests 'com.moonkey.androidagent.llm.CloudStreamRetryRunnerTest' --tests 'com.moonkey.androidagent.llm.CodexSseParserTest' --tests 'com.moonkey.androidagent.llm.OpenAIErrorClassifierTest'` passed
doc/todo/holistic-review/llm-integration/review_milestone.md:18:- **Files:** `app/src/main/kotlin/com/moonkey/androidagent/llm/CloudStreamRetryRunner.kt:47-53`, `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIResponseClient.kt:142-145`, `app/src/test/kotlin/com/moonkey/androidagent/llm/CloudStreamRetryRunnerTest.kt:147-159`
doc/todo/holistic-review/llm-integration/review_milestone.md:27:- **Files:** `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexResponseClient.kt:144-156`, `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexResponseClient.kt:205-207`
doc/todo/holistic-review/llm-integration/review_milestone.md:35:- **Files:** `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt:128`, `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt:192-224`
doc/todo/holistic-review/llm-integration/review_milestone.md:44:- **Files:** `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifier.kt:72-83`, `app/src/test/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifierTest.kt:60-85`
doc/todo/holistic-review/llm-integration/review_milestone.md:54:- **Files:** `app/src/test/kotlin/com/moonkey/androidagent/llm/CloudStreamRetryRunnerTest.kt:214-233`, `app/src/main/kotlin/com/moonkey/androidagent/llm/CloudStreamRetryPolicy.kt:31-45`
doc/todo/holistic-review/llm-integration/review_p1_tests.md:3:**Scope:** `git diff e5904b5..HEAD -- app/src/test/kotlin/com/moonkey/androidagent/llm/`
doc/todo/holistic-review/llm-integration/review_p1_tests.md:4:**Local verification:** `./gradlew testDebugUnitTest --tests 'com.moonkey.androidagent.llm.CloudStreamRetryPolicyTest' --tests 'com.moonkey.androidagent.llm.CloudStreamRetryRunnerTest' --tests 'com.moonkey.androidagent.llm.CodexSseParserTest' --tests 'com.moonkey.androidagent.llm.OpenAIErrorClassifierTest'` passed
doc/todo/holistic-review/llm-integration/review_p1_tests.md:27:- **Files:** `app/src/test/kotlin/com/moonkey/androidagent/llm/CodexSseParserTest.kt:176-258`, `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexSseParser.kt:118-147`
doc/todo/holistic-review/llm-integration/review_p2_streaming.md:3:**Scope:** `git diff eec4595..HEAD -- app/src/main/kotlin/com/moonkey/androidagent/llm/ app/src/test/kotlin/com/moonkey/androidagent/llm/`
doc/todo/holistic-review/llm-integration/review_p2_streaming.md:4:**Local verification:** `./gradlew testDebugUnitTest --tests 'com.moonkey.androidagent.llm.*' --tests 'com.moonkey.androidagent.trace.*'` passed
doc/todo/holistic-review/llm-integration/review_p2_streaming.md:14:- **Files:** `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexSseParser.kt:98-103`, `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexResponseClient.kt:161-182`, `app/src/main/kotlin/com/moonkey/androidagent/llm/CloudStreamRetryRunner.kt:32-75`
doc/todo/holistic-review/llm-integration/review_p2_streaming.md:27:- **Files:** `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt:191-224`, `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionInterop.kt:264-274`, `app/src/main/kotlin/com/moonkey/androidagent/llm/LFMLLMClient.kt:292-302`, `app/src/main/kotlin/com/moonkey/androidagent/llm/LlmLogger.kt:30-34`, `app/src/main/kotlin/com/moonkey/androidagent/trace/LlmInputItemsTraceSerializer.kt:23-30`
doc/todo/holistic-review/llm-integration/review_p3_harden.md:4:**Local verification:** `./gradlew testDebugUnitTest --tests 'com.moonkey.androidagent.llm.OpenAIErrorClassifierTest' --tests 'com.moonkey.androidagent.llm.CloudStreamRetryRunnerTest' --tests 'com.moonkey.androidagent.llm.CodexSseParserTest'` passed
doc/todo/holistic-review/llm-integration/review_p3_harden.md:14:- **Files:** `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifier.kt:11-18`, `app/src/test/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifierTest.kt:111-118`
doc/todo/holistic-review/llm-integration/review_p3_harden.md:23:- **Files:** `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexResponseClient.kt:143-155`, `app/src/main/kotlin/com/moonkey/androidagent/llm/CodexResponseClient.kt:208-211`
doc/todo/holistic-review/llm-integration/review_p3_harden.md:33:- **Files:** `app/src/test/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifierTest.kt:111-127`
doc/todo/holistic-review/performance-resources/codex_review.md:5:1. **Severity:** high. **Affected:** `app/proguard-rules.pro:38-44`, `app/src/main/kotlin/com/moonkey/androidagent/platform/virtualdisplay/ShizukuDisplayTransport.kt:3,83-85,104-105,154,201,318-323`. **What is wrong:** the new R8 rules keep Shizuku library classes, but they do not keep the app-generated hidden-framework AIDL type `android.hardware.display.IVirtualDisplayCallback`. That type is instantiated directly (`object : IVirtualDisplayCallback.Stub()`) and used in reflective `getMethod(...)` lookups against `IDisplayManager`. The current release mapping already shows `android.hardware.display.IVirtualDisplayCallback -> e.a` and `IVirtualDisplayCallback$Stub -> R8$$REMOVED$$CLASS$$2` in `app/build/outputs/mapping/release/mapping.txt:7992-7994`, so the virtual-display/Shizuku release path is not actually safe even though `assembleRelease` passes. **Recommended fix:** add explicit keep rules for `android.hardware.display.IVirtualDisplayCallback` and its nested `Stub`/`Proxy`/`Default` classes, or more generally keep the app-generated hidden AIDL stubs under `android.hardware.display.**`, then smoke-test the Shizuku virtual-display flow on a release build.
doc/todo/holistic-review/performance-resources/codex_review.md:7:2. **Severity:** high. **Affected:** `app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt:68-71,215-218,298-304`. **What is wrong:** the single-pass traversal does not preserve the old over-collection behavior for non-interactive nodes. Before this change, the second `ALL` pass could still collect up to `2 * maxElements` total candidates and let `applyTruncation()` score them. Now `nonInteractiveCap = maxElements`, so once the first `maxElements` non-interactive nodes are seen, every later non-interactive node is dropped before scoring. On screens with few or zero interactive nodes, or when a huge non-interactive subtree is encountered first, selection degrades from score-based truncation to DFS encounter order. That is exactly the edge case the plan called out to avoid when it said to preserve prioritization in later phases. **Recommended fix:** restore non-interactive over-collection as well, e.g. allow at least `2 * maxElements` non-interactive candidates or use a shared total cap that still feeds `applyTruncation()` a larger pool, and add parity tests for empty-interactive trees and “large non-interactive subtree first” cases.
doc/todo/holistic-review/performance-resources/codex_review.md:9:3. **Severity:** medium. **Affected:** `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIResponseClient.kt:107-110,179-181`, `app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt:139-142,261-263`. **What is wrong:** the new cancellation hook only becomes effective after `createStreaming(...)` returns and `activeStream` is populated. If the collector cancels while `createStreaming(...)` is still blocked establishing the HTTP request/response, `awaitClose` sees `null` and only cancels the coroutine job; it does not have a request handle to abort the underlying network call. So this does improve cancellation for an already-open stream, but it still does not reliably cancel the in-flight request from the start of the operation. The `activeStream.set(null)`/`awaitClose` race itself is not the main problem; the missing pre-publication window is. **Recommended fix:** capture a cancellable request handle before the blocking call if the SDK exposes one, or wrap the SDK behind a lower-level transport that exposes the underlying `Call`/subscription earlier, and add an early-cancel regression test that cancels before the first response event arrives.
doc/todo/holistic-review/performance-resources/codex_review.md:11:4. **Severity:** medium. **Affected:** `app/src/main/kotlin/com/moonkey/androidagent/perception/PerceptorInternals.kt:41-46,57-64`. **What is wrong:** the text-enrichment rewrite does not fully preserve previous behavior. The old code bubbled contained text sources in original candidate/traversal order; the new code sorts all sources by `bounds.top` first and then emits the first three matches in that new order. That changes the joined text for any control whose contained labels are encountered out of vertical order in the tree, and there is no regression test for that semantic change. For a product-sensitive prompt path, that is a behavior change, not just an optimization detail. **Recommended fix:** keep the binary-search/spatial-index pruning, but retain original candidate order within the matched slice by storing each source’s original index and sorting matched hits back to that order before `take(3)`, then add an explicit regression test with out-of-order child labels.
doc/todo/holistic-review/performance-resources/qa_report.md:33:- No FATAL / ClassNotFoundException / ANR for `com.moonkey.androidagent`
doc/todo/holistic-review/state-concurrency/codex_review.md:5:- `HEAD` is still `e1741ede` in this workspace, so the literal range `e1741ede..HEAD` is empty. This review therefore used the current worktree diff against `e1741ede` for the scoped files under `app/src/main/kotlin/com/moonkey/androidagent/**` and `app/src/test/kotlin/com/moonkey/androidagent/**`.
doc/todo/holistic-review/state-concurrency/codex_review.md:8:- The persistence work is directionally correct: the new single-writer path in [SessionRecordingService.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/history/SessionRecordingService.kt:40) plus atomic-ish file replacement in [SessionStorage.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/history/storage/SessionStorage.kt:77) addresses the original stale-write race much better than the baseline.
doc/todo/holistic-review/state-concurrency/codex_review.md:16:1. **`Op.Takeover` still races with task completion, so the post-turn state is not deterministic.** [AgentSession.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:364), [AgentSession.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:426), [Agent.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/agent/Agent.kt:156), [Op.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/protocol/Op.kt:18), [AgentSessionTest.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/test/kotlin/com/moonkey/androidagent/session/AgentSessionTest.kt:197)
doc/todo/holistic-review/state-concurrency/codex_review.md:20:2. **The new token model does not provide “real cancellation” for built-in long-running tools, especially `delegate_task`.** [ToolRouter.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:347), [ToolRouter.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:359), [DelegateTaskTool.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/tool/impl/DelegateTaskTool.kt:144), [SubAgentRunner.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/agent/subagent/SubAgentRunner.kt:61), [ToolRouterTest.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/test/kotlin/com/moonkey/androidagent/tool/ToolRouterTest.kt:132)
doc/todo/holistic-review/state-concurrency/codex_review.md:25:1. **Introducing `TakeoverPending` broke the existing queued-input contract in `SessionCoordinator`.** [SessionState.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/protocol/SessionState.kt:42), [AgentSession.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/session/AgentSession.kt:282), [SessionCoordinator.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/session/SessionCoordinator.kt:51), [MainActivity.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:431)
doc/todo/holistic-review/state-concurrency/codex_review.md:29:2. **The Phase 6 off-main bootstrap hardening breaks at least one live caller.** [SessionLlmBootstrapper.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/session/SessionLlmBootstrapper.kt:90), [DefaultOnboardingDemoController.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/onboarding/DefaultOnboardingDemoController.kt:91), [MainActivity.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:160), [SessionLlmBootstrapperTest.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/test/kotlin/com/moonkey/androidagent/session/SessionLlmBootstrapperTest.kt:17)
doc/todo/holistic-review/state-concurrency/codex_review.md:33:3. **The new tests do not actually validate several of the design doc’s load-bearing invariants.** [SessionRecordingServiceTest.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/test/kotlin/com/moonkey/androidagent/history/SessionRecordingServiceTest.kt:242), [ToolRouterTest.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/test/kotlin/com/moonkey/androidagent/tool/ToolRouterTest.kt:132), [AgentSessionTest.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/test/kotlin/com/moonkey/androidagent/session/AgentSessionTest.kt:198), [AgentSessionTest.kt](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/test/kotlin/com/moonkey/androidagent/session/AgentSessionTest.kt:340)
doc/todo/holistic-review/state-concurrency/codex_review.md:41:- Ran: `./gradlew :app:testDebugUnitTest --tests 'com.moonkey.androidagent.history.SessionRecordingServiceTest' --tests 'com.moonkey.androidagent.history.SessionStorageTest' --tests 'com.moonkey.androidagent.session.AgentSessionTest' --tests 'com.moonkey.androidagent.session.SessionLlmBootstrapperTest' --tests 'com.moonkey.androidagent.tool.ToolRouterTest'`
doc/todo/holistic-review/state-concurrency/qa_report.md:51:Process: com.moonkey.androidagent, PID: 19162
doc/todo/holistic-review/state-concurrency/qa_report.md:53:  com.moonkey.androidagent.app.AgentService@3b7a393:
doc/todo/holistic-review/state-concurrency/qa_report.md:62:  at com.moonkey.androidagent.llm.CodexResponseClient.cleanup(221)
doc/todo/holistic-review/state-concurrency/qa_report.md:63:  at com.moonkey.androidagent.session.SessionServices.cleanup(230)
doc/todo/holistic-review/state-concurrency/qa_report.md:64:  at com.moonkey.androidagent.session.AgentSession.handleShutdown(539)
doc/todo/holistic-review/state-concurrency/qa_report.md:65:  at com.moonkey.androidagent.app.AgentService.onDestroy(215)
doc/todo/holistic-review/state-concurrency/qa_report.md:105:- Force-stopped app: `adb shell am force-stop com.moonkey.androidagent`
doc/todo/holistic-review/test-architecture/codex_review.md:15:1. `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifier.kt:72-83`, `app/src/test/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifierTest.kt:60-79`
doc/todo/holistic-review/test-architecture/codex_review.md:18:2. `app/src/test/kotlin/com/moonkey/androidagent/llm/ChatCompletionClientTest.kt:125-148`, underlying behavior in `app/src/main/kotlin/com/moonkey/androidagent/llm/CloudLlmRetry.kt:34-38`
doc/todo/holistic-review/test-architecture/codex_review.md:21:3. `app/src/test/kotlin/com/moonkey/androidagent/onboarding/PermissionStateMonitorTest.kt:15-25`
doc/todo/holistic-review/test-architecture/codex_review.md:25:1. `app/src/test/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModelTest.kt:101-125`, `app/src/test/kotlin/com/moonkey/androidagent/llm/ChatCompletionClientTest.kt:153-185`, `app/src/test/kotlin/com/moonkey/androidagent/llm/CodexResponseClientTest.kt:201-216`
doc/todo/holistic-review/test-architecture/codex_review.md:28:2. `app/src/test/kotlin/com/moonkey/androidagent/tool/impl/ShellToolExecutionTest.kt:20-31`
doc/todo/holistic-review/test-architecture/codex_review_v2.md:10:   `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifier.kt:72-83` now uses non-alphanumeric boundaries for `429` and `5xx` matching instead of plain substring checks. `app/src/test/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifierTest.kt:62-107` adds the missing negative cases, including the previously problematic letter-adjacent tokens. The focused test slice passed.
doc/todo/holistic-review/test-architecture/codex_review_v2.md:13:   `app/src/main/kotlin/com/moonkey/androidagent/onboarding/PermissionStateMonitor.kt:50-72` now exposes a pure companion `deriveRepairModel(...)` helper, and `app/src/test/kotlin/com/moonkey/androidagent/onboarding/PermissionStateMonitorTest.kt:8-55` exercises that pure logic directly instead of spying and stubbing the runtime probes. The focused test slice passed.
doc/todo/holistic-review/test-architecture/codex_review_v2.md:16:   `app/src/test/kotlin/com/moonkey/androidagent/llm/ChatCompletionClientTest.kt:125-148` no longer asserts that the final exception is the raw `SocketTimeoutException`; it now checks retry behavior instead. That resolves the original review concern. Separate note: this test is still part of the new suite-performance issue below, but that is a different problem from the original correctness concern.
doc/todo/holistic-review/test-architecture/codex_review_v2.md:26:   `app/src/test/kotlin/com/moonkey/androidagent/llm/ChatCompletionClientTest.kt:125-148` drives the full `CloudLlmRetry` backoff loop under `runBlocking`; the recorded runtime is `16.391s` in `app/build/test-results/testDebugUnitTest/TEST-com.moonkey.androidagent.llm.ChatCompletionClientTest.xml:2-4`. `app/src/test/kotlin/com/moonkey/androidagent/onboarding/HttpLlmCredentialValidatorTest.kt:74-79` uses `SocketPolicy.DISCONNECT_AT_START` against production `HttpURLConnection` timeouts and took `20.018s` in `app/build/test-results/testDebugUnitTest/TEST-com.moonkey.androidagent.onboarding.HttpLlmCredentialValidatorTest.xml:2-10`. `app/src/test/kotlin/com/moonkey/androidagent/tool/impl/ShellToolExecutionTest.kt:20-32` still shells out to `sleep 15` and took `10.019s` in `app/build/test-results/testDebugUnitTest/TEST-com.moonkey.androidagent.tool.impl.ShellToolExecutionTest.xml:2-8`. In aggregate, the focused 10-class slice took `48s`, and these three tests account for almost all of that runtime. Remediation: inject retry/backoff clocks and process/HTTP failure seams so timeout classification can be asserted instantly in unit tests, and leave real-time timeout coverage to a small opt-in integration layer.
doc/todo/holistic-review/test-architecture/codex_review_v2.md:30:   `app/src/test/kotlin/com/moonkey/androidagent/llm/ChatCompletionClientTest.kt:153-185`, `app/src/test/kotlin/com/moonkey/androidagent/llm/CodexResponseClientTest.kt:201-216`, and `app/src/test/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModelTest.kt:113-120` reach into private methods and fields instead of testing public seams. They pass today, but they will fail on harmless refactors to request-building or teardown internals. Remediation: extract request/error mapping into explicit collaborators or test through injected clients, and verify `ChatViewModel` teardown through observable behavior rather than `eventCollectionJob`.
doc/todo/holistic-review/test-architecture/codex_review_v3.md:10:  --tests 'com.moonkey.androidagent.llm.OpenAIErrorClassifierTest' \
doc/todo/holistic-review/test-architecture/codex_review_v3.md:11:  --tests 'com.moonkey.androidagent.onboarding.PermissionStateMonitorTest' \
doc/todo/holistic-review/test-architecture/codex_review_v3.md:12:  --tests 'com.moonkey.androidagent.tool.impl.ShellToolExecutionTest' \
doc/todo/holistic-review/test-architecture/codex_review_v3.md:13:  --tests 'com.moonkey.androidagent.onboarding.HttpLlmCredentialValidatorTest' \
doc/todo/holistic-review/test-architecture/codex_review_v3.md:14:  --tests 'com.moonkey.androidagent.session.SessionCoordinatorTest' \
doc/todo/holistic-review/test-architecture/codex_review_v3.md:15:  --tests 'com.moonkey.androidagent.llm.ChatCompletionClientTest' \
doc/todo/holistic-review/test-architecture/codex_review_v3.md:16:  --tests 'com.moonkey.androidagent.llm.CodexResponseClientTest' \
doc/todo/holistic-review/test-architecture/codex_review_v3.md:17:  --tests 'com.moonkey.androidagent.auth.OpenAIOAuthTest'
doc/todo/holistic-review/test-architecture/codex_review_v3.md:25:| v1 Medium #1 — OpenAIErrorClassifier false-positive boundary matching | FIXED | `app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifier.kt:72-83` now uses non-alphanumeric boundaries for `429` and `500/502/503/504`; `app/src/test/kotlin/com/moonkey/androidagent/llm/OpenAIErrorClassifierTest.kt:62-91` and `:165-186` cover both negative and positive cases. |
doc/todo/holistic-review/test-architecture/codex_review_v3.md:26:| v1 Medium #2 — ChatCompletionClientTest pinned the raw timeout leak | FIXED | `app/src/test/kotlin/com/moonkey/androidagent/llm/ChatCompletionClientTest.kt:125-155` now asserts that retry happens instead of pinning the terminal exception type. |
doc/todo/holistic-review/test-architecture/codex_review_v3.md:27:| v1 Medium #3 — PermissionStateMonitor test spied and stubbed the object under test | FIXED | `app/src/main/kotlin/com/moonkey/androidagent/onboarding/PermissionStateMonitor.kt:50-72` extracts pure derivation logic; `app/src/test/kotlin/com/moonkey/androidagent/onboarding/PermissionStateMonitorTest.kt:8-55` tests that pure helper directly. |
doc/todo/holistic-review/test-architecture/codex_review_v3.md:28:| v2 Medium — slow real-time tests | FIXED | `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/ShellTool.kt:16-18`, `:61-67`, `:116-122` and `app/src/main/kotlin/com/moonkey/androidagent/onboarding/HttpLlmCredentialValidator.kt:20-30`, `:46-50` make timeouts injectable; `app/src/test/kotlin/com/moonkey/androidagent/llm/ChatCompletionClientTest.kt:128-154` now exits after the second retry observation. Verified current runtimes in `app/build/test-results/testDebugUnitTest/TEST-com.moonkey.androidagent.llm.ChatCompletionClientTest.xml`, `TEST-com.moonkey.androidagent.onboarding.HttpLlmCredentialValidatorTest.xml`, and `TEST-com.moonkey.androidagent.tool.impl.ShellToolExecutionTest.xml`. |
doc/todo/holistic-review/test-architecture/codex_review_v3.md:29:| v2 Low — reflection-heavy white-box tests | NOT-FIXED | Reflection remains in `app/src/test/kotlin/com/moonkey/androidagent/llm/ChatCompletionClientTest.kt:159-191`, `app/src/test/kotlin/com/moonkey/androidagent/llm/CodexResponseClientTest.kt:201-216`, and `app/src/test/kotlin/com/moonkey/androidagent/ui/chat/ChatViewModelTest.kt:113-124`. Non-blocking, but still brittle under harmless refactors. |
doc/todo/holistic-review/test-architecture/qa_report.md:199:- Unit test results: `app/build/test-results/testDebugUnitTest/TEST-com.moonkey.androidagent.{llm.OpenAIErrorClassifierTest,llm.CloudStreamRetryPolicyTest,agent.TurnErrorClassifierTest}.xml`
doc/todo/holistic-review/tool-system-design/ux_qa_policy_tier_report.md:34:PolicyEngine: Policy check: tool=open_app, pkg=com.moonkey.androidagent, dest=com.google.android.gm, tier=CAUTIOUS, mode=SMART
doc/todo/holistic-review/tool-system-design/ux_qa_policy_tier_report.md:67:PolicyEngine: Policy check: tool=open_app, pkg=com.moonkey.androidagent, dest=com.android.chrome, tier=NORMAL, mode=SMART
doc/todo/holistic-review/tool-system-design/ux_qa_policy_tier_report.md:92:PolicyEngine: Policy check: tool=open_app, pkg=com.moonkey.androidagent, dest=com.robinhood.android, tier=BLOCKED, mode=SMART
doc/todo/holistic-review/tool-system-design/ux_qa_policy_tier_report.md:93:ToolRouter: Policy decision for open_app: Deny(reason=Blocked: financial/auth app (com.moonkey.androidagent))
doc/todo/holistic-review/tool-system-design/ux_qa_policy_tier_report.md:119:PolicyEngine: Policy check: tool=open_app, pkg=com.moonkey.androidagent, dest=com.android.settings, tier=NORMAL, mode=SMART
doc/todo/onboarding_wizard/eng_design.md:29:app/src/main/kotlin/com/moonkey/androidagent/
doc/todo/openai_oauth/design.md:247:app/src/main/kotlin/com/moonkey/androidagent/
doc/todo/openai_oauth/path_b_design.md:52:package com.moonkey.androidagent.llm
doc/todo/openai_oauth/path_b_design.md:104:package com.moonkey.androidagent.llm
doc/todo/openai_oauth/path_b_design.md:156:package com.moonkey.androidagent.llm
doc/todo/security_hardening/agent_security_review/current_impl.md:64:> 源码: `app/src/main/kotlin/com/moonkey/androidagent/tool/PolicyEngine.kt`
doc/todo/security_hardening/agent_security_review/current_impl.md:213:> 源码: `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt`
doc/todo/security_hardening/agent_security_review/current_impl.md:317:> 源码: `app/src/main/kotlin/com/moonkey/androidagent/protocol/ApprovalTypes.kt`
doc/todo/security_hardening/agent_security_review/current_impl.md:434:> 源码: `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/AskUserTool.kt`
doc/todo/security_hardening/agent_security_review_round2.md:5:1. `open_app` can bypass the BLOCKED tier entirely. `PolicyEngine.check()` classifies only the current foreground package, not the app being opened, so `open_app("Chase")` from a NORMAL app is allowed even though the destination is BLOCKED (`app/src/main/kotlin/com/moonkey/androidagent/tool/PolicyEngine.kt:33-46`, `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/OpenAppTool.kt:188-220`). This also immediately captures the blocked screen after launch.
doc/todo/security_hardening/agent_security_review_round2.md:7:2. Escape handling is broken for the actual back/home tool. The policy special-case only looks at `action` or `toolName`, but real back/home navigation now goes through `system_button(button="back"|"home")`, while `mobile_action` no longer accepts `back`/`home` at all (`app/src/main/kotlin/com/moonkey/androidagent/tool/PolicyEngine.kt:41-42`, `app/src/main/kotlin/com/moonkey/androidagent/tool/PolicyEngine.kt:78-81`, `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/MobileActionTool.kt:55-60`). Result: the agent can be trapped on a BLOCKED app. The corresponding tests are stale because they assert impossible `mobile_action(action="back"|"home")` cases (`app/src/test/kotlin/com/moonkey/androidagent/tool/PolicyEngineTest.kt:38-54`).
doc/todo/security_hardening/agent_security_review_round2.md:9:3. The perception gate is only applied to the pre-turn snapshot. Raw blocked content still flows through post-action and approval-refresh captures, then gets emitted to events/traces as normal screen state (`app/src/main/kotlin/com/moonkey/androidagent/agent/AgentTurnRunner.kt:147-163`, `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:220-224`, `app/src/main/kotlin/com/moonkey/androidagent/tool/impl/OpenAppTool.kt:207-216`, `app/src/main/kotlin/com/moonkey/androidagent/tool/handlers/UIActionInvocation.kt:74-80`, `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt:108-146`, `app/src/main/kotlin/com/moonkey/androidagent/agent/TurnExecutionPhaseRunner.kt:215-236`). So `BLOCKED -> masked` is not correct end-to-end.
doc/todo/security_hardening/agent_security_review_round2.md:11:4. The memory gate only checks the current foreground app, not the memory target. From a NORMAL screen, the agent can still write `scope=app` memory for a BLOCKED package such as `com.chase.sig.android` (`app/src/main/kotlin/com/moonkey/androidagent/tool/impl/RememberExperienceTool.kt:144-159`). If blocked apps are supposed to be non-observable/non-learnable, this bypasses that guarantee.
doc/todo/security_hardening/agent_security_review_round2.md:17:2. No test covers the actual escape path `system_button(button="back"|"home")` on a BLOCKED app; the current tests cover an invalid `mobile_action` shape instead (`app/src/test/kotlin/com/moonkey/androidagent/tool/PolicyEngineTest.kt:38-54`).
doc/todo/security_hardening/agent_security_review_round2.md:21:4. `RememberExperienceToolTest` does not cover blocked foreground rejection or blocked `package_name` writes (`app/src/test/kotlin/com/moonkey/androidagent/tool/impl/RememberExperienceToolTest.kt:81-110`).
doc/todo/security_hardening/basic_security_review.md:4:1. `AppSettingsStore` still imports secrets from shared external storage and leaves the plaintext copy behind. [`app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt:220`](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt:220) reads `/sdcard/api_key.txt`, and [`app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt:228`](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt:228) only copies it into encrypted prefs; it never deletes the file. The manifest still requests external-storage access at [`app/src/main/AndroidManifest.xml:6`](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/AndroidManifest.xml:6) and [`app/src/main/AndroidManifest.xml:7`](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/AndroidManifest.xml:7). That means the new encrypted storage does not remove the plaintext secret exposure path. Remove this bootstrap path, or migrate once and delete the old file and permissions.
doc/todo/security_hardening/basic_security_review.md:6:2. The new encrypted-settings path can crash app startup on keystore/keyset failures. [`app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt:68`](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt:68) creates `MasterKey` / `EncryptedSharedPreferences` with no error handling, and [`app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt:95`](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt:95) runs that path unconditionally during load. Because settings load happens in [`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:101`](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:101) and [`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:102`](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:102), a corrupted/invalidated keystore now becomes an app-launch crash instead of a recoverable empty-settings case. Wrap encrypted prefs init/read/write in recovery logic.
doc/todo/security_hardening/basic_security_review.md:8:3. The TLS hardening is incomplete: release builds are blocked, but every debug build still disables certificate validation globally. [`app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt:41`](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/llm/ChatCompletionClient.kt:41) and [`app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIResponseClient.kt:45`](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/llm/OpenAIResponseClient.kt:45) still wire `InsecureSslConfig` into all DEBUG traffic, and [`app/src/main/kotlin/com/moonkey/androidagent/llm/InsecureSslConfig.kt:36`](/Users/moonkey/workspace/android-agent-workspace/androidagent/app/src/main/kotlin/com/moonkey/androidagent/llm/InsecureSslConfig.kt:36) trusts any certificate. That leaves developer/debug builds using real API keys MITM-vulnerable, even though `usesCleartextTraffic` is now false. Scope this to an explicit eval/emulator-only flag or build flavor instead of `BuildConfig.DEBUG`.
doc/todo/security_hardening/design_review_codex.md:11:   The design treats approval as "approve this action in this app", but it never requires a second policy check when the user finally taps approve. In the current router flow, policy is evaluated once before the wait, the router blocks on approval, and then execution resumes directly; after the wait it only refreshes the snapshot, and even that masking path still uses the stale `packageName` captured before the wait instead of the current foreground package (`app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:101`, `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:156`, `app/src/main/kotlin/com/moonkey/androidagent/tool/ToolRouter.kt:220`). The UX spec only covers "user switches away from capsule" as a visibility issue, not "foreground app changed while approval was pending" (`doc/todo/security_hardening/ux_spec_approval_ui.md:137`). For a security gate, that is too weak.
doc/todo/security_hardening/design_review_codex.md:17:   The spec says `ALWAYS_ASK` should still show approval UI, but only hides `Always`; `Session` is still available (`doc/todo/security_hardening/ux_spec_approval_ui.md:143`). The proposed `PolicyEngine` order also puts user allow-lists ahead of approval mode (`doc/todo/security_hardening/ux_spec_approval_ui.md:112`, `doc/todo/security_hardening/design_approval_ui.md:212`). That means a previously remembered app would silently bypass `ALWAYS_ASK`, even though the current engine treats `ALWAYS_ASK` as an unconditional prompt (`app/src/main/kotlin/com/moonkey/androidagent/tool/PolicyEngine.kt:49`).
doc/todo/security_hardening/design_review_codex.md:23:   The proposal says the controller should map `ApprovalResponse` to `ApprovalDecision` and directly call `policyEngine.allowPackageForSession()` / `allowPackagePersistent()` (`doc/todo/security_hardening/design_approval_ui.md:162`). That does not fit the current architecture. User intents are supposed to enter the session as immutable `Op`s, and approval already has a dedicated op type (`app/src/main/kotlin/com/moonkey/androidagent/protocol/Op.kt:89`). `ServiceOverlayController` currently only turns UI actions into ops/callbacks and has no `PolicyEngine` reference (`app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:35`, `app/src/main/kotlin/com/moonkey/androidagent/app/AgentService.kt:137`). `PolicyEngine` itself is created inside session tooling bootstrap, not in the overlay stack (`app/src/main/kotlin/com/moonkey/androidagent/session/SessionToolingBootstrapper.kt:29`).
doc/todo/security_hardening/design_review_codex.md:31:   The current capsule row is a single horizontal strip with a left button group and right nav group, no wrapping, and fixed button padding (`app/src/main/kotlin/com/moonkey/androidagent/ui/capsule/surface/SmartCapsuleSurfaceParts.kt:66`). `ButtonsSpec` is also intentionally tiny today: `primary` plus `stop` (`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/model/CapsuleRenderSpec.kt:15`). The design first proposes an action list, then backs away, then lands on `primary`/`secondary`/`tertiary`/`stop`, which is a sign the abstraction is being stretched by the UI (`doc/todo/security_hardening/design_approval_ui.md:36`). On phone widths, `[Allow] [Session] [Always] [Deny]` is likely to feel cramped even before accounting for icon/text padding (`doc/todo/security_hardening/ux_spec_approval_ui.md:81`).
doc/todo/security_hardening/design_review_codex.md:37:   The UX spec only hides `Always` when `packageName == null` (`doc/todo/security_hardening/ux_spec_approval_ui.md:63`), but `Session` also cannot remember anything without a package, so the proposed behavior is ambiguous. Separately, the design mentions `onApprovalResolved(callId)` in the state holder (`doc/todo/security_hardening/design_approval_ui.md:248`) but the routing table only wires `ApprovalRequired`; the current event handler would still drop `ApprovalResolved` in its fallback branch (`app/src/main/kotlin/com/moonkey/androidagent/app/AgentServiceEventHandler.kt:162`). By contrast, the existing ask-user path has an explicit immediate transition helper, `onUserResponseSent`, that moves the capsule out of waiting state as soon as the user taps (`app/src/main/kotlin/com/moonkey/androidagent/ui/overlay/CapsuleStateHolder.kt:168`, `app/src/main/kotlin/com/moonkey/androidagent/app/ServiceOverlayController.kt:85`).
doc/todo/security_hardening/qa_report.md:79:| Own app (com.moonkey.androidagent) → Allow | **PASS** (after fix) |
doc/todo/security_hardening/qa_report.md:119:**Problem:** `com.moonkey.androidagent` and `com.android.launcher3` were not in `app_tiers.json` NORMAL list. When the agent was inside its own app (or on home screen) and tried to execute screen-changing tools (e.g., `open_app`), the PolicyEngine classified the current foreground app as CAUTIOUS, requiring user approval. In headless `debug-run.sh` mode, no one taps approve, so the 60-second timeout expired and cancelled every action.
doc/todo/security_hardening/qa_report.md:128:**Fix:** Added `com.moonkey.androidagent` and `com.android.launcher3` to NORMAL tier in `app/src/main/assets/security/app_tiers.json`.
doc/todo/settings_redesign/code_review_codex.md:12:./gradlew testDebugUnitTest --tests 'com.moonkey.androidagent.app.AppSettingsStateTest' --tests 'com.moonkey.androidagent.llm.ModelCatalogTest'
doc/todo/settings_redesign/code_review_codex.md:23:- `MainActivityContent` passes the API Key tab `settingsState.apiKey` and `settingsState::updateApiKey` instead of `openAiManualApiKey` and `updateOpenAiManualApiKey` (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivityContent.kt:88-91`).
doc/todo/settings_redesign/code_review_codex.md:24:- Onboarding still writes OpenAI credentials through `updateApiKey(...)` for both OAuth success and manual OpenAI save (`app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModel.kt:119`, `app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModel.kt:491`).
doc/todo/settings_redesign/code_review_codex.md:25:- The one-time cleanup hook `migrateCredentialSplit(...)` is implemented but never called anywhere (`app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsStore.kt:228-244`).
doc/todo/settings_redesign/code_review_codex.md:33:- Settings sign-in saves `OAuthCredentialStore` and the transient `openAiOAuthAccessToken`, but not the legacy `apiKey` fallback (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:671-678`).
doc/todo/settings_redesign/code_review_codex.md:34:- App startup restores only `authMethod` and the auth-card UI state; it never reloads the stored access token into `AppSettingsState` (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:121-128`, `app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:646-652`).
doc/todo/settings_redesign/code_review_codex.md:35:- `buildApiKeys()` prefers `openAiOAuthAccessToken` in OAuth mode and only falls back to `apiKey` if that transient field is blank (`app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsState.kt:155-161`).
doc/todo/settings_redesign/code_review_codex.md:36:- Refresh goes the opposite direction: `refreshOAuthTokenIfNeeded()` writes the refreshed token to `apiKey`, but does not update `openAiOAuthAccessToken` (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:706-716`).
doc/todo/settings_redesign/code_review_codex.md:47:`LlmAuthSettingsPage` correctly changes backend/auth mode on tab click (`app/src/main/kotlin/com/moonkey/androidagent/ui/settings/LlmAuthSettingsPage.kt:91-110`), but only backend is durably persisted:
doc/todo/settings_redesign/code_review_codex.md:49:- `onAuthMethodChange` is wired to `settingsState::updateAuthMethod` (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivityContent.kt:88-89`).
doc/todo/settings_redesign/code_review_codex.md:50:- `updateAuthMethod()` only mutates in-memory Compose state (`app/src/main/kotlin/com/moonkey/androidagent/app/AppSettingsState.kt:140-142`).
doc/todo/settings_redesign/code_review_codex.md:51:- Startup later restores auth mode from `OnboardingStore` (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:127`).
doc/todo/settings_redesign/code_review_codex.md:59:- `openAiSignIn()` blocks in `withContext(Dispatchers.IO) { server.waitForCallback() }` (`app/src/main/kotlin/com/moonkey/androidagent/auth/OpenAiSignIn.kt:47-48`).
doc/todo/settings_redesign/code_review_codex.md:60:- `waitForCallback()` itself blocks in `ServerSocket.accept()` (`app/src/main/kotlin/com/moonkey/androidagent/auth/OpenAIOAuth.kt:99-103`).
doc/todo/settings_redesign/code_review_codex.md:61:- The only cleanup is `finally { server.stop() }` after the suspend function returns (`app/src/main/kotlin/com/moonkey/androidagent/auth/OpenAiSignIn.kt:75-76`).
doc/todo/settings_redesign/code_review_codex.md:62:- Both cancel paths now only cancel the coroutine / update UI (`app/src/main/kotlin/com/moonkey/androidagent/app/MainActivity.kt:689-692`, `app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModel.kt:138-141`).
doc/todo/settings_redesign/code_review_codex.md:70:- `preferredModelFor()` returns the first matching model (`app/src/main/kotlin/com/moonkey/androidagent/llm/ModelCatalog.kt:122-128`).
doc/todo/settings_redesign/code_review_codex.md:71:- Settings canonicalization also picks the first matching model (`app/src/main/kotlin/com/moonkey/androidagent/ui/settings/LlmAuthSettingsPage.kt:339-345`).
doc/todo/settings_redesign/code_review_codex.md:72:- Onboarding still picks `lastOrNull()` for provider defaults (`app/src/main/kotlin/com/moonkey/androidagent/onboarding/OnboardingViewModel.kt:475-480`).

## Ambiguous prose (MANUAL REVIEW REQUIRED in phase 5)

Every active-doc line where "Android agent" / "android agent" / "AndroidAgent" could mean "an agent that controls Android" (platform concept) rather than the ClosePaw product. Reviewer decides each hit: rewrite to `ClosePaw` (product), rewrite to `Android agent` (lowercase, platform concept) or rephrase, or leave. Err on the side of reviewing — borderline hits are included.

.claude/skills/cog-tune/SKILL.md:3:description: Analyze Android Agent cognition using debug-run traces/replay artifacts and eval results, then propose and implement improvements to prompts, tool definitions, context packing (todo/scratchpad/history), and multi-agent coordination. Use when a debug run feels wrong, when eval metrics regress, when tuning context engineering for generalizable gains, or when reviewing LLM input/output and tool usage; produce both a report and code/doc changes.
.cursorrules:1:# Android Agent
AGENTS.md:1:# Android Agent
CLAUDE.md:1:# Android Agent
GEMINI.md:1:# Android Agent
doc/PROGRESS.md:596:- Provide a clearer product and architecture frame for deciding what Android Agent should absorb from OpenClaw-family systems versus what should be reinterpreted natively for a phone-first agent.
doc/dev/development.md:5:This guide covers the development workflow for Android Agent - building, testing, and debugging.
doc/dev/visual_debug_guide.md:1:# Android Agent Visual Debugging Guide
doc/dev/visual_debug_guide.md:5:Visual debugging approach for the Android Agent's ReAct loop using screenshots + logs.
doc/main/README.md:1:# Android Agent Documentation
doc/main/agent/overview.md:3:> Design principles, architecture, and package structure for the Android Agent.
doc/main/eval/eval.md:7:The eval runner bridges the Android Agent app with
doc/main/infra/tool/click_transport_experiment.md:97:W ey.androidagent: Accessing hidden method IInputManager$Stub$Proxy.injectInputEvent (max-target-o, reflection, denied)
doc/main/infra/tool/click_transport_experiment.md:98:W ey.androidagent: Accessing hidden method IInputManager.injectInputEvent (unsupported, reflection, allowed)
doc/main/infra/tools.md:177:- [mobile_action.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/main/infra/tool/mobile_action.md)
doc/main/ui/style.md:8:The Android Agent uses Material 3 with a chat-focused aesthetic. Dark mode support via system theme detection.
doc/main/ui/user_interaction.md:18:│  │ ChatHeader     │ [≡] Android Agent [+]                  │   │
doc/todo/0.5_memory/memory_v2_note.md:87: - Codex 2026-03-13 16:52:27 EDT: 对 Android agent 更合适的是 3 个 scope-first 文件：
doc/todo/0.5_memory/old_round1/memu/memu_codex.md:1:# memU - repo study summary + ideas for Android Agent
doc/todo/0.5_memory/old_round1/memu/memu_codex.md:99:## Concrete adaptation for our Android agent
doc/todo/0.5_memory/old_round1/openclaw/openclaw_codex.md:1:# OpenClaw - repo study summary + ideas for our Android agent
doc/todo/0.5_memory/old_round1/openclaw/openclaw_codex.md:60:## Android node specifics (relevant to our Android agent)
doc/todo/0.5_memory/old_round1/openclaw/openclaw_codex.md:66:## What we can borrow for our Android agent (actionable)
doc/todo/0.5_memory/old_round1/openclaw/openclaw_reviews_codex.md:23:### Product takeaways (for our Android agent)
doc/todo/0.5_memory/old_round2/design/android_agent_memory_architecture_codex.md:1:# Android Agent Memory System 建议（Codex）
doc/todo/0.5_memory/old_round2/design/android_agent_memory_architecture_codex.md:4:面向你当前 Android agent（ReAct 回路 + a11y 感知 + session/history/todos/scratchpad）的现实约束，我建议采用 **Local-first、分层记忆、策略化写入、预算化检索** 的 memory system。
doc/todo/0.5_memory/old_round2/design/android_agent_memory_architecture_codex.md:110:- 过早引入复杂多租户/RBAC（当前是单用户 Android agent 语境）。
doc/todo/0.5_memory/old_round2/design/memory_rollout_codex.md:1:# Android Agent Memory 落地路线（Codex）
doc/todo/0.5_memory/old_round2/design/memory_rollout_codex.md:4:用最小风险方式把 memory system 接入现有 Android agent，优先提升“任务执行成功率与稳定性”，而不是追求 memory 功能完整度。
doc/todo/0.5_memory/old_round2/design/recommendations_claude.md:1:# Recommendations: Android Agent Memory System Design
doc/todo/0.5_memory/old_round2/design/recommendations_claude.md:3:Concrete design recommendations for the Android agent's memory system, grounded in analysis of 9 reference systems and the agent's actual architecture.
doc/todo/0.5_memory/old_round2/design/synthesis_claude.md:1:# Synthesis: Reference Memory Systems → Android Agent
doc/todo/0.5_memory/old_round2/design/synthesis_claude.md:73:- This is *exactly* what the Android agent already has (scratchpad = working memory, but no archival memory yet)
doc/todo/0.5_memory/old_round2/design/synthesis_claude.md:83:- For Android agent, a subset is relevant: **app_knowledge**, **user_preference**, **task_pattern**, **procedure**
doc/todo/0.5_memory/old_round2/design/synthesis_claude.md:145:## 4. Comparison Matrix: Applicability to Android Agent
doc/todo/0.5_memory/qi_note.md:3:我想给我的 Android agent 设计一套记忆系统。你帮我看看我的 .reference/mem/ 底下的这些不同的 memory system 都是怎么设计的。不需要 get into details，只需要 analyze 每一个的对于 agent memory 的 high-level 理解。它们是怎么设计他们的架构的。给我挨个梳理一下。
doc/todo/0.5_memory/review/memory_codex.md:16:4. 如果目标是“让 Android agent 下次进同一个 app 少踩坑”，我们现在这版 **非常对题**；如果目标升级成“真正的通用长期个体记忆”，那现在这版还远不够。
doc/todo/0.5_memory/review/memory_codex.md:153:这里直接对照 [memory.md](/Users/moonkey/workspace/android-agent-workspace/androidagent/doc/main/agent/memory.md) 说。
doc/todo/0.5_memory/review/memory_codex.md:182:这是一个非常好的 domain-specific 选择，因为 Android agent 的失败大头，本来就不是“忘了用户叫什么”，而是“忘了这个 app 上次怎么点才对”。
doc/todo/0.5_memory/review/memory_codex.md:258:对 Android agent 来说，这反而是最自然的：
doc/todo/0.5_memory/review/memory_codex.md:311:  Android agent 的 memory 应该优先服务“减少 UI 探索成本”，不是先做通用人格系统。
doc/todo/0.5_memory/review/memory_codex.md:373:对于 Android agent，这个选择是成立的。
doc/todo/0.5_openclaw/9_mobile_portability_analysis/discussion/0001_CLAUDE.md:25:   - Added explicit compression comparison (Android Agent's pipeline is superior — no absorption needed)
doc/todo/0.5_openclaw/9_mobile_portability_analysis/discussion/0003_CLAUDE.md:13:1. **"Port Directly" → table with concrete Android Agent implementations.** The bullet list didn't show _what_ already exists. Added a table mapping each OpenClaw concept to its specific Android Agent component (e.g., `HistoryManager` for compaction, `AgentDefRegistry` for multi-agent). Also added explicit compaction comparison note — Android Agent's 3-phase pipeline is already superior, no absorption needed.
doc/todo/0.5_openclaw/claude/10_product_strategy_vs_openclaw_claude.md:1:# 产品路线：Android Agent 与 OpenClaw 的关系
doc/todo/0.5_openclaw/claude/10_product_strategy_vs_openclaw_claude.md:5:OpenClaw 很火，用户量大。跟 Android Agent 在大 purpose 上都是 general personal agent，但路径有冲突。需要决定怎么 ride the wave。
doc/todo/0.5_openclaw/claude/10_product_strategy_vs_openclaw_claude.md:87:    └── Android Agent ← 一个 tool，但它自己有大脑
doc/todo/0.5_openclaw/claude/10_product_strategy_vs_openclaw_claude.md:90:  Android Agent (standalone app)
doc/todo/0.5_openclaw/claude/1_memory_system_claude.md:20:Android Agent 目前的 session history 是线性的、一次性的。任务结束了，上下文就丢了。
doc/todo/0.5_openclaw/claude/2_voice_first_claude.md:28:Android Agent 目前完全依赖打字输入 + 屏幕阅读。但手机的核心使用场景里，很多时候用户不方便打字：
doc/todo/0.5_openclaw/claude/3_session_model_claude.md:26:Android Agent 目前的 session 概念比较弱：
doc/todo/0.5_openclaw/claude/4_agent_identity_claude.md:34:Android Agent 现在的 system prompt 是写在代码里的硬编码字符串。问题：
doc/todo/0.5_openclaw/claude/5_three_axis_security_claude.md:30:Android Agent 目前的安全模型很简单：无障碍服务开了就全开了。
doc/todo/0.5_openclaw/claude/5_three_axis_security_claude.md:65:- 对 Android Agent，Phase 1 的 tool 风险分级最实用，其余是远期
doc/todo/0.5_openclaw/claude/6_device_capability_advertising_claude.md:32:Android Agent 目前的 tool 定义是静态的 — 代码里写了什么就有什么。
doc/todo/0.5_openclaw/claude/7_canvas_host_claude.md:23:Android Agent 目前和用户的交互只有两种：
doc/todo/0.5_openclaw/claude/9_mobile_portability_analysis_claude.md:85:OpenClaw 用 "电脑 Gateway + 手机 Node" 的架构，本质上是因为桌面能力强、手机只是传感器。但 Android Agent 恰好反过来 — 手机是主战场（无障碍服务、App 操作、传感器），不需要桌面做 Gateway。
doc/todo/0.5_openclaw/codex/openclaw_product_takeaways_codex.md:11:对 Android Agent 来说，这个思路比单纯抄功能更有价值。我们现在更像“一个会操作手机的 agent”，而 OpenClaw 做得更像“一个有统一控制面的个人助理系统”。
doc/todo/0.5_openclaw/codex/openclaw_product_takeaways_codex.md:19:这点很值得借鉴，因为 Android Agent 现在天然会滑向“所有事情都塞进手机 App 里”，最后容易出现：
doc/todo/0.5_openclaw/codex/openclaw_product_takeaways_codex.md:45:对 Android Agent，这里最值得抄的是“运维面板”思路，而不是 UI 样式。我们当前非常需要一个低成本的控制面，至少应覆盖：
doc/todo/0.5_openclaw/codex/openclaw_product_takeaways_codex.md:69:Android Agent 也应该有自己的 onboarding 向导，至少覆盖：
doc/todo/0.5_openclaw/codex/openclaw_product_takeaways_codex.md:88:这对 Android Agent 很重要，尤其如果后面加：
doc/todo/0.5_openclaw/codex/openclaw_product_takeaways_codex.md:106:对 Android Agent，这很有启发。未来很多需求不应该继续写死在核心 agent 里，比如：
doc/todo/0.5_openclaw/codex/openclaw_product_takeaways_codex.md:133:对 Android Agent，后续如果要做 Web 控制台、消息入口、桌面端，这一点应该尽早定下来：
doc/todo/0.5_openclaw/codex/openclaw_product_takeaways_codex.md:145:Android Agent 也应该尽早整理自己的 showcase，哪怕先只做 5 个高频案例：
doc/todo/0.5_openclaw/codex/openclaw_product_takeaways_codex.md:159:OpenClaw 的广渠道覆盖是它的产品特色，但对 Android Agent 来说，短期不值得学这个“广度”。
doc/todo/0.5_openclaw/codex/openclaw_product_takeaways_codex.md:170:OpenClaw 的 plugin / community 生态已经有体量支撑。Android Agent 现在更适合先做：
doc/todo/0.5_openclaw/codex/openclaw_product_takeaways_codex.md:180:OpenClaw 的架构有明显“控制平面 / 执行节点”分离，这个思路值得借鉴；但 Android Agent 不必一上来复制出一个很重的分布式系统。
doc/todo/0.5_openclaw/codex/openclaw_product_takeaways_codex.md:203:如果做对，它能把 Android Agent 从“只会点屏幕”升级成“能联合手机与宿主环境完成任务”的 agent。
doc/todo/0.5_openclaw/common/analysis_claude.md:167:## 三、Desktop/Cloud Agent 特有（Android Agent 难以具备）
doc/todo/0.5_openclaw/common/analysis_claude.md:225:## 四、Android Agent 特有优势（Desktop/Cloud Agent 不具备）
doc/todo/0.5_openclaw/common/analysis_claude.md:240:**关键洞察：** Desktop agent 操作 app 的主要方式是 API/SDK（需要每个 app 适配），而 Android agent 通过 a11y service 获得了**通用的 app 操控能力**，这是一个根本性的架构优势。
doc/todo/0.5_openclaw/common/analysis_claude.md:244:| 能力 | Desktop Agent | Android Agent |
doc/todo/0.5_openclaw/common/analysis_claude.md:256:| 能力 | Desktop Agent | Android Agent |
doc/todo/0.5_openclaw/common/analysis_claude.md:265:| 场景 | Desktop Agent | Android Agent |
doc/todo/0.5_openclaw/common/analysis_claude.md:295:| 能力 | Desktop/Cloud | Android Agent | 谁更强 |
doc/todo/0.5_openclaw/common/analysis_claude.md:319:## 六、Android Agent 应该从 OpenClaw 生态吸收的能力
doc/todo/0.5_openclaw/common/analysis_claude.md:358:Android Agent     = 个人智能助手 + 真实世界传感器 + App 操控入口
doc/todo/0.5_openclaw/common/analysis_claude.md:361:**Android Agent 的护城河不在于复制 desktop 能力，而在于：**
doc/todo/0.5_openclaw/common/analysis_claude.md:370:两者不是竞争关系，而是互补。Android Agent 应该能作为 OpenClaw 生态的 node（已有先例：openclaw 支持 Android 作为 node），同时保持独立运作的能力。
doc/todo/0.5_openclaw/common/common_capabilities_analysis_cn_codex.md:9:3. 哪些能力你的 Android Agent 可以吸收，哪些反而是手机端天然更强、桌面系 agent 做不到或很难做好的。
doc/todo/0.5_openclaw/common/common_capabilities_analysis_cn_codex.md:91:你的 Android Agent 后续如果做远程接入，应该把外部渠道当作 **intent source**，不是第二套 runtime。
doc/todo/0.5_openclaw/common/common_capabilities_analysis_cn_codex.md:459:它们适合桌面和云端，但不应该成为 Android Agent 的中心设计。
doc/todo/0.5_openclaw/common/common_capabilities_analysis_cn_codex.md:461:## 你的 Android Agent 可以有的能力
doc/todo/0.5_openclaw/common/common_capabilities_analysis_cn_codex.md:477:所以你的 Android Agent 本质上已经是一个 **sessioned personal agent runtime**，不是单纯的 UI automation demo。
doc/todo/0.5_openclaw/common/common_capabilities_analysis_cn_codex.md:535:你的 Android Agent 可以直接处在用户实际生活发生的设备上：
doc/todo/0.5_openclaw/common/common_capabilities_analysis_cn_codex.md:583:而你的 Android Agent 可以直接操作：
doc/todo/0.5_openclaw/common/common_capabilities_analysis_cn_codex.md:612:## 对当前 Android Agent 的具体结论
doc/todo/0.5_openclaw/common/common_capabilities_analysis_cn_codex.md:648:你的 Android Agent 完全可以继承这个定义。
doc/todo/0.6_proactive/events_chatgpt.md:3:# 我现在有一个android agent，现在是被动的，怎么让它变成主动的？比如新拍的照片，新的notification，接下来的events等等的时候，它可以自动地trigger一个agent loop来做一些事情。这个可能需要看看android允许我作为一个developer monitor什么样的events。
doc/todo/0.6_proactive/events_chatgpt.md:5:要把 Android agent 从“被动（只在 UI 输入时跑）”变成“主动（系统事件→自动触发 agent loop）”，核心就是：**把各种系统/数据源事件统一抽象成 Trigger，然后在合适的后台执行机制里，把 Trigger 转成一次 `Op.UserInput(...)`（或等价 op）提交给你现有的 `AgentSession`**。你现在的架构已经很适合做这层：`AgentSession` 本来就提供了 `submit(op: Op)` / `handleUserInput(text)` 作为启动任务入口，并且任务生命周期是标准的 “UserInput → Running → Idle” 循环。 
doc/todo/0.6_proactive/proactive_agent_ux_design_claude.md:1:# Proactive Android Agent — Product & UX Design
doc/todo/0.6_proactive/proactive_agent_ux_design_claude.md:7:The user of an AI-powered Android agent.
doc/todo/0.6_proactive/proactive_agent_ux_design_claude.md:780:The proactive agent transforms the Android Agent from a tool you operate into an assistant that works for you. The design rests on three convictions:
doc/todo/0.6_proactive/proactive_agent_ux_design_codex.md:6:Scope: 将 Android Agent 从“被动触发”升级为“事件驱动的主动协作”
doc/todo/0.9_planner_executor_improv/planner_executor_improvement_gemini.md:3:This document benchmarks the current AndroidAgent `Executor` implementation against the `MobileWorld` implementation to identify areas for improvement.
doc/todo/0.9_planner_executor_improv/planner_executor_improvement_gemini.md:19:## 2. Your Current AndroidAgent Implementation (`SubAgentRunner.kt`)
doc/todo/0.9_planner_executor_improv/planner_executor_improvement_gemini.md:35:| Feature | MobileWorld (Ref) | AndroidAgent (Yours) | Eval / Gap |
doc/todo/1_publish/2_release_build_claude.md:20:  -keystore release.keystore -alias androidagent \
doc/todo/1_publish/2_release_build_claude.md:30:        keyAlias = System.getenv("KEY_ALIAS") ?: "androidagent"
doc/todo/1_publish/3_privacy_claude.md:51:Android Agent uses the accessibility service to:
doc/todo/1_publish/4_open_source_claude.md:19:# Android Agent
doc/todo/1_publish/5_play_store_claude.md:45:Android Agent lets you automate any task on your phone using plain English.
doc/todo/1_publish/publish_gap_codex.md:1:# Android Agent 发布差距评估（Codex）
doc/todo/2_lock_screen/lock_screen_design_codex.md:57:    val wakeLockTag: String = "AndroidAgent:LockExecution",
doc/todo/2_persist_a11y_access/persist_a11y.md:58:            .setContentTitle("Android Agent")
doc/todo/2_transcribe_screen_tool/analysis_gemini.md:15:**Tool Definition** ([executor_tools.py](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/executor_tools.py#L311-330)):
doc/todo/2_transcribe_screen_tool/analysis_gemini.md:38:**Implementation** ([transcription.py](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/transcription.py#L12-69)):
doc/todo/2_transcribe_screen_tool/analysis_gemini.md:66:**Prompt Guidance** ([prompts.py](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/autodevice_android_world/android_world/agents/autodev/prompts.py)):
doc/todo/2_transcribe_screen_tool/analysis_gemini.md:84:**Implementation** ([text_localization.py](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/MobileAgent/PC-Agent/PCAgent_v1/text_localization.py#L35-61)):
doc/todo/2_transcribe_screen_tool/analysis_gemini.md:112:**State Model** ([state.py](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/droidrun/droidrun/agent/droid/state.py#L17-27)):
doc/todo/2_transcribe_screen_tool/analysis_gemini.md:133:**Implementation** ([android_controller.py](file:///Users/moonkey/workspace/android-agent-workspace/androidagent/.reference/mobile_agent/minitap-mobile-use/minitap/mobile_use/controllers/android_controller.py#L90-110)):
doc/todo/3_agent_io_design/android_specific.md:194:Android agents cross:
doc/todo/3_agent_io_design/android_specific.md:206:## 5. What This Means for *Our* Current Architecture (`androidagent`)
doc/todo/3_agent_io_design/android_specific.md:243:## 6. Checklist: Android Agent Infra “Must Haves”
doc/todo/3_agent_io_design/python_playground_design.md:130:1. `android-agent-playground run --goal "..."`
doc/todo/3_agent_io_design/screenshot_input_design.md:1:# Screenshot Input for Android Agent
doc/todo/holistic-review/dead-code-overabstraction/qa_report.md:23:| F | Logcat crash check | ✅ PASS (no `androidagent` fatals) | — |
doc/todo/holistic-review/dead-code-overabstraction/qa_report.md:34:- Screenshot shows **Step 1 of 5** "Let Android Agent control your phone" with progress bar — rendered without crash.
doc/todo/holistic-review/dead-code-overabstraction/qa_report.md:36:- No `FATAL EXCEPTION` from `moonkey/androidagent` in logcat during fresh-install boot.
doc/todo/holistic-review/dead-code-overabstraction/qa_report.md:86:grep -iE "fatal exception|androidruntime.*fatal" logcat_full.log | grep -i "moonkey\|androidagent"
doc/todo/holistic-review/dead-code-overabstraction/qa_report.md:89:`adb logcat -d | grep -iE "fatal|crash"` returned no `moonkey/androidagent` matches.
doc/todo/nextstep/cn/scope_review.md:1:# Scope Review：Android Agent 下一步做什么？
doc/todo/nextstep/scope_review.md:1:# Scope Review: What Should Android Agent Do Next?
doc/todo/onboarding_wizard/ux_design.md:6:Scope: First-launch onboarding wizard for Android Agent on Android 12+ (`minSdk 31`)
doc/todo/onboarding_wizard/ux_design.md:28:Android Agent only becomes valuable after setup is complete. If the first session feels like permission whack-a-mole, users assume the app is broken and give up.
doc/todo/onboarding_wizard/ux_design.md:138:| Accessibility | Read UI state, perform taps in other apps | `Open Accessibility Settings` | **Yes** | "Without Accessibility, Android Agent cannot automate tasks." |
doc/todo/onboarding_wizard/ux_design.md:220:- Top: `Set up Android Agent` title, step count (`Step 2 of 5`), linear progress bar.
doc/todo/onboarding_wizard/ux_design.md:239:│  Let Android Agent control your phone    │
doc/todo/onboarding_wizard/ux_design.md:256:- On return: if service not live → `Still off. Turn on Android Agent in the Accessibility list, then come back.`
doc/todo/onboarding_wizard/ux_design.md:272:│  return to Android Agent.                │
doc/todo/onboarding_wizard/ux_design.md:399:│       [ Start Using Android Agent ]      │
doc/todo/openai_oauth/design.md:38:| Redirect URI | `androidagent://oauth/callback` |
doc/todo/openai_oauth/design.md:80:- `androidagent://oauth/callback` → "Authentication Error" (rejected)
doc/todo/openai_oauth/design.md:267:| `AndroidManifest.xml` | Add intent filter for `androidagent://oauth/callback` |
doc/todo/openai_oauth/design.md:372:        <data android:scheme="androidagent" android:host="oauth" android:path="/callback" />
doc/todo/openai_oauth/design.md:380:Custom Tab redirects → androidagent://oauth/callback?code=xxx&state=yyy
doc/todo/openai_oauth/design.md:394:    if (uri?.scheme == "androidagent" && uri.host == "oauth") {
doc/todo/openai_oauth/design.md:465:&redirect_uri=androidagent://oauth/callback
doc/todo/openai_oauth/design.md:730:1. **OpenAI OAuth client registration**: Register Android Agent as an OAuth application on OpenAI's platform. Obtain `client_id`. Register `androidagent://oauth/callback` as redirect URI.
doc/todo/openai_oauth/design.md:771:- **Custom scheme over App Links**: `androidagent://` is simpler than verified App Links (no `.well-known/assetlinks.json` hosting needed). Acceptable because the OAuth flow is user-initiated and the redirect is immediate.
doc/todo/openai_oauth/findings.md:13:- **Redirect URI**: Only `http://localhost:*` is whitelisted. Custom schemes (`androidagent://`) and external domains (`closepaw.ai`) are rejected
doc/todo/security_hardening/agent_security_review/current_impl.md:3:> 本文档记录 Android Agent 当前安全实现的完整基线。
doc/todo/security_hardening/agent_security_review/reference_ironclaw.md:11:IronClaw 是一个 Rust 编写的个人 AI assistant，核心哲学是 **"your AI assistant should work for you, not against you"**。与 Android Agent 的共同点是都让 AI 代替用户执行操作（IronClaw 通过 WASM tools 和 Docker sandbox；Android Agent 通过 accessibility service），因此都面临相同的核心安全问题：**如何防止 agent 被 prompt injection 操纵、如何限制 tool 权限、如何保护敏感数据**。
doc/todo/security_hardening/agent_security_review/reference_ironclaw.md:15:- Android Agent: Kotlin + Android accessibility service + on-device LLM
doc/todo/security_hardening/agent_security_review/reference_ironclaw.md:47:**Android Agent 启发**：我们的 tool 目前没有 capability-based 权限系统。每个 tool 要么可用要么不可用，缺少细粒度控制（如 "shell tool 只能执行 read 命令"）。
doc/todo/security_hardening/agent_security_review/reference_ironclaw.md:91:**Android Agent 启发**：我们的 LLM API key 目前通过 `BuildConfig` 注入，shell tool 执行的命令理论上可以读取 env vars。应当考虑类似的 host-boundary injection 模式。
doc/todo/security_hardening/agent_security_review/reference_ironclaw.md:173:**Android Agent 启发**：我们目前没有 tool-level approval 机制。所有 tool 一旦可用就自动执行。需要引入类似的分级 approval，特别是对 financial app 内的操作。
doc/todo/security_hardening/agent_security_review/reference_ironclaw.md:197:**Android Agent 启发**：当我们引入 app skills（`SKILL.md`）时，外部/社区贡献的 skill 应该自动降低 tool 权限上限。一个恶意 skill 不应能通过 prompt injection 让 agent 执行 shell 命令或操作金融 app。
doc/todo/security_hardening/agent_security_review/reference_ironclaw.md:235:## 与 Android Agent 的对比/启发
doc/todo/security_hardening/agent_security_review/reference_ironclaw.md:239:| 维度 | IronClaw | Android Agent | Gap |
doc/todo/security_hardening/agent_security_review/reference_ironclaw.md:252:### 对 Android Agent 最有价值的 3 个设计
doc/todo/security_hardening/agent_security_review/reference_ironclaw.md:256:Android Agent 操作的是 **真实设备上的真实 app**，impact 远高于 IronClaw 的 HTTP 请求。我们需要：
doc/todo/security_hardening/agent_security_review/reference_ironclaw.md:275:当 Android Agent 引入外部 app skills 时：
doc/todo/security_hardening/agent_security_review/reference_ironclaw.md:287:1. **`ApprovalRequirement` 三级枚举** — 直接适用于 Android Agent 的 tool 执行流
doc/todo/security_hardening/agent_security_review/reference_ironclaw.md:295:1. **WASM sandbox → Action sandbox**: IronClaw 用 WASM 隔离 tool；Android Agent 需要在 action 层实现 "dry-run" 或 "preview" 机制（如 "将要点击 '确认付款'，是否继续？"）
doc/todo/security_hardening/agent_security_review/reference_ironclaw.md:302:1. **Docker sandbox** — Android Agent 运行在单一设备上，无 container 隔离
doc/todo/security_hardening/agent_security_review/reference_ironclaw.md:316:对 Android Agent 而言，最紧迫的借鉴是 **tool approval 分级** 和 **screen content sanitization**。这两个直接对应了我们面临的最大风险：agent 在敏感 app 中执行不可逆操作，以及 malicious UI content 触发 prompt injection。
doc/todo/security_hardening/agent_security_review/reference_openclaw.md:184:## 与 Android Agent 的对比/启发
doc/todo/security_hardening/agent_security_review/reference_openclaw.md:186:| 维度 | OpenClaw | Android Agent | 差距/启发 |
doc/todo/security_hardening/note.md:8:> 我现在有个localhost支持https的方法。看~/workspace/workflow，我可以用https://laptop.tail6bd948.ts.net/。通过tailscale实现的。为了让androidagent的llm proxy也可以，可能要配合~/workspace/cproxy的修改和tailscale的设置。同时我想让workflow的上面继续可以work，不知道这里会不会有冲突(e.g., they all want port 80)。
doc/todo/security_hardening/note.md:10:>> **[回复]** 看了 cproxy——它是 HTTP proxy 跑在 `127.0.0.1:18080`。Tailscale 的 HTTPS 方案（`laptop.tail6bd948.ts.net`）是 Tailscale 自动签 TLS cert 后反代到本地端口，不占 80/443。所以不冲突：workflow 和 cproxy 可以各自映射到不同的 Tailscale hostname 或同一 hostname 的不同 path。具体做法：(1) cproxy 继续监听 18080，(2) Tailscale serve 把 `https://laptop.tail6bd948.ts.net/cproxy/` 反代到 `localhost:18080`，(3) workflow server 用另一个 path 或端口。实现时在 `tailscale serve` 配置里加一条就行。这个改动属于 infra，不影响 androidagent 代码，只需要把 androidagent 里 LLM endpoint 配置从 `http://...` 改成 `https://laptop.tail6bd948.ts.net/...`。可以在 security hardening 实现时一起做。
doc/todo/security_hardening/ux_spec_approval_ui.md:5:**Who**: Users running the Android Agent in SMART approval mode (the default).
doc/todo/settings_redesign/cn/ux_design.md:56:│  Android Agent v1.0 (1)                 │
doc/todo/settings_redesign/ux_design.md:56:│  Android Agent v1.0 (1)                 │
eval/README.md:1:# Android Agent Evaluation Harness
eval/README.md:232:3. Launch the Android Agent app, which will trigger the Shizuku permission
inspection_tool/README.md:1:# Android Agent Replay Viewer
scripts/README.md:1:# Android Agent Development Scripts
scripts/README.md:192:2. Find "Android Agent"
sop/adhoc/multithread_work.md:19:cd androidagent
sop/adhoc/multithread_work.md:20:git worktree add ../androidagent-profiling feature/profiling
sop/adhoc/multithread_work.md:21:git worktree add ../androidagent-feature-x feature/x
sop/adhoc/multithread_work.md:27:android-agent-workspace/      <- Open Cursor from HERE
sop/adhoc/multithread_work.md:28:├── androidagent/             -> main branch (chat)
sop/adhoc/multithread_work.md:29:├── androidagent-profiling/   -> feature/profiling branch
sop/adhoc/multithread_work.md:30:└── androidagent-feature-x/   -> feature/x branch

## Summary counts

- **Phase 1**: 3 files (`settings.gradle.kts`, `app/build.gradle.kts`, `app/proguard-rules.pro`).
- **Phase 2**: 4 package dirs to `git mv` (one per source set) + 382 `.kt` files requiring package/import rewrite (main: 264, test: 116, debug: 1, release: 1) + 7 intent/className literal sites (5 in kt, 1 in script, 1 in manifest path `app/src/main/...` already covered; manifest itself has 0 intent-action literals carrying the old package).
- **Phase 3**: 6 resource/manifest edits (`AndroidManifest.xml` ×2 theme refs, `strings.xml` app_name, `themes.xml` ×2 style names, `app_tiers.json` ×1 package key).
- **Phase 4**: 22 files — 8 under `scripts/**` (including `scripts/README.md`) + 14 under `eval/**` (4 configs, `pyproject.toml`, `README.md`, 4 bridge sources, 4 tests).
- **Phase 5**: 36 active-doc files carry 177 unambiguous `com.moonkey.androidagent` package-token hits (see §Package-token hit list). 11 brand-label sites (`# Android Agent` headers and in-app labels) are unambiguous replacements.
- **Excluded**: 1778 tracked files across `doc/archive/**` (1051), `doc/autotune/round_*/**` (467), `doc/todo/**/{final,initial}/**` (259), `eval/results/**` (1). ~1820 "android agent" / package hits in those trees are **NOT** reviewed. Build/.cxx/.gradle/.worktrees are untracked and also excluded.
- **Ambiguous prose**: 177 active-doc sites flagged for phase-5 manual review (full list above).

### Verification
- `grep -rn '^package com\.moonkey\.' app/src` returns 382 hits, all under `com.moonkey.androidagent` — no stray subpackages. Confirms Phase 2 scope is complete.
- Excluded-rule sanity: `doc/archive` contributes 1600 mentions, `doc/autotune/round_*` contributes 220 mentions. Those numbers are frozen per design §"Scope decisions" item 8.
- Every required acceptance string appears above: `settings.gradle.kts`, `app/build.gradle.kts`, `AndroidManifest.xml`, `strings.xml`, `themes.xml`, `scripts/setup.sh`, `scripts/debug-run.sh`, `eval/config`, `eval/pyproject.toml`, `CLAUDE.md`.
