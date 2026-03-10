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
1. **Scroll the entire expense list** top to bottom. Record every expense (name, amount, category) in scratchpad.
2. Look for TWO entries with IDENTICAL name, amount, AND category — these are the true duplicates.
3. Be careful: some entries may have the same name but different amounts. These are NOT duplicates.
4. Once you find a matching pair, delete exactly ONE of them (either one is fine).
5. To delete: swipe the expense left, or long-press → Delete, or open it → Delete.
6. Do NOT delete entries that merely look similar but have different amounts.
