# ExpenseDeleteDuplicates2 — Failure Analysis

## Scripted Success Criteria

The scoring chain is: `ExpenseDeleteDuplicates2` -> `_ExpenseDeleteDuplicates` -> `DeleteDuplicateRows` -> `DeleteMultipleRows`.

`DeleteMultipleRows.is_successful` calls `validate_deletion_integrity(before, after)`.

`_ExpenseDeleteDuplicates.validate_deletion_integrity` checks:
```python
target1, target2 = self.rows_to_delete   # the two identical DB rows
return validate_rows_removal_integrity(before, after, [target1.expense_id], db_key) \
    or validate_rows_removal_integrity(before, after, [target2.expense_id], db_key)
```

`validate_rows_removal_integrity` verifies:
1. The specified row ID is absent from `after`.
2. Every other row in `before` is still present in `after` (no collateral deletions).
3. No new rows were added.

**Bottom line**: exactly one of the two duplicate rows (by DB `expense_id`) must be deleted, and all other 40 noise rows must remain untouched.

## Task Setup

`generate_random_params` creates:
- 1 target expense (random name, amount, category, note, timestamps).
- That target is duplicated: `ROW_OBJECTS = [target, target]` — two rows with identical `name`, `amount`, `category`, `note`, `created_date`, `modified_date` (only `expense_id` differs after DB insertion).
- 38 noise rows (`n_rows_noise=40`, minus 3 used for perturbations, plus the popped target = 38 unique noise).
- 3 **perturbation rows**: same `name` as the target, but with `amount` offset by a random 50-1000 cents, and randomized timestamps. These are added to the noise list.

The result in the DB: 42 rows total. Among those, the target `name` appears **5 times** (2 exact duplicates + 3 perturbations with different amounts/dates). The agent must identify which two share **all** field values and delete exactly one.

## Agent's Last Attempt (R28, run 20260309_210911)

- **33 turns, 35 tool calls, score 0.0**
- Agent opened Pro Expense, navigated to Expense Logs, and scrolled through the full list over ~16 turns, recording expenses in scratchpad segments (scans 1-5).
- Scratchpad notes show the agent recognized a cluster of "Jeans" entries with different amounts (500.10, 501.98, 499.38, 496.36) — these are likely the perturbations plus the duplicate pair.
- After reviewing all scans (turns 16-21), the agent scrolled back through the list looking for exact duplicates.
- **Critical mistake**: the agent deleted a "Pest Control" entry (amount 234.63, date Oct 7), claiming it appeared twice. However, Pest Control was a noise row, not the actual duplicate pair. The real duplicate pair shared the same name AND amount AND category AND note AND dates — the agent confused visual similarity (same name appearing multiple times due to perturbations) with exact duplication.
- Agent called `complete_task` with status "success", reporting deletion of "Pest Control | 2023 Oct 7 | 234.63".

**Result**: The agent deleted a noise row instead of one of the actual duplicate pair, causing `validate_rows_removal_integrity` to fail because (a) a non-target row was removed and (b) both actual duplicate rows remained.

## Gap Analysis

| Aspect | Scripted Expectation | Agent Behavior |
|--------|---------------------|----------------|
| Duplicate definition | All fields identical except `expense_id` (name, amount, category, note, created_date, modified_date) | Agent compared only name + amount visible in list view |
| Perturbation trap | 3 same-name entries with different amounts exist as distractors | Agent noticed the Jeans cluster but could not reliably distinguish exact duplicates from perturbations in list view |
| Which row deleted | One of 2 target rows | Deleted a noise row (Pest Control) |
| Fields checked | All 6 non-ID fields | Only name + amount (category, note, dates not compared) |

**Root cause**: The Pro Expense list view shows only name, amount, and date. The agent cannot see `category` or `note` in the list. To identify exact duplicates, the agent would need to tap into each candidate's detail view and compare ALL fields — but the agent only compared what was visible in the list. With 42 rows and 5 sharing the same name, the list-level scan was insufficient. The agent then incorrectly concluded "Pest Control" was duplicated (possibly two entries happened to be adjacent after scrolling) and deleted the wrong row entirely.

## Verdict

**Fixable with skill improvement, but expensive at runtime.**

The core issue is the agent's duplicate-detection strategy: it only compared list-visible fields (name, amount, date) instead of opening detail views to compare all fields. An app skill could instruct the agent to:

1. First pass: scan list, group entries by name.
2. For any name appearing 2+ times, open each entry's detail view and record all fields (name, amount, category, note, date).
3. Only flag entries as duplicates if ALL fields match.
4. Delete exactly one from each duplicate group.

However, with 42 rows and potentially many name collisions (5 for the target name), this requires many detail-view taps. The 50-turn limit should be sufficient (33 turns used in this attempt), but the strategy change is non-trivial. An alternative: use the shell tool to query the SQLite DB directly (`sqlite3 /data/data/com.arduia.expense/databases/accounting.db`), which would give exact field comparison — but this requires the DB path to be accessible (no root needed if the agent process has the same UID, which it does not).

**Classification**: Fixable via skill update (detail-view comparison strategy). Not structural.
