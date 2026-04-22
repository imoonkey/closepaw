# Rename: Android Agent → ClosePaw

## Goal

Rename the product from "Android Agent" / `com.moonkey.androidagent` to **ClosePaw** /
`ai.closepaw`. The domain `closepaw.ai` is registered; the reverse-DNS `ai.closepaw`
becomes the new Android package and application ID.

Pre-release product → no backward-compat shims, no install upgrades from the old
`applicationId`, no legacy aliases. One clean sweep.

## Scope decisions (non-negotiable)

1. **Kotlin package root**: `com.moonkey.androidagent.*` → `ai.closepaw.*`
   - Drop the `.androidagent` suffix entirely. Root *is* the product name.
   - Drop the `com.moonkey.` vendor prefix. It was a placeholder; ClosePaw is the vendor.
2. **`applicationId`**: `com.moonkey.androidagent` → `ai.closepaw`
   - New app identity on device. Uninstall old, install new. No migration.
3. **App display name** (`strings.xml/app_name`): `Android Agent` → `ClosePaw`.
4. **Gradle root project** (`settings.gradle.kts`): `AndroidAgent` → `ClosePaw`.
5. **Theme**: `Theme.AndroidAgent[.FullScreen]` → `Theme.ClosePaw[.FullScreen]`.
6. **Intent action strings**: `com.moonkey.androidagent.ACTION_*` → `ai.closepaw.ACTION_*`.
7. **Scripts/eval**: all `PACKAGE=` constants, YAML `package_name`/`activity`,
   Python path constants, pyproject name (`android-agent-eval` → `closepaw-eval`),
   remote env file (`~/.android-agent-env` → `~/.closepaw-env`),
   remote sync target (`~/androidagent` → `~/closepaw`).
8. **Historical docs are frozen**. Do NOT rewrite:
   - `doc/archive/**`
   - `doc/autotune/round_*/**`
   - `doc/todo/**/final/**`, `**/initial/**`, any dated snapshots or completed review trees
   - `eval/results/**` worker configs
   These are point-in-time records. Rewriting them destroys evidence and churns thousands
   of files for no code benefit. The *current* code carries the new name; the archive
   documents what the product was called at that time.
9. **Active docs do get updated**: `CLAUDE.md`, `AGENTS.md` (symlink — no change),
   `doc/main/**`, `doc/dev/**`, `doc/PROGRESS.md`, `README.md` if present, active
   `doc/todo/tasks.json` entries, `eval/README.md`.
10. **Out of scope** (user action, separate follow-ups):
    - Workspace directory rename `…/androidagent` → `…/closepaw` on disk.
    - Git repo rename on GitHub.
    - Launcher icon redesign (ic_launcher / ic_launcher_round).
    - Website at closepaw.ai.
    - Privacy policy / legal copy.

## Approach

Mechanical migration in a single branch, one commit per phase so that any phase can be
bisected or reverted independently. Each phase ends with a green `./gradlew clean
assembleDebug lint test` (or phase-appropriate check). No half-landed state.

Key move: **`git mv` the package directory trees before rewriting package statements**,
so Git tracks the rename as a rename (not delete+add) and blame survives.

## Components

### Changes
- `settings.gradle.kts` — `rootProject.name`
- `app/build.gradle.kts` — `namespace`, `applicationId`
- `app/src/main/AndroidManifest.xml` — `android:theme`
- `app/src/main/res/values/strings.xml` — `app_name`
- `app/src/main/res/values/themes.xml` — style names `Theme.AndroidAgent*`
- `app/src/{main,test,debug,release}/kotlin/com/moonkey/androidagent/**` — moved to
  `ai/closepaw/**`; `package` + `import` statements rewritten
- `app/src/main/assets/app_skills/**/SKILL.md` — audit for any hardcoded package refs
  (unlikely, but check)
- `scripts/setup.sh`, `scripts/debug-run.sh`, `scripts/action-test.sh`,
  `scripts/eval_parallel.sh`, `scripts/token_counts.py` — `PACKAGE` and path constants
- `scripts/remote/provision.sh`, `scripts/remote/sync.sh` — env file + remote dir
- `eval/config/*.yaml` — `package_name`, `activity`
- `eval/pyproject.toml` — project `name`
- `eval/README.md`, `doc/main/**`, `doc/dev/**`, `doc/PROGRESS.md`, `CLAUDE.md`
- Active `doc/todo/tasks.json` entries referencing the old package
- `.env.example` if it mentions the package (spot-check)

### Not changed
- `com.openai.*`, `ai.liquid.leap.*`, `dev.rikka.shizuku.*`, `moe.shizuku.*`,
  `org.lsposed.*` third-party packages — untouched.
- Class names containing "Android" that describe Android platform concepts
  (`AndroidManifest`, `AccessibilityPlatform`, etc.) — untouched.
- `OpenAIOAuth`, `OpenAiSignIn` — product "Open AI", not related.
- Historical docs (see Scope #8).

## Interactions

### Rename state machine (one branch, phase-gated)

```
 [main] ──► phase0-audit ──► phase1-gradle ──► phase2-kotlin ──► phase3-resources
                                                                       │
                                                                       ▼
 phase7-merge ◄── phase6-verify ◄── phase5-docs ◄── phase4-scripts-eval
```

Each phase must end green:

| Phase | Gate |
|-------|------|
| 0 | audit output committed to `doc/todo/rename-closepaw/audit.md` |
| 1 | `./gradlew tasks` succeeds with new rootProject name |
| 2 | `./gradlew assembleDebug` succeeds with new package tree |
| 3 | App installs, launches, shows "ClosePaw" label; theme resolves |
| 4 | `scripts/debug-run.sh "Open Settings"` works end-to-end on device |
| 5 | `grep -rni 'android agent\|androidagent' doc/main doc/dev CLAUDE.md` returns empty |
| 6 | Full `./gradlew clean assembleDebug lint test` + eval smoke (1 task) green |
| 7 | PR merged |

### Sed rewrite rules (exact)

Applied only to files under version control, excluding `build/`, `.cxx/`, `.gradle/`,
`doc/archive/`, `doc/autotune/`, `eval/results/`, and any path matching
`doc/todo/**/final/**` or `doc/todo/**/initial/**`.

```
com.moonkey.androidagent        → ai.closepaw
com/moonkey/androidagent        → ai/closepaw
Theme.AndroidAgent              → Theme.ClosePaw
Theme\.AndroidAgent             → Theme.ClosePaw
AndroidAgent (rootProject/theme context only, NOT in prose)  → ClosePaw
android-agent-eval              → closepaw-eval
~/.android-agent-env            → ~/.closepaw-env
~/androidagent                  → ~/closepaw
/tmp/androidagent_eval_parallel → /tmp/closepaw_eval_parallel
"Android Agent" (app_name/user-facing label)  → "ClosePaw"
```

Prose occurrences of "Android agent" meaning *an agent that controls Android* are
ambiguous and must be reviewed by hand in phase 5 — do not blanket-replace in Markdown.

## Tasks

Each task: slug · scope · acceptance · deps.

### rename-closepaw/0-audit
- Scope: none (read-only)
- Produce `doc/todo/rename-closepaw/audit.md` listing:
  - Every file matching the sed rules above, grouped by phase.
  - Every ambiguous prose "Android agent" site (for manual review in phase 5).
  - Confirmation that `grep -rn '^package com\.moonkey\.' app/src` has zero
    non-`androidagent` subpackages (already verified: none exist).
- Acceptance: audit file committed; reviewer signs off on scope.
- Deps: none.

### rename-closepaw/1-gradle-identity
- Scope: `settings.gradle.kts`, `app/build.gradle.kts`
- Changes:
  - `rootProject.name = "ClosePaw"`
  - `namespace = "ai.closepaw"`
  - `applicationId = "ai.closepaw"`
- Acceptance: `./gradlew tasks` runs; `./gradlew :app:assembleDebug` still builds
  (Kotlin sources still have old package — manifest namespace mismatch will surface
  in phase 2, which is fine; this phase only proves Gradle config parses).
- Deps: 0.

### rename-closepaw/2-kotlin-package
- Scope: `app/src/{main,test,debug,release}/kotlin/com/moonkey/androidagent/**`
- Steps:
  1. `git mv app/src/main/kotlin/com/moonkey/androidagent  app/src/main/kotlin/ai/closepaw`
     (and same for `test`, `debug`, `release`).
  2. Remove now-empty `com/moonkey/` dirs.
  3. Rewrite all `package com.moonkey.androidagent` → `package ai.closepaw` and all
     `import com.moonkey.androidagent` → `import ai.closepaw` (Kotlin files only).
  4. Rewrite intent action literal strings `com.moonkey.androidagent.ACTION_*` →
     `ai.closepaw.ACTION_*` wherever they appear (Kotlin + manifest + scripts).
- Acceptance: `./gradlew assembleDebug lint test` green. `grep -rn
  'com\.moonkey\.androidagent\|com/moonkey/androidagent' app/src` returns empty.
- Deps: 1.

### rename-closepaw/3-resources-manifest
- Scope: `app/src/main/AndroidManifest.xml`, `res/values/strings.xml`,
  `res/values/themes.xml`
- Changes:
  - `app_name = "ClosePaw"`
  - Rename styles `Theme.AndroidAgent` → `Theme.ClosePaw` (both main and `.FullScreen`).
  - Update manifest `android:theme="@style/Theme.ClosePaw"`.
- Acceptance: APK installs, launches, shows **ClosePaw** in launcher; no theme
  resolution errors in logcat.
- Deps: 2.

### rename-closepaw/4-scripts-eval
- Scope: `scripts/**`, `eval/config/*.yaml`, `eval/pyproject.toml`, `eval/README.md`
- Changes:
  - `PACKAGE="ai.closepaw"` in `setup.sh`, `debug-run.sh`, `action-test.sh`.
  - `ACTION_INTENT="ai.closepaw.ACTION_DEBUG_EXEC"` in `action-test.sh`.
  - `scripts/token_counts.py` path constants point at new tree.
  - `scripts/eval_parallel.sh` tmp-log prefix.
  - `scripts/remote/provision.sh`: env file → `~/.closepaw-env`; bashrc guard string.
  - `scripts/remote/sync.sh`: `REMOTE_DIR="~/closepaw"`.
  - All `eval/config/*.yaml`: `package_name: ai.closepaw`,
    `activity: ai.closepaw/.app.MainActivity`.
  - `eval/pyproject.toml`: `name = "closepaw-eval"`.
- Acceptance: `scripts/setup.sh` succeeds on a connected device; `scripts/debug-run.sh
  "Open Settings"` runs one full agent turn.
- Deps: 3.

### rename-closepaw/5-active-docs
- Scope: `CLAUDE.md`, `doc/main/**`, `doc/dev/**`, `doc/PROGRESS.md`, `eval/README.md`,
  any active `doc/todo/tasks.json` entry mentioning the old package.
- Changes:
  - Replace `com.moonkey.androidagent` with `ai.closepaw`.
  - Replace product-name mentions ("Android Agent" as product label) with "ClosePaw".
  - Leave intact phrases where "Android" describes the platform (e.g., "Android
    accessibility service", "AI-powered Android automation" → "AI-powered Android
    automation using accessibility services" can stay; rephrase top-of-file one-liner
    in CLAUDE.md to "ClosePaw — AI-powered Android automation…").
  - Update `settings.gradle.kts` refs in docs.
- Acceptance: `grep -rni 'android agent\|androidagent' CLAUDE.md doc/main doc/dev
  doc/PROGRESS.md eval/README.md` returns zero. Reviewer confirms prose still reads
  correctly (no "ClosePaw Manifest" nonsense).
- Deps: 4.

### rename-closepaw/6-verify
- Scope: none (verification only)
- Steps:
  1. `./gradlew clean assembleDebug lint test` — green.
  2. `scripts/setup.sh` on a real device — green.
  3. `scripts/debug-run.sh "Open Settings"` — green (agent completes a turn).
  4. Run one eval task end-to-end: `eval/run.py …` with a trivial task — green.
  5. `adb shell pm list packages | grep closepaw` shows `ai.closepaw`.
  6. Launcher shows **ClosePaw** label.
- Acceptance: all six checks pass; screenshots + logs committed to
  `doc/todo/rename-closepaw/verify.md`.
- Deps: 5.

### rename-closepaw/7-merge
- Scope: PR hygiene.
- Steps: squash-optional commit per phase; PR title `refactor: rename to ClosePaw
  (ai.closepaw)`; description links this design doc.
- Acceptance: merged to `main`.
- Deps: 6.

## Trade-offs

1. **Why `ai.closepaw` and not `ai.closepaw.app` or `ai.closepaw.android`?**
   - Shorter, matches the domain directly. Sub-packaging (`.app`, `.agent`, `.tool`,
     etc.) already lives below the root. A single-segment product root is the
     convention for first-party apps (e.g., `com.whatsapp`, `com.slack`).
   - Alternative considered: `com.closepaw.android`. Rejected — `com.` vendor prefix is
     legacy, and `.android` is redundant since the `applicationId` only targets Android
     packages by definition.

2. **Why not preserve `applicationId` to allow in-place upgrade?**
   - Pre-release, no user base, global rule forbids backcompat hacks. A clean identity
     now avoids a second rename later. The install-side cost is `adb uninstall
     com.moonkey.androidagent && adb install app-debug.apk` — trivial.

3. **Why freeze historical docs?**
   - They are dated snapshots: autotune round analyses, past design reviews, QA
     reports. Rewriting them either (a) falsifies the record of what the product was
     called when the work happened, or (b) spreads the rename across ~3k+ files with
     zero code impact. The old name is part of the project's history; the current code
     owns the new name. Anyone reading a 2025/2026 round analysis understands the app
     has since been renamed — a single note at the top of `doc/archive/README.md` (or
     `doc/autotune/README.md`) can make that explicit if needed.
   - Alternative considered: rewrite everything. Rejected — pure churn.

4. **Why phase-per-commit instead of one giant commit?**
   - Bisect-ability. If a phase breaks something subtle (theme resolution on a specific
     Android version, a hardcoded string somewhere the sed rule missed), `git bisect`
     pinpoints the phase immediately. A 8000-line mega-diff is unbisectable.
   - Cost is low: seven commits, one branch, one PR.

5. **Why `git mv` the directory tree before editing package lines, instead of a
   find/sed-everything-in-place?**
   - Preserves `git blame` / `git log --follow` across the rename. Kotlin IDE
     refactoring would do this too, but it's not scriptable across four source sets and
     a fresh checkout from CI. A shell one-liner (`git mv …` + `find … -exec sed -i ''
     's/…/…/g'`) is reproducible and reviewable.

## Self-review

- Covers Gradle identity ✓
- Covers Kotlin package tree ✓
- Covers resources (theme, label) ✓
- Covers manifest intent-filter / theme refs ✓
- Covers scripts + eval ✓
- Covers active docs ✓
- Freezes historical docs with explicit rationale ✓
- Verification gate includes a real-device smoke and an eval smoke ✓
- Explicit out-of-scope list prevents scope creep ✓
- Ambiguous prose "Android agent" → manual review in phase 5, not blanket-replaced ✓
- Each phase has a build-green gate, so no broken-windows intermediate state ✓

Open question (defer to reviewer):
- **Launcher icon**: the mipmap `ic_launcher*` is kept as-is. Should phase 3 swap in a
  ClosePaw icon, or leave that to a separate branding task? Recommendation: separate
  task. Renaming and rebranding are orthogonal; a bad placeholder icon shouldn't block
  the rename merge.
