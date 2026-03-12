---
name: com.arduia.expense
description: App-specific guidance for Pro Expense.
---

# Pro Expense Skill

## Adding Expenses
- Tap the FAB (floating action button) to add a new expense.
- **Fill ALL 4 fields**: name → amount → category → note → save. Do not skip any field. Avoid parallel writes to prevent field misalignment.
- Before tapping Save, re-read the name and amount fields on screen to confirm they match the intended values — field focus issues can silently overwrite the wrong field.

## Category Selection
- The category row is a horizontally scrollable RecyclerView. It shows only 5 categories at a time.
- **ALWAYS scroll the category row left/right to reveal all categories before selecting one or concluding a category is missing.**
- Match the category text exactly. Do not guess or substitute a nearby category.

## Viewing All Expenses
- The "Recent" section on the Home screen shows only a few entries. For a complete view, open the menu → Expense Logs.
- The Expense Logs list may be longer than one screen — scroll down to see all entries.

## Comparing Expenses
- Expenses with the same name may differ in amount, category, or note. Open each expense to compare all fields before concluding they are identical.

## Deleting Expenses
- Swipe an expense left, or long-press → Delete, or open it → Delete.
