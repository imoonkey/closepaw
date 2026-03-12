# R37 Analysis — Skill Generalization Round 2

## Run
- ID: `20260311_232252`
- Model: gpt-5.4
- Tasks: 8 (remaining from R36)
- Success rate: **2/8 = 25.0%** (overall: 23/29 with R36 passes)

## Changes Made
Added general guidance to 2 app skills:
1. **Broccoli** — "Recipes with the same title may differ in other fields — open each to compare all fields."
2. **Pro Expense** — "Before tapping Save, re-read the name and amount fields on screen to confirm they match the intended values."

## Results

### Passed (2)
| Task | Score | Notes |
|------|-------|-------|
| BrowserMultiply | 1.0 | Flaky — passed without skill changes |
| ExpenseAddMultipleFromGallery | 1.0 | Flaky — passed without skill changes |

### Failed (3)
| Task | Score | Root Cause |
|------|-------|-----------|
| ExpenseAddMultipleFromMarkor | 0.0 | ActionFailure — field mismatch persists despite guidance |
| RecipeDeleteDuplicateRecipes2 | 0.0 | FalseCompletion — count tracking failure persists |
| RecipeDeleteDuplicateRecipes3 | 0.0 | FalseCompletion — count tracking failure persists |

### No Score / Infra (3)
| Task | Score | Root Cause |
|------|-------|-----------|
| RetroCreatePlaylist | None | Scorer SQL syntax error |
| RetroPlaylistDuration | None | Perception gap + scorer bug |
| RetroSavePlaylist | None | Scorer SQL bug + export failure |

## Key Takeaway
General guidance (one-liners) insufficient for the 3 remaining failures. Root causes are action execution reliability and agent reasoning limitations, not knowledge gaps. Retro tasks parked as infra issues.
