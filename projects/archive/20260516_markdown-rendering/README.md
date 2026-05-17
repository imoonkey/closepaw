# Markdown rendering — design task

> **Status:** design only. No implementation in this scope. Decide library +
> integration shape, then split into a build task.

## Problem

The agent's `FinalAnswer` (`AgentRow.kt:89-97`) and streaming Final blocks
(`StreamingText.kt`) currently render as plain `Text(bodyLarge)`. But the
underlying LLM frequently emits markdown:

- **bold**, *italic*, `inline code`
- fenced ``` ```code blocks``` ```, often with a language tag
- bullet / numbered lists
- links (URLs, `tel:`, `mailto:`, app deep links)
- tables (less common, but present in summaries)
- headings (`##`) when the answer organizes multiple sections

Today this all lands as raw text with backticks and asterisks visible. Users
notice — Maya's email summary loses its bullet shape; an action plan that
should be a numbered list reads as one paragraph; the rare code snippet has
no monospace contrast against the body.

ThoughtHeader and ActionRow `expandedContent` could *also* benefit but are
lower priority — Thought is short, expanded action content is mostly mono
already.

## What "streamdown-quality" means here

[Vercel's streamdown](https://vercel.com/changelog/introducing-streamdown)
makes one specific promise that distinguishes a real solution from "just
parse on every token":

- **Incomplete-token resilience.** The mid-stream string `**bold thi` should
  render as `**bold thi`, not crash a parser or flicker between asterisks
  and bold styling. When the closing `**` arrives, the rendered output
  silently transitions to **bold thi** — no visual jolt.
- **Code-fence in flight.** A half-open ``` ```python\nimport ``` block must
  render as a code block with a partial body, not as inline text with
  backticks until the closing fence arrives.
- **List items as they're typed.** `- first\n- sec` should render two list
  items, the second still being typed, not require the trailing newline.

For ClosePaw, the streaming surface is `StreamingText.kt` (one of the rare
moments where the cursor blinks). Whatever we pick has to either solve this
natively or let us implement a small "tolerant lexer" wrapper around it.

## Library survey (Android / Compose, late 2026)

| Library | Style | Streaming-safe? | Verdict |
|---|---|---|---|
| **mikepenz/multiplatform-markdown-renderer** | Pure Compose, KMP | Tolerant — re-parses cheaply, handles partial tokens reasonably | **Default candidate.** Active, idiomatic Compose, theme-friendly. |
| **Markwon** (`io.noties.markwon`) | Android View, ext via `compose-markdown` wrappers | Re-parses entire string on each frame; not designed for streaming | Mature, plugin-rich (math, syntax highlighting), but View-system at the leaf. Bridging into Compose adds AndroidView overhead. |
| **jeziellago/compose-markdown** | Wraps Markwon in `AndroidView { TextView }` | Inherits Markwon limits | Quick win for static content; not the right primitive for streaming. |
| **CommonMark Java** + custom Compose render | Parser only; we render | We control it — can implement streaming-tolerant render | Most flexible, most work. Likely 2-4 weeks of effort to match `multiplatform-markdown-renderer` quality. |
| **Roll our own minimal renderer** | Hand-written | Trivially streaming-safe by construction | Reasonable if we limit to bold/italic/code/lists/links and skip tables. ~1 week, plus ongoing maintenance. |

## Integration shape (sketch — defer for design)

The smallest seam:

```kotlin
// New: ui/chat/components/Markdown.kt
@Composable
fun Markdown(
    text: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
)
```

Call sites:

| Today | After |
|---|---|
| `AgentRow.kt:89-97` `FinalAnswer { Text(...) }` | `Markdown(text = text)` |
| `StreamingText.kt` final-text path | `Markdown(text = text, streaming = true)` (cursor stays) |
| `AgentTrace.kt::ThoughtGroupView` mid-stream Text branch | `Markdown(text = item.text)` |
| `ActionRow::expandedContent` | left in mono, opt-in only if it's clearly markdown |

The `streaming = true` path is what differs from "just slap markdown on";
it tells the renderer to use the tolerant codepath (no backtracking on
unclosed delimiters).

## D1 / Bound-Edition alignment

Whatever lib we pick must respect:

- **Geist** for body markdown text
- **JetBrains Mono** for `inline code`, code blocks, autolinks
- **Fraunces italic** could be the markdown italic style — *italic* in Bound
  Edition would look like *italic* (serif), tying the editorial register
  back into the answer prose. Optional — pick during design.
- **Claw** for link color (the one place chrome that's currently link-less
  could legitimately spend a touch of brand color)
- Lists: hanging indent matching Bound Edition's ThoughtGroup left rule
  (1 char hang, no Material list bullets). Bullet marker in `inkFaint`.
- Headings inside Final body: rare, but if present, render as Geist 500 at
  +1 step, no Fraunces (Fraunces is reserved for identity surfaces — see
  `ui-refresh-0422/non-regressions.md` §1).
- Code blocks: `surfaceVariant` background, `MaterialTheme.shapes.small`
  radius, `monoSmall`. Same vocabulary as the existing `expandedContent`
  surface (`AgentTrace.kt:227-241`) — reuse, don't invent.

## Open questions for design

1. **Tables — yes or no?** They almost never appear in chat answers, and
   they break under any narrow phone width. Default: skip them. Render as
   code block fallback if the markdown contains a `|`-table.
2. **Syntax highlighting in code blocks?** Markwon supports it via
   Prism4j. multiplatform-markdown-renderer doesn't ship it. Default:
   no — monochrome code at first, revisit if users complain.
3. **Math (`$..$`)?** Not seen in our agent's outputs. Skip.
4. **Inline images?** Not relevant to phone-agent answers. Skip.
5. **Should ThoughtHeader render markdown?** Currently bodyLarge regular.
   Thoughts are short and rarely have markdown. Default: leave as plain
   text; add later if needed.
6. **Streaming visual contract:** when an unclosed `**` arrives, do we
   render the asterisks visibly until closure, or render eagerly as bold
   and let the closing ** "complete" it visually? Streamdown's choice is
   eager rendering with a soft transition. We should match for the same
   reason — fewer flickers.
7. **Library lock-in:** if we pick `multiplatform-markdown-renderer` and
   later need a feature it doesn't have (e.g., custom inline content for
   `@mention`-style tokens), how painful is the migration?

## Acceptance criteria (when this task graduates to a build task)

- [ ] FinalAnswer renders bold, italic, inline code, fenced code, lists, links
- [ ] Streaming FinalText doesn't flicker as delimiters arrive token by token
- [ ] D1 typography respected (Geist body, JetBrains Mono for code, optional
      Fraunces italic for `*italic*`)
- [ ] Links open in default handler; long-press → "Copy link"
- [ ] Code blocks copyable (long-press)
- [ ] Reduced motion: any internal animation (e.g. cursor inside code block)
      respects `ClosePawMotion.reducedMotion()`
- [ ] No regression on plain-text answers (the common case stays fast)
- [ ] Apk size delta documented; if > 200KB, justify

## Out of scope

- HTML rendering (markdown only — agent prompt restricts to MD)
- Markdown editor / input — input bar stays plain text
- Markdown in user bubbles — user input is plain text by contract
- Per-language syntax highlighting (see Q2)
- Math (see Q3)
- Tables (see Q1, deferred)

## Next step

Pick one:

- **A.** Spike `multiplatform-markdown-renderer` against three real agent
  outputs (long answer, code-heavy, list-heavy) on both themes; confirm
  streaming behavior. ~1 day. If it survives, jump to build task.
- **B.** Run `/double-design` between Claude and Codex on this note before
  spiking, since the streaming-safety contract is subtle.

Recommend A first; escalate to B only if A surfaces an architectural
question (e.g. the lib doesn't expose an incremental parse hook).

## Related

- `projects/active/ui-refresh-0422/` — Bound Edition round; FinalAnswer is
  the surface this would most visibly improve. Land Bound Edition first;
  markdown layers on top.
- `app/src/main/kotlin/ai/closepaw/ui/chat/components/AgentRow.kt:89-97` —
  current `FinalAnswer` composable to be replaced.
- `app/src/main/kotlin/ai/closepaw/ui/chat/components/StreamingText.kt` —
  streaming path; the harder integration of the two.
