# Our Perceptor (AccessibilityNodeInfo -> snapshot) notes (focus: `resource_id`)

## Quick answer: do we keep `resource_id` today?
Yes.

- Extraction: `AccessibilityNodeInfo.viewIdResourceName` -> `PerceptionElement.resourceId` in `app/src/main/kotlin/com/moonkey/androidagent/perception/Perceptor.kt`.
- Prompt JSON: emitted as `id` (not `resource_id`) via `Perceptor.toPromptJson()` (`put("id", elem.resourceId)`).
- Tool usage: our tool schema/handlers use the parameter name `resource_id` (e.g. `MultiSelectorTargeting` + `ClickTargetInvocation`).

So the data is preserved, but **the prompt field name (`id`) and tool argument name (`resource_id`) are inconsistent**.

## What might cause `resource_id` targeting to fail (even if we “keep it”)?
1) **Some nodes do not have view IDs**:
   - `viewIdResourceName` can be `null` for many widgets (especially inside WebView, some Compose hierarchies, custom views).
   - Practical implication: `resource_id` cannot be the only reliable selector; we still need text/desc/bounds fallbacks.

2) **Current “keep” policy can drop nodes that only have `resource_id`**:
   - In `TraversalMode.ALL`, we keep nodes if: clickable/editable/scrollable OR (text/desc not blank).
   - A node with `resourceId != ""` but no text/desc and not marked clickable/editable/scrollable is currently **dropped**.
   - This is a mismatch with any strategy that wants to *use* `resource_id` as a locator: IDs are often present on icon-only nodes with no text/desc.

3) **String truncation**:
   - `resourceId` is truncated to 60 chars (`MAX_STRING_LENGTH`). Usually OK, but worth remembering if you ever see “partial IDs”.

4) **Fallback order nuance (not a bug, but easy to misunderstand)**
   - Current selector attempt order in code is: `bounds -> x/y -> resource_id -> text -> element_index` (`MultiSelectorTargeting.attemptsFromParams()`).
   - If the model includes bounds/x/y in the same tool call, we will try those first even when `resource_id` is present.
   - This is fine as long as prompt policy is consistent with the code: only include bounds/x/y when you actually want them tried first.

## Low-effort improvements that directly help `resource_id` click reliability
- Emit **both** `resource_id` and `id` in the prompt JSON (or rename `id` -> `resource_id`) to reduce LLM mapping ambiguity.
- Treat `resourceId.isNotBlank()` as “content” when deciding whether to keep elements (so nodes with only IDs survive truncation).
- Add per-element **occurrence indices** for `resource_id` and `text` (e.g. `resource_id_index`, `text_index`) so the model can deterministically choose the nth match without “counting in its head”.
