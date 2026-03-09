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
