# ExpenseAddMultipleFromGallery — Failure Analysis

## Scripted Success Criteria

The `is_successful` method (inherited from `AddMultipleRows` in `sqlite_validators.py:304`) does:

1. Reads all rows from the `expense` table in `/data/data/com.arduia.expense/databases/accounting.db` AFTER the agent runs.
2. Calls `validate_addition_integrity(before, after, reference_rows)` which checks:
   - **Each of the 3 reference rows** must exist in `after` (fuzzy match on `name` and `note`; exact match on `amount` and `category`).
   - `len(after) == len(before) + 3` — exactly 3 new rows, no more, no less.
   - All pre-existing rows in `before` are still present unaltered in `after`.
3. Returns `1.0` only if ALL three conditions pass; `0.0` otherwise.

Compare fields: `name`, `amount`, `category`, `note`.
Free-form (fuzzy) fields: `name`, `note`.
Non-fuzzy fields: `amount` (int, in cents), `category` (int ID).

## Task Setup

`initialize_task` (expense.py:379):

1. Clears device storage (gallery images).
2. Generates 3 random `Expense` objects as targets (`n_rows = 3`) and 10 noise expenses (`n_rows_noise = 10`).
3. Renders the 3 target expenses as a `text_block` image (`expenses.jpg`) with `wrap_width=60` and writes it to gallery via `write_to_gallery`.
4. Renders 10 separate noise images (`old_expenses_0.jpg` through `old_expenses_9.jpg`), each containing the 10 noise expenses, also written to gallery.

The `text_block` format for each expense looks like:
```
Expense: <name>
 amount_dollars: $<amount/100>
 category_name: <category_name>
 note: <note>
```

The goal given to the agent: "Add the expenses from expenses.jpg in Simple Gallery Pro to pro expense."

## Agent's Last Attempt (R30, run 20260309_215257)

19 turns, 104s duration, stop_reason = Error (agent self-reported failure).

**Turn-by-turn summary:**

1. **Turn 1**: `open_app("Simple Gallery Pro")` — app not found or wrong name.
2. **Turn 2**: `open_app("Gallery")` — opens gallery app.
3. **Turn 3**: `click(element_index=11)` — opens DCIM folder, sees thumbnail grid of ALL 11 images (1 target + 10 noise). **Never opened expenses.jpg individually.**
4. **Turn 4**: `scratchpad(write)` — attempts to extract expenses from the **thumbnail grid view**, not from the actual opened image. Stores:
   - Mortgage - $266.49 - Housing - note: Want to have
   - Shoes - $79.65 - Clothes - note: Monthly recurring
   - Specialty Foods - **amount not fully visible**
   - Clothing/Plaid by card - $436.35 - Clothes (noise image data)
   - Furnishing - $350.07 - Housing (noise image data)
   - Dental Care - $89.54 - Health Care (noise image data)
5. **Turns 5-6**: Opens Pro Expense, taps FAB to add expense.
6. **Turns 7-10**: Adds Mortgage ($266.49, Housing) — saved successfully.
7. **Turns 11-17**: Adds Shoes ($79.65, Clothes) — saved successfully (scrolled categories to find Clothes).
8. **Turn 18**: Taps FAB to start third expense.
9. **Turn 19**: `complete_task(status="failure")` — gives up, citing "Specialty Foods had no reliable visible amount" and partial captures.

**Result**: Only 2 of 3 required expenses added. Agent self-reported failure.

## Gap Analysis

| Aspect | Required | Agent Did |
|--------|----------|-----------|
| Read expenses.jpg | Open the specific file to see full content | Read thumbnails from folder grid view |
| Expenses to add | 3 (Mortgage, Shoes, Specialty Foods) | Added 2 (Mortgage, Shoes), abandoned Specialty Foods |
| Amount for Specialty Foods | Had a value (generated randomly, e.g. $436.35 visible in middle column) | Claimed "amount not fully visible" |
| Data source discrimination | Only expenses.jpg content | Mixed target + noise image data in scratchpad |
| Note field | Must match (fuzzy) | Added expenses without notes |

**Root causes (ordered by impact):**

1. **Never opened expenses.jpg**: The agent stayed on the DCIM folder grid, reading tiny thumbnails of all 11 images. It never tapped on a specific image to open it full-screen. The thumbnail grid shows all images at once, making it impossible to distinguish which data belongs to `expenses.jpg` vs the 10 `old_expenses_*.jpg` noise images.

2. **Confused target and noise data**: Because the agent read from the grid view, it mixed data from multiple images. The middle column entries (Specialty Foods $436.35, Furnishing $350.07, Dental Care $39.54) appear to be from a noise image, not the target. The actual `expenses.jpg` target entries (3 items) may have different amounts.

3. **Gave up prematurely**: After adding 2 expenses, the agent declared failure instead of going back to Gallery to properly open and read `expenses.jpg`.

4. **Missing notes on added expenses**: The agent typed name, amount, and category but there is no evidence it entered the `note` field for either Mortgage or Shoes. The validation uses fuzzy match on `note`, but an empty note vs a non-empty reference note would likely fail.

## Verdict

**Fixable** — with a targeted app skill improvement for Simple Gallery Pro.

The core issue is navigational: the agent must open `expenses.jpg` by name (not read thumbnails from the grid). A Gallery app skill should instruct the agent to:
1. Navigate to the DCIM folder.
2. Tap the specific image file (`expenses.jpg`) to open it full-screen.
3. Read the full-resolution content from the opened image.
4. Also: fill in ALL fields including `note` when adding expenses to Pro Expense.

This is not a structural/platform limitation — the agent can tap on individual images and read their content. It simply chose the wrong strategy (reading thumbnails instead of opening the file). An app skill hint like "Always open the named image file to read its content; do not rely on thumbnail previews in folder view" would likely fix this.

Secondary fix needed: the Pro Expense skill should emphasize filling all four fields (name, amount, category, note) for every expense entry.
