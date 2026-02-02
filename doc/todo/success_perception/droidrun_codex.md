# DroidRun - a11y tree filtering/formatting notes (focus: `resourceId`)

## Sources (local)
- `.reference/mobile_agent/droidrun/droidrun/tools/android/adb.py`
- `.reference/mobile_agent/droidrun/droidrun/tools/filters/concise_filter.py`
- `.reference/mobile_agent/droidrun/droidrun/tools/filters/detailed_filter.py`
- `.reference/mobile_agent/droidrun/droidrun/tools/formatters/indexed_formatter.py`

## 1) How it captures + represents the hierarchy
- Capture: `portal.get_state()` returns `a11y_tree` (already JSON, with nested `children`).
- Core fields used downstream:
  - `resourceId`
  - `contentDescription` / `text`
  - `boundsInScreen` (left/top/right/bottom)
  - `className`

## 2) Filtering: keep tree useful + smaller
DroidRun explicitly filters *before* formatting:
- `ConciseFilter`:
  - drop nodes fully outside screen
  - drop nodes smaller than `min_element_size`
- `DetailedFilter`:
  - optional clip bounds to screen
  - can remove keyboard elements via `resourceId` prefix (e.g. Gboard IDs)
  - remove nodes with <10% visible area unless they have surviving children

This is a concrete “token budget / relevance” tactic we can port to our Perceptor.

## 3) Formatting: index assignment + flattening
- `IndexedFormatter` flattens the (filtered) tree and assigns a sequential `index`.
- Output element dict includes:
  - `index`, `resourceId`, `className` (short), `text` (fallback to contentDescription/resourceId), `bounds` (string `"l,t,r,b"`).
- DroidRun is primarily **index-based** for actions (tap_by_index), but it still carries `resourceId` through the pipeline for display/debugging and other workflows.

## 4) Bonus: overlap-aware tapping
- `tap_on_index` computes overlapping “blockers” and chooses a clear tap point inside the target bounds.
- This is a pragmatic fix for “element is covered / occluded”, which pure `resourceId` or center-tap can’t solve reliably.

