# Google Photos App Skill Rewrite Design

Tier: 2 (6-12 lines target)
Phase: 1 (low risk)
Eval context: No direct eval tasks — used indirectly as photo picker/gallery.

## 1. Line-by-Line Classification of Current Skill

| # | Current line | Classification | Reasoning |
|---|---|---|---|
| 1 | `---` / YAML frontmatter | **remove** | Decorative; runtime does not parse it. |
| 2 | `# Gallery Skill` | **remove** | Decorative heading; `TurnPlanningPhaseRunner` already wraps with `## App Skill` + package name. |
| 3 | `## Viewing Images` | **remove** | Header can be dropped if content is compact enough without it. |
| 4 | "open it full-screen first by tapping the thumbnail" | **app** | Real app truth — thumbnails are too small for reliable perception. This is the single most important fact about Google Photos for agent use. |
| 5 | "Do NOT try to read text from the grid/thumbnail view — thumbnails are too small for reliable OCR" | **app (redundant)** | Restates line 4 in negative form. One direction is enough. |
| 6 | "Pinch-to-zoom on the full-screen image if text is still hard to read" | **app** | Valid fallback mechanic specific to image viewing. Worth keeping. |
| 7 | `## Navigating` | **remove** | Header not needed for two bullets. |
| 8 | "Use the bottom navigation or back button to return to the gallery grid" | **core** | Generic Android navigation — back button and bottom nav are standard. Agent should already know this. |
| 9 | "Images are sorted by date. Scroll to find older images" | **app** | Mild app truth about default sort order. Low value — most gallery apps sort by date and the agent can see the dates on screen. Borderline keep/remove. |

Summary: 2 strong app lines (full-screen viewing, pinch-to-zoom), 1 redundant restatement, 1 generic navigation line, 1 borderline app line, rest is decorative overhead.

## 2. Proposed Rewrite With Rationale

### Lines kept (rewritten for brevity)

1. **Full-screen before reading** — The highest-failure-cost rule. Thumbnails in the grid are too small for the agent's perception to extract text or visual details reliably. This is the #1 app truth.

2. **Pinch-to-zoom fallback** — Valid interaction mechanic specific to image viewing. When full-screen is still insufficient, zoom is the next step. Kept as a natural continuation of line 1.

3. **Grid is sorted by date (newest first)** — Retained in compressed form. Knowing the sort order helps the agent plan scrolling direction. While many gallery apps sort by date, this is cheap (one line) and prevents wasted scrolling in the wrong direction.

### Lines removed

- **Negative restatement of full-screen rule** — "Do NOT try to read text from the grid" is the same rule as "open full-screen before reading," just in negative form. One direction is sufficient.
- **Bottom navigation / back button** — Generic Android navigation. Covered by core agent knowledge.
- **All decorative elements** — YAML frontmatter, `# Gallery Skill` heading, section headers.

### Lines added

4. **Photo picker behavior** — Google Photos often appears as the system photo picker when other apps request an image. The agent should know that selecting a photo in the picker confirms immediately (tap = select + return), which differs from browsing inside the Photos app itself. This is a real interaction pitfall for an agent that navigates across apps.

5. **Search** — Google Photos has a powerful search bar that accepts natural language queries (people, places, things, dates). This is a non-obvious navigation shortcut that is more efficient than scrolling for finding specific images.

6. **Albums / Library tab** — Photos organized into albums live under the Library tab. The main feed (Photos tab) shows everything chronologically. This is a useful data-location fact.

## 3. Final Proposed Skill Text

```
- Open images full-screen before reading text or visual details — grid thumbnails are too small for reliable perception.
- Pinch-to-zoom on the full-screen image if details are still hard to read.
- Photos tab shows all images by date (newest first); Library tab holds albums and device folders.
- Use the search bar for natural-language queries (people, places, objects, dates) instead of manual scrolling.
- When Photos appears as a picker for another app, tapping a photo selects and returns it immediately.
```

Line count: 5 content lines.

This falls at the low end of the Tier 2 range (6-12). The skill could be classified as Tier 1 given its simplicity, which aligns with the framework's note that "a skill can move tiers during rewrite if its content shrinks or expands." Google Photos is a straightforward app with few hidden traps, so 5 tight lines is the right size.

## 4. Review Checklist

**Would this help with a different real-user task in the same app?**
Yes. The full-screen rule helps any task requiring image inspection (reading signs, identifying objects, checking photo metadata). The search bar line helps any "find a specific photo" task. The picker line helps any cross-app task where Photos serves as an image source. None of these are tied to a specific eval scenario.

**Does any line describe how to solve a benchmark instead of how the app works?**
No. Every line describes an app behavior or interaction mechanic. There are no counting procedures, scratchpad formats, or batch strategies.

**Could this line be said without mentioning the app? If yes, it probably belongs elsewhere.**
- "Open images full-screen" — Could partially apply to Simple Gallery, but the thumbnail-size problem is app-specific (Google Photos grid thumbnails are particularly small). The line names no app but is contextually tied to this app's grid layout. Keep.
- "Pinch-to-zoom" — Generic gesture, but the application (reading image text after going full-screen) is app-contextual. Keep as a natural extension of line 1.
- "Photos tab / Library tab" — App-specific navigation structure. Keep.
- "Search bar with natural language" — Specific to Google Photos' ML-powered search. Keep.
- "Picker behavior" — Specific to how Google Photos acts as a system picker. Keep.

**Is the first line the most important app truth?**
Yes. The full-screen rule prevents the most common failure mode: attempting to read from thumbnails and getting garbage results.

**Is the skill as short as it can be without losing a real app constraint?**
Yes. Each of the 5 lines carries a distinct, non-overlapping app fact. Removing any one would leave a real gap in the agent's understanding of Google Photos behavior.
