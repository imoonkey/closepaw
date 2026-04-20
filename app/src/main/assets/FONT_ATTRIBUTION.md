# Font Attribution

ClosePaw bundles the following font families under `app/src/main/res/font/`.
All three are open-source and free for commercial redistribution.

| Family | Files | License | Source |
|---|---|---|---|
| Geist Sans | `geist_regular.ttf`, `geist_medium.ttf` | SIL OFL 1.1 | [vercel/geist-font](https://github.com/vercel/geist-font) (`packages/next/dist/fonts/geist-sans/`) |
| Fraunces 9pt | `fraunces_regular.ttf`, `fraunces_italic.ttf` | SIL OFL 1.1 | [googlefonts/fraunces](https://github.com/googlefonts/fraunces) (`fonts/static/ttf/`, master branch) |
| JetBrains Mono | `jetbrains_mono_regular.ttf`, `jetbrains_mono_medium.ttf` | SIL OFL 1.1 | [JetBrains/JetBrainsMono](https://github.com/JetBrains/JetBrainsMono) (`fonts/ttf/`) |

All three families ship under the SIL Open Font License 1.1 — see each
upstream repository's `OFL.txt` for the full license text.

Wiring lives in `ai/closepaw/ui/theme/Type.kt`. The `FontFamily` aliases
resolve directly to the bundled `R.font.*` resources; system fallbacks are
no longer used.
