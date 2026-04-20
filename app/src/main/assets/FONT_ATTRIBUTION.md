# Font Attribution

ClosePaw bundles the following font families under `app/src/main/res/font/`. Each
family must ship its license file alongside the binaries when the assets are
dropped in.

| Family | Files | License |
|---|---|---|
| Geist | `geist_*.ttf` (variable preferred) | SIL OFL 1.1 |
| Fraunces | `fraunces_*.ttf` (variable preferred) | SIL OFL 1.1 |
| JetBrains Mono | `jetbrains_mono_*.ttf` | Apache 2.0 |

Wiring lives in `ai/closepaw/ui/theme/Type.kt`. Until binaries land, the
`FontFamily` aliases fall back to the matching system family (sans / serif /
monospace) so the type role mapping is real and the swap is a one-file change.
