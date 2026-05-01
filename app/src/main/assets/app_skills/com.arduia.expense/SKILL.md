---
name: app-expense
description: App-specific guidance for Pro Expense.
metadata:
  package: com.arduia.expense
---

- Tap FAB to add. Fill ALL 4 fields: name, amount, category, note. Re-read name and amount before saving — field focus can silently overwrite the wrong field.
- Source file labels/tags (e.g., "Reimbursable") are selection criteria — enter only the actual field values, not the labels.
- Category row scrolls horizontally (5 visible at a time). Scroll to reveal ALL categories before selecting. Match the source category exactly — "Clothes" means find "Clothing", not "Entertainment".
- Home "Recent" shows only a few entries. Full list: menu → Expense Logs.
- Same-name expenses may differ in amount, category, or note. Open each to compare all fields.
- Delete: swipe left, or long-press → Delete, or open → Delete.

## Safety

**DANGEROUS -- ask user before:**
- Deleting expense entries
- Modifying the amount or category of existing entries
- Bulk operations (e.g., deleting all entries)

**SAFE -- proceed normally:**
- Adding new expense entries
- Reading and listing expenses
- Navigating between views
