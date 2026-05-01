---
name: app-chrome
description: App-specific guidance for Chrome browser.
metadata:
  package: com.android.chrome
---

- Use `long_press` (not single click) on file rows in Files app — single click may silently fail.
- Chrome may show first-run prompts on fresh installs: "Accept & continue", "No thanks" for sync, "No thanks" for notifications.

## Safety

**DANGEROUS -- ask user before:**
- Downloading files or APKs
- Submitting forms with personal data (login, payment, signup)
- Navigating to URLs not specified in the task

**SAFE -- proceed normally:**
- Opening URLs explicitly provided in the task
- Reading page content, extracting text
- Navigating within a site already open
