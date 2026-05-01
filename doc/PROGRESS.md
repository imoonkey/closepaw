# Changelog

## 2026-05-01: Agent Skills (agentskills.io) + App Skill frontmatter migration

**What changed:**
- **agentskill feature** — new `agent/cognition/skills/` package: `SkillFrontmatterParser` (SnakeYAML + lenient regex fallback), `AgentSkillCatalog` (one-time discovery of `filesDir/skills/*/SKILL.md`), `AgentSkillManager` (`@Synchronized` activation, `/skill-name` mention parsing), `ActivateSkillTool`. Wired into `SessionServices` (manager preserved across `copy()`), `SessionToolingBootstrapper` (conditional tool registration when catalog non-empty), `TurnPlanningPhaseRunner` (catalog → system prompt; explicit-mention bodies → user-role messages, NOT system prompt). All three roles (`StandaloneAgentDef`, `PlannerAgentDef`, `ExecutorAgentDef`) added `activate_skill` to allowedTools. SnakeYAML dependency added to `app/build.gradle.kts`.
- **App skill frontmatter migration** — all 17 `app/src/main/assets/app_skills/<package>/SKILL.md` migrated from old `name: com.android.settings` to agentskills.io-compatible `name: app-<short-name>` (e.g. `app-settings`, `app-chrome`) + `metadata.package: <pkg>`. Directory still keyed by package — lookup path unchanged.
- **App skill loader unification** — `AssetAppSkillRepository` now uses shared `SkillFrontmatterParser`; local `FRONTMATTER_REGEX` and `stripFrontmatter()` deleted.
- **Build-time guard** — new `AppSkillAssetIntegrityTest` parses every real `app_skills/*/SKILL.md` and asserts `name: app-*` + `metadata.package == directory name`. Catches typos at unit-test time.

**Why:**
- Two distinct skill types now coexist with one shared parser. App Skills remain auto-loaded per foreground package; Agent Skills are general-purpose task/capability instructions activatable by user (`/skill-name` in goal) or model (`activate_skill` tool). Skill bodies enter conversation as user-role messages or tool results, NOT system prompt — preserving the design priority where Active Agent Skills sit below user goal, screen evidence, and app skills. Re-activation re-reads the file and re-delivers the body so the model can recover after compaction.
- Two codex review rounds caught 1 CRITICAL + 6 HIGH issues, all fixed before merge: synchronized activation state (LinkedHashSet shared via copy()), explicit-mention body delivery, read-failure rollback, description length cap (≤1024 chars), mention-regex boundary (no `/data/local/tmp` false positives), prompt-layer authority (bodies as user messages not system prompt), idempotent re-activation with body, multiline-description sanitization (newlines/control → space).

**Key files:**
- agent/cognition/skills: `SkillFrontmatterParser.kt`, `SkillFrontmatter.kt`, `AgentSkillCatalog.kt`, `AgentSkillEntry.kt`, `AgentSkillManager.kt`
- tool: `impl/ActivateSkillTool.kt`, `ToolName.kt`, `ui/common/ToolUi.kt`
- session/agent wiring: `SessionServices.kt`, `SessionToolingBootstrapper.kt`, `TurnPlanningPhaseRunner.kt`, definition/`{Standalone,Planner,Executor}AgentDef.kt`
- app skill: `agent/cognition/prompt/AppSkillRepository.kt` + 17 migrated `assets/app_skills/*/SKILL.md`
- tests: `agent/cognition/skills/{SkillFrontmatterParser,AgentSkillCatalog,AgentSkillManager}Test.kt`, `tool/impl/ActivateSkillToolTest.kt`, `agent/cognition/prompt/{AssetAppSkillRepository,AppSkillAssetIntegrity}Test.kt`, `agent/definition/AgentDefTest.kt`
- build: `app/build.gradle.kts` (SnakeYAML dep)
- docs: `doc/main/agent/agent_skills.md` (new), `doc/main/agent/turn_prompt_anatomy.md`, `doc/main/README.md`

**Verification:** `./gradlew clean assembleDebug` BUILD SUCCESSFUL. `./gradlew testDebugUnitTest` all green. Two codex review rounds, all 7 HIGH/CRITICAL issues addressed. Real-device QA on Nubia P0110 (via Tailscale + cproxy): 5/5 PASS for agent skill scenarios — catalog in system prompt, model-driven `activate_skill`, `/skill-name` explicit, invalid-skill skipped, app skill regression. Migrated Settings app skill confirmed loading: `App skill lookup: pkg=com.android.settings, found=true`.
**Commit:** `15e59b28..c9ac836f` (agentskill feature) · `e806fe32..7e1651e6` (app skill migration + loader unify) · `c9ac836f` (asset integrity test)
**Next:** Skill installation UX (currently ADB push only — future skill hub integration noted in design)
**Blockers:** None

## 2026-04-28: Settings UI — full-screen pages replace ModalBottomSheet

**What changed:**
- Replaced `ModalBottomSheet` wrapper in `MainActivityContent.kt` with a full-screen `Surface` overlay
- Added `BackHandler` in `SettingsSheet.kt`: sub-page → system back returns to HOME; HOME → system back dismisses settings
- Removed `ExperimentalMaterial3Api` opt-in (no longer needed)

**Why:**
- KISS: bottom sheet added complexity (drag state, partial expand, scrim, dismiss gestures) with no benefit for a full-screen settings flow. Full-screen pages are simpler and less error-prone.

**Key files:** `app/MainActivityContent.kt`, `ui/settings/SettingsSheet.kt`
**Verification:** `./gradlew assembleDebug` green. Real-device QA on Nubia P0110 (via Tailscale ADB): all 8 flows PASS — settings home fullscreen, 3 sub-pages fullscreen, system back from each sub-page → HOME, system back from HOME → dismiss to chat. Codex code review: 2 findings (BackHandler, modal barrier) — both addressed.
**Commit:** `21cdd77e`, `72bfd550`, `aa074bcc`
**Next:** None
**Blockers:** None

## 2026-04-22: ux-feedback-0422 — capsule + chat hierarchy + final-answer survives collapse

**What changed:**
- **uxfb-1 · full thought preservation** — `sanitizeThought` (40-char truncation) renamed to `compactThought` (80-char, opt-in only). `ThoughtUpdate` carries `{full, compact}` — reducer + history persist `full`. `IslandOverlayHost.modeText` `.take(24)` removed.
- **uxfb-2 · capsule marquee** — `Modifier.basicMarquee` on `StatusIslandCompose` + `SmartCapsuleSurface` thought lines. Reduced-motion (`ClosePawMotion.reducedMotion()`) renders `compactThought` ellipsis instead. StatusIsland `widthIn(max = 220.dp)` on both branches stops overlay growing off-screen.
- **uxfb-3 · FinalText + CollapsePill + MessageBubble split** — new `ContentBlock.FinalText` populated by reducer per `Turn.kt:205-209` stop criteria (complete_task.answer or last text-without-tools). Recorder mirrors via `AgentMessageBuffer.recordFinalAnswer` (drains streamed `textBuffer` to avoid duplicate Text+FinalText). Legacy history migration in `MessageConverter.migrateLegacyFinalText`. New `CollapsePill` owns click + `Role.Button`; row root no longer clickable. `MessageBubble.kt` 463 → ~90 lines, extracted into `AgentRow.kt` + `AgentSummary.kt` + `CollapsePill.kt`.
- **uxfb-4 · ThoughtGroup hierarchy** — `AgentTrace.kt` extracted, restructures `ExpandedTrace` into ThoughtGroups (one `Thought` + N consecutive `Action`s = one group). 2dp `outlineVariant` left rule, thought as `bodyLarge` group header (no italic, no `✱`), actions indented `monoSmall onSurfaceVariant`. `style.md` typography table updated.
- **uxfb-followup · IME header anchoring + complete_task answer untrimmed** — `ActionDescriptionFormatter.formatCompleteTask` `.take(50)` removed. `AndroidManifest.xml` MainActivity gets `android:windowSoftInputMode="adjustNothing"`; `ChatScreen` bottomBar uses `Modifier.imePadding()` + Scaffold `contentWindowInsets = WindowInsets(0)`. Followup2 added `imePadding` to `OnboardingShell` + `SettingsSheet`; `OnboardingShell` also keys scroll fallback off `WindowInsets.isImeVisible` so portrait API-key step CTA stays reachable.

**Why:**
- User feedback: (1) thought truncated with `…` in capsule and chat; (2) chat trace was a flat dump with thought styled as the *least* prominent item while mono actions looked most prominent; (3) on collapse, the agent's actual closing answer disappeared because `collapsedHeadline` showed the *first* in-row signal. Three architectural decisions locked before implementation: D1 thought preserved end-to-end, D2 dedicated CollapsePill (row root no longer clickable to avoid fighting selection/copy), D3 final defined by `Turn.kt` stop criteria.

**Key files:**
- protocol: `TextUtils.kt`, `ThoughtEvents.kt`
- agent: `TurnPlanningPhaseRunner.kt`, `AgentEventDispatcher.kt`, `ActionDescriptionFormatter.kt`
- app: `AgentServiceEventHandler.kt`, `AndroidManifest.xml`
- history: `AgentMessageBuffer.kt`, `SessionRecordingService.kt`, `MessageRecord.kt`, `MessageConverter.kt`
- ui chat: `ChatViewModel.kt`, `ChatEventReducer.kt`, `ChatScreen.kt`, `model/ContentBlock.kt`, `components/{MessageBubble,AgentRow,AgentTrace,AgentSummary,CollapsePill}.kt`
- ui overlay: `StatusIslandCompose.kt`, `SmartCapsuleSurface.kt`, `IslandOverlayHost.kt`, `CapsuleStateHolder.kt`
- ui onboarding/settings: `OnboardingShell.kt`, `SettingsSheet.kt`
- docs: `doc/main/ui/{tech_design,style}.md`, `doc/main/ui/capsule/{architecture,state_machine}.md`, `doc/main/protocol/overview.md`

**Verification:** `./gradlew :app:assembleDebug :app:test` green throughout (1185+ tests). Real-device QA on EP0110MZ0BC101266W validated all 4 chat scenarios + IME header anchoring + Settings API-key with IME open. 5 codex `/code-review` rounds caught 5 HIGHs (island width, fabricated FinalText, legacy migration, recorder boundary, activity-wide adjustNothing scope, onboarding scroll threshold) — all fixed; final pass clean.
**Commit:** `7bede637..f34e6d21` (10 commits).
**Next:** P3 deferred — in expanded state the final answer also shows as `complete_task` action's `resultSummary` inside trace, duplicating the FinalText below. Decide whether to drop the duplicate or keep both for redundancy.
**Blockers:** None.


## 2026-04-22: Doc / project separation cutover

**What changed:**
- `doc/todo/` → `projects/active/`, `doc/archive/` → `projects/archive/`. State files (`tasks.json`, `progress.json`, `.tasks.json.lock` if present) promoted to `projects/` root. `doc/PROGRESS.md` stays in `doc/`.
- Sweep across `CLAUDE.md`, `README.md`, `AGENTS.md`, `doc/main/`, `doc/dev/`, `projects/active/` for stale `doc/todo` / `doc/archive` strings. Targeted rewrite of `projects/archive/*.json` design fields where present.

**Why:**
- `doc/` was mixing stable reference (read to *learn* the codebase) with live workstream state (read to *execute* in-flight work). Splitting on audience lets the file explorer surface coherent content and unblocks publishing `doc/` as a public artifact later. Cross-workspace rollout coordinated from `~/workspace/workflow/projects/archive/20260422_doc-separation/design.md`; this repo is one of the 11 migrated.

**Key files:** all of `doc/{todo,archive}` (renamed), state files at `projects/`, plus the swept reference docs above.
**Verification:** `grep -rE 'doc/(todo|archive)'` in source code returns empty (excluding `doc/PROGRESS.md` narrative + `projects/archive/**` historical prose). Single migration commit landed on `main`.
**Commit:** `b22be2b9`.
**Next:** None — Phase 0 skill freeze across `~/workspace/*` has been lifted.
**Blockers:** None.


## 2026-04-22: ui — frontend revamp polish, logic, session-fix landing (post-D2)

**What changed:**
- Font binaries shipped (D2): `res/font/{geist,fraunces,jetbrains_mono}_*.ttf` real files; Fraunces/Geist/JetBrains Mono now resolve to bundled glyphs instead of system fallbacks (`882d8dc1`).
- Semantic naming convention rolled out across capsule + overlay: dropped `Row1/Row2/Row3` for `Status* / Detail / Control / Input` parts; `CapsuleColors.kt` retired in favor of inline derivation; `CapsuleBinding` value type extracted so `ChatScreen` no longer reaches into `AgentService` singletons (`2d51d580`, `e9e1f608`, `e0cf9c61`).
- Track A chat row anatomy locked (`fc48c183` + `d507e37e`): trace items render `Thought` (italic) / `Action` (mono `→ tool(args) ✓`) / `Final` (bodyLarge), separated by `outlineVariant` hairline. Inline-content streaming cursor (`Placeholder 0.5em × 1em` + serif `|` on `CursorBlink`).
- Reducer + row-state hardening (`951c82f5`, `89977c14`, `ecf67fae`, `a2bba8a7`, `c9c36282`, `823b5332`): per-bubble `RowState` (Live/Waiting/Complete/Error) round-trips through history persistence; `Error` is locked-open and never downgraded to `Complete`; final block split from terminal text; `TaskOutcome.ERROR` preserves real error text (was clobbered by generic copy); ThinkingIndicator tint pinned + a11y label + done-bridge guard; live-pill FAB; collapsed-headline tri-state fallback. JVM tests + new `CollapsedHeadlineTest`, `ChatThoughtAndRowStateTest`, `ChatSupplementAndActionTransitionTest`, `ChatDoneBridgeTest`, `ChatStreamingCursorTest`, `ThinkingIndicatorCadenceTest`.
- Surface polish (`68150070`, `aa8ee97d`, `d730ab07`, `4d0e1168`, `43c2a61f`, `8069e5fc`, `4b6e55bd`, `784b8b2e`): Material defaults swapped for ClosePaw motifs; Fraunces on identity surfaces; reduced-motion contract wired (`ClosePawMotion.reducedMotion()` per surface); paw-glyph thinking cadence per Motion §4; consistent Claw primary CTA; banner typography + Local-tab gating; onboarding bottom-pin preserved on short heights; resume refresh of Setup Issue banner; timestamps via app DateFormat instead of hardcoded `Locale.US`.
- Capsule waiting-row persistence fix (`d23537e8`): tapping Done in chat clears stale `WaitingFor*` capsule state.
- INV-1 in flight: capsule overlay verify+fix worker dispatched for missing-overlay diagnosis (`8a027442`, `e597e7b1`); root-cause doc in `doc/archive/20260422_ui-polish/capsule_investigation.md`. `OverlayComposeHost.kt` has uncommitted changes pending the worker output.
- Security: `app_tiers.json` enriched with installed apps (`d3f9128f`).
- Settings: platform mode toggle + Shizuku status surface landed and milestone archived (`44b6cb83`, `7c58661b`).

**Why:**
- Track A/D2 visual baseline + state-machine refactor needed end-to-end UX validation. Two parallel review passes (Claude + Codex) on logic and polish surfaced regressions that were fixed in dedicated PR-A through PR-E orchestrators (11/11 landed). Session-review round 2 cleared the milestone for archive.

**Key files:** `app/src/main/kotlin/ai/closepaw/ui/{theme,chat,capsule,overlay,onboarding,settings,navigation}/**`, `app/src/main/res/font/*.ttf`, `app/src/{androidTest,test}/kotlin/ai/closepaw/{qa,ui,history}/**`.
**Verification:** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` green across rounds; on-device QA reports in `doc/archive/20260420_frontend-ui-revamp/eng-design/qa_report*.md`; aligned session review in `doc/archive/20260422_ui-polish/session_review_aligned.md` (8 VERIFIED / 0 PARTIAL / 0 REGRESSION on round 2).
**Commit:** `f4a699b9..HEAD` (71 commits).
**Next:** INV-1 worker output → fix overlay missing case; reconcile 7 deferred test specs (followup task filed).
**Blockers:** None at code level. INV-1 still investigating.

## 2026-04-20: ui/{capsule,overlay} — D2-3 capsule + overlay restyle on tokens + motion

**What changed:**
- `GlowOverlayHost` retired its local `PULSE_DURATION_MS` (800ms) and `FADE_OUT_DURATION_MS` (500ms) constants. Pulse, fade-in, fade-out, and the post-fade hide delay all route through `ClosePawMotion` (`GlowPulse` 900ms / `EaseInOutSine`, `SurfaceSwap` 240ms, `OverlayFadeOut` 480ms). The `SUCCESS_HIDE_DELAY_MS` (2000ms UX dwell) stays — it is product timing, not animation cadence.
- `SmartCapsuleSurface` now wears `Modifier.foldedPaper(MaterialTheme.shapes.large)` per D1 §4.4 (warm under-shadow + top hairline), drops `shadowElevation = 8.dp`, and consumes `closePaw.spacing` for inner padding. Status-dot color animates on `tween(ClosePawMotion.StatusFlip, EaseInOutSine)` instead of the default Compose spring.
- `StatusIslandCompose`, `CapsuleControlBar`, `CapsuleInputBar`: all `RoundedCornerShape(...)` literals replaced with `MaterialTheme.shapes.large/medium`; arrangement / dot sizing / horizontal padding flow through `closePaw.spacing` (`xs`/`sm`/`md`). Action button text switched from raw `14.sp` to `MaterialTheme.typography.labelLarge`.
- `Color(0x...)` literals: zero in `ui/capsule/**` and `ui/overlay/**`. `tween(`/`spring(` references: only via `ClosePawMotion`.

**Why:**
- Phase D2-3 acceptance (`design_aligned.md` §5): capsule + overlay consume D1 palette / shapes / typography / motion; no local ad-hoc timing constants; no raw color literals. Folded-paper modifier carries the D1 §4.4 chrome onto the capsule per §2.

**Key files:** `app/src/main/kotlin/ai/closepaw/ui/capsule/surface/{SmartCapsuleSurface,CapsuleControlBar,CapsuleInputBar}.kt`, `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/{GlowOverlayHost,StatusIslandCompose}.kt`.
**Verification:** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` green. `grep -rn "Color(0x" app/src/main/kotlin/ai/closepaw/ui/capsule app/src/main/kotlin/ai/closepaw/ui/overlay` → 0 results. `grep -rn "tween(\|spring("` → only `ClosePawMotion`-prefixed references.
**Commit:** `bc9cc4e8` on `task/d2-impl`.
**Next:** `d2-6-contrast-handoff` is the only remaining D2 task.
**Blockers:** None.

## 2026-04-20: ui/chat — D2-4 Track-A restyle + inlineContent streaming cursor

**What changed:**
- Chat row + components consume `MaterialTheme.closePaw` tokens (spacing, `bodyItalic`, `serifItalic`, `monoBody`, `inkFaint`). Trace items keep the 8dp Track A spacing via `closePaw.spacing.sm`. Final block now sits below an `outlineVariant` (InkGhost) hairline.
- Legacy bordered `ActionCard` composable retired. Actions render as inline trace rows (`→ tool_name(args)` mono + right-aligned status glyph `⏳/✓/✕/⊘`) inside `MessageBubble.AgentRow`. The `ActionCardData` *model* survives — only the surface form is replaced. `ActionCard.kt` and its androidTests deleted.
- `StreamingText` rewritten to use `inlineContent` with `Placeholder(0.5em × 1em, TextCenter)`. Cursor is a serif `|` at `bodyLarge` size, blinking on `ClosePawMotion.CursorBlink` (480ms / Linear / Reverse). Cursor child carries `qa-streaming-cursor` test tag; `ChatStreamingCursorTest` updated to match.
- `ThinkingIndicator` runs on `ClosePawMotion.ThinkingPulse`. `EmptyState` subtitle switched to identity-tier `closePaw.serifItalic`; spacing/shape now flow through tokens + `MaterialTheme.shapes.large`.
- Verification artifact: `doc/todo/frontend-ui-review/eng-design/track-d2/verification/d2-4-streaming-cursor.md` + on-device screenshot of D2-themed chat surface. Full Fraunces baseline check is gated on font binaries shipping in `res/font/` (current alias falls back to system Serif).

**Why:**
- Phase D2-4 acceptance: Track A row styling lands on D2 theme/motion; `inlineContent` cursor implemented per `track-d2/final/design_aligned.md` §2; legacy action-card path retired (visual aligned spec §6.2: actions are inline trace rows, not cards).

**Key files:** `app/src/main/kotlin/ai/closepaw/ui/chat/components/{MessageBubble,StreamingText,ThinkingIndicator,EmptyState}.kt`, removed `app/src/main/kotlin/ai/closepaw/ui/chat/components/ActionCard.kt`, `app/src/androidTest/kotlin/ai/closepaw/qa/ChatStreamingCursorTest.kt`.
**Verification:** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug` green. JVM unit suite (Track A reducer + Track C state-machine tests) stays green. APK installed and chat surface verified on a physical device (screenshot in verification dir).
**Commit:** `b0c741f9` on `task/d2-impl`.
**Next:** `d2-6-contrast-handoff` once D2-3 capsule/overlay restyle settles.
**Blockers:** Streaming-cursor Fraunces baseline check is deferred to font-asset bundling; the design's documented Geist `|` fallback is one style swap away.

## 2026-04-20: ui/{settings,navigation,onboarding} — D2-5 theme rollout

**What changed:**
- `SettingsSheet` page transitions now use `ClosePawMotion.PageSlide` (240ms / `EaseOutCubic`) instead of the default Compose tween. Settings home ↔ subpage slides share the D1 cadence with the rest of the app.
- `OnboardingShell`, `NavigationDrawer`, and `SettingsWidgets.SettingsSection` consume `MaterialTheme.closePaw.spacing` for natural-fit scale matches (`xs`/`sm`/`md`/`xl`). Non-matching values (16/24dp) are left as raw `.dp` rather than rounded to the scale.

**Why:**
- Phase D2-5 acceptance: lower-frequency surfaces stop relying on hardcoded vocabulary; settings transitions live on the shared motion layer (`design_aligned.md` §5).

**Key files:** `app/src/main/kotlin/ai/closepaw/ui/settings/{SettingsSheet,SettingsWidgets}.kt`, `app/src/main/kotlin/ai/closepaw/ui/navigation/NavigationDrawer.kt`, `app/src/main/kotlin/ai/closepaw/ui/onboarding/OnboardingShell.kt`.
**Verification:** Targeted edits compile; full `:app:compileDebugKotlin` blocked by w-d2-2's in-flight capsule/overlay edits in the shared `d2-impl` worktree (out of D2-5 scope).
**Commit:** `7d4740ba` on `task/d2-impl`.
**Next:** `d2-6-contrast-handoff` (after capsule/chat surfaces settle).
**Blockers:** None for D2-5 itself.

## 2026-04-20: ui/theme — D2-1 theme foundation (D1 baseline wired)

**What changed:**
- Renamed `ChatTheme` → `ClosePawTheme`. Wraps `MaterialTheme` and provides one thin extension surface — `MaterialTheme.closePaw: ClosePawTokens` — for the D1 residue Material does not slot cleanly.
- Rewrote `ui/theme/Color.kt` around the D1 palette (Paper / Ink / Claw / Moss / Amber / Rust + light + dark). Removed every legacy `Chat*` / `UserBubble*` / `Status*` color. Mapped D1 onto Material roles per the eng spec table.
- Rewrote `ui/theme/Type.kt`: every Material slot is Geist; Fraunces and JetBrains Mono are reached only through `ClosePawTokens` extras (`bodyItalic`, `serifItalic`, `monoBody`, `monoSmall`). Brand families currently fall back to system `SansSerif` / `Serif` / `Monospace`; binaries land in `app/src/main/res/font/` per `app/src/main/assets/FONT_ATTRIBUTION.md` with a one-line swap.
- Rewrote `ui/theme/Shape.kt`: three Material radii (8 / 10 / 16dp). Removed `BubbleShapeUser/Agent`, `CapsuleShape`, `CardShape`, `InputShape`, `PillShape`, `SheetShape`, `AgentShapes`, `AgentTypography`.
- Added `ui/theme/Tokens.kt` (`ClosePawTokens`, `ClosePawSpacing`, `LocalClosePawTokens`, `MaterialTheme.closePaw`, `Modifier.foldedPaper`).
- Added `ui/theme/Motion.kt` (`ClosePawMotion`: four durations, two easings, named primitives, `reducedMotion()` helper).
- Mechanical sweep across call sites (`MainActivity*`, `OverlayComposeHost`, `MessageBubble`, `ActionCard`, `SettingsWidgets`, `OpenAiAuthCard`, `IslandOverlayHost`, `ActionVisualizerCompose`) — every legacy color/shape symbol replaced with a Material slot or `closePaw` token. Zero `Color(0x..)` literals remain in `app/src/main/kotlin/ai/closepaw/ui/` outside `theme/Color.kt` (the palette source).

**Why:**
- Track D2 is "implement D1 in Compose with the smallest architecture that can carry it." A foundation, not a framework: Material first; one extra token surface; one motion surface; nothing pre-built for hypothetical second callers. D2-1 is the load-bearing first step every other `d2-*` task depends on.

**Key files:** `app/src/main/kotlin/ai/closepaw/ui/theme/{Theme,Color,Type,Shape,Tokens,Motion}.kt`, `app/src/main/kotlin/ai/closepaw/{app,ui/chat/components,ui/settings,ui/overlay/compose}/*`, `app/src/main/assets/FONT_ATTRIBUTION.md`, `doc/main/ui/style.md`, `doc/main/ui/tech_design.md`.
**Verification:** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug` all green. Acceptance greps confirmed: `Tokens.kt` + `Motion.kt` exist, `ClosePawTheme` used at app root + overlay host, no legacy color/shape symbol survives, no `Color(0x..)` outside the palette source.
**Commit:** task/d2-impl branch.
**Next:** `d2-2-semantic-visual-model-cleanup` (drop raw palette values from non-Compose render models).
**Blockers:** None. Font binaries can be dropped in any time without changing call sites.

## 2026-04-20: ui — Track C state-machine docs + tests as refactor safety net

**What changed:**
- New `doc/main/state_machines/ui_capsule.md` — test-locked CapsuleMode FSM reference (9 states, transition matrix, guard rules, sidecar flags, render derivation, input routing).
- New `doc/main/state_machines/ui_chat.md` — chat reducer reference (per-message `AgentMessageState`, per-action `ActionState`, conversation timeline append/split rules).
- Indexed both in `doc/main/state_machines/README.md`. Cross-linked from `doc/main/ui/capsule/state_machine.md` (visibility decision machine) and `doc/main/ui/tech_design.md` (ChatEventReducer section).
- New `app/src/test/kotlin/ai/closepaw/ui/overlay/CapsuleApprovalTransitionTest.kt` — 28 tests filling gaps in `CapsuleStateHolderTest`: approval flow, takeover-from-Running shortcut, stop-request guards across all source modes, ERROR-from-active-mode terminal mapping, all `TaskOutcome` variants.
- New `app/src/test/kotlin/ai/closepaw/ui/chat/ChatSupplementAndActionTransitionTest.kt` — 8 tests covering supplement-as-user-turn, executed-without-proposal (with seeded buffer), FAILED/SKIPPED outcomes, error-without-open-agent, TurnStarted buffer reset, action-splits-text.
- Extended `app/src/test/kotlin/ai/closepaw/ui/overlay/model/CapsuleRenderSpecTest.kt` (+2 tests) — `clearInput=true` on entering `WaitingForInput` from a different mode; `clearInput=false` re-entering from itself.

**Why:**
- Track C is the safety-net before Track B's UI architecture refactor (semantic naming + capsule componentization). Without behavioral tests + a doc that mirrors current code, the refactor could silently regress UX. Two iterations of dual-reviewer (code-reviewer subagent + Codex gpt-5.4) feedback ensured docs match Kotlin source — no invented states, no aspirational design.

**Key files:** `doc/main/state_machines/ui_capsule.md`, `doc/main/state_machines/ui_chat.md`, `doc/main/state_machines/README.md`, `doc/main/ui/capsule/state_machine.md`, `doc/main/ui/tech_design.md`, `app/src/test/kotlin/ai/closepaw/ui/overlay/CapsuleApprovalTransitionTest.kt`, `app/src/test/kotlin/ai/closepaw/ui/chat/ChatSupplementAndActionTransitionTest.kt`, `app/src/test/kotlin/ai/closepaw/ui/overlay/model/CapsuleRenderSpecTest.kt`
**Verification:** `./gradlew test` — 1151 tests, 0 failures. Two independent review rounds (Claude code-reviewer + Codex) both APPROVE.
**Next:** Track B (UI architecture refactor + semantic naming) is now unblocked.
**Blockers:** None.

## 2026-04-20: chat — decouple ChatScreen from AgentService singleton + drop dead getters

**What changed:**
- New `ui/capsule/CapsuleBinding.kt` — value type wrapping the three StateFlows (`mode`, `platformMode`, `isStopPending`) and two callbacks (`onStopRequested`, `onApprovalResolved`) the chat surface needs from `CapsuleStateHolder`. `InertCapsuleBinding` is the unbound-runtime fallback.
- `ChatScreen` now takes a `CapsuleBinding` parameter instead of reaching into `AgentService.instance?.capsuleStateHolder` and threading three remembered fallback flows through itself; no more `AgentService` import in the chat package's renderer.
- `MainActivityContent` builds the live binding via a private `rememberCapsuleBinding()` helper that reads the singleton (the activity is the right place for that lookup).
- Deleted the unused `ChatMessage.Agent.content` and `Agent.actions` "backward-compatibility" convenience getters. The one real consumer (`ChatScreen.scrollKey` text-length signal) inlined into a single `when (block)`.

**Why:**
- Track B follow-up. UI-layer code shouldn't know about `AgentService.instance` — the singleton dep made `ChatScreen` un-previewable, hard to test, and tied to the runtime's bind state. The fix isolates that coupling at the activity boundary.
- The dead getters were flagged in the Track B audit as "backward compatibility" with no compatibility consumers; removing them simplifies `ChatMessage` and forces callers to handle `ContentBlock` variants explicitly.

**Key files:** `app/src/main/kotlin/ai/closepaw/ui/capsule/CapsuleBinding.kt`, `app/src/main/kotlin/ai/closepaw/ui/chat/ChatScreen.kt`, `app/src/main/kotlin/ai/closepaw/ui/chat/model/ChatMessage.kt`, `app/src/main/kotlin/ai/closepaw/app/MainActivityContent.kt`, `doc/main/ui/capsule/architecture.md`, `doc/main/ui/{overlay,tech_design}.md`, `doc/main/README.md`.
**Verification:** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` PASS. `grep -rn "AgentService" app/src/main/kotlin/ai/closepaw/ui/chat/` shows only `ChatViewModel.kt` (out of scope — separate `dismissError()` call).
**Commit:** e0cf9c61
**Next:** Track A implement (add `ContentBlock.Thought` reducer branch + renderer arm).
**Blockers:** None.

## 2026-04-20: capsule UI — semantic naming + slim orchestrator (Track B)

**What changed:**
- Renamed all positional capsule names. Spec: `row3 → input`, `Row3Spec → InputSpec`, `buttonText → submitLabel`, `clearInput → clearDraft`, local `row2Hidden → controlBarHidden`. Composables: `CapsuleRow1/2/3 → CapsuleStatusLine / CapsuleControlBar / CapsuleInputBar`. Public param `onRow1Click → onStatusClick`.
- File reorg: `SmartCapsuleSurfaceParts.kt → CapsuleControlBar.kt` (also factored into private `ActionButtonCluster` + `NavButtonCluster`); new `CapsuleInputBar.kt` owns the draft state + `pendingInputText` / `clearDraft` / `inputEnabled` lifecycle; `SmartCapsuleCompose.kt` pass-through wrapper deleted (`ChatScreen` calls `SmartCapsuleSurface` directly); `NavAction` enum hoisted into its own file.
- Synced affected SOTA docs and the `ux-visual-debug` skill to the new vocabulary.

**Why:**
- `Row1/Row2/Row3` named where things sat, not what they did. Item 2 of `doc/todo/frontend-ui-review/eng-design/note.md` explicitly called this out, and `SmartCapsuleSurface` had grown into a god-composable that owned layout + input draft + submit routing + startup-error placement at once. Track A's chronological-trace chat row also benefits from a stable, semantically-named capsule surface as the bottomBar.
- Path was decided via `/double-design` (Claude + Codex independent designs → cross-review → aligned spec). KISS line: one decomposition that earned its keep, no `ChatTurnRenderSpec`, no MVI wrapper, no theming churn. `ContentBlock.Thought` deferred to Track A's implement phase.

**Key files:** `app/src/main/kotlin/ai/closepaw/ui/capsule/**`, `app/src/main/kotlin/ai/closepaw/ui/overlay/model/CapsuleRenderSpec.kt`, `app/src/main/kotlin/ai/closepaw/ui/overlay/compose/CapsuleOverlayHost.kt`, `app/src/main/kotlin/ai/closepaw/ui/chat/ChatScreen.kt`, `doc/main/state_machines/ui_capsule.md`, `doc/main/ui/capsule/{architecture,user_flows,state_machine}.md`, `doc/main/ui/{overlay,user_interaction,tech_design}.md`, `doc/main/README.md`, `.claude/skills/ux-visual-debug/{SKILL.md,references/ux_checks.md}`, `doc/todo/frontend-ui-review/eng-design/track-b/`.
**Verification:** `./gradlew :app:compileDebugKotlin` + `:app:testDebugUnitTest` green (all Track C state-machine tests preserved). `grep -rn "row[123]\|Row[123]" app/src/main/kotlin/ai/closepaw/ui/` returns 0 hits.
**Commit:** 2d51d580 (refactor + Track B design docs + state-machine doc); follow-up commit pending for SOTA-doc + skill sync.
**Next:** Track A implement (add `ContentBlock.Thought` reducer branch + `MessageBubble.AgentBubble` thought rendering on the cleaned chat surface).
**Blockers:** None.

## 2026-04-20: security — enrich app_tiers.json with installed apps

**What changed:**
- `assets/security/app_tiers.json` grew from 60 → 114 entries: +39 NORMAL (Google suite — Gmail/Maps/Messages/Docs/Chat/Gemini/NotebookLM/YT Music/YouTube; AI — ChatGPT/Claude/Liquid/Arm AI/Doubao/AIRecord; social — WhatsApp/Telegram/FB/Messenger/IG/X/TikTok Lite/小红书; shopping — Amazon/Walmart/AliExpress/Temu/Shein/McDonald's; rideshare — Uber/Lyft/DoorDash; all `com.obric.*` system apps + butterfly), +1 BLOCKED (`com.tencent.mm` — WeChat has integrated wallet/payments), +4 CAUTIOUS (`moe.shizuku.privileged.api`, `com.tailscale.ipn`, `com.aurora.store`, `com.apkmirror.helper.prod` — privileged/sideload tools).
- Final tier distribution: 74 NORMAL / 36 BLOCKED / 4 CAUTIOUS.

**Why:**
- Common apps like Gmail/Maps were unlisted and defaulted to `CAUTIOUS`, forcing approval prompts (and timeouts) in SMART mode for routine use. The base tier list shipped with only seed entries; this is the first pass to align it with what's actually installed on the test device.
- Architecture/semantics unchanged — pure data update. `AppClassifier` and `PolicyEngine` behavior is identical; only the lookup table is fuller.

**Key files:** `app/src/main/assets/security/app_tiers.json`.
**Verification:** `python3 -m json.tool` valid; `:app:testDebugUnitTest --tests AppClassifierSecurityTest --tests PolicyEngineTest` PASS; live device QA via `debug-run.sh "Open Google Maps and search for coffee shops nearby"` — 3 turns, `outcome=GOAL_ACHIEVED`, `ToolRouter: Policy decision for open_app/mobile_action/complete_task: Allow`, no approval prompts.
**Commit:** d3f9128f
**Next:** None — extend list as new common apps appear.
**Blockers:** None.

## 2026-04-19: settings — Display Mode section, effective-platform-mode signal, Shizuku status

**What changed:**
- New **Display Mode** section in `PermissionsAdvancedSettingsPage` (between Permissions and Debug): 2-option selector (Accessibility / Virtual Display) + reactive Shizuku status row. VD option disabled iff Shizuku status != Ready; Grant button calls `ShizukuClient.requestPermission`, Learn-more opens shizuku.rikka.app.
- `AgentService.effectivePlatformMode: StateFlow<PlatformMode?>` published from the actually-constructed `AndroidPlatform.mode` (not persisted intent). Collected by `MainActivityContent` and threaded into Settings + Home subtitle (chip ` · A11y` / ` · VD` shown only when non-null).
- `ShizukuClient.addRequestPermissionResultListener` / `remove…` wrappers (proxied via `ShizukuRuntimeGateway`) so the Compose layer never imports `Shizuku.*`. `rememberShizukuStatus` uses `produceState` + ON_RESUME + permission-result + binder-dead listeners — no polling.
- `AndroidPlatform.mode` promoted to abstract member; `AccessibilityPlatform`, `VirtualDisplayPlatform`, and test fakes override.
- New instrumented `DisplayModeSettingsTest` (3 tests).

**Why:**
- `PlatformMode` was already persisted and wired through `PlatformFactory`, but only changeable via debug intent — end users had no way to switch, and no signal for why VD might be unavailable. Coupling Shizuku status to the VD option turns "why is this disabled?" into one canonical answer.
- Persisted vs. effective split tells the truth in two places: selector reflects intent for next session; subtitle chip reflects what the live session actually built. Binder-death mid-session does not retro-snap the selector.
- App-tier global override toggle explicitly rejected: security policy that the user can disable isn't policy.

**Key files:** `app/src/main/kotlin/ai/closepaw/ui/settings/{DisplayModeSection,RememberShizukuStatus,ShizukuStatus,PermissionsAdvancedSettingsPage,SettingsHomePage,SettingsSheet}.kt`, `app/src/main/kotlin/ai/closepaw/app/{AgentService,MainActivity,MainActivityContent}.kt`, `app/src/main/kotlin/ai/closepaw/platform/{AndroidPlatform,AccessibilityPlatform}.kt`, `app/src/main/kotlin/ai/closepaw/platform/virtualdisplay/{ShizukuClient,ShizukuRuntimeGateway,VirtualDisplayPlatform}.kt`, `app/src/androidTest/kotlin/ai/closepaw/qa/{DisplayModeSettingsTest,QaSettingsHelpers}.kt`.
**Verification:** `./gradlew :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:lintDebug` green. `connectedDebugAndroidTest` `DisplayModeSettingsTest` 3/3 PASS. Real-device QA matrix on EP0110MZ0BC101266W (Shizuku Ready): selector toggles A11y↔VD both directions and persists; intent-path `--es platform_mode VIRTUAL_DISPLAY` still applies; Home subtitle chip appears after first session — all PASS.
**Commit:** 44b6cb83
**Next:** None.
**Blockers:** None.

## 2026-04-18: settings — Cloud Model dropdown placeholder when cross-provider

**What changed:**
- `ui/settings/SettingsDropdowns.kt` `CloudModelDropdown`: when the global `selectedModel` isn't in the current provider's `modelOptions`, render the placeholder `"Select a model"` instead of falling back to the raw model id.

**Why:**
- The API Key tab's Provider segmented selector is local view state; the global `selectedModel` doesn't change on provider toggle. Previously, an OAuth user with `gpt-5.4-codex` who tapped OpenRouter or Novita on the API Key tab saw `"gpt-5.4-codex"` in the Cloud Model dropdown — a model that doesn't belong to those providers — because the dropdown's text fallback was the raw id.
- Placeholder is the safest fix: no auto-write to settings on a tab/segment click (would surprise users), and the dropdown clearly invites a fresh selection for the segmented provider.

**Key files:** `app/src/main/kotlin/ai/closepaw/ui/settings/SettingsDropdowns.kt`.
**Verification:** `./gradlew assembleDebug` green. UX QA on EP0110MZ0BC101266W (OAuth signed-in, global=gpt-5.4-codex): API Key tab → OpenAI/OpenRouter/Novita segments all show "Select a model"; picking GLM-5 in OpenRouter then displays "GLM-5" correctly. Sign In tab still shows "GPT-5.4 (ChatGPT sign-in)" since that provider owns the global model.
**Commit:** 60a74d59
**Next:** None.
**Blockers:** None.

## 2026-04-18: onboarding UX — auto-return from browser via custom scheme

**What changed:**
- `app/src/main/AndroidManifest.xml`: new VIEW intent-filter on MainActivity for `closepaw://oauth-complete` (BROWSABLE / DEFAULT categories).
- `auth/OpenAIOAuth.kt` `successHtml()`: page now contains `<meta http-equiv="refresh" content="0;url=closepaw://oauth-complete">` plus a 50ms JS fallback `setTimeout(...location.href=...)` plus a "tap here" link as a manual fallback. Once the localhost callback server has captured `code`/`state` and rendered the success page, the browser navigates to the deep-link, Android matches the intent-filter, ClosePaw is brought to front automatically.

**Why:**
- Manual swipe-back from Chrome to ClosePaw after Sign-in complete was annoying. Looked at four options: custom scheme (chosen), `intent://` URL (Chrome-only), JVM-side `FLAG_ACTIVITY_REORDER_TO_FRONT` from the callback server (blocked by Android 12+ background-activity-launch restrictions when our task isn't visible), and AndroidX Custom Tabs (cleanest UX but requires new dependency + reroutes auth.openai.com through CCT — much larger change).
- Custom scheme is browser-agnostic, fires from foreground (no BAL issues), and adds one intent-filter. Deep-link carries no extras → `MainActivityIntentPayload.from(intent)` produces all-null payload → `applyIntentPayloadToSettings` no-ops, so existing intent handling for `goal`/`agent_mode`/`openai_base_url`/etc. is untouched.

**Key files:** `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/ai/closepaw/auth/OpenAIOAuth.kt`.
**Verification:** `./gradlew assembleDebug` green. EP0110MZ0BC101266W: simulated via `adb shell am start -a VIEW -d "closepaw://oauth-complete"` from Home → MainActivity brought to front cleanly. End-to-end Settings → sign out → sign in confirmed by user: auto-return works, "Finishing up with OpenAI" visible for ~15-20s during server-side token exchange (expected).
**Commit:** da4252e5
**Next:** None.
**Blockers:** None.

## 2026-04-18: onboarding UX — OAuth latency cut, recap reads live permissions, "Finishing up" copy, ClosePaw rename

**What changed:**
- `auth/OpenAiSignIn.kt`: dropped `OAuthCodexValidator.validate()` after token exchange. That step issued a real chat-completion to `chatgpt.com/backend-api/codex/responses` and blocked until SSE headers arrived (5-15s + Android background-socket throttling). Run Demo on step 5 already exercises the same path, so the separate validation was redundant. Post-Chrome wait dropped from ~30s to ~10s on EP0110MZ0BC101266W.
- `auth/OpenAiSignIn.kt`: added optional `onCallbackReceived: () -> Unit = {}` arg fired right after `waitForCallback()` returns Success and before `OAuthTokenExchange.exchange()` runs. New `ApiKeyStepState.OAuthFinishing` and `OpenAiAuthUiState.Finishing` rendered with copy "Browser sign-in complete. Finishing up with OpenAI — this can take ~20 seconds." Removes the ambiguous "Complete sign-in in your browser" spinner state during the ~20s server-side token-exchange wait at `auth.openai.com/oauth/token`.
- `ui/onboarding/OnboardingSteps.kt` recap (`CompleteStepContent`): three permission rows (accessibility/overlay/battery) now render via new `LiveStatusRow` reading `vm.isAccessibilityEnabled() / isOverlayEnabled() / isBatteryOptimized()` from `PermissionStateMonitor`. Previous code read `outcomes.accessibility/overlay/battery` from `OnboardingStore` (cached). Auto-skipped permission steps never wrote `StepOutcome.Done`, so the recap rendered red ✗ for actually-granted permissions when launched via setup.sh. Same anti-pattern as the `authMethod` side-channel the auth-setting-cleanup milestone removed.
- 7 user-visible strings renamed "Android Agent" → "ClosePaw": OAuth Sign-in complete HTML page, onboarding Complete CTA, Accessibility + Overlay permission copy, onboarding screen title, ChatHeader, EmptyState, CapsuleContext doc comment.

**Why:**
- The OAuth latency investigation (logcat on device) attributed ~20s to `OpenAIOAuth.kt:301 postTokenRequest` (the auth_code → tokens POST itself, server-side at auth.openai.com). `exchangeForApiKey` is fast (~300ms, 401s, falls back to access_token — expected for Codex tokens). The 5-15s LLM validation was extra cost on top of that and the easiest to drop. The remaining ~20s is server-side and not worth chasing without changing the OAuth mechanism; instead the UX copy fix tells the user what's happening.
- The recap bug shipped with the auth-setting-cleanup milestone but only surfaced once a tester used setup.sh (which auto-grants permissions before launch). User caught it on the first fresh-install walkthrough after the milestone closed.

**Key files:** `app/src/main/kotlin/ai/closepaw/auth/{OpenAiSignIn, OpenAIOAuth}.kt`, `app/src/main/kotlin/ai/closepaw/onboarding/{OnboardingViewModel, OnboardingState}.kt`, `app/src/main/kotlin/ai/closepaw/ui/onboarding/{OnboardingScreen, OnboardingSteps}.kt`, `app/src/main/kotlin/ai/closepaw/ui/settings/OpenAiAuthCard.kt`, `app/src/main/kotlin/ai/closepaw/ui/chat/components/{ChatHeader, EmptyState}.kt`, `app/src/main/kotlin/ai/closepaw/app/MainActivity.kt`.

**Verification:** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug` green. Device EP0110MZ0BC101266W: fresh-install walkthrough — OAuth post-Chrome wait observed ~10s (down from ~30s), recap shows ✓ for granted permissions, "Finishing up" copy renders correctly during the post-callback exchange window.
**Commit:** 556aae84, a40130ad
**Next:** Investigate auto-return from Chrome to ClosePaw after OAuth (currently requires manual app switch).
**Blockers:** None.

## 2026-04-18: holistic-review followups — demo baseUrl, AuthStore off-main, doc enum

**What changed:**
- HIGH bug fix: `OnboardingDemoController` now resolves `baseUrlOverrides` from `AppSettingsState.openaiBaseUrl` and passes them into the demo `AgentSession`, matching the `MainActivity.kt:527` pattern. Previously demo sessions ignored the debug intent override and hit `api.openai.com`. New JVM test in `DemoStepStateTest`.
- MEDIUM regression fix: `AuthStore.set` is no longer called from the main thread. Three call sites moved to `Dispatchers.IO`: `LlmAuthSettingsPage:215` (per-keystroke API-key writes), `OnboardingViewModel:104+219`, `MainActivity:751`.
- HIGH catch on the round-2 fix: per-keystroke off-main writes were not serialized — concurrent IO dispatches could persist out-of-order, last-typed key not always winning. Round-3 fix in `LlmAuthSettingsPage`: 250 ms debounce per provider + `Mutex.withLock` around the IO block. New 109-line JVM test `LlmAuthApiKeyPersistTest` asserts last-write-wins under interleaved keystrokes.
- LOW doc fix: corrected `SessionEndReason` enumeration in `doc/main/state_machines/session_state.md` and `doc/main/ui/session/state_machine.md`. `Created → Shutdown` emits `USER_STOPPED` (not `INTERRUPTED`); `INTERRUPTED` is documented as the reacquire-fail terminal source.

**Why:**
- Filed by codex holistic review after the harness-simplify milestone landed. Two were latent bugs (demo override silently dropped; main-thread encrypted-prefs write); one was doc drift surfaced by the new state-machine docs themselves.
- Round-2 fix exposed a subtler concurrency hole: `Dispatchers.IO` parallelism + per-keystroke writes ≠ ordered persistence. Debounce alone collapses keystroke storms; mutex guarantees serialization across whatever writes still survive the debounce.

**Key files:** `app/src/main/kotlin/ai/closepaw/app/MainActivity.kt`, `app/src/main/kotlin/ai/closepaw/onboarding/{OnboardingDemoController,OnboardingViewModel}.kt`, `app/src/main/kotlin/ai/closepaw/ui/settings/LlmAuthSettingsPage.kt`, `app/src/test/kotlin/ai/closepaw/ui/settings/LlmAuthApiKeyPersistTest.kt`, `doc/main/state_machines/session_state.md`, `doc/main/ui/session/state_machine.md`.
**Verification:** `./gradlew :app:testDebugUnitTest` green after each merge (3 separate fix-loop iterations for the AuthStore item).
**Commits:** d5132e2f..HEAD (~11 commits incl. 3 fix merges, 1 doc merge, task-tracking chores).
**Next:** None outstanding from this review.
**Blockers:** None.

## 2026-04-18: harness-simplify milestone — formalize all FSMs, characterize, simplify

**What changed:**
- New `doc/main/state_machines/` (11 FSM docs + index): authoritative reference for SessionState, SessionCoordinator, Agent.run loop, ToolCall, CloudStreamRetryPolicy + StreamRetryRunner, LFMLLMClient ModelLoadingState, OnboardingWizard + 3 step states, plus a KISS-rationale doc explaining why the 3 step-state hierarchies are kept (5 cast sites < 23-case flat sealed in every renderer).
- New `doc/main/error_handling.md` and `doc/main/data_schemas.md`: catalog silent-failure sites with file:line refs and core-schema redundancy findings.
- 12 characterization test classes added under `app/src/test/`, exhaustively covering every transition + guard rejection in each FSM. Each survived 1–4 codex review rounds; recurring high-severity catches: skipped pause tests via `Assume.assumeTrue`, vacuous FIFO assertions, `@Ignore`'d cancellation paths, missing listener wiring/unwiring tests, scheduler-sensitive sync points.
- 5 production simplifications merged:
  - `simp-enum-logging` — `Log.w` on silent enum-parse fallbacks in `AgentSession.reload` + `SessionCheckpointCoordinator.toSessionConfig`.
  - `simp-app-settings-local-model-trio` — collapse `selectedLocalModelId/localModelSlug/localModelQuant` → non-null `LocalModelOption` (net −21 LOC; eliminates `?.let / ?: ""` shims).
  - `simp-app-settings-loading-status` — extract `ModelLoadingStatusHolder`; reset is centralized so all backend-change paths (UI lambda + `applyIntentPayloadToSettings`) clear stale status uniformly. (Round-1 caught HIGH stale-status regression on intent-driven backend switch; fixed by routing intent applier through holder.)
  - `simp-services-cleanup-result` — `SessionServices.cleanup()` returns `CleanupResult` sealed type. (Round-1 caught HIGH user-visible regression: partial failures emitted `SessionError` → user-visible `⚠️ Cleanup partial failure...`. Reverted to log-only; `CleanupResult` is now an internal contract for tests.)
  - `simp-idle-recover-failure` — bounded `reacquirePlatform()` retries: after `MAX_REACQUIRE_FAILURES = 3`, transition `Idle → Shutdown(SessionEndReason.INTERRUPTED)`. Counter resets on success. Bug fix; previously stuck Idle forever.
- 1 production simplification cancelled: `simp-checkpoint-result-type` — codex flagged KISS violation (synthetic `IOException` at `flushIdleReady()` adds API surface without real failure-info, since `forceCheckpoint()` still returns `Boolean`). Filed as note for potential follow-up.
- `simp-onboarding-state-flatten` — KEEP decision documented in new `doc/main/state_machines/onboarding_step_states.md`.
- Doc-code alignment pass closed major drift in `doc/main/ui/session/state_machine.md`, `infra/session.md`, `ui/capsule/state_machine.md`, `ui/capsule/user_flows.md`, `ui/session/user_flows.md` (Hot Idle wording, `WaitingForApproval` in force-capsule, `schemaVersion == 2`, reacquire-failure transitions). 2 minor MEDIUM doc-precision gaps remain in `protocol/overview.md:77` and `ui/capsule/user_flows.md:100` — filed as follow-up.
- `scripts/debug-run.sh` — default `MAIN_MODEL` is now backend-aware (`gpt-5.4` for `openai`, `minimax-m2.5` otherwise); `.env`-sourced `OPENAI_API_KEY` + `OPENAI_BASE_URL` already propagated.
- `doc/main/README.md` doc map updated to link new `state_machines/` folder + `error_handling.md` + `data_schemas.md`.

**Why:**
- KISS-direction: production code net-flat-or-smaller while doubling the executable spec coverage. Codex review on every diff was the load-bearing safety net — caught two HIGH user-visible regressions before merge and forced the `checkpoint-result-type` cancellation that would otherwise have added API surface for no real benefit.
- Tests-first sequencing: every Phase-D simplification was gated on the matching characterization test landing first, so behavior-preservation was provable rather than asserted.
- Bounded `reacquirePlatform` retries fix a real stuck-Idle bug surfaced by reading the FSM doc against the code.

**Key files:** `doc/main/state_machines/*` (12 new), `doc/main/error_handling.md`, `doc/main/data_schemas.md`, `doc/main/README.md` (doc map update); `app/src/main/kotlin/ai/closepaw/session/{AgentSession,SessionServices,SessionCheckpointCoordinator}.kt`; `app/src/main/kotlin/ai/closepaw/app/{AppSettingsState,AppSettingsStore,ModelLoadingStatusHolder,MainActivity,MainActivityContent,MainActivityIntentApplier}.kt`; `app/src/test/kotlin/ai/closepaw/{session,llm,onboarding,tool,agent}/*Test.kt` (12 new/augmented); `scripts/debug-run.sh`.
**Verification:** `./gradlew clean assembleDebug lint test` green; device smoke test (`./scripts/debug-run.sh "Open Settings"`) — session lifecycle clean (`Created → Running → Idle → Shutdown(USER_STOPPED) → SessionCompleted`), new `CleanupResult` returns `failures=0`, no user-visible cleanup error event (confirms HIGH regression fix held).
**Commits:** 96024d91..HEAD (~52 commits incl. 12 test merges + 5 simp merges + 1 doc-align merge + 11 fix follow-ups).
**Next:** Optional follow-ups noted in `tasks.json`: 2 MEDIUM doc-precision gaps; potential push of `Result<Unit>` down into `SessionRecordingService.forceCheckpoint()` if richer failure info ever wanted.
**Blockers:** None.

## 2026-04-18: Characterization tests — LFMLLMClient model loading FSM

**What changed:**
- New JVM unit test `ai.closepaw.llm.LFMLLMClientTest` (11 tests) characterizing every transition and guard in `doc/main/state_machines/local_model_loading.md`: NotLoaded→Downloading→Loading→Ready, Downloading/Loading→Error (rethrow), Error→Downloading retry, Ready→NotLoaded via `cleanup()`, idempotency guard when `modelRunner != null`, `"Unknown error"` fallback, strict `onProgress` ordering.
- Uses `mockkConstructor(LeapDownloader::class)` to capture the progress lambda and drive `ProgressData(bytes, total)` events; `ModelRunner` is a relaxed mock; `Context.filesDir` returns a tmp dir.

**Why:**
- Locks in the FSM doc as executable spec so future refactors of `LFMLLMClient.loadModelLocked` cannot silently regress state ordering, the `progress >= 1f` Loading boundary, the post-error retry path, or the `modelRunner != null` early-return guard.

**Key files:** `app/src/test/kotlin/ai/closepaw/llm/LFMLLMClientTest.kt`.
**Verification:** `./gradlew :app:testDebugUnitTest --tests 'ai.closepaw.llm.LFMLLMClientTest'` (11/11 green); full `:app:testDebugUnitTest` suite green. No production code changed (`git diff` shows only the new test file).
**Commit:** c22de8b2.
**Next:** None.
**Blockers:** None.

## 2026-04-18: auth-setting-cleanup milestone — single AuthStore, flat LLMProvider

**What changed:**
- New `auth/AuthStore` (`EncryptedSharedPreferences`-backed, app-scoped via `AuthStoreHolder`) is the single source of truth for all cloud credentials, keyed by flat `LLMProvider`. Sealed `AuthCredential` (`ApiKey | OAuth`); typed errors `MissingCredential` / `OAuthRefreshFailed` / `WrongCredentialType`. Mutex-guarded OAuth refresh near 5-min expiry with abort-protection if the credential changes mid-refresh.
- Flat `LLMProvider` enum: `OPENAI_API`, `OPENAI_CODEX`, `OPENROUTER`, `NOVITA`, `LOCAL_LFM`, each carrying a `mode: AuthMode` accessor for UI grouping. New catalog entries `gpt-5.4-codex` / `gpt-5.2-codex` under `OPENAI_CODEX`.
- `LLMClientFactory` rewritten: routes purely on `entry.provider`, atomic `compute()` cache keyed by `(modelName → Entry(generation, client))`, generation-bump invalidation on any `AuthStore.set/clear`.
- `CodexResponseClient` no longer captures OAuth state. Constructor takes `suspend () -> CodexHeaders` supplier; every request reads fresh `accessToken` + `chatgptAccountId` + `email`. Account switches and token rotations work without invalidating the cached client.
- Runtime wiring: `MainActivity`, `AgentSession.create/reload`, `SessionServices`, `SessionLlmBootstrapper`, intent applier, banner deep-link all share the same `AuthStore` instance. `MainActivityIntentApplier` writes credentials off-main via `Dispatchers.IO` inside `lifecycleScope.launch`. `SessionRuntimeSnapshot.schemaVersion` bumped 1→2; `AgentSession.reload` rejects v1 with user-visible "Session from previous version" message. `SessionCoordinator.createAndSubmit` returns sealed `CreateResult { Success, LockBusy, Aborted }`; `Aborted` clears `pendingInputs` so a v1-rejected goal does not auto-run in the next fresh session.
- Settings UI canonicalization: tab switch is view-only; `selectedProviderForTab` derives from `selectedModel.provider` when its mode matches the tab, otherwise the tab's default provider. Provider sub-selector clicks no longer commit a model. `executorModel` reset/remapped on commit when its provider no longer matches main. `SettingsSheet` accepts `initialPage` / `initialAuthTab` for banner deep-links.
- Onboarding: `OnboardingProvider` aligned with flat `LLMProvider`; `OnboardingStore` schema v2 deletes legacy `auth_method` + encrypted `api_key_draft` keys; step-resume derives state from `AuthStore.has(provider)`; demo errors surface as inline card with required `onGoToAuthStep` recovery action.
- Settings state shrink: deleted `OAuthCredentialStore.kt`, `AppSettingsState.{authMethod, openAiOAuthAccessToken, openAiManualApiKey, apiKey, openRouterApiKey, novitaApiKey, buildApiKeys}`, plus three obsolete tests. Zero migration code (pre-release; design Section 8 — empty AuthStore on upgrade → banner → re-auth).
- Docs: `doc/main/infra/llm.md` updated for flat enum + factory + Codex header supplier; `doc/main/app/settings.md` rewritten for credential-elsewhere model. New `doc/todo/auth-setting-cleanup/implementation_summary.md`.

**Why:**
- Original bug: onboarding Codex OAuth → "Run Demo" failed with `openai_api_key not found for model gpt-5.4`. Root cause was three concepts (auth mode, provider, credential) tangled across `AppSettingsState` (fallback chains), `OnboardingStore` (duplicate `auth_method` + encrypted draft), and `LLMClientFactory` (`__AUTH_METHOD_OPENAI` magic key, `isOAuth` sniff). The selected model was supposed to determine which client runs, but two different "OpenAI" usages (API-key vs OAuth) collided on a single enum value.
- Fix isolates auth mode at the type layer: `OPENAI_API` and `OPENAI_CODEX` are distinct flat-enum entries with separate catalog entries. The selected model now determines provider, which deterministically determines credential source and client class.

**Key files:** `app/src/main/kotlin/ai/closepaw/auth/{AuthStore, AuthCredential, AuthErrors}.kt`, `app/src/main/kotlin/ai/closepaw/llm/{LLMProvider, LLMClientFactory, CodexResponseClient, ModelCatalog}.kt`, `app/src/main/assets/llm_models.json`, `app/src/main/kotlin/ai/closepaw/app/{MainActivity, MainActivityContent, MainActivityIntentApplier, MainActivityModelValidation, AuthStoreHolder}.kt`, `app/src/main/kotlin/ai/closepaw/session/{AgentSession, SessionCoordinator, SessionServices, SessionLlmBootstrapper, SessionCheckpointCoordinator}.kt`, `app/src/main/kotlin/ai/closepaw/ui/{settings/LlmAuthSettingsPage, settings/SettingsSheet, chat/ChatViewModel, chat/SettingsDeepLink, capsule/surface/SmartCapsuleSurface}.kt`, `app/src/main/kotlin/ai/closepaw/onboarding/{OnboardingViewModel, OnboardingState, OnboardingStore, OnboardingDemoController, OnboardingViewModelFactory}.kt`.

**Verification:** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin` all green (888 unit tests pass, including 14 new AuthStore tests with concurrent-clear-during-refresh + 50-way concurrent-set generation count + factory atomic-cache torture). Device QA on EP0110MZ0BC101266W: S2/S3/S4/S5/S8 verified after F1/F2 fixes (see `doc/todo/auth-setting-cleanup/qa_report.md`); S1 (OAuth), S6/S7 (prior-build upgrade) skipped per scope.

**Commit:** 894d10f3..900b606f (8 milestone commits + 2 inline F1/F2 fixes at 97d5362c, be68d8c6).
**Next:** Re-run S1 (OAuth) + S6/S7 (upgrade-from-prior-build) when a baseline APK is available; archive milestone.
**Blockers:** None.

## 2026-04-18: auth-setting-cleanup — F1/F2 device-QA defects fixed

**What changed:**
- F1: `OnboardingViewModel.resolveBaseUrl(entry)` now mirrors `LLMClientFactory.build()` — for `OPENAI_API` entries, `AppSettingsState.openaiBaseUrl` (debug-only intent override) wins over `entry.effectiveBaseUrl`. JVM test added in `OnboardingViewModelTest`.
- F2: `MainActivity.pendingSettingsDeepLink` state + `validateCloudKeysForSelectedModels()` now populate a `SettingsDeepLink(LLM_AUTH, missing.first().provider.mode)` before flipping `showSettings`. `MainActivityContent` accepts `initialSettingsDeepLink` and seeds its internal `pendingDeepLink` from it. Banner-tap path was already wired; gap was the pre-flight auto-open.
- Removed stale TODO in `SettingsDeepLink.kt`.
- `doc/main/app/settings.md`: documented Settings Deep-Link two-path convergence + onboarding base URL resolution rule.

**Why:**
- F1 blocked debug-build onboarding via OpenAI: validator hit `api.openai.com` with `gpt-5.4` mock IDs → HTTP 400 → "Provider configuration issue". Fix unblocks the proxy path the rest of the runtime already used.
- F2: pre-flight credential check was a separate code path from the banner-tap path; only the latter was deep-link-aware. Result: missing-credential auto-open landed on Settings home, requiring an extra navigation step.

**Key files:** `app/src/main/kotlin/ai/closepaw/onboarding/OnboardingViewModel.kt`, `app/src/main/kotlin/ai/closepaw/app/MainActivity.kt`, `app/src/main/kotlin/ai/closepaw/app/MainActivityContent.kt`, `app/src/main/kotlin/ai/closepaw/ui/chat/SettingsDeepLink.kt`, `doc/todo/auth-setting-cleanup/qa_report.md`, `doc/main/app/settings.md`
**Verification:** `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug` green. Device EP0110MZ0BC101266W: S2 → HTTP 200 advances to step 5; S8 → sheet auto-opens on LLM Auth / API Key / OpenAI tab; end-to-end `gpt-5.4` proxy run completed in 2 turns (open_app → complete_task).
**Commit:** 97d5362c
**Next:** Re-run S1 (OAuth), S6/S7 (prior-build upgrade) when a baseline APK is available.
**Blockers:** None.

## 2026-04-17: qa_test — Compose UI behavior-guard layer bootstrapped (45 tests, 3 batches)

**What changed:**
- New instrumented test layer at `app/src/androidTest/kotlin/ai/closepaw/qa/`. AndroidJUnitRunner + Compose UI Test, `animationsDisabled=true`. 45 tests across:
  - Batch 1 Chat (17): Sanity, EmptyState, Header, BubbleAlignment, ThinkingState, StreamingCursor, ActionCard state icons, ActionCard expand.
  - Batch 2 SmartCapsule (15): Rendering (Hidden/Running/Takeover), Input (WaitingForInput field/send), Approval (WaitingForAction/WaitingForApproval), Lifecycle (Done auto-dismiss via real `CapsuleStateHolder.scheduleAutoHide`, Error, Stop-pending, Navigation).
  - Batch 3 Settings (13): Sheet nav + StateRestorationTester rotation, LLM Auth tab/OAuth/provider/model-canonicalization, AgentBehavior Pro/Basic, Permissions traces banner + Clear-Traces dialog.
- Minimal production touchpoints (5 testTag additions, all justified):
  - `ui/chat/components/ThinkingIndicator.kt` — `qa-thinking-indicator`
  - `ui/capsule/surface/SmartCapsuleSurfaceParts.kt` — `qa-capsule-input`
  - `ui/settings/PermissionsAdvancedSettingsPage.kt` — clear-traces dialog anchor
  - `ui/settings/LlmAuthSettingsPage.kt` — `qa-executor-model-dropdown` wrapper Box (Sign-In + API-Key Pro branches)
  - `ui/chat/components/MessageBubble.kt` — user/agent bubble container tags
- `app/build.gradle.kts`: `testInstrumentationRunner = AndroidJUnitRunner`, `testOptions.animationsDisabled = true`, `androidTestImplementation` for Compose UI Test + uiautomator + ext:junit.
- Design framing reframed in `doc/todo/qa_test/final/cn/design_kiss.md`: from "bug-driven" → "behavior-guard". Bug reports are one trigger to add new guards, not the only legitimate one. bootstrap_plan.md unchanged.

**Why:**
- The existing test layers (`app/src/test/` JVM units, `eval/` AndroidWorld benchmarks) didn't cover Compose UI behavior. Manual UX QA was the only safety net for chat / capsule / settings regressions.
- bootstrap by behavior inventory (not by waiting for bugs) up-front guards the high-value flows; bug reports later add point guards as needed.
- KISS rules: flat layout, no Robot/base classes/annotations, `org.junit.Assert` for verdicts (Kotlin built-in `assert(...)` is a silent no-op without `-ea`), `testTag` only when text/contentDescription unavailable.

**Key files:** `app/src/androidTest/kotlin/ai/closepaw/qa/**`, `app/build.gradle.kts`, `doc/todo/qa_test/final/cn/{design_kiss,bootstrap_plan}.md`, the 5 production files above.

**Verification:** `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=ai.closepaw.qa` → 45 tests green on device EP0110MZ0BC101266W. 3 codex review rounds (1 per batch + 1 fix-recheck on Batch 3) caught and resolved K11 fake auto-dismiss, S9 false-positive via shared section title, S11 vacuous dialog assertion, plus several smaller assertion-hygiene issues.

**Commit:** `3c4f586d..96024d91`

**Next:** C12/C13 (chat scroll FAB) deferred — needs full ChatScreen + lazy scroll state. Settings helper (`QaSettingsHelpers.kt`, 145 lines) could be slimmed once 3rd-repetition rule applies. Otherwise behavior-guard growth is event-driven (new behavior or bug fix → new test).

**Blockers:** None.

## 2026-04-16: protocol-communication — 4 fixes, codex APPROVE, 5/6 QA PASS

**What changed:**
- Split `CompletionReason` into `TaskOutcome` (GOAL_ACHIEVED / MAX_TURNS / TASK_IMPOSSIBLE / ERROR / USER_STOPPED) and `SessionEndReason` (USER_STOPPED / IDLE_TIMEOUT / INTERRUPTED). `TaskCompleted.outcome` and `SessionCompleted.reason` now carry the right shape; `SessionCompleted.result` (always null) removed. Impossible branches pruned in `AgentServiceEventHandler` and `CapsuleStateHolder.onSessionEnded`.
- `SessionRecordingService.completedNormally` now derives from `lastTaskOutcome` (cleared on `TaskStarted`, persisted in `SessionRuntimeSnapshot`, restored via `AgentSession.reload()`). `handleShutdown()` emits `TaskCompleted(USER_STOPPED)` for any in-flight task before `SessionCompleted`.
- `SessionCheckpointCoordinator` now round-trips `actionDelayMs`, `approvalMode`, `debugMode`, `traceEnabled`, `traceRunId`, `excludedTools` (previously silently dropped on reload — could change security posture).
- `AgentSession.handleApproval()` reordered: `toolRouter.resolveApproval()` gates allow-list mutation; unmatched/duplicate `Op.Approve` is logged and dropped without touching package allow-lists.
- Pruned dead event surface: `TodosUpdated`, `ScratchpadUpdated`, `ApprovalResolved` (no consumers), `StatusUpdate.emoji` field, `TurnStarted.phase` field, `ApprovalRequired.actionId` field.

**Why:**
- Double-design holistic review dimension 11 identified completion-semantics as a data-integrity bug (successful tasks recorded as failed when session later idled out) and approval validation as a security invariant (stale `Op.Approve` could mutate policy without matching a pending request).
- Checkpoint field loss silently changed runtime behavior after reload — `approvalMode: SMART` would become default, `traceEnabled` would reset.
- ~257 lines of dead event types were pure protocol overhead.

**Key files:** `protocol/TaskOutcome.kt`, `protocol/SessionEndReason.kt`, `protocol/TaskLifecycleEvents.kt`, `protocol/SessionLifecycleEvents.kt`, `session/AgentSession.kt`, `session/SessionCheckpointCoordinator.kt`, `history/SessionRecordingService.kt`, `history/model/SessionRuntimeSnapshot.kt`, `app/AgentServiceEventHandler.kt`, `ui/overlay/CapsuleStateHolder.kt`, `protocol/AgentEventDispatcher.kt`
**Verification:** `./gradlew assembleDebug test lint` pass. 2 codex review rounds: v1 REQUEST CHANGES (1 High `lastTaskOutcome` lifecycle, 2 Medium test-coverage gaps → fixed in `236dfbf3`), v2 **APPROVE**. Real-device QA on device EP0110MZ0BC: 5/6 PASS, 1 SKIPPED (stale `Op.Approve` not externally triggerable, verified by code review + unit tests). No crashes. Evidence in `doc/todo/holistic-review/protocol-communication/final/qa_evidence/`.
**Commit:** `9f7ddf72..682286b0` (12 commits)
**Next:** dead-code-overabstraction (parallel milestone, already closed in same window).
**Blockers:** None.

## 2026-04-16: dead-code-overabstraction — 4 phases, ~600 lines deleted, codex APPROVE

**What changed:**
- Phase 1 (safe deletions): removed 3 dead files (`StatusUtils.kt`, `SessionServicesSummaryFormatter.kt`, stray `.DS_Store`), 9 dead methods (`SessionServices.getSummary/updateApprovalMode`, `AppClassifier.addUserOverride`, 4 tool-result helpers, `ToolSpec.toFunctionSchema`, `ActionResult.isSuccess`), 3 dead composables (`ApiKeysSection`, `BackendSelector`, `SettingsDropdownOptionWithDescription`), dead `refreshOAuthToken()` in `OpenAiSignIn`, and dead `ScreenSnapshotDebug.captureQualityPath` field.
- Phase 2 (API surface): dropped dead `OnboardingViewModel.context` and `DefaultOnboardingDemoController.modelCatalog` constructor params; shrank `SessionHistoryManager` (made `loadSessionByFileName` private; deleted `deleteSessionByFileName`, `getMostRecentSession`, `hasActiveSession`, `endSession`, unused `scope` field); removed dead `data` field + writers from `ToolCallResult.Success` / `ToolExecutionResult.Success`.
- Phase 3 (onboarding): collapsed single-impl interface — deleted `OnboardingDemoController` interface, promoted `DefaultOnboardingDemoController` → concrete `OnboardingDemoController`, switched `OnboardingViewModel` from nullable late-assigned field to constructor injection.
- Phase 4 (delegate_task): removed `agent_name` parameter + validation + lookup from `DelegateTaskTool` (registry now always resolves to the single executor role); `AgentDefRegistry` + `AgentRoleDef` retained for real role resolution.

**Why:**
- Double-design holistic review dimension 12 identified these as zero-behavior-change safe deletions. Targets were confirmed dead by cross-file `rg` in both `app/src/main` and `app/src/test`. Speculative interface + two-phase injection in onboarding had one implementation and one call-site — the abstraction had no consumers.
- `delegate_task` exposed a fake multi-agent choice (`agent_name`) but the registry only resolved to executor, so the LLM was burning tokens on a no-op parameter.

**Key files:** `session/SessionServices.kt`, `tool/AppClassifier.kt`, `tool/ToolCallResult.kt`, `tool/ToolSpec.kt`, `tool/ToolRouter.kt`, `tool/impl/DelegateTaskTool.kt`, `platform/ActionResult.kt`, `platform/AccessibilityPlatform.kt`, `ui/settings/ApiKeyFields.kt`, `ui/settings/SettingsDropdowns.kt`, `auth/OpenAiSignIn.kt`, `onboarding/OnboardingViewModel.kt`, `onboarding/OnboardingDemoController.kt`, `app/MainActivity.kt`, `history/SessionHistoryManager.kt`
**Verification:** `./gradlew assembleDebug test` pass; `./gradlew lint` pre-existing-baseline only (2 `NewApi` errors in untouched `ServiceOverlayController.kt` — later patched in `29793c26`). Codex review: **APPROVE** (zero Critical/High/Medium; one Low observation that `delegate_task` no longer rejects a stray `agent_name` at runtime — deliberate, schema drops the field). Real-device QA on device EP0110MZ0BC: 6/6 scenarios PASS (onboarding fresh-install, settings UI, PRO delegation without `agent_name`, session history, normal single-turn, logcat crash check). Evidence in `doc/todo/holistic-review/dead-code-overabstraction/qa_evidence/`.
**Commit:** `43665d44..65897e0f` (7 commits)
**Next:** None for dimension 12 — deferred items (`AgentError.kt`, `LlmCredentialValidator`, `AgentEventDomains` marker interfaces, `ToolRouterContext` flatten) intentionally left, documented in `final/improvement_plan.md`. `AgentError.kt` separately removed during error-resilience work (commit `799336d3`).
**Blockers:** None.

## 2026-04-17: test-architecture — 28 unit-test tasks landed, codex APPROVE

**What changed:**
- Added 26 new test classes + extended 2 across 6 phases (LLM contract, orchestration seams, safety tools, onboarding/auth, chat/history, VD+trace). Test count: 833 → ~920.
- Fixed live bug: `OpenAIErrorClassifier` was matching `429`/`500` as substrings (status `14291` classified as rate-limit). Replaced `message.contains("429")` with non-alphanumeric-boundary regex that also rejects letter-adjacent tokens like `req_429abc`.
- Fixed latent JDK-21-API-on-JDK-17 crash: `SessionCoordinator.drainLocked()` used `List.removeFirst()` → `removeAt(0)`. Was never exercised before new `SessionCoordinatorTest` hit the drain path.
- Extracted `PermissionStateMonitor.deriveRepairModel(...)` as a pure companion fn so tests exercise pure logic instead of spying on the Android probes.
- Made production timeouts injectable (`ShellTool(timeoutSeconds)`, `HttpLlmCredentialValidator(connectTimeoutMs, readTimeoutMs)`) so tests can use short values; production defaults unchanged.
- Added test dep `com.squareup.okhttp3:mockwebserver:5.2.1` matching the existing okhttp 5.x on the main classpath.

**Why:**
- Double-design holistic review (2026-04-08) identified that the LLM contract boundary, orchestration seams, and onboarding/auth had near-zero direct test coverage — regressions were cheap to introduce and invisible until runtime.
- The classifier bug shipped because tests preserved it as a `KNOWN BUG` marker; plan required both fixing the code and removing the marker.
- Codex v2 review flagged 3 tests totaling 46s of real-time wait (`sleep 15`, `DISCONNECT_AT_START`, full retry backoff loop). Unit suite time budget matters; constructor injection was the right shape.

**Key files:** `app/src/main/kotlin/ai/closepaw/llm/OpenAIErrorClassifier.kt`, `.../session/SessionCoordinator.kt`, `.../onboarding/PermissionStateMonitor.kt`, `.../onboarding/HttpLlmCredentialValidator.kt`, `.../tool/impl/ShellTool.kt`, `app/build.gradle.kts`, 26 new test files under `app/src/test/kotlin/ai/closepaw/`
**Verification:** `./gradlew :app:testDebugUnitTest` passes. 3 codex review rounds: v1 REQUEST CHANGES (3 Medium → fixed in `301df1d5`), v2 REQUEST CHANGES (1 Medium slow tests → fixed in `6c3ee6fb`), v3 **APPROVE**. Real-device QA (device EP0110MZ0BC): S1 classifier fail-fast PASS, S2 multi-turn GOAL_ACHIEVED PASS, S3 provider routing PARTIAL PASS (chat-API routed, upstream stream issue unrelated), S4 airplane-mode SKIP per operator. No crashes/ANRs.
**Commit:** `ca787bc5..7c97c8e0` (30 commits)
**Next:** Backlog items if they ever become load-bearing (`CloudLlmRetryTest`, `SessionLlmBootstrapperTest` extend, `LlmInputItemsTraceSerializerTest`).
**Blockers:** None.

## 2026-04-17: tool-system-design — backfilled 5 tasks as done (no code change)

**What changed:**
- Verified all 5 `tsd-*` tasks were already implemented in earlier commits; marked parent `tool-system-design` and children `done` in `doc/todo/tasks.json`.
- No code changes — this was pure task-state bookkeeping.

**Why:** Tasks had been implemented during tool-system design work but never transitioned from `ready`/`running`. Spotted during test-architecture milestone close-out.

**Key files:** `doc/todo/tasks.json`
**Verification:** `appClassifier` threading, `ToolName.AskUser`/`Shell`, `ActionResult.Cancelled` mapping, `BLOCKED_COMMANDS` + truncation indicator, and absent `detectScrollBoundary` / `MobileActionName.Back|Home` branches all confirmed via grep. Tests green.
**Commit:** `7c97c8e0`
**Blockers:** None.

## 2026-04-16: perf-resources — 10 perf fixes, R8 enabled, real-device QA PASS

**What changed:**
- Hot-path O(n²) → O(n): `HistoryManager.compress()` delta-tracks tokens; `PerceptorInternals.applyTruncation()` uses `HashSet` dedup; `enrichEmptyTextElements()` sorts text sources by top + binary-searches per candidate while preserving candidate-order in joined output.
- `Perceptor.snapshot()` collapsed from two root traversals to one; per-pool counters (`PoolCounters`) keep interactive / non-interactive caps at `2 × maxElements` each so `applyTruncation` still gets a full score-based pool.
- `FileTraceRecorder`: `WriteOp.Flush` now actually calls `writer.flush()`; `AppendLine` stops flushing per event (BufferedWriter batches, close flushes).
- Streaming clients: `LlmLogger.isVerboseEnabled` gates `StringBuilder` / tool-call accumulators. `OpenAIResponseClient` + `ChatCompletionClient` now hold `AtomicReference<AutoCloseable>` to the active stream and cancel it from `awaitClose` (mirrors `CodexResponseClient`).
- `BitmapUtils.compressJpeg()` pre-sizes BAOS from pixel count; `AccessibilityScreenshotCapturer.compressScreenshot()` moves bitmap recycling into outer `finally` for exception safety.
- `app/proguard-rules.pro` added; release build enables `isMinifyEnabled=true` + `isShrinkResources=true`. Keep rules cover kotlinx.serialization, Shizuku AIDL, `android.hardware.display.IVirtualDisplayCallback` (and `IDisplayManager` / `VirtualDisplayConfig` stubs — review caught R8 renaming these to `e.a`), OpenAI SDK + Jackson reflection, Leap SDK JNI, HiddenApiBypass, Compose runtime, app entry points.
- `doc/dev/development.md`: new "Debug vs Release APK — always debug unless shipping" section plus two release-specific troubleshooting rows.

**Why:**
- Double-design review (Claude + Codex, revalidated 2026-04-16) identified the hotspots. Heaviest impact was release APK size and per-capture perception cost.
- R8 was simply never turned on; 74% of the APK was dead code.
- Trace `flush()` was a silent no-op masked by per-line flushing — removing the per-line flush without co-fixing `Flush` would have lost data.

**Key files:** `app/build.gradle.kts`, `app/proguard-rules.pro`, `history/HistoryManager.kt`, `perception/Perceptor.kt`, `perception/PerceptorInternals.kt`, `trace/FileTraceRecorder.kt`, `llm/LlmLogger.kt`, `llm/CodexResponseClient.kt`, `llm/OpenAIResponseClient.kt`, `llm/ChatCompletionClient.kt`, `platform/BitmapUtils.kt`, `platform/AccessibilityScreenshotCapturer.kt`, `doc/dev/development.md`
**Verification:** `./gradlew test`, `./gradlew assembleDebug`, `./gradlew assembleRelease` all pass. New unit tests: `BitmapUtilsTest`, `FileTraceRecorderTest`, two `HistoryManagerTest`, two `PerceptorInternalsTest` (1000-candidate scalability + out-of-order enrichment). Codex review saved to `doc/todo/holistic-review/performance-resources/codex_review.md` — 2 high + 1 medium fixed, 1 medium deferred (SDK limitation on pre-publication stream cancel). Real-device QA (device EP0110MZ0BC, 5 scenarios, all PASS) — `qa_report.md`. Release APK 96 MB → 25 MB (−74%).
**Commit:** 12b3e403..b0509f75 (14 commits)
**Next:** Before shipping a public release APK, run a full LLM tool-call on the signed release build to cover the `OpenAIResponseClient` / `ChatCompletionClient` R8 paths that QA (d) could not exercise without credentials.
**Blockers:** None.

## 2026-04-16: UI/UX Quality Improvement — 6 Phases Complete

**What changed:**
- Phase 1: Capsule composition correctness — removed composition-time side effects (`previousModeState`, input clearing), callers provide `previousMode`, clearing via `LaunchedEffect`
- Phase 2: Settings state hoisting — `rememberSaveable` for page/tab/provider, decoupled tab exploration from backend mutations
- Phase 3: Chat scroll — intent-based `followMode` with `programmaticScroll` guard, content-aware `scrollKey` (text + action card state), scroll-to-bottom FAB, removed `SimpleDateFormat` and double rotation animation
- Phase 4: Destructive action confirmation dialogs for session delete, Clear Traces, Clear Session History
- Phase 5: Accessibility — `IconButton` for onboarding back, `contentDescription` on capsule nav buttons, theme tokens + `Role.Button` on status island
- Phase 6: Overlay state unification — removed duplicate flows from `CapsuleOverlayHost`, added `hasIsland` to `CapsuleStateHolder`, `ServiceOverlayController` writes to stateHolder only

**Why:**
- State ownership drift was the primary quality problem — composition-time state writes, initialize-once patterns, duplicate state flows
- Chat scroll was broken for streaming (only tracked message count, not content growth)
- Three destructive actions lacked confirmation

**Key files:** `SmartCapsuleSurface.kt`, `SmartCapsuleCompose.kt`, `ChatScreen.kt`, `MessageBubble.kt`, `ActionCard.kt`, `SettingsSheet.kt`, `LlmAuthSettingsPage.kt`, `PermissionsAdvancedSettingsPage.kt`, `NavigationDrawer.kt`, `OnboardingShell.kt`, `StatusIslandCompose.kt`, `SmartCapsuleSurfaceParts.kt`, `CapsuleOverlayHost.kt`, `CapsuleStateHolder.kt`, `ServiceOverlayController.kt`
**Verification:** `./gradlew assembleDebug` + `./gradlew test` pass. Codex code review. Human on-device QA (capsule transitions, chat scroll, multi-turn regression). 4 scroll bugs found and fixed during QA.
**Commit:** d9be858a..ce30041c (7 commits)
**Next:** Error resilience and performance-resources task trees
**Blockers:** None

## 2026-04-10: LLM Integration Phases 5+6 — Local Semantics + Deduplication

**What changed:**
- Phase 5: `LocalLlmSemantics` object declaring 4 Leap backend limitations, cross-referenced at each occurrence in `LFMLLMClient`
- Phase 6: `ToolParameterExtractor` merging duplicate tool parameter extraction from `CodexRequestBuilder` and `LeapToolSchemaAdapter`

**Why:**
- Final phases of LLM integration holistic review — make implicit lossiness explicit, reduce code duplication

**Key files:** `LFMLLMClient.kt`, `ToolParameterExtractor.kt`, `CodexRequestBuilder.kt`, `LeapFunctionInterop.kt`
**Verification:** `./gradlew test` passed
**Commit:** 3d6b52ae, 5003fc5c
**Next:** All 6 phases complete — LLM integration holistic review done
**Blockers:** None

## 2026-04-10: LLM Integration Phase 4 — Extract Shared Helpers

**What changed:**
- Extracted `StreamRetryRunResult.closeFlow()` — identical post-retry epilogue block from 3 streaming clients into a single method on the result data class
- Streaming loop internals intentionally NOT extracted (fundamentally different event sources)

**Why:**
- Phase 4 of LLM integration holistic review — reduce duplication without over-engineering

**Key files:** `CloudStreamRetryRunner.kt`, `OpenAIResponseClient.kt`, `CodexResponseClient.kt`, `ChatCompletionClient.kt`
**Verification:** `./gradlew test` and `./gradlew assembleDebug` passed
**Commit:** 73916643
**Next:** Phase 5 — Declare local capability gaps
**Blockers:** None

## 2026-04-10: LLM Integration Phase 3 — Classification, SSL, Cancellation

**What changed:**
- 3.1: OpenAIErrorClassifier hardened with typed SDK exception fast-paths (Retry-After header extracted from SDK RateLimitException), domain exception preservation, string fallback last
- 3.2: InsecureSslConfig gated behind `BuildConfig.INSECURE_SSL_FOR_EVAL` (default false); eval runner passes `-PinsecureSslForEval=true`
- 3.3: CodexResponseClient stores OkHttp Call, cancels from awaitClose registered before streamWithRetry via launch{}

**Why:**
- Phase 3 of LLM integration holistic review — eliminates fragile heuristics, narrows SSL bypass surface, prevents 120s hang on flow cancellation

**Key files:** `OpenAIErrorClassifier.kt`, `InsecureSslConfig.kt`, `CodexResponseClient.kt`, `build.gradle.kts`, `runner_preflight.py`
**Verification:** `./gradlew test` and `./gradlew assembleDebug` passed
**Commit:** 913b5086..855d9fc4
**Next:** Phase 4 — Extract shared Responses helpers
**Blockers:** None

## 2026-04-10: LLM Integration Phase 2 — P0 Streaming Correctness

**What changed:**
- 2.1: Domain exceptions (RateLimitException/TransientException) preserved in streamWithRetry — no longer reclassified
- 2.2: Created event no longer blocks retry — only TextDelta/ToolCallDone set emittedEvent
- 2.3: response.incomplete → Failed with incomplete_reason; streaming loop breaks on Failed
- 2.4: ChatCompletionClient tracks sawFinishReason, throws TransientException if missing
- 2.5: Stream-ended-without-completion now throws TransientException (retryable)
- 2.6: MessageContentExtractor deleted, typed ChatCompletionInterop.extractStringContent used
- Review fix: CodexResponseClient streaming breaks immediately on Failed event

**Why:**
- Phase 2 of LLM integration holistic review — eliminates silent truncation, lost retries, garbage Leap input

**Key files:** `CloudStreamRetryRunner.kt`, `CodexResponseClient.kt`, `CodexSseParser.kt`, `ChatCompletionClient.kt`, `OpenAIResponseClient.kt`, `ChatCompletionInterop.kt`, `LFMLLMClient.kt`, `LlmLogger.kt`, `LlmInputItemsTraceSerializer.kt`
**Verification:** `./gradlew test` and `./gradlew assembleDebug` passed
**Commit:** 2a0c6b2e..6c821852
**Next:** Phase 3 — Harden error classification, SSL, cancellation
**Blockers:** None

## 2026-04-10: LLM Integration Phase 1 — Streaming/Retry Tests

**What changed:**
- Added 4 test classes (62 tests) covering streaming/retry system: `OpenAIErrorClassifierTest`, `CloudStreamRetryPolicyTest`, `CloudStreamRetryRunnerTest`, `CodexSseParserTest`
- 6 KNOWN BUG tests capture current broken behavior (will flip when fixes land): false-positive substring matching in classifier, domain exception reclassification, Created event blocking retry, response.incomplete treated as success
- Virtual-time assertions prove backoff timing and retryAfterMs loss

**Why:**
- Phase 1 prerequisite for LLM integration holistic review — tests must lock down current behavior before correctness fixes in Phase 2

**Key files:** `app/src/test/kotlin/ai/closepaw/llm/CloudStreamRetry{Runner,Policy}Test.kt`, `CodexSseParserTest.kt`, `OpenAIErrorClassifierTest.kt`
**Verification:** `./gradlew test` passed
**Commit:** 1391287e..11d76d0f
**Next:** Phase 2 — Fix P0 streaming correctness (5 items + MessageContentExtractor bug)
**Blockers:** None

## 2026-04-10: Tool System Design Improvements (5 phases)

**What changed:**
- Phase 0: Observation masking gap — `appClassifier` threaded through `PostActionAnalysis` and all executors so BLOCKED-app post-action observations are masked; `open_app` checks destination tier before launch (denied for BLOCKED apps)
- Phase 1a: ToolName metadata — `ask_user` and `shell` added to `ToolName` enum with `isScreenChanging=false`; previously parsed as `Unknown(isScreenChanging=true)`, causing false approval prompts and spurious `complete_task` drops
- Phase 3: Shell hardening — metacharacter rejection (`;|&`><$\n\r`), expanded blocklist (`env`, `xargs`, `find` added to existing `am/pm/reboot/su`), truncation indicator when output exceeds `MAX_OUTPUT_CHARS`
- Phase 2: Action runtime normalization — `SwipeExecutor` returns `Cancelled` (not `Failed`) on system cancellation; `TypeExecutor` explicit `Cancelled` handling at each attempt; `ScrollExecutor` fails immediately for unresolvable explicit targets; `PointActionExecutorCore` retarget observability (diagnostic note in warnings)
- Phase 5: Dead code cleanup — removed `UiChangeDetector.detectScrollBoundary()`, removed `UIActionInvocation.detectScrollBoundary()`, removed `MobileActionName.Back/Home` from `PolicyEngine.isEscape()` (unreachable path), deleted `DataQueryInvocation.kt` (zero callers), removed duplicate `OpenAppTool` companion constants, `SystemButtonTool` unreachable else branch now throws

**Why:**
- Holistic tool-system-design review (Claude + Codex double-blind validation) found observation masking gap, metadata misclassification, shell bypass vectors, inconsistent cancellation handling, and accumulated dead code

**Key files:** `PostActionAnalysis.kt`, `ObservationBuilder.kt`, `ToolName.kt`, `PolicyEngine.kt`, `ShellTool.kt`, `OpenAppTool.kt`, `PointActionExecutorCore.kt`, `ClickExecutor.kt`, `LongPressExecutor.kt`, `TypeExecutor.kt`, `ScrollExecutor.kt`, `SwipeExecutor.kt`, `MobileActionInvocation.kt`, `UIActionInvocation.kt`, `UiChangeDetector.kt`, `SystemButtonTool.kt`
**Verification:** `./gradlew assembleDebug test` passes
**Commit:** 98e2d907..9d07973a (6 commits)
**Next:** None — tool-system-design complete
**Blockers:** None

## 2026-04-10: Platform Robustness Hardening (8 phases + 8 follow-up fixes)

**What changed:**
- P1: VD lifecycle serialization — `VdLifecycleArbiter` state machine (Stopped/Running/Broken), lifecycle mutex with preDrainState, Running lease for ops, start() rollback, binder death → Broken + clearCachedProxies
- P2: Bounded callback waits — shared `boundedCallback()` helper with timeout (5s a11y, 3s PixelCopy) and invokeOnCancellation; late-callback HardwareBuffer cleanup
- P3: Gesture cancellation safety — best-effort ACTION_CANCEL on interrupted gestures, MOVE failure fails gesture
- P4: Window selection coherence — layer-ordered topmost window for actions/privacy/screenshot on both platforms
- P5: Real display metrics — WindowManager.maximumWindowMetrics instead of app content metrics
- P6: Boundary correctness — CancellationException rethrown, Perceptor off Main, truthful app launch, surface replacement
- P7: Resource cleanup — window recycling, debug screenshot retention cap, dead code removal
- P8: Regression tests for VdLifecycleArbiter and BoundedCallback
- Follow-up: arbiter admission race fix (preDrainState), PixelCopy bitmap safety, HardwareBuffer leak, shell input fallback (`input -d`), setDisplayId round-trip verification, VD overlay approval visibility, debug-only exported viewer
- Codex final review: Draining state (keeps resources for in-flight ops during stop), start-from-Broken cleanup, PixelCopy timeout/failure split, isActive race removal, force capsule for attention modes, PixelCopy counter reset on surface replacement

**Why:**
- Holistic review found unbounded callback waits, VD lifecycle races, stale binder proxies, window selection bugs, resource leaks, and silent failures at the platform boundary
- QA on real device uncovered HiddenApiBypass failure (void method false positive in setDisplayId), and VD overlay hiding approval dialogs

**Key files:** VdLifecycleArbiter.kt, VirtualDisplayPlatform.kt, BoundedCallback.kt, VirtualDisplayInputInjector.kt, VirtualDisplayCaptureCoordinator.kt, AccessibilityScreenshotCapturer.kt, AccessibilityPlatform.kt, VirtualDisplayWindowAccessor.kt, OverlayLocationPolicy.kt
**Verification:** `./gradlew assembleDebug test` passes; QA on P0110 (Android 16): a11y mode, VD mode, hybrid screenshots, PixelCopy/LIVE_PREVIEW, multi-session lifecycle, overlay approval all verified
**Commit:** ddb581d..d7f3e47 (8 commits)
**Next:** None — platform robustness complete
**Blockers:** None

## 2026-04-09: Agent Core Simplicity (9 tasks)

**What changed:**
- P0: Fixed action-signature derivation bug — signatures now derived from actually-executed tools, not pre-computed plan
- P1: Split ExecutorStepPolicy into isFinalTurn() + DelegationSummaryFormatter; removed dead WarnApproaching/narrativeSummaryOnLimit
- P1: Unified AgentDef + AgentDefinition into AgentRoleDef — one role model for top-level and delegated agents
- P2: Removed dead NavigationState fields (consecutiveScrollActions, recentActions, fingerprint, CRITICAL severity), PreTurnContext.appTier
- P3: Extracted TurnObservation as canonical per-turn screen payload, eliminating prompt/history temporal coupling
- P3: Consolidated all agent event emission through AgentEventDispatcher
- P3: Extracted shared ActionTarget decoder for ActionDescriptionFormatter + ActionSignature
- P4: Added TextRecovery telemetry to Turn.kt; named magic delay constants
- Codex review: fixed screenshot-only observation divergence, removed vestigial action-signature return path
- Codex final review: deleted orphaned ActionSignature.kt, fixed history-resume fail-open bug, fixed new-session event-collection race

**Why:**
- Double-design review (Claude + Codex) identified runtime invariant mismatch, duplicate role definitions, and accumulated dead code
- Owner corrected P0 scope: multi-action turns are intentional for form-filling; only the signature derivation bug needed fixing

**Key files:** agent/TurnExecutionPhaseRunner.kt, agent/AgentTurnRunner.kt, agent/cognition/policy/TurnBudget.kt, agent/definition/AgentRoleDef.kt, agent/cognition/prompt/TurnObservation.kt, agent/AgentEventDispatcher.kt, agent/ActionTarget.kt
**Verification:** `./gradlew test` passes (all 59 tasks), Codex code review completed
**Commit:** 55b597f..fd5060b (12 commits)
**Next:** None — ACS complete
**Blockers:** None

## 2026-04-08/09: Security & Privacy Hardening (10 tasks)

**What changed:**
- P0.1: Intent control plane lockdown — production ignores external security-sensitive extras, goal dispatch requires user confirmation
- P0.2: Capture-layer privacy gate — blocked-app masking moved before trace artifact writes; null-package = skip capture (fail-closed)
- P0.3: Fail-closed encrypted storage — no plaintext SharedPreferences fallback; legacy plaintext migration code deleted (pre-release, no backward compat)
- P0.4: Auth PII removed from logs (id_token claims, email, OAuth callback request line)
- P1.1: Password field suppression — `Perceptor` checks `isPassword`, replaces text with `[password]`
- P1.2: Shell blocklist reduced to `am/pm/reboot/su`; no metacharacter restriction, no path denylist (security theater with `sh -c`)
- P1.3: InsecureSslConfig moved to debug-only source set (compile-time guarantee)
- P1.4: AppClassifier fails closed on missing/corrupt/invalid-tier app_tiers.json
- P2.2: Data & Storage settings section (trace toggle, one-tap wipe)
- P2.3: Security regression tests (6 unit tests + 1 instrumentation test)
- Post-review fixes: capture gate null-package, remaining PII logs, legacy secret scrub, strict tier parsing, trace toggle plumbed to AgentService

**Why:**
- Holistic security review (Claude+Codex double-design) identified boundary placement and privilege composition issues
- Owner review simplified several items (editable-field suppression dropped, shell kept permissive, OAuth localhost hardening dropped as ineffective)

**Key files:** `MainActivityIntentApplier.kt`, `AppSettingsStore.kt`, `OAuthCredentialStore.kt`, `OpenAIOAuth.kt`, `Perceptor.kt`, `ShellTool.kt`, `InsecureSslConfig.kt` (debug/release split), `AppClassifier.kt`, `AccessibilityPlatform.kt`, `ObservationBuilder.kt`, `PermissionsAdvancedSettingsPage.kt`, `AgentService.kt`
**Verification:** `./gradlew test assembleDebug` pass; ADB smoke test 4/4 pass; Codex code review → 6 findings all addressed
**Commit:** `a3b4a60..92f1b79` (15 commits)
**Next:** `sec-app-tiers-expansion` (expand app_tiers.json to 100-1000 apps, design in progress)
**Blockers:** None

## 2026-04-07: Codex multi-turn fix + settings auth label

**What changed:**
- CodexRequestBuilder: assistant messages now use `"output_text"` content type (was `"input_text"` for all roles, causing HTTP 400 on multi-turn Codex conversations)
- SettingsHomePage: subtitle dynamically shows "OAuth" or "API key" based on `authMethod` (was hardcoded "API key")

**Why:**
- Codex API rejects `input_text` for assistant role content — only `output_text` and `refusal` are valid
- Settings label was misleading for OAuth users

**Key files:** `CodexRequestBuilder.kt`, `SettingsHomePage.kt`, `SettingsSheet.kt`
**Verification:** `./gradlew assembleDebug` pass; on-device (nubia M153) — multi-turn Codex conversation runs 7+ turns without HTTP 400; Settings correctly shows "GPT-5.4 · OAuth"
**Commit:** `59532da`
**Next:** None
**Blockers:** gpt-5.4 via CodexResponseClient sends malformed tool calls (multiple targeting methods) — model-side issue, not actionable in our code

## 2026-04-04: Settings page restructure

**What changed:**
- Two-level settings navigation: Home (3 nav rows) → sub-pages with AnimatedContent transitions
- LLM & Authentication sub-page: 3-tab structure (Sign In / API Key / Local)
  - Sign In: OpenAI OAuth account card + RESPONSE-only model selector
  - API Key: provider sub-selector (OpenAI/OpenRouter/Novita) + linked model/key
  - Local: local model selector + download status
- Agent Behavior sub-page: max turns, agent mode, perception mode
- Permissions & Advanced sub-page: a11y, overlay, debug toggle
- Split manual OpenAI API key from OAuth token (credential isolation)
- Shared OAuth suspend helpers (`auth/OpenAiSignIn.kt`) — reused by onboarding and settings
- Provider-linked model filtering (`ModelCatalog.modelsFor/preferredModelFor`)
- Model/executor canonicalization on provider switch
- OpenAI Auth Card with 4 states (SignedOut/InProgress/SignedIn/Error)
- Tab switching immediately persists backend/authMethod
- One-time migration for legacy credential split

**Why:**
- OAuth was invisible in settings — no post-onboarding management
- Flat layout didn't scale (8 sections, 26 parameters)
- Manual OpenAI key was destroyed on OAuth sign-in (overloaded single field)

**Key files:**
- New: `SettingsHomePage.kt`, `LlmAuthSettingsPage.kt`, `AgentBehaviorSettingsPage.kt`, `PermissionsAdvancedSettingsPage.kt`, `OpenAiAuthCard.kt`, `OpenAiSignIn.kt`
- Modified: `SettingsSheet.kt`, `AppSettingsState.kt`, `AppSettingsStore.kt`, `MainActivity.kt`, `MainActivityContent.kt`, `ModelCatalog.kt`, `OnboardingViewModel.kt`

**Design docs:** `doc/todo/settings_redesign/` (UX spec, double-design, aligned design)
**QA:** On-device (nubia M153) — Settings navigation PASS, API key path PASS, OAuth state display PASS
**Verification:** `./gradlew assembleDebug` + `./gradlew test` pass; Codex code review completed + fixes applied
**Commit range:** `dc8cd16..HEAD`
**Next:** On-device OAuth re-login E2E test (Sign In button flow)
**Blockers:** None

## 2026-04-02: CodexResponseClient for OAuth users

**What changed:**
- New `CodexResponseClient`: raw OkHttp + SSE client targeting `chatgpt.com/backend-api/codex/responses` for OAuth users
- New `CodexRequestBuilder`: serializes ResponseInputItem/FunctionTool to Codex-specific JSON format
- New `CodexSseParser`: SSE parsing with parallel-safe `ToolCallAccumulator` (map-keyed by output_index), normalizes Codex-specific events (`response.done` → `Completed`)
- `LLMClientFactory`: OAuth routing via `__AUTH_METHOD_OPENAI` signal in apiKeys map; `isOAuth()` detection for OPENAI provider + RESPONSE API
- `AppSettingsState`: new `authMethod` property, `buildApiKeys()` includes OAuth signal
- `MainActivity`: initializes `authMethod` from `OnboardingStore` at startup and after onboarding completion
- Added direct OkHttp dependency (`com.squareup.okhttp3:okhttp:4.12.0`)

**Why:**
- OAuth access tokens lack platform API scopes (`api.responses.write`, `model.request`), so they cannot use `api.openai.com`. The Codex endpoint at `chatgpt.com/backend-api` is the only working path for ChatGPT subscription users.

**Key files:** `llm/CodexResponseClient.kt`, `llm/CodexRequestBuilder.kt`, `llm/CodexSseParser.kt`, `llm/LLMClientFactory.kt`, `app/AppSettingsState.kt`, `app/MainActivity.kt`
**Design doc:** `doc/todo/openai_oauth/path_b_design.md`
**Verification:** `./gradlew assembleDebug` + `./gradlew test` pass; code review completed
**Commit:** `9693895`
**Next:** On-device E2E validation with real OAuth token
**Blockers:** None

## 2026-04-02: Onboarding wizard implementation

**What changed:**
- Full first-launch onboarding wizard: Accessibility → Overlay → Battery → API Key → Demo → Complete
- `OnboardingStore`: own prefs file, encrypted draft key, legacy user migration
- `OnboardingViewModel`: state machine with auto-advance, A11y polling, step persistence
- `PermissionStateMonitor`: reusable A11y/Overlay/Battery live checks
- `HttpLlmCredentialValidator`: direct HTTP validation with auth vs network error mapping
- `DefaultOnboardingDemoController`: throwaway AgentSession, "Open Settings" goal, 60s timeout
- `OnboardingScreen` + `OnboardingShell` + `OnboardingSteps`: full-screen step UI with progress bar
- `PermissionRepairCard`: post-onboarding in-chat repair for revoked permissions
- `MainActivity`: root routing (onboarding vs chat), eval bypass, onResume integration
- Code review fixes: JSON injection (JSONObject), encrypted draft safety, Dispatchers.Main callbacks, Mutex for demo session

**Why:**
- First-run experience was confusing — users hit permission failures with no guidance
- Wizard ensures all required setup (A11y, Overlay, API key) is complete before chat

**Key files:** `onboarding/` package (8 files), `ui/onboarding/` package (4 files), `MainActivity.kt`, `MainActivityContent.kt`
**Design docs:** `doc/todo/onboarding_wizard/ux_design.md`, `doc/todo/onboarding_wizard/eng_design.md`
**Verification:** `./gradlew assembleDebug` + `./gradlew test` pass; code review completed
**Commit range:** `080385b..6a869cb`
**Next:** On-device QA, unit tests for ViewModel state machine
**Blockers:** None

## 2026-04-01: Security hardening QA + network security config

**What changed:**
- Full QA of basic-security (5 items) and agent-security (KISS 4+1 layers) on physical device (nubia M153) with gpt-5.4 via Tailscale HTTPS
- QA results (all PASS):
  - EncryptedSharedPreferences: encrypted XML on device, plain prefs clean, migration works, corruption fallback in place
  - allowBackup=false, cleartext blocked: confirmed via package flags
  - InsecureSslConfig: gated behind BuildConfig.DEBUG (code review)
  - PolicyEngine: NORMAL apps → Allow (Settings navigation, 6 tool calls logged), CAUTIOUS → AskUser (WhatsApp, approval UI with Allow/Session/Always buttons displayed), BLOCKED → Deny (Robinhood, screen masked, memory write blocked)
  - Approval UI: three-tier buttons rendered correctly, 60s timeout → cancel works
  - Perception gate: BLOCKED app screen masked — LLM saw "⛔ Screen hidden" message
  - Memory gate: remember_experience on BLOCKED app → Error (blocked)
- Replaced `usesCleartextTraffic="false"` with `networkSecurityConfig` to support emulator: release blocks all cleartext, debug allows 10.0.2.2/localhost only
- Verified emulator cleartext works on remote desktop (emulator-5554, HTTP to cproxy via 10.0.2.2)
- Fixed Tailscale serve config: 443→workflow(5173), 8741→cproxy(18080)
- Updated development.md with Tailscale/cproxy/emulator documentation
- Renamed remote SSH user moonkey→qiguo, hostname qiguo-ld1→desktop across scripts and docs

**Why:**
- Close all QA gaps before moving on from Phase 1 security work
- Emulator path was broken by cleartext=false; networkSecurityConfig gives per-build granularity

**Key files:** `app/src/main/res/xml/network_security_config.xml`, `app/src/debug/res/xml/network_security_config.xml`, `AndroidManifest.xml`, `doc/dev/development.md`, `scripts/remote/`
**Verification:** `./gradlew assembleDebug` + `./gradlew test` pass; live QA on nubia M153 (gpt-5.4 via Tailscale); emulator smoke test on remote desktop
**Commit:** `e1790d5..1398d42`
**Next:** Pick up next Phase 1 task (onboarding-wizard) or priority.md #0 (prompt-tune)
**Blockers:** None

## 2026-03-24: Doc structure alignment with /init-all and /update-doc standards

**What changed:**
- Renamed `AIDEV.md` → `CLAUDE.md` as source of truth; `AGENTS.md`, `GEMINI.md`, `.cursorrules` now symlink to `CLAUDE.md`
- Renamed `.ai-dev/` → `.claude/` as source of truth directory; `.agents/`, `.codex/` now symlink to `.claude/`
- Rewrote `CLAUDE.md` from 106 → 46 lines following pointer convention (≤50 lines, no embedded architecture/patterns)
- Split 5 oversized doc/main/ files (>300 line limit):
  - `app/history.md` (550) → `app/history/{overview,persistence,runtime,models}.md` (316 total)
  - `ui/overlay.md` (443) → `ui/overlay.md` (144) + `ui/capsule/architecture.md` (116)
  - `protocol/protocol.md` (419) → `protocol/{overview,events,config}.md` (297 total)
  - `infra/platform.md` (410) → `infra/platform.md` (152) + `infra/virtual_display.md` (74)
  - `infra/llm.md` (366) → trimmed to 138 lines
- Trimmed `doc/dev/development.md` from 328 → 265 lines
- Fixed archive naming: `diff_review` → `260206_diff_review`, `future_features.md` → `260206_future_features/`
- Updated all `.ai-dev/` → `.claude/` references in skill files, sop files, and PROGRESS.md
- Updated all cross-references from deleted files to new split locations

**Why:**
- Align with `/init-all` (CLAUDE.md as authority, ≤50 lines pointer doc, symlink direction) and `/update-doc` (≤300 line docs, YYYYMMDD archive naming) standards

**Key files:** `CLAUDE.md`, `.claude/`, `doc/main/README.md`, `doc/main/app/history/`, `doc/main/protocol/`, `doc/main/infra/virtual_display.md`, `doc/main/ui/capsule/architecture.md`
**Verification:** All README.md cross-reference links verified
**Commit:** `6ee3fd6`
**Blockers:** None

## 2026-03-13: Memory V2 Implementation

**What changed:**
- Replaced the V1 memory layout with V2 scope-first files: `memory/user.md`, `memory/device.md`, and `memory/apps/<package>.md`.
- Added a shared `MemorySchema` and rewrote `MemoryStore` to write canonical fixed-section markdown with full timestamps.
- Simplified `MemoryRecaller` to deterministic full-file recall for user, device, and current app memory.
- Redesigned `remember_experience` around `scope + section` routing instead of V1 `category + [kind]`.
- Memory writes now propagate explicit success/failure so `remember_experience` and failure auto-retain do not silently report success on failed saves.
- Updated failure auto-retain to write app `Operational Notes`, refreshed prompt guidance, and added regression tests for store, recall, prompt ordering, and tool validation/execution.
- Updated the main memory doc and added an implementation plan for this task.

**Why:**
- Bring runtime behavior in line with the agreed Memory V2 design: KISS scope-first files, deterministic recall, and no extra session-log memory layer.

**Key files:** `app/src/main/kotlin/ai/closepaw/memory/MemorySchema.kt`, `app/src/main/kotlin/ai/closepaw/memory/MemoryStore.kt`, `app/src/main/kotlin/ai/closepaw/memory/MemoryRecaller.kt`, `app/src/main/kotlin/ai/closepaw/tool/impl/RememberExperienceTool.kt`, `app/src/main/kotlin/ai/closepaw/agent/Agent.kt`, `app/src/main/kotlin/ai/closepaw/agent/definition/StandaloneAgentDef.kt`, `doc/main/agent/memory.md`, `doc/todo/0.5_memory/memory_v2_implementation_plan.md`
**Verification:** `./gradlew assembleDebug testDebugUnitTest`, `./gradlew assembleDebug lint test`
**Commit:** `56aded7`
**Blockers:** None

## 2026-03-13: Memory V2 Design Note Capture

**What changed:**
- Added `doc/todo/0.5_memory/memory_v2_note.md` to preserve the agreed Memory V2 design discussion and final sketch.

**Why:**
- Preserve the current design consensus in-repo so implementation can proceed from one concrete source of truth instead of scattered chat context.

**Key files:** `doc/todo/0.5_memory/memory_v2_note.md`
**Commit:** `79b28a9`
**Blockers:** None

## 2026-03-13: Eval Config Overlay Loading

**What changed:**
- Eval config loading now always starts from `eval/config/default.yaml` and deep-merges any explicitly requested config on top.
- Parallel eval now uses the same merged config path as serial eval, so worker shard configs inherit default settings before device-specific overrides apply.

**Why:**
- Remove duplicated config copies, keep remote config minimal, and make config variants inherit new default settings automatically instead of drifting.

**Key files:** `eval/aw_bridge/runner.py`, `eval/aw_bridge/parallel_runner.py`, `eval/config/remote.yaml`
**Commit:** `2fbfeb2`
**Blockers:** None

## 2026-03-13: Eval Memory Hygiene

**What changed:**
- Eval bridge now clears `files/memory` before each task launch.
- Eval configs now exclude `remember_experience` by default so the memory tool is not exposed during eval.
- Updated eval and memory docs to document the clean-eval contract.
- Added regression tests for config loading and bridge cleanup/launch behavior.

**Why:**
- Prevent `RememberExperience` and persisted memory from contaminating eval runs while keeping the app runtime logic simple.

**Key files:** `eval/aw_bridge/native_agent_bridge.py`, `eval/aw_bridge/runner.py`, `eval/config/default.yaml`, `eval/config/remote.yaml`, `eval/config/gpt54_never_succeeded.yaml`, `eval/tests/test_native_agent_bridge.py`, `eval/tests/test_runner.py`, `doc/main/agent/memory.md`, `doc/main/eval/eval.md`, `eval/README.md`
**Verification:** `./gradlew assembleDebug test`, `./gradlew lint`, `python3 -m pytest eval/tests/test_native_agent_bridge.py eval/tests/test_runner.py eval/tests/test_runner_preflight_policy.py`
**Commit:** `7f18cdb`
**Next:** Keep future eval configs and task overrides aligned with the same clean-memory contract.
**Blockers:** None

## 2026-03-11: Remote Eval Worker Phase 2 & 3

**What changed:**
- Phase 2 (Dual Emulator): `provision.sh` now creates both `AndroidWorldAvd` and `AndroidWorldAvd2`. Runbook extended with dual-emulator baseline prep and parallel eval commands.
- Phase 3 (Operational Hardening): New `eval_tmux.sh` tmux wrapper for SSH-disconnect-safe eval. New `openai-proxy-tunnel.service` systemd unit with autossh auto-reconnect. `proxy_tunnel.sh` rewritten as service manager (install/start/stop/status/logs/manual). `provision.sh` now installs `autossh` and `tmux`.
- Updated `/cog-tune` and `/autotune` skills with remote eval commands and references.
- Added explicit git push/pull sync step to remote eval runbook.
- Remote smoke test: AVD2 created, autossh service verified active, proxy reachable. Required keychain env sourcing fix for passphrase-protected SSH keys.

**Why:**
- Enable parallel eval on remote to cut wall-clock time, and harden operations so long-running evals survive SSH disconnects and tunnel drops.

**Key files:** `scripts/remote/provision.sh`, `scripts/remote/eval_tmux.sh`, `scripts/remote/openai-proxy-tunnel.service`, `scripts/remote/proxy_tunnel.sh`, `doc/dev/remote_eval_worker.md`, `.claude/skills/autotune/SKILL.md`, `.claude/skills/cog-tune/SKILL.md`
**Verification:** `bash -n` on all scripts, remote smoke test (AVD2 created, autossh active, proxy ok)
**Commit:** `afa9713..ced92dc`
**Next:** Run dual-emulator baseline prep and parallel eval end-to-end on `desktop`. Ubuntu 22.04 upgrade deferred.
**Blockers:** None

## 2026-03-11: Remote Eval Worker Hardening and Validation

**What changed:**
- Hardened remote eval config loading so `android_world.adb_path` and emulator paths are expanded before use.
- Routed eval preflight ADB calls through the configured binary instead of assuming `adb` is on `PATH`.
- Pinned remote provisioning to emulator `32.1.15` from `emulator-linux_x64-10696886.zip` for Ubuntu 18.04 compatibility.
- Updated the remote worker docs to reflect the actual stale-checkout proxy failure mode and the validated rerun outcome.
- Re-ran the five previously failing app tasks on `desktop` after syncing the fixed bridge path; all five passed in `eval/results/20260311_102822`.

**Why:**
- Remove remote-only setup drift around ADB resolution, emulator versioning, and proxy routing so remote eval failures surface as real task behavior instead of infra noise.

**Key files:** `eval/aw_bridge/runner.py`, `eval/aw_bridge/runner_preflight.py`, `eval/tests/test_runner.py`, `scripts/remote/provision.sh`, `doc/dev/remote_eval_worker.md`, `doc/todo/remote_emulator/implementation_summary.md`
**Verification:** `eval/.venv/bin/python -m unittest eval.tests.test_runner eval.tests.test_runner_preflight_policy`, `bash -n scripts/remote/provision.sh`, `./gradlew assembleDebug lint test`, remote rerun `eval/results/20260311_102822` (`5/5` scripted success)
**Commit:** `76ee8ba`
**Next:** Harden remote worker operations (`autossh`/service wrapper, dual-emulator path) and investigate the `ExpenseAddSingle` agent-side verification gap.
**Blockers:** None

## 2026-03-11: Remote Emulator Eval Worker

**What changed:**
- Provisioned `desktop` (Ubuntu 18.04, i9-7900X, 62G RAM) as a remote Android eval worker with headless emulator.
- New `scripts/remote/provision.sh`: one-shot setup (JDK 17, Python 3.11, Android SDK, AVD).
- New `scripts/remote/proxy_tunnel.sh`: SSH tunnel helper for LLM proxy access.
- New `eval/config/remote.yaml`: remote-specific eval config with correct adb path.
- Modified `scripts/prepare_baseline.sh` and `scripts/eval_parallel.sh`: added `--headless` flag, venv Python preference, `~/android-sdk` emulator search path.
- Changed cproxy `proxy.js` bind from `127.0.0.1` to `0.0.0.0` so remote workers can connect directly.
- New `doc/dev/remote_eval_worker.md`: operational runbook.

**Why:**
- Move eval compute off the laptop onto a dedicated machine with more CPU/RAM and KVM support for faster x86_64 emulation.

**Key files:** `scripts/remote/provision.sh`, `scripts/remote/proxy_tunnel.sh`, `eval/config/remote.yaml`, `scripts/prepare_baseline.sh`, `scripts/eval_parallel.sh`, `doc/dev/remote_eval_worker.md`

**Design:** `doc/todo/remote_emulator/remote_emulator_eval_codex.md`

## 2026-03-11: Memory Auto-Retain Fallback Fix (`faf18ab`)

**What changed:**
- Auto-retain pitfall hook now tracks `lastKnownPackage` through the agent turn loop and uses it as fallback when `getCurrentPackageName()` returns null at failure time (e.g. a11y tree has 0 elements).
- Added logging for auto-retain writes.

**Why:**
- When a task fails with 0 accessibility elements, `getCurrentPackageName()` returned null, silently skipping the pitfall memory write. E2E testing on local emulator with gpt-5.4 revealed this gap.

**E2E verification:** Memory recall confirmed working — seeded `com.android.settings.md` was injected as `## Recalled Memory` into LLM prompt and visible to model.

**Key files:** `agent/Agent.kt`
**Commit:** `faf18ab`

## 2026-03-11: Cross-Session Memory System V1

**What changed:**
- New `memory/` package: `MemoryStore` (file I/O, entry caps, path traversal protection, atomic writes) and `MemoryRecaller` (elastic-budget recall per turn).
- New `RememberExperienceTool`: LLM-callable tool with `[workflow]`/`[pitfall]`/`[verification]` kind tags. Auto-allowed, cognitive tool (non-screen-changing).
- Recall injected into prompt between working memory and app skill via new `recalledMemory` param in `PromptBuilder.buildInputItems()`.
- Elastic budget: device 1KB + user_prefs 1.5KB + app gets remainder up to 3.5KB, total ≤6KB. Newest entries kept on truncation.
- Failure auto-retain hook in `Agent.kt`: when task fails and LLM never called `remember_experience`, auto-saves a `[pitfall]` entry.
- Storage: `<filesDir>/memory/apps/<package>.md`, `user_prefs.md`, `device.md`. Entry caps: 30/app, 20/user_prefs, 10/device.
- Added `ToolName.RememberExperience` variant, `StandaloneAgentDef` allowedTools + Long-Term Memory system prompt section.

**Why:**
- Let the agent learn from experience across sessions. App-specific quirks, pitfalls, and verification strategies persist as markdown and are recalled when the same app is foregrounded.

**Key files:** `memory/MemoryStore.kt`, `memory/MemoryRecaller.kt`, `tool/impl/RememberExperienceTool.kt`, `agent/Agent.kt`, `agent/TurnPlanningPhaseRunner.kt`, `agent/cognition/prompt/PromptBuilder.kt`, `agent/definition/StandaloneAgentDef.kt`, `session/SessionServices.kt`, `tool/ToolName.kt`

**Design:** `doc/todo/0.5_memory/final/design.md`

## 2026-03-10: OpenClaw Family Common Capability Analysis

**What changed:**
- Added a new comparative analysis doc at `doc/todo/0.5_openclaw/common/common_capabilities_analysis_cn_codex.md`.
- Distinguished core OpenClaw-family runtimes from adjacent ecosystem projects in `.reference/claws/`.
- Summarized the shared capability stack across OpenClaw variants: ingress surfaces, sessioned runtime, tools/execution, memory/workspace, autonomy/scheduling, ops, and security boundaries.
- Mapped platform boundaries into three buckets: desktop/cloud-specific strengths, Android-portable capabilities, and mobile-native advantages that desktop/cloud agents do not naturally own.

**Why:**
- Provide a clearer product and architecture frame for deciding what ClosePaw should absorb from OpenClaw-family systems versus what should be reinterpreted natively for a phone-first agent.

**Key files:** `doc/todo/0.5_openclaw/common/common_capabilities_analysis_cn_codex.md`, `doc/changelog.md`

## 2026-03-09: Perception High-Fidelity Capture and Text Targeting Alignment (`f23287d`)

**What changed:**
- `Perceptor` capture now keeps raw `text`, `description`, and `hintText` without capture-time whitespace normalization.
- Prompt `text` now reflects visible/accessibility text semantics only (`text -> description -> hintText`) and no longer falls back to `resourceId` suffix.
- Added downstream-only `normalizeForMatching()` and used it for `text_index` / `desc_index` grouping and `TargetResolver` text lookup.
- `TargetResolver` now prioritizes prompt-text semantics first, then falls back to `description` / `hintText` when needed.
- Added dedicated perception/targeting tests, a new `doc/main/infra/perception.md` SOTA doc, and a perception-specific design note at `doc/autotune/round_14/percetion_fidelity_codex.md`.

**Why:**
- Preserve accessibility observations as source-of-truth and avoid irreversible capture-time rewriting.
- Fix drift between what the model sees in prompt JSON and what `text` targeting can actually resolve.

**Key files:** `Perceptor.kt`, `PerceptorInternals.kt`, `TargetResolver.kt`, `PerceptorTest.kt`, `TargetResolverTest.kt`, `doc/main/infra/perception.md`, `doc/autotune/round_14/percetion_fidelity_codex.md`

## 2026-03-09: Autotune Rounds 10-14 — qwen3.5 Targeted Tuning (0→20/22)

**What changed:**
- System prompt (`StandaloneAgentDef.kt`): added cross-app destination rule (#10), strengthened Information mode anti-hallucination, expanded Completion section with file-ops verification + date verification + scratchpad cross-checking. Later softened anti-hallucination to defer to app skill guidance.
- Perception (`PerceptorInternals.kt:236`): changed `MULTI_NEWLINE` replacement from `"\n"` to `"\n\n"` to preserve paragraph breaks in a11y tree.
- Debug logging (`TurnPlanningPhaseRunner.kt:158`): added `Log.d` for app skill lookup results.
- New/updated app skills:
  - `com.simplemobiletools.calendar.pro` — NumberPicker scroll, 24h format, date verification
  - `code.name.monkey.retromusic` — Songs tab add flow
  - `org.tasks` — diff-based completion detection, overflow menu location, priority turn budget, date reasoning example, partial-answer guidance
  - `de.dennisguse.opentracks` — Edit-based activity type checking with smart name filtering
- Eval config (`default.yaml`): added `max_turns: 50` for SportsTracker tasks + TasksHighPriorityTasks.

**Why:**
- Targeted tuning of 22 tasks that failed in R8/R9 (qwen3.5 model). Improved from 0/22 to 20/22 (90.9%) across 5 rounds.

**Key files:** `StandaloneAgentDef.kt`, `PerceptorInternals.kt`, `TurnPlanningPhaseRunner.kt`, `app/src/main/assets/app_skills/`, `eval/config/default.yaml`

## 2026-03-07: Add `/prompt-tune` Skill

**What changed:**
- New skill at `.claude/skills/prompt-tune/` with `SKILL.md` and `references/ownership_model.md`.
- Encodes the three-layer ownership model from the Round 4 prompt refactor design: core system prompt → tool descriptions → app skills.
- Provides a 5-step workflow: classify ownership → read target → apply change → anti-pattern check → validate.
- Includes a decision tree for ownership classification and an anti-pattern table.
- Absorbed `llm_best_practices.md` content (was a dead reference under cog-tune).
- Updated `/cog-tune` SKILL.md: replaced "External best practices" section with "Related skills" pointing to `/prompt-tune`.

**Why:**
- Separates diagnosis (`/cog-tune`) from treatment (`/prompt-tune`). Cog-tune analyzes traces and classifies root causes; prompt-tune applies the actual prompt/tool-desc/app-skill changes with ownership guardrails.

**Key files:** `.claude/skills/prompt-tune/SKILL.md`, `.claude/skills/prompt-tune/references/ownership_model.md`, `.claude/skills/cog-tune/SKILL.md`

## 2026-03-07: Fix Unchanged-Fallback Double-Click (`2042beb`)

**What changed:**
- `PointActionExecutorCore.buildPointActionOutcome()` no longer treats `Unchanged` as channel failure. Returns `ActionOutcome.Success` with `verified=false` and warning instead.
- No automatic fallback to next channel (e.g. `gesture_tap`) when `node_action_click` succeeds but screen content stays the same.
- Updated `mobile_action.md` pipeline docs and "Accepted but unchanged" semantics.

**Why:**
- When a click succeeds but the screen content happens to stay the same (e.g. random number repeats), the old logic fell through to `gesture_tap` causing a spurious second click. Observed in BrowserMultiply A11Y T16: 4th button click repeated the same number → extra click overwrote the 5th number.

**Key files:** `PointActionExecutorCore.kt`, `ClickExecutorTest.kt`, `doc/main/infra/tool/mobile_action.md`

## 2026-03-06: Provider Base URL Override (Local Proxy Support)

**What changed:**
- Added `OPENAI_BASE_URL` support in `.env` to route OPENAI-provider models through a local proxy without modifying `llm_models.json`
- New `ModelCatalog.withBaseUrlOverrides()` applies provider-level base URL overrides at session bootstrap via `__BASE_URL_<PROVIDER>` convention in the `apiKeys` map
- Full intent chain: `.env` → `debug-run.sh` / eval runner → intent extra → `AppSettingsState` → `SessionLlmBootstrapper` → `ModelCatalog` → client creation
- Eval runner (`native_agent_bridge.py`) now forwards `openai_base_url` intent extra
- Enabled `android:usesCleartextTraffic="true"` in manifest for HTTP proxy connections

**Why:**
- Route gpt-5.4/gpt-5.2 through a local OpenAI-compatible proxy (e.g. for quota management) as a script-level config, not baked into the model catalog

**Key files:** `session/SessionLlmBootstrapper.kt`, `llm/ModelCatalog.kt`, `app/AppSettingsState.kt`, `app/MainActivityIntentPayload.kt`, `eval/aw_bridge/native_agent_bridge.py`, `eval/aw_bridge/runner.py`, `scripts/debug-run.sh`, `AndroidManifest.xml`

## 2026-03-06: BrowserMultiply Eval — Two New Click Issues

**What changed:**
- Documented two new click failure patterns from BrowserMultiply eval runs (A11Y: `20260306_230038`, VD: `20260306_232810`):
  1. **First-click-after-launch**: `node_action_click` on Files RecyclerView item returns `true` but UI unchanged after 1800ms. Same click succeeds after other interactions (long_press → context menu). Reproducible in both A11Y and VD modes. Root cause unknown.
  2. **Unchanged-fallback double-click**: When a click succeeds but screen content stays the same (e.g. random number repeats), `UiChangeDetector` sees `Unchanged` → executor falls through to `gesture_tap` → extra click. Caused BrowserMultiply to lose the 5th number.
- Planned fix for #2: treat `Unchanged` as warning only, not channel failure.

**Why:**
- Both issues cause BrowserMultiply to fail (MaxTurnsReached at 30). Issue #2 is actionable — removing the fallback-on-unchanged behavior avoids the double-click. Issue #1 is a deeper Files RecyclerView quirk that needs further investigation.

**Key files:** `doc/main/infra/tool/mobile_action.md`, eval results `eval/results/20260306_230038/`, `eval/results/20260306_232810/`

## 2026-03-06: VD Click Transport Experiment (`edb4acd`, `55976dd`)

**What changed:**
- Added Shizuku injection and display-id targeting to `DebugActionExecutor` and `action-test.sh` for isolated transport testing.
- Ran 2x2 agent-loop matrix (A11Y/VD × node_click-first/gesture_tap-first) plus 8 isolated action-debug tests and secondary display smoke test on Files RecyclerView.
- Updated `mobile_action.md` with corrected transport matrix — previous claim that VD `injectInputEvent` "works like real touch" was not supported by test data on this surface.

**Why:**
- Files RecyclerView is a known difficult surface where `dispatchGesture` false-succeeds. Needed to determine if Shizuku `injectInputEvent` was a viable alternative. Result: `node_action_click` is the only reliable channel for this surface (8/8). Shizuku false-succeeded (0/3), but this may be a test setup issue — most other app surfaces work fine with all transports. Current priority order `node_click → gesture_tap` confirmed correct.

**Key files:** `app/.../debug/DebugActionExecutor.kt`, `scripts/action-test.sh`, `doc/main/infra/tool/click_transport_experiment.md`, `doc/main/infra/tool/mobile_action.md`

## 2026-03-06: Harden Post-Action Change Detection (`5ee310a`)

**What changed:**
- Excluded `isFocused` from `UiChangeDetector` fingerprint — RecyclerView items gain focus on `ACTION_CLICK` without actually navigating, causing false-positive "Changed" verdicts.
- Extended `PostActionAnalysis` verify window from 800ms (300+500) to 1800ms (300+500+1000) with a third retry round for slow transitions like intent resolution.
- Documented `gesture_tap` false-success pattern on Files RecyclerView items: `dispatchGesture()` accepted but UI unchanged. This is a platform limitation, not a runtime bug.

**Why:**
- The two bugs compounded: the detector reported success on the first channel (node click + isFocused false positive), so the runtime never fell through to retry or fallback. With both fixes, node click now correctly retries and succeeds within the 1800ms window.

**Key files:** `app/.../tool/action/UiChangeDetector.kt`, `app/.../tool/action/PostActionAnalysis.kt`, `doc/main/infra/tool/mobile_action.md`

## 2026-03-06: Click Hotspot Selection Fix (`04618f3`)

**What changed:**
- `refinePointActionTarget()` now searches for the nearest actionable child within a promoted container instead of defaulting to `container.center`.
- Added `findBestActionableChild()` with 80% area threshold and distance-based scoring.
- Added diagnostic logging for target promotion decisions.

**Why:**
- Files app regression: `container.center` landed on a dead zone where `ACTION_CLICK` was accepted but had no effect. The icon hotspot worked. Fix generalizes to any compound row without app-specific workarounds.

**Key files:** `app/src/main/kotlin/ai/closepaw/tool/action/PointActionExecutorCore.kt`

## 2026-03-06: Local Parallel Eval Workflow (`68d1f88`..`456d3aa`)

**What changed:**
- Hardened `eval/aw_bridge/parallel_runner.py` so the supervisor owns one-time APK build/install, honors `runner.perform_bridge_setup`, and merges results back into `eval/results/<run_id>/`.
- Added `scripts/eval_parallel.sh` as the standard local 2-device entry point for `AndroidWorldAvd` (`emulator-5554`) and `AndroidWorldAvd2` (`emulator-5556`).
- Updated eval docs plus `/autotune` and `/cog-tune` guidance to use the standard result contract and the new local parallel workflow.

**Why:**
- Cut eval wall-clock time with a real local parallel path without creating a second result format or breaking downstream tooling such as `scoreboard.py` and eval analysis flows.

## 2026-03-06: Prompt Ownership Refactor (`02844a5`)

**What changed:**
- Added asset-backed app skills under `app/src/main/assets/app_skills/` and load them per turn from the current foreground package.
- Injected the active app skill into the prompt between Working Memory and Observation.
- Rewrote the standalone and planner system prompts around cross-tool policy instead of app/tool-specific appendices.
- Expanded tool descriptions so `mobile_action`, `open_app`, `shell`, and `complete_task` own their local semantics.

**Why:**
- Separate global behavior, tool semantics, and app-specific knowledge so tuning changes land in one clear owner layer instead of accumulating in one monolithic prompt.

**Key files:** `app/src/main/kotlin/ai/closepaw/agent/cognition/prompt/AppSkillRepository.kt`, `app/src/main/kotlin/ai/closepaw/agent/TurnPlanningPhaseRunner.kt`, `app/src/main/kotlin/ai/closepaw/agent/definition/StandaloneAgentDef.kt`, `app/src/main/kotlin/ai/closepaw/agent/definition/PlannerAgentDef.kt`, `app/src/main/assets/app_skills/`
