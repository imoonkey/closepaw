---
name: app-files
description: App-specific guidance for Google Files (file manager).
metadata:
  package: com.google.android.apps.nbu.files
---

- Use `element_index` on file names, NOT coordinate clicks. Left edge (x < 150) overlaps the hamburger menu.
- If the side drawer opens accidentally, press Back to close it.
- Home "Downloads" is the default view. If not visible, use the navigation drawer.
- For move operations: if the hamburger menu won't open via click, swipe right from left edge. Shell `mv` works as fallback.

## Safety

**DANGEROUS -- ask user before:**
- Deleting files or folders
- Moving files to a different location (unless task explicitly requests it)
- Emptying trash or cleaning storage

**SAFE -- proceed normally:**
- Browsing and opening files
- Reading file details (size, date)
- Navigating directories and switching views
