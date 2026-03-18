# App Skill Framework: Top-Down Rewrite Design

## Goal

Define a framework for rewriting all 17 app skills with a unified structure that generalizes to real users without regressing eval pass rates. Every structural element must earn its existence.

## The Core Principle

**App skills encode the app's data model and interaction model — the "what" and "where." The agent's general intelligence handles the "how."**

The litmus test for every line: **Would this appear in a concise user manual for this app?** A manual tells you where features live and how the UI works. It never tells you how to approach a counting problem or how to format your notes.

---

## 1. Content Principles

### YES — Include

| Category | What it covers | Example |
|----------|---------------|---------|
| **Hidden data locations** | Where data lives when not visible from UI | "Activity type is ONLY in Edit screen, not inferable from track name" |
| **Silent failure modes** | Approaches that look correct but fail | "General search is proximity-based and misses villages — use Address tab" |
| **Navigation mechanics** | Non-obvious paths to common operations | "Wi-Fi toggle: Network & internet → Internet → Wi-Fi row" |
| **Interaction pitfalls** | UI elements that behave unexpectedly | "Hamburger menu overlaps left edge (x < 150) — use element_index" |
| **Accessibility gaps** | A11y tree limitations specific to this app | "Month grid cells have NO per-cell accessibility nodes" |
| **Platform quirks** | First-run dialogs, extension fields, picker modes | "New-file dialog has separate extension field defaulting to .md" |
| **Terminology mapping** | App-specific vocabulary | "Priority radio buttons L→R: None, Low, Medium, High" |

### NO — Exclude

| Category | Why it's out | Example to remove |
|----------|-------------|-------------------|
| **Solver algorithms** | LLM's job, not app knowledge | "Maintain unchecked/checked scratchpad lists, move names as you go" |
| **Scratchpad format prescriptions** | Working memory strategy, not app fact | `"unique: A, B, C"` format instructions |
| **Batch-size tuning** | Eval-specific optimization | "Add 8-10 songs WITHOUT visiting the playlist between adds" |
| **Generic rules already in core prompt** | Redundant token cost | "Scroll down repeatedly until content stops changing" |
| **Eval-specific data patterns** | Overfit to test fixtures | "perturbed groups", "decoy entries" |
| **Hardcoded counts/thresholds** | Tuned to eval data, not general | "scroll exactly 40 items", "compare 7 fields" |
| **Task-category procedures** | Strategy for a class of tasks | "For counting tasks: scan once, write findings, then act from memory" |

### The Decomposition Rule

When existing content mixes app knowledge with solver algorithms, **extract the fact, discard the procedure**:

| Before (mixed) | After (fact only) |
|----------------|-------------------|
| "For counting by activity type: scroll list, write all tracks to scratchpad using compact format, open Edit for EVERY track, move from unchecked to checked..." | "Track names do NOT indicate activity type. Activity type is ONLY in Edit screen → Activity type field." |
| "To check completion: open detail → scroll down. If `Completion YYYY-MM-DD` → completed. If only `Created`/`Modified` → not completed." | Same — this IS app knowledge (where completion status lives). Keep. |
| "Add 8-10 songs WITHOUT visiting the playlist. Then check total on Playlists tab. Add or remove to adjust." | "Playlist detail view shows total duration. Individual song durations are in the Songs list, not Details." |

The distinction: **a navigation path to find data = app knowledge. A strategy for processing data = solver algorithm.**

---

## 2. CRITICAL Section Protocol

CRITICAL callouts are the most effective prompt mechanism. Overuse dilutes impact.

### Criteria (all three required)

1. **Silent failure** — the default/obvious approach fails without clear error signal
2. **Non-obvious alternative** — the correct approach can't be inferred from the screen
3. **App-specific** — not a generic rule (scroll more, verify results) amplified

### Format

```
## CRITICAL — {One-Phrase Topic}
{What fails and why — one sentence.} {What to do instead — one sentence.}
{Optional: 1-2 line minimal navigation path if the path IS the knowledge.}
```

### Constraints

- **Max 1 CRITICAL per skill.** If an app has two silent-failure modes, pick the one that causes worse damage. The other goes as a regular bullet.
- **Max 3 content lines.** A CRITICAL that needs a paragraph is probably a solver algorithm in disguise.
- **No strategy content.** "NEVER open song Details to check durations" is a strategy directive. "Playlist detail view shows total duration" is a fact that achieves the same result.

### Current CRITICALs — Audit

| Skill | Current CRITICAL | Verdict | Reason |
|-------|-----------------|---------|--------|
| OsmAnd | Use Address tab for search | KEEP | Meets all 3 criteria — general search silently geocodes wrong |
| VLC | Use Library tabs, not Browse | KEEP | Browse silently breaks multi-select |
| Tasks.org | Completion status check procedure | KEEP (trim) | Where completion lives is genuine hidden knowledge |
| Markor | Scroll file list before selecting | DEMOTE | Generic scroll rule — fails criterion 3 |
| RetroMusic | Playlist batch efficiency | REMOVE | Solver algorithm — fails criteria 1 and 3 |

---

## 3. Structural Template

Structure scales with complexity. Don't force sections on simple apps.

### Tier 1 — Minimal (2-5 content lines)

```markdown
# {App Name}

- {Quirk or pitfall bullet}
- {Another if needed}
```

No section headers. Just the frontmatter header + bullets. For apps with no notable quirks, don't create a skill file at all.

### Tier 2 — Standard (6-12 content lines)

```markdown
# {App Name}

- {General app bullet}
- {Another}

## {Topic} (only if 3+ related bullets)
- {Detail}
- {Detail}
```

One optional section header max. The header groups related points — it doesn't create hierarchy for its own sake.

### Tier 3 — Complex (13-20 content lines)

```markdown
# {App Name}

## CRITICAL — {Topic}
{Failure mode + correct alternative. Max 3 lines.}

## {Section A}
- {Bullets}

## {Section B}
- {Bullets}
```

CRITICAL at top (if earned). Then 2-3 sections max. Each section = a cluster of related app knowledge.

### Universal Rules

- **Bullet-first.** Prose paragraphs waste tokens. Use concise bullets.
- **Headers earn their existence.** A section with 1-2 bullets doesn't need a header — merge into the general area.
- **Action-oriented language.** "Use Address tab" not "The Address tab can be used for."
- **Hard cap: 20 content lines.** Frontmatter and the `# Name` header don't count. If a skill can't fit in 20 lines, content is either overfit or belongs in core prompt.

---

## 4. Token Budget

| Tier | Content Lines | ~Tokens | Apps (current assessment) |
|------|--------------|---------|--------------------------|
| Minimal | 2-5 | 30-80 | Audio Recorder, Documents UI |
| Standard | 6-12 | 80-180 | Google Calendar, Google Photos, Simple Gallery, Chrome, Google Files, Settings, Pro Expense |
| Complex | 13-20 | 180-300 | Tasks.org, OpenTracks, RetroMusic, VLC, OsmAnd, Markor, Simple Calendar, Broccoli |

**Total budget target**: All 17 skills combined should use fewer tokens than the core system prompt (~70 lines, ~1200 tokens). Current state likely exceeds this. Target: ~800-1000 tokens total across all skills.

**Per-turn cost**: Only 1 skill loads per turn (the foreground app). So the per-turn cost is the individual skill's token count, not the sum. This means the hard cap per skill matters more than total budget.

---

## 5. Shared Patterns Policy

### Already in Core Prompt (do not repeat in skills)

The `StandaloneAgentDef` system prompt covers:
- Scroll to see all items before counting
- Use scratchpad for multi-step tasks
- Match exact filenames
- Verify outcomes after actions
- Compute date ranges before filtering
- Prefer semantic UI targets over coordinates
- Don't repeat failed actions

**Rule: Never repeat these in a skill.** If the agent isn't following a core prompt rule for a specific app, the fix is to strengthen the core prompt OR add an app-specific CRITICAL that explains WHY this app is different — not to echo the generic rule.

### App-Specific Variants — When They Earn a Line

A skill bullet about a "generic" topic earns its line only when the app's behavior deviates from the norm:

| Generic rule | App-specific variant that earns a line |
|-------------|---------------------------------------|
| "Scroll to see all" | "Category row scrolls **horizontally**" (unusual axis) |
| "Use semantic targets" | "Hamburger menu overlaps left edge — always use `element_index`" (specific coordinate trap) |
| "Verify after action" | "After toggling, verify the switch state. Shell `svc wifi` fails silently from agent process." (platform-specific failure mode) |

### Candidates for Core Prompt Promotion

If the rewrite reveals that 3+ skills share a pattern not in core prompt, promote it rather than duplicating:

- **Gallery apps**: "Open image full-screen before reading text — thumbnails are too small for OCR." This appears in Google Photos and Simple Gallery. If it applies to any image-viewing context, consider adding to core prompt as a general perception rule.
- **File browser apps**: "Verify file gone from source and present at destination after move/copy." This appears in Markor, Google Files, and Documents UI. Already partially covered by core prompt's completion verification.

Decision: evaluate during Phase 1 rewrite. If a pattern appears in 3+ skills, create a core prompt PR instead.

---

## 6. Before/After Examples

### Example A: OpenTracks (Complex → Complex, but pruned)

**Before (18 lines, includes solver algorithm):**
```
## Counting/Summing by Activity Type
For any count or sum by activity type:
1. FIRST scroll the entire track list end-to-end. There are ALWAYS items below the fold.
2. Write all tracks in the date range to scratchpad. Use compact format: "unchecked: Name1, ..."
3. Open Edit for EVERY track on the checklist.
4. Activity types must match EXACTLY — "biking" is NOT "mountain biking".
5. Do NOT call complete_task until every track is marked checked.
```

**After (14 lines, app knowledge only):**
```
# OpenTracks

## CRITICAL — Activity Type Is Hidden
Track names do NOT indicate activity type. The authoritative type is ONLY in: tap track → 3-dot menu → Edit → Activity type field. Exact match required — "biking" is NOT "mountain biking".

## Track Data
- List view shows distance and time. Tap a track → Stats tab for details.
- Track list uses relative date labels (Today, Yesterday, Monday). Resolve to absolute dates using device date.

## Reading Activity Type
Tap track → More options (3 dots) → Edit → read Activity type field → back to return.
```

Removed: scratchpad format prescription, step-by-step counting procedure, "ALWAYS items below the fold" (generic scroll rule). Kept: where activity type lives (hidden knowledge), exact-match requirement (app-specific terminology), navigation path to Edit (interaction model).

### Example B: RetroMusic (Complex → Standard, solver removed)

**Before (18 lines, CRITICAL is a solver algorithm):**
```
## CRITICAL — Playlist Efficiency
- NEVER open song Details to check individual durations — the playlist detail view shows total.
- Add 8-10 songs WITHOUT visiting the playlist between adds. Then check total.
- If total EXCEEDS target, remove last-added song(s).
- If multi-select toolbar icon doesn't respond on first try, switch to per-song 3-dot menu.
```

**After (12 lines, app knowledge only):**
```
# Retro Music

- Playlist detail view shows total duration — no need to check individual songs.
- "Add to playlist" button inside a playlist adds the PLAYLIST to another playlist, NOT songs into it. Add songs from the Songs tab.
- If multi-select toolbar doesn't respond, use per-song 3-dot menu → "Add to playlist" instead.

## Navigation
- Bottom tabs: Home, Songs, Albums, Artists, Playlists.
- Create playlist first (Playlists tab), then go to Songs tab to add.

## Adding Songs
- Multi-select: from Songs tab, long-press first → tap additional → toolbar "Add to playlist" icon.
- Per-song: 3-dot menu → "Add to playlist" → select target.
```

Removed: CRITICAL (was a solver), batch-size tuning, "scroll Songs list to see ALL" (generic), "if duration stops increasing" heuristic. Kept: where total duration is shown, the confusing "Add to playlist" button, multi-select mechanics, navigation model.

### Example C: Audio Recorder (Minimal stays Minimal)

**Before (5 lines):**
```
## Recording and Naming a File
1. Tap record → wait 2-3 seconds → tap stop
2. Recording appears with auto-generated name
3. To rename: 3-dot menu or long-press → Rename
4. Type exact filename verbatim
5. Confirm and verify
```

**After (3 lines):**
```
# Audio Recorder

- To rename a recording: tap 3-dot menu or long-press the recording → Rename.
- Type the exact filename from the goal verbatim, including any extension.
```

Removed: obvious recording procedure (tap record, wait, tap stop). Kept: rename mechanic (non-obvious interaction path), exact filename requirement (app-specific — the default name is auto-generated).

---

## 7. Migration Strategy

### Phase 1 — Low Risk (7 apps)
Audio Recorder, Documents UI, Chrome, Google Files, Settings, Google Photos, Simple Gallery.

These are Tier 1-2 skills with straightforward content. Rewrite establishes the pattern. Run eval on affected tasks. Expected: zero regressions.

### Phase 2 — Moderate Risk (4 apps)
RetroMusic, Broccoli, Simple Calendar, Pro Expense.

These have clear solver algorithms to remove. Rewrite + eval. If any task regresses:
- Check if the regression is from removing a solver or from removing an app fact that was embedded in the solver.
- If an app fact was lost, extract and re-add it as a clean bullet.
- If the regression is purely from losing the solver, accept it per tuning principles ("acceptable regressions").

### Phase 3 — High Risk (6 apps)
Tasks.org, OpenTracks, OsmAnd, VLC, Markor, Google Calendar.

These have fragile tasks AND load-bearing CRITICALs. Rewrite with extra care:
- Preserve all CRITICAL content that meets the three criteria.
- For Tasks.org and OpenTracks, the completion-status and activity-type knowledge is load-bearing — guard it.
- Run eval, compare per-task to previous round. Any regression → investigate before proceeding.

### Per-Phase Process
1. Rewrite skills per framework
2. Rebuild APK + install
3. Run eval on affected app tasks only
4. Compare per-task pass rates against last stable round
5. If regressions: diagnose, adjust, re-eval
6. If clean: proceed to next phase

---

## 8. Trade-offs

### Risk: Removing solver algorithms may regress fragile tasks
**Mitigation**: Phased rollout. The framework distinguishes app facts from solver algorithms — the decomposition rule ensures we extract load-bearing facts before discarding procedures. Acceptable regressions are documented per tuning principles.

### Risk: Single CRITICAL limit may under-protect complex apps
**Mitigation**: The second-most-important warning becomes a regular bullet — still visible, just not CRITICAL-emphasized. If eval shows a specific non-CRITICAL warning being ignored, we can reconsider the limit for that skill.

### Risk: 20-line hard cap may not fit genuinely complex apps
**Mitigation**: The cap forces prioritization. If an app truly needs >20 lines, the likely cause is either: (a) generic content that belongs in core prompt, or (b) content that should be split between the skill and the core prompt. The cap is a design pressure, not an arbitrary constraint.

### What this framework does NOT address
- Core prompt changes (out of scope, but Phase 1 may surface candidates)
- Skill injection mechanism changes (always inject foreground app's skill — unchanged)
- New skill creation for apps not yet covered (framework applies, but discovery is a separate process)
