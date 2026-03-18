# Audio Recorder App Skill Rewrite — Design

## Goal

Rewrite the Audio Recorder app skill (`com.dimowner.audiorecorder/SKILL.md`) following the Unified App Skill Rewrite Framework. Target: Tier 1 (2-5 lines), Phase 1 (low risk).

## Current State

The current skill is 5 numbered steps describing the full record-and-rename procedure, wrapped in YAML frontmatter and decorative headings. Total: 14 raw lines, ~10 content lines.

## Line-by-Line Classification

| Line | Classification | Verdict |
|---|---|---|
| YAML frontmatter (lines 1-4) | Runtime noise | **Remove** — loader doesn't parse it, wastes tokens |
| `# Audio Recorder Skill` heading | Decorative | **Remove** — runtime already wraps with `## App Skill` and package name |
| `## Recording and Naming a File` heading | Solver framing | **Remove** — frames the skill as a procedure, not app knowledge |
| Step 1: "Tap record button (large circle) → wait 2-3 seconds → tap stop" | Obvious procedure + eval timing | **Remove** — recording is self-evident; "2-3 seconds" is eval-specific |
| Step 2: "Recording appears in list with auto-generated name" | Weak app fact | **Remove** — standard recording-app behavior, not a trap |
| Step 3: "To rename: tap 3-dot menu or long-press → Rename" | **App truth** | **Keep** — the rename mechanic is non-obvious and has two entry points |
| Step 4: "Type the exact filename from the goal verbatim, including any extension characters" | Generic agent discipline + eval instruction | **Remove** — "follow the task exactly" belongs in core prompt, not app skill |
| Step 5: "Confirm rename and verify the new name in the list" | Generic verification | **Remove** — covered by core prompt verification rules |

## Key Design Questions

### 1. Is there any app truth beyond the rename mechanic?

**No.** Audio Recorder is a single-purpose app with a straightforward UI. Recording (big button), playback, and deletion are all standard patterns. The only non-obvious mechanic is how to rename: via 3-dot menu or long-press.

### 2. Should auto-generated names be mentioned?

**No.** This is standard recording-app behavior. The agent will see the auto-generated name in the list after recording. Mentioning it doesn't prevent any mistake or reveal hidden state — it just narrates what already happens visibly on screen.

### 3. What about the "extension characters" hint?

The current skill says "including any extension characters." This could hint at a rename-dialog quirk (e.g., the dialog might show or strip the file extension). **If the rename dialog pre-fills the current name including the extension, or if it has a separate extension field (like Markor), that would be an app truth worth keeping.** Based on the eval scoreboard (AudioRecorderRecordAudioWithFileName at 2/3), there may be a subtle issue here.

**Recommendation:** The rewrite should note the rename mechanic. If eval evidence shows extension handling is a real trap, a second line can be added. Otherwise, one line suffices.

## Proposed Rewrite

```md
- Rename a recording from its 3-dot menu or by long-pressing it.
```

**Line count: 1.** This is below the Tier 1 floor of 2 lines, but the framework says tiers are "targets, not identity labels." Adding a line just to hit 2 would violate token minimalism. One genuine app truth = one line.

### If extension handling is a real trap (conditional second line)

```md
- Rename a recording from its 3-dot menu or by long-pressing it.
- The rename dialog includes the file extension in the name field.
```

This second line would only be added if testing confirms the extension behavior is a real interaction pitfall (not just eval-specific "exact filename" discipline).

## What Changed

| Aspect | Before | After |
|---|---|---|
| Format | YAML frontmatter + heading + numbered procedure | Plain bullet(s) |
| Content | 5-step recording-and-naming walkthrough | 1 app-truth line about the rename mechanic |
| Lines | ~10 content lines | 1 (possibly 2) |
| Tier | Overweight for Tier 1 | Tier 1 |
| CRITICAL block | None | None (correct — no hidden state or silent failure) |

## Trade-Offs

- **Lost:** Step-by-step recording guidance. Acceptable because recording is self-evident.
- **Lost:** "Exact filename" instruction. Acceptable because this is generic agent discipline, not app knowledge.
- **Gained:** Radical compression. The agent reads one focused line instead of parsing a procedure to extract the one fact it needs.
- **Risk:** The 2/3 score on AudioRecorderRecordAudioWithFileName might be related to extension handling, not solver logic. If so, the extension line should be added after verification.

## Review Checklist

- Would this help with a different real-user task? **Yes** — any task involving renaming recordings.
- Does any line describe how to solve a benchmark? **No.**
- Could this line be said without mentioning the app? **No** — it names the app-specific rename entry points.
- Is the first line the most important app truth? **Yes** — it's the only app truth.
- Is the skill as short as it can be? **Yes.**

## Next Steps

1. Get design approval.
2. Implement the rewrite (replace SKILL.md content).
3. Build and install.
4. Run evals for AudioRecorderRecordAudio and AudioRecorderRecordAudioWithFileName.
5. Compare results against last stable round.
6. If AudioRecorderRecordAudioWithFileName regresses, investigate extension handling and add the conditional second line if warranted.
