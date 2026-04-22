# Design: `com.android.settings` App Skill Rewrite

## Goal

Rewrite the Settings app skill into the unified framework so the raw skill body keeps only durable app-local truths: shell restrictions for system toggles, canonical Wi-Fi and Bluetooth routes, and the brightness-slider rule. The runtime already adds `## App Skill` and the package name, so the rewritten body must stand on its own without frontmatter or decorative headings.

## Constraints

- Plain text with short bullets.
- No YAML frontmatter.
- No decorative app-name heading.
- No `CRITICAL` block; this app does not have a hidden-state trap that earns it.
- Remove generic verification and retry language.
- Order content by failure cost: shell failure first, then navigation paths, then brightness.
- Stay compact; Tier 2 is the target, but only if every line earns its place.

## Keep / Remove Decisions

Keep:
- Shell toggles such as `svc wifi enable` fail from the agent process because they require ADB-level permissions.
- Wi-Fi route: `Network & internet -> Internet`; tap the Wi-Fi row to toggle.
- Bluetooth route: `Connected devices -> Connection preferences -> Bluetooth`.
- Brightness should be changed with the Settings slider UI because shell brightness writes are unreliable across devices.

Remove:
- YAML frontmatter.
- `# Android Settings Skill`.
- Section headings from the current draft.
- "Navigate the Settings UI..." because it is generic filler.
- "Verify on the same screen", retry advice, and "Do not declare success" because those are core-prompt behavior, not Settings-specific truth.

## Proposed Final Skill Body

```md
- `svc wifi enable`-style shell toggles need ADB-level permissions and fail from the agent process.
- Use the Settings UI for Wi-Fi and Bluetooth changes.
- Wi-Fi: `Network & internet -> Internet`; tap the Wi-Fi row to toggle.
- Bluetooth: `Connected devices -> Connection preferences -> Bluetooth`; use the toggle there.
- Use the Settings brightness slider UI for brightness changes.
- Shell brightness commands are unreliable across devices.
```

## Trade-offs

- Use flat bullets instead of headers. The content is small enough that headers cost more tokens than they save.
- Keep one bridge line about using the Settings UI so the shell-failure warning scopes the next two route bullets cleanly and still fits the Tier 2 target.
- Do not preserve any verification language; the app does not expose a hidden authoritative state that requires an app-specific verification anchor.

## Self-Review

- The proposed body keeps only stable Settings truths that should help real users beyond the current eval set.
- No solver procedure, retry budget, or eval-specific phrasing remains.
- The draft is compact, ordered by failure cost, and matches the unified framework's raw-text runtime model.
