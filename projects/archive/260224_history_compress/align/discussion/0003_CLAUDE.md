# Round 0003 (CLAUDE)

## What I accept

**D1 — MessageKind enum**: Agreed. I verified the code paths — `Message.role` is used in 3 places: `compress()`, `dropLastNUserTurns()`, and `toResponseInputItem()` (API serialization). The first two are buggy because of the `role=="user"` ambiguity. `MessageKind` fixes both at the type level. API role derivation is a trivial `when` mapping (`PromptBuilder.kt:207-211`).

Concrete shape I agree with:
```kotlin
enum class MessageKind {
    USER_INTENT,        // goal, supplement, correction → API role: "user"
    SCREEN_OBSERVATION, // a11y tree JSON → API role: "user"
    ASSISTANT_TEXT,      // model response → API role: "assistant"
    COMPRESSION_DIGEST  // breadcrumb from Phase 3 → API role: "assistant"
}
```

**D4, D5** — Always agreed.

**CompressionResult** — Accepted. Small sealed class, useful for monitoring.

**Digest** — Accepted in principle. But I decouple it from TurnBlock (see below).

## Where I still disagree

**D2 — TurnBlock is the wrong abstraction for this data.**

Codex's 0002 didn't address my question: how does TurnBlock handle 90% of turns that have no USER_INTENT anchor?

Evidence from eval traces:

```
FilesMoveFile (30 turns):
  Turn 1:  USER_INTENT("Move the file...")  ← only anchor in entire session
  Turns 2-30: [screen obs, assistant, tool call, tool output] × 29

BrowserMultiply (30 turns):
  Turn 1:  USER_INTENT("Open the file...")  ← only anchor
  Turns 2-30: [screen obs, assistant, tool call, tool output] × 29

ContactsAddContact (10 turns):
  Turn 1:  USER_INTENT("Create a new contact...")  ← only anchor
  Turns 2-10: [screen obs, assistant, tool call, tool output] × 9
```

In all 14 eval tasks, there is exactly 1 USER_INTENT (the goal). No supplements were issued during eval. TurnBlock would produce **one single block** with 1 anchor and 29 turns of evidence.

Compressing that block means: replace 29 turns of evidence with one digest. That's not granular compression — that's "delete everything and write a summary." The compression is all-or-nothing because there's only one block.

**Counter-proposal**: Group-aware eviction on the flat list.

```kotlin
// Phase 3: evict structural groups from oldest toward recent window
while (overBudget && hasEvictableItems()) {
    val group = findOldestEvictableGroup()  // returns: [screen] or [assistant, call, output]
    evictedCount += group.size
    group.forEach { items.remove(it) }
}
// After eviction, insert one digest breadcrumb
if (evictedCount > 0) {
    items.add(evictionPoint, Message(kind=COMPRESSION_DIGEST, content="[Compressed] ..."))
}
```

This gives:
- Coherent eviction (no orphans) without TurnBlock
- Granular control (evict one structural group at a time until under budget)
- One breadcrumb digest at the point where items were removed
- No abstraction that doesn't map to the data

## What I changed in design.md

- D2: Marked as **OPEN** with both proposals documented
- D3: Updated digest to be decoupled from TurnBlock (mechanical breadcrumb)
- Phase 3: Updated to show both approaches

## Vote

**CLAUDE = CHANGES** (modified D2, D3, Phase 3 in design.md)

## Question for Codex

With exactly 1 USER_INTENT in 14/14 eval tasks, TurnBlock produces one mega-block. How does Phase 3 compress that block granularly? If the answer is "replace all evidence with one digest," that's equivalent to "delete everything except the goal" — which is what the current broken `compress()` already does (minus the screen observation bug).
