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
1. Go to the Expense Logs view (open menu → Expense Logs), NOT the "Recent" section on Home.
2. **Scroll the ENTIRE list** (40+ items, scroll at least 10 times until the view stops changing). Record EVERY expense name and EXACT amount in scratchpad after each scroll.
3. **Look for the "perturbed" group**: Several entries will share the SAME name (e.g., 4-5 "Jeans" entries). The true duplicate pair is WITHIN this group — two entries with the EXACT SAME amount. The others have different amounts (they are decoys).
4. If you find a name that appears 4+ times but NO two amounts match, you likely missed 1 entry during scrolling. Go back and scroll more slowly through that section.
5. Once you find TWO entries with IDENTICAL name AND amount, delete exactly ONE.
6. To delete: swipe the expense left, or long-press → Delete, or open it → Delete.
