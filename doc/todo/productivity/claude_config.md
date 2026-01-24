# AI Coding Tool Configuration Design

**Status**: Implemented  
**Created**: 2025-01-23  
**Updated**: 2025-01-23  
**Goal**: Adopt high-impact configurations from [everything-claude-code](https://github.com/affaan-m/everything-claude-code) adapted for Android Agent development.

## Revision History

| Date | Changes |
|------|---------|
| 2025-01-23 | Initial design |
| 2025-01-23 | Added: doc-update skill, architect agent, coding-standards skill, /update-docs command. Updated workflow to include doc sync before commits. |
| 2025-01-23 | **IMPLEMENTED**: All P0-P3 items created. See `.claude/` directory. |

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Meta-Analysis: What Matters Most](#meta-analysis-what-matters-most)
3. [The 80/20 Extraction](#the-8020-extraction)
4. [Minimal Configuration Set](#minimal-configuration-set)
5. [Recommended Workflow](#recommended-workflow)
6. [What We're Missing: TDD and Beyond](#what-were-missing-tdd-and-beyond)
7. [Implementation Plan](#implementation-plan)
8. [Setup: Cross-Tool Symlinks](#setup-cross-tool-symlinks)

---

## Executive Summary

We analyzed the `everything-claude-code` reference repository (production configs from an Anthropic hackathon winner) to extract the highest-impact configurations for our Android Agent development workflow.

**Key Insight**: Skills > Commands > Agents > CLAUDE.md is the priority order for impact. Most TypeScript-specific hooks and redundant rules can be skipped.

**Outcome**: A minimal set of 5-6 skills, 4 agents, and 5 commands that capture ~80% of the value while being adapted to Android/Kotlin context.

---

## Meta-Analysis: What Matters Most

### Component Hierarchy

| Component | How It Works | Effectiveness |
|-----------|--------------|---------------|
| **Skills** | Procedural knowledge auto-discovered and applied | **Highest** - Modern best practice |
| **Agents** | Specialized subagents for delegation | **High** - Focuses context on specific tasks |
| **Commands** | Quick shortcuts to invoke workflows | **High** - Reduces repetitive prompting |
| **CLAUDE.md** | Project-level critical rules | **Medium-High** - Always loaded context |
| **Contexts** | Mode-switching (dev vs review) | **Medium** - Nice for workflow stages |
| **Rules** | Modular conventions (`.claude/rules/`) | **Medium** - Superseded by skills |
| **Hooks** | Event-triggered automation | **Low-Medium** - Powerful but complex |

### Skills vs Rules (Modern Best Practice)

**Skills** are the modern replacement for rules:
- Auto-discovered by Claude when relevant
- Progressive disclosure (lightweight index → detailed docs on demand)
- Support custom slash commands
- Package procedural workflows, not just guidelines

**Rules** are older practice:
- Always loaded (wastes context window)
- Static guidelines rather than workflows
- Useful for project conventions but less dynamic

**Recommendation**: Invest in skills, use CLAUDE.md for critical rules, skip separate rules files.

---

## The 80/20 Extraction

### From Reference Repo

**High-Value (Keep)**:
1. Verification Loop Skill - Quality gates before PR
2. Strategic Compact Skill - Context preservation at task boundaries
3. Doc Update Skill - Keep docs in sync before commits
4. Planner Agent - Structured planning before coding
5. Code Reviewer Agent - Systematic review process
6. Build Error Resolver Agent - Specialized build fixing
7. Architect Agent - System design and ADRs
8. Commands: `/plan`, `/verify`, `/build-fix`, `/update-docs`
9. Project CLAUDE.md - Consolidated critical rules

**Selectively Adopt**:
- TDD workflow skill - High value for core logic, skip for UI
- Coding standards skill - Principles universal, adapt examples for Kotlin

**Redundant (Skip)**:
- Separate rules files (consolidate into skills)
- Frontend/backend pattern skills (TypeScript-specific)
- Most hooks (TypeScript/Prettier-specific)
- E2E runner agent (web-specific)
- Continuous learning hooks (complex setup)
- Eval harness (overkill initially)
- `/orchestrate` command (overkill, manual agent invocation simpler)

### Current State vs Proposed State

| Our Current Practice | Reference Best Practice | Recommendation |
|---------------------|------------------------|----------------|
| `doc/main`, `doc/dev` context docs | Memory persistence hooks | **Keep ours** - simpler, works |
| `sop/` folder with procedures | Skills folder with SKILL.md | **Migrate** SOPs to skill format |
| Manual design → code → review | `/plan` → code → `/verify` → `/code-review` | **Adopt** command workflow |
| `development.md` debugging guide | Build-error-resolver agent | **Add** specialized agent |
| No project-level AI config | CLAUDE.md in project root | **Add** CLAUDE.md |

---

## Minimal Configuration Set

### Tier 1: Must-Have (Biggest Impact)

#### 1. Project CLAUDE.md

Single file in repo root containing:
- Project overview (what Android Agent does)
- Critical coding rules (Android/Kotlin conventions)
- File structure reference
- Build/test commands (`./gradlew`, `./scripts/`)
- Links to `doc/main/` for architecture

#### 2. Core Skills

| Skill | Purpose | Source |
|-------|---------|--------|
| `verification-loop` | Build → Lint → Test → Security before commits | Reference + Gradle adaptation |
| `doc-update` | Sync docs with code changes before commits | `sop/doc_update.md` + reference doc-updater |
| `planning-workflow` | Structured planning with risk assessment | `sop/planning.md` + reference |
| `code-review-workflow` | Systematic review checklist | `sop/diff_review.md` + reference |
| `coding-standards` | Android/Kotlin coding conventions and patterns | Reference (adapted from TypeScript) |

#### 3. Core Agents

| Agent | Purpose | Adaptation |
|-------|---------|------------|
| `planner` | Creates implementation plans, waits for confirmation | Minimal - language-agnostic |
| `build-error-resolver` | Fixes Gradle/Kotlin build errors | Heavy - Android patterns |
| `code-reviewer` | Systematic code review with severity checklist | Medium - Android-specific checks |
| `architect` | System design, trade-offs, ADRs | Medium - Android architecture patterns |

#### 4. Core Commands

| Command | Purpose |
|---------|---------|
| `/plan` | Invoke planner agent, create step-by-step plan |
| `/verify` | Run verification loop (build, lint, test) |
| `/build-fix` | Fix build errors incrementally |
| `/update-docs` | Sync documentation with code changes |
| `/tdd` | Test-driven development workflow (selective use) |

### Tier 2: Nice-to-Have

- **Strategic Compact Skill**: Suggests context compaction at logical boundaries
- **TDD Workflow Skill**: For core logic (state machines, data transformations)

### Tier 3: Skip for Now

- Hooks (except session-start for context loading)
- Continuous learning hooks
- Eval harness
- `/orchestrate` command (overkill for single developer)
- All TypeScript/web-specific content

---

## Recommended Workflow

### Standard Development Cycle

```
1. /plan "Implement feature X"
   → Planner agent creates structured plan
   → User confirms or modifies
   → (Optional) /tdd for core logic components

2. Code implementation
   → Reference CLAUDE.md for conventions
   → Skills auto-apply (review checklist, etc.)

3. /verify
   → Runs: ./gradlew assembleDebug → lint → test
   → Reports: READY/NOT READY for commit

4. /build-fix (if verify fails)
   → Build-error-resolver fixes incrementally
   → Re-runs /verify

5. /update-docs (before commit)
   → Sync doc/main if architecture changed
   → Sync doc/dev if development workflow changed
   → Update relevant active project docs
   → Verify doc links and examples

6. Review & Commit
   → Code reviewer agent or diff_review SOP
   → Commit with conventional message
```

### Workflow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                     DEVELOPMENT CYCLE                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────┐     ┌──────────┐     ┌─────────┐     ┌──────────┐ │
│  │  /plan  │ ──► │   Code   │ ──► │ /verify │ ──► │  PASS?   │ │
│  └─────────┘     └──────────┘     └─────────┘     └──────────┘ │
│       │               │                                │       │
│       │               │                           YES  │  NO   │
│       ▼               ▼                                ▼       │
│  ┌─────────┐     ┌──────────┐                    ┌──────────┐  │
│  │  /tdd   │     │  Manual  │                    │/build-fix│  │
│  │(optional│     │  Test    │                    └──────────┘  │
│  └─────────┘     └──────────┘                         │        │
│                                                       │        │
│                                                       ▼        │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    /update-docs                          │   │
│  │  • Sync doc/main (if architecture changed)              │   │
│  │  • Sync doc/dev (if workflow changed)                   │   │
│  │  • Update active project docs                           │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                  │
│                              ▼                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    Review & Commit                       │   │
│  │  • Code reviewer agent (or diff_review SOP)             │   │
│  │  • git commit with conventional message                 │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### When to Use Each Command

| Command | When to Use |
|---------|-------------|
| `/plan` | Starting new feature, complex bugfix, architectural change |
| `/tdd` | Core logic, state machines, data transformations |
| `/verify` | Before any commit, after significant changes |
| `/build-fix` | When `/verify` fails with build errors |
| `/update-docs` | Before commit, after any user-facing or architectural change |

---

## What We're Missing: TDD and Beyond

### Should We Adopt TDD?

**Short answer**: Yes, selectively. TDD provides high value for specific scenarios.

**TDD Workflow Overview** (from reference):
```
1. Write user journeys / acceptance criteria
2. Generate test cases (they should FAIL)
3. Implement minimal code to pass tests
4. Refactor while keeping tests green
5. Verify 80%+ coverage
```

**When TDD is High-Value for Android Agent**:

| Scenario | TDD Value | Why |
|----------|-----------|-----|
| Core agent logic (state machines, orchestration) | **High** | Complex logic, easy to break |
| Data transformation utilities | **High** | Pure functions, deterministic |
| Protocol handlers | **High** | Contract-driven, testable |
| UI components | **Low** | Visual testing harder, manual verification ok |
| Integration with Android system | **Low** | Mocking complex, e2e testing better |

**Recommended TDD Adoption**:

1. **Add `/tdd` command** for when TDD is explicitly wanted
2. **Add TDD workflow skill** that activates for core logic changes
3. **Don't make it mandatory** - keep manual testing for UI/integration

**TDD Skill Adaptation for Android**:
```markdown
# TDD Workflow (Android/Kotlin)

## When to Activate
- Core agent orchestration logic
- State machine implementations
- Data utilities and transformations
- Protocol implementations

## Workflow
1. Write test cases in JUnit/MockK
2. Run: ./gradlew test (should fail)
3. Implement minimal code
4. Run: ./gradlew test (should pass)
5. Refactor, keep tests green
6. Verify coverage: ./gradlew jacocoTestReport
```

### Other Valuable Practices We're Missing

#### 1. Strategic Compaction (Medium-High Value)

**What it is**: Suggest manual `/compact` at logical task boundaries instead of relying on auto-compaction.

**Why it matters**: 
- Auto-compaction triggers at arbitrary points (mid-task)
- Strategic compaction preserves important context
- Compact after planning, after debugging, at phase transitions

**Recommendation**: Add as Tier 1 skill.

#### 2. Memory Persistence Hooks (Medium Value)

**What it is**: Automatically save/load session context.

**Current alternative**: Our `doc/main` and `doc/dev` docs serve similar purpose manually.

**Recommendation**: Skip for now, our manual approach works.

#### 3. Continuous Learning (Low-Medium Value)

**What it is**: Auto-extract patterns from sessions into reusable skills.

**Why we skip**: Complex setup, high maintenance, marginal benefit initially.

**Recommendation**: Consider later when workflow is stable.

#### 4. Eval Harness (Low Value Initially)

**What it is**: Formal eval-driven development (EDD) - "unit tests for AI behavior".

**Why we skip**: Overkill for current project size. Valuable for teams/complex AI workflows.

**Recommendation**: Revisit when we have stable CI/CD.

### Summary: What to Add

| Practice | Priority | Why |
|----------|----------|-----|
| Doc update skill + `/update-docs` | **P1** | Keeps docs in sync, prevents drift |
| Strategic compaction skill | **P1** | Prevents context loss |
| TDD workflow (selective) | **P2** | High value for core logic |
| `/tdd` command | **P2** | Explicit TDD invocation |
| Code reviewer agent | **P2** | Complements existing SOP |
| Architect agent | **P3** | ADRs valuable for design decisions |
| Coding standards skill | **P3** | Universal principles, Android examples |
| Continuous learning | **P4** | Future consideration |
| Eval harness | **P4** | Future consideration |
| `/orchestrate` command | **P4** | Skip - manual agent invocation simpler |

---

## Implementation Plan

### Task Overview

| Priority | Task | Impact | Effort |
|----------|------|--------|--------|
| **P0** | Task 1: CLAUDE.md | High | Medium |
| **P0** | Task 2a: Verification-loop skill | High | Medium |
| **P1** | Task 2b: Doc-update skill | High | Medium |
| **P1** | Task 2c: Strategic-compact skill | High | Low |
| **P1** | Task 3a: Build-error-resolver agent | High | High |
| **P1** | Task 4a: /verify command | High | Low |
| **P1** | Task 4b: /update-docs command | High | Low |
| **P2** | Task 3b: Planner agent | Medium | Medium |
| **P2** | Task 3c: Code-reviewer agent | Medium | Medium |
| **P2** | Task 4c: /plan command | Medium | Low |
| **P2** | Task 2d: Code-review skill | Medium | Medium |
| **P2** | Task 2e: TDD workflow skill | Medium | Medium |
| **P2** | Task 4d: /tdd command | Medium | Low |
| **P3** | Task 3d: Architect agent | Medium | Medium |
| **P3** | Task 2f: Coding-standards skill | Medium | Medium |
| **P3** | Task 2g: Planning skill | Low | Low |
| **P3** | Task 4e: /build-fix command | Medium | Low |
| **P4** | Task 5: Contexts | Low | Low |
| **P4** | Task 6: Existing doc updates | Low | Low |

### Detailed Tasks

#### Task 1: Create Project CLAUDE.md
**File**: `androidagent/CLAUDE.md`  
**Description**: 
- Project overview from existing docs
- Android/Kotlin coding conventions
- Build commands (`./gradlew build`, `./scripts/setup.sh`)
- Link to `doc/main/` for architecture
- Critical rules (no hardcoded secrets, error handling, etc.)

#### Task 2: Create Skills

##### Task 2a: `verification-loop/SKILL.md`
Adapt for Gradle:
- `./gradlew assembleDebug` (build check)
- `./gradlew lint` (lint check)
- `./gradlew test` (unit tests)
- Check for hardcoded API keys
- Check for proper permission handling

##### Task 2b: `doc-update/SKILL.md`
Adapt from reference doc-updater + `sop/doc_update.md`:
- **When to activate**: Before any commit, after architecture/workflow changes
- **Doc priorities** (from existing SOP):
  - `doc/main`, `doc/dev`: Must stay up-to-date
  - `doc/todo/active_proj`: Reflect latest status
  - `doc/archive`: OK if outdated
- **Update checklist**:
  - If architecture changed → update `doc/main/agent_infra.md`, `doc/main/agent_protocol.md`
  - If UI changed → update `doc/main/ui_stack.md`
  - If dev workflow changed → update `doc/dev/development.md`
  - Verify all doc links work
  - Keep discussion at appropriate detail level
- **Quality checks**:
  - No stale references to removed code
  - Examples still runnable
  - Timestamps updated

##### Task 2c: `strategic-compact/SKILL.md`
Adapt from reference:
- Suggest compaction after planning phase
- Suggest compaction after debugging
- Don't suggest mid-implementation

##### Task 2d: `code-review-workflow/SKILL.md`
Merge `sop/diff_review.md` and `sop/codebase_review.md`:
- Severity prioritization (Critical → High → Medium → Low)
- Android-specific checks (lifecycle, memory leaks, thread safety)

##### Task 2e: `tdd-workflow/SKILL.md`
Adapt for Android:
- JUnit + MockK test patterns
- Gradle test commands
- Coverage with JaCoCo
- When to activate (core logic, state machines)

##### Task 2f: `coding-standards/SKILL.md`
Adapt from reference (TypeScript → Kotlin):
- **Keep universal principles**: KISS, DRY, YAGNI, readability first
- **Kotlin-specific patterns**:
  - Sealed classes for state representation
  - Data classes for models
  - Extension functions for utilities
  - Coroutine patterns (structured concurrency)
  - Null safety idioms (`?.`, `?:`, `!!` avoidance)
  - Immutability with `val` and `copy()`
- **Android-specific patterns**:
  - Lifecycle-aware components
  - ViewModel + StateFlow for UI state
  - Proper context handling (avoid leaks)
  - Main-safe coroutine dispatchers
- **Code smell detection** (adapted):
  - Large activities/fragments (>400 lines)
  - Deep callback nesting
  - God activities
  - Context leaks

##### Task 2g: `planning-workflow/SKILL.md`
Merge `sop/planning.md` with reference:
- Risk assessment
- Dependency analysis
- Phase breakdown

#### Task 3: Create Agents

##### Task 3a: `build-error-resolver.md`
Heavy adaptation for Android:
- Gradle sync errors
- Manifest merge conflicts
- Resource not found
- Kotlin version mismatches
- Missing permissions
- ProGuard/R8 issues
- Dependency conflicts
- Kotlin/Java interop issues

##### Task 3b: `planner.md`
Copy reference structure, adapt examples to Android/Kotlin:
- Phase breakdown with Android considerations
- Risk assessment for Android-specific issues
- Dependency analysis including Gradle modules

##### Task 3c: `code-reviewer.md`
Adapt checklist for Android:
- **Security checks**: Hardcoded secrets, insecure storage, permission handling
- **Android-specific**:
  - Lifecycle awareness (leaks via static references)
  - Memory leak patterns (Context in singletons)
  - Thread safety (main thread violations, coroutine scope)
  - Null safety (Kotlin nullability, platform types)
  - Permission handling
  - Accessibility service best practices
- **Output format**: Critical → High → Medium → Low with fix suggestions
- **Approval criteria**: Block on Critical/High, warn on Medium

##### Task 3d: `architect.md`
Adapt from reference for Android:
- **Architecture patterns**:
  - MVVM / MVI patterns
  - Clean architecture layers
  - Dependency injection (Hilt/Koin)
  - Module organization
- **Android-specific concerns**:
  - Service vs Activity vs BroadcastReceiver decisions
  - IPC/Binder considerations
  - Accessibility service architecture
  - Background task strategies (WorkManager vs Service)
- **ADR format** (Architecture Decision Records):
  - Context, Decision, Consequences
  - Alternatives considered
  - Keep in `doc/archive/` or `doc/adr/`
- **Trade-off analysis**: Performance vs maintainability, battery vs responsiveness

#### Task 4: Create Commands

##### Task 4a: `verify.md`
Android verification sequence:
```bash
./gradlew clean assembleDebug
./gradlew lint
./gradlew test
# Check for API keys, permissions
```
Output format: `VERIFICATION: PASS/FAIL` with detailed report.

##### Task 4b: `update-docs.md`
Documentation sync command:
- **Trigger**: Before commits, after architecture/workflow changes
- **Process**:
  1. Analyze git diff to identify changed areas
  2. Check if changes affect documented architecture
  3. Update relevant docs in `doc/main/`, `doc/dev/`
  4. Verify doc links and examples still work
  5. Update timestamps
- **Output**: List of docs updated, verification status

##### Task 4c: `plan.md`
Invoke planner agent with guidance on when to use.

##### Task 4d: `tdd.md`
Invoke TDD workflow for explicit test-driven development:
- Best for: Core logic, state machines, data transformations
- Skip for: UI, Android system integration

##### Task 4e: `build-fix.md`
Invoke build-error-resolver agent:
- Parse Gradle error output
- Fix one error at a time
- Re-verify after each fix
- Stop if fix introduces new errors

#### Task 5: Update Documentation

##### Task 5a: Update `doc/dev/development.md`
Add reference to new commands (`/plan`, `/verify`, `/build-fix`, `/tdd`).

##### Task 5b: Archive redundant SOPs
Mark old SOPs as deprecated once skills work.

---

## Setup: Cross-Tool Symlinks

These configurations should work across Claude Code, Cursor, and Codex (OpenAI) since they use similar formats.

### Directory Structure

```
androidagent/
├── .claude/                    # Primary config location
│   ├── skills/
│   │   ├── verification-loop/
│   │   │   └── SKILL.md
│   │   ├── doc-update/
│   │   │   └── SKILL.md
│   │   ├── strategic-compact/
│   │   │   └── SKILL.md
│   │   ├── code-review-workflow/
│   │   │   └── SKILL.md
│   │   ├── tdd-workflow/
│   │   │   └── SKILL.md
│   │   ├── coding-standards/
│   │   │   └── SKILL.md
│   │   └── planning-workflow/
│   │       └── SKILL.md
│   ├── agents/
│   │   ├── build-error-resolver.md
│   │   ├── planner.md
│   │   ├── code-reviewer.md
│   │   └── architect.md
│   ├── commands/
│   │   ├── verify.md
│   │   ├── update-docs.md
│   │   ├── plan.md
│   │   ├── tdd.md
│   │   └── build-fix.md
├── .cursor -> .claude          # Symlink for Cursor
├── .codex -> .claude           # Symlink for Codex
├── CLAUDE.md                   # Project-level config
└── AGENTS.md -> CLAUDE.md      # Symlink for Codex compatibility
```

### Setup Script

Create `scripts/setup-ai-config.sh`:

```bash
#!/bin/bash
# Setup symlinks for cross-tool compatibility
# Works with: Claude Code, Cursor, Codex (OpenAI)

cd "$(dirname "$0")/.."

# Create .claude directory structure if not exists
mkdir -p .claude/skills
mkdir -p .claude/agents
mkdir -p .claude/commands
mkdir -p .claude/contexts

# Create symlinks for other tools
# Remove existing symlinks/directories first (preserve if real directories)
[ -L .cursor ] && rm .cursor
[ -L .codex ] && rm .codex
[ -L AGENTS.md ] && rm AGENTS.md

# Create symlinks for tool directories
ln -sf .claude .cursor
ln -sf .claude .codex

# Create symlink for Codex AGENTS.md compatibility
[ -f CLAUDE.md ] && ln -sf CLAUDE.md AGENTS.md

echo "AI config symlinks created:"
echo "  .cursor -> .claude"
echo "  .codex -> .claude"
[ -f CLAUDE.md ] && echo "  AGENTS.md -> CLAUDE.md"

# Verify structure
echo ""
echo "Directory structure:"
ls -la | grep -E '^\.(claude|cursor|codex)|CLAUDE\.md|AGENTS\.md' || true
```

### Verification

After setup, verify with:
```bash
ls -la | grep -E '^\.(claude|cursor|codex)'
```

Expected output:
```
drwxr-xr-x  .claude
lrwxr-xr-x  .codex -> .claude
lrwxr-xr-x  .cursor -> .claude
```

### Notes on Cross-Tool Compatibility

| Tool | Config Location | Format | Notes |
|------|----------------|--------|-------|
| Claude Code | `.claude/` | SKILL.md, agents/*.md | Primary target |
| Cursor | `.cursor/` (or `.claude/`) | Similar format | Symlink works |
| Codex (OpenAI) | `.codex/` | Similar but evolving | Symlink works, may need adjustment |

**CLAUDE.md equivalents**:
- Cursor: Uses `CLAUDE.md` or `.cursorrules` 
- Codex: Uses `AGENTS.md` or similar

**Recommendation**: Keep `CLAUDE.md` as primary, create symlinks/copies as needed:
```bash
# If Cursor needs .cursorrules
cp CLAUDE.md .cursorrules

# If Codex needs AGENTS.md  
ln -sf CLAUDE.md AGENTS.md
```

---

## References

### External
- [everything-claude-code](https://github.com/affaan-m/everything-claude-code) - Source reference repo
- [Claude Code Skills Documentation](https://docs.claude.com/en/docs/claude-code/skills)
- [Claude Code Rules Directory](https://claudefa.st/blog/guide/mechanics/rules-directory)

### Internal (Our Existing Resources)
- `sop/diff_review.md` - Existing diff review process
- `sop/codebase_review.md` - Existing codebase review process
- `sop/doc_update.md` - Existing doc update principles
- `sop/planning.md` - Existing planning process (empty, to be migrated)
- `doc/main/` - Architecture documentation
- `doc/dev/development.md` - Development workflow

### Key Reference Files (from everything-claude-code)
- `.reference/everything-claude-code/agents/doc-updater.md` - Doc update agent
- `.reference/everything-claude-code/agents/architect.md` - Architecture agent
- `.reference/everything-claude-code/agents/code-reviewer.md` - Code review agent
- `.reference/everything-claude-code/skills/coding-standards/SKILL.md` - Coding standards
- `.reference/everything-claude-code/skills/verification-loop/SKILL.md` - Verification workflow
