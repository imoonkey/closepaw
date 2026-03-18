# Android Settings Skill Rewrite — Claude Design

## Current State

17 lines (including frontmatter, headings, blanks). All Settings eval tasks passing:
SystemWifiTurnOn/Off(Verify), SystemBluetoothTurnOn/Off(Verify), SystemBrightnessMax/Min(Verify), TurnOffWifiAndTurnOnBluetooth, TurnOnWifiAndOpenApp.

## Line-by-Line Classification

| Line | Content | Verdict | Reason |
|------|---------|---------|--------|
| 1-4 | YAML frontmatter | REMOVE | Runtime doesn't parse; wastes tokens |
| 6 | `# Android Settings Skill` | REMOVE | Runtime wraps with `## App Skill` + package name |
| 8 | `## System Toggles (Wi-Fi, Bluetooth)` | REMOVE | Merge into bullets |
| 9 | "Navigate the Settings UI to toggle..." | REMOVE | Generic/obvious filler |
| 10 | Wi-Fi nav path | KEEP (APP) | Canonical route — non-obvious 3-level nesting |
| 11 | Bluetooth nav path | KEEP (APP) | Canonical route — deeply nested, easy to get lost |
| 12 | Shell commands fail from agent | KEEP (APP) | Platform quirk — shell `svc` silently fails without ADB perms |
| 13 | Verify toggle + retry | REMOVE (CORE) | Generic verification; core prompt handles this |
| 15 | `## Brightness` | REMOVE | Merge into bullets |
| 16 | Slider UI, shell unreliable | KEEP (APP) | Platform quirk — shell brightness commands device-dependent |

## CRITICAL Assessment

No CRITICAL block needed. The shell restriction is important but does not meet the CRITICAL protocol:
- The failure is not silent — the command errors out.
- The agent can recover by falling back to UI navigation.
- No hidden state or destructive trap.

Shell restriction earns top position by failure cost but as a regular bullet.

## Proposed Skill (8 lines)

```
Shell commands (svc, settings put) require ADB permissions and fail from the agent process. Always use UI navigation.
- Wi-Fi: Network & internet -> Internet -> tap Wi-Fi toggle
- Bluetooth: Connected devices -> Connection preferences -> Bluetooth -> toggle
- Brightness: use the Settings slider, not shell commands
```

## Design Rationale

### Ordering (by failure cost)
1. **Shell restriction** (line 1): Highest cost — agent could waste multiple turns attempting shell commands for any Settings task. Stated as a general rule covering all Settings operations, not just Wi-Fi.
2. **Wi-Fi path** (line 2): Non-obvious 3-level nesting.
3. **Bluetooth path** (line 3): Deepest nesting of the three.
4. **Brightness** (line 4): Reinforces "use UI" for the one remaining common task, since shell brightness is tempting but unreliable.

### What was removed and why
- **YAML frontmatter**: Runtime ignores it. Pure token waste.
- **Decorative heading**: Runtime already provides `## App Skill — com.android.settings`.
- **Section headers**: With only 4 bullets, headers add overhead without aiding comprehension.
- **"Navigate the Settings UI..."**: Generic filler that says nothing the bullets don't already say.
- **Verify/retry paragraph**: Generic verification behavior. The core prompt already covers "verify state after action" and "retry if state didn't change". No app-specific verification anchor needed — the toggle state is visible on the same screen.

### What was kept and why
- **Shell restriction**: This is the single most important app-local fact. The agent's shell tool runs inside the agent process without ADB privileges, so `svc wifi enable/disable`, `settings put system screen_brightness`, etc. all fail. This is a platform quirk specific to how the agent interacts with Settings. Generalized from Wi-Fi-only to cover all shell commands.
- **Nav paths**: Three non-trivial navigation routes. Wi-Fi is 3 levels deep, Bluetooth is 4 levels deep. Without these, the agent would waste turns exploring menus.
- **Brightness slider note**: Reinforces shell restriction for the specific case where a shell shortcut is most tempting.

### Tier fit
- **Tier 2** target: 6-12 lines.
- Proposed: 4 content lines (8 raw lines counting the blank line — well within budget).
- Could arguably be Tier 1, but the 3 distinct nav paths plus the shell restriction justify Tier 2 classification.

## Review Checklist

- [x] Would this help with a different real-user task in the same app? Yes — any Settings toggle task benefits from the nav paths and shell warning.
- [x] Does any line describe how to solve a benchmark? No — all lines describe app behavior.
- [x] Could this line be said without mentioning the app? The shell restriction is app-specific (agent process lacks ADB perms). Nav paths are inherently app-specific.
- [x] Is the first line the most important app truth? Yes — shell failure is the highest-cost mistake.
- [x] Is the skill as short as it can be without losing a real app constraint? Yes — 4 bullets, no filler.

## Risk Assessment

**Low risk.** All changes are subtractive (removing generic content and decoration). The three app facts that matter are preserved. All 14 Settings eval tasks are currently passing and should remain unaffected since:
- Nav paths are unchanged (only reformatted slightly).
- Shell restriction is generalized (covers more cases, not fewer).
- Removed content was generic verification language the core prompt already provides.
