---
name: com.arduia.expense
description: App-specific guidance for Pro Expense.
---

# Pro Expense Skill

## Adding Expenses
- Tap the FAB (floating action button) to add a new expense.
- Fill fields sequentially: name → amount → category → note → save. Avoid parallel writes to prevent field misalignment.

## Category Selection
- The category row is a horizontally scrollable RecyclerView. It shows only 5 categories at a time.
- **ALWAYS scroll the category row left/right to reveal all categories before selecting one or concluding a category is missing.**
- Match the category text exactly. Do not guess or substitute a nearby category.

## Finding and Deleting Duplicate Expenses
When asked to delete duplicate expenses:
1. **CRITICAL: The expense list has 40+ items.** You MUST scroll repeatedly (at least 8-10 times) until the list stops moving. Record EVERY expense (name, exact amount including decimals, category) in scratchpad.
2. **Watch for same-name traps**: There may be 3-4 entries with the SAME name but DIFFERENT amounts. These are NOT duplicates. Only TWO entries with the EXACT SAME name AND the EXACT SAME amount are true duplicates.
3. Compare all recorded entries from scratchpad to find the matching pair.
4. Once you find a matching pair, delete exactly ONE of them (either one is fine).
5. To delete: swipe the expense left, or long-press → Delete, or open it → Delete.
6. Do NOT delete entries that merely look similar but have different amounts.
7. If you haven't found duplicates yet, you probably haven't scrolled far enough — keep scrolling!
