---
name: com.google.android.apps.nbu.files
description: App-specific guidance for Google Files (file manager).
---

# Google Files Skill

## Opening Files
- Use `element_index` on the file name, NOT coordinate-based clicks.
- AVOID clicking near left edge (x < 150) — the hamburger menu overlaps that area.
- If side drawer opens accidentally, press Back to close it, then retry with element_index.

## Navigating to Downloads
Downloads is usually the default view. If not, use navigation drawer → "Downloads".

## Opening HTML Files with Chrome
Click file name via `element_index`. If "Open with" chooser appears, select Chrome.
