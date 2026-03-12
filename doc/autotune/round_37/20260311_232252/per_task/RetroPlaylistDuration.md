# RetroPlaylistDuration — R37

**Score**: None | **Turns**: 20 | **Outcome**: Error (PerceptionGap + ScorerBug)

## What Happened
Agent created playlist "Reggae Rhythms 51" but couldn't find song durations in Retro Music's accessibility data or UI. The Songs list shows titles and artists but no track lengths. Agent attempted shell commands and UI exploration but ultimately declared failure, unable to build a playlist totaling 45-50 minutes without duration information.

## Root Cause
Song durations not exposed in accessibility tree. The removed "Duration-Constrained Playlists" section prescribed an add-all-then-prune workaround. Without it, the agent correctly identifies the limitation and gives up. Additionally, scorer has SQL syntax error.

## Classification
PerceptionGap + ScorerBug

## Actionable?
No — restoring the duration algorithm would be eval-specific. Accepted regression.
