---
name: com.google.android.apps.nbu.files
description: App-specific guidance for Google Files (file manager).
---

# Google Files Skill

## CRITICAL: Opening Files
When tapping a file in the file list:
- Use `element_index` targeting on the file name text element, NOT coordinate-based clicks
- AVOID clicking near the left edge (x < 150) — the hamburger/navigation menu button overlaps that area and will open the side drawer instead of the file
- If the side drawer opens accidentally, tap on the main content area or press Back to close it, then retry with element_index

## Navigating to Downloads
1. The Downloads folder is usually the default view
2. If not, use the navigation drawer (hamburger menu) and select "Downloads"
3. Files are listed with name and size info

## Opening HTML Files with Chrome
1. Find the file (e.g., task.html) in the Downloads list
2. Click the file name text using `element_index`
3. When the "Open with" chooser appears, select Chrome
4. If no chooser appears and the file opens directly, that's fine too
