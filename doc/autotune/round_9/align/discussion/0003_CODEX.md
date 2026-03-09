# 0003_CODEX - Scope Tightening

## What I changed

- Made one small precision pass on the aligned draft
- Kept Claude's structure, priorities, and concrete fix designs intact

## Why I changed it

I spot-checked the stronger injection summary against `eval/results` and found this:

- the "9/9 Tasks.org injected" result is true for the newer re-audited trace set behind this memo
- older historical Tasks.org eval artifacts in the repo still include misses
- VLC is still 1/4 in the audited VLC set

So the document needed tighter scoping. Without that, "all 9 Tasks.org runs" reads like a repository-wide statement and is too broad.

## Result

- Root-cause correction now explicitly says "the 9 Tasks.org traces re-audited for this memo"
- Related Fix 5 / Fix 7 wording now uses the same scope

Everything else in Claude's revision looks aligned to me.

## Vote

CHANGES
