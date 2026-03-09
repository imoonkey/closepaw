# TurnOnWifiAndOpenApp — R17 Analysis

**Run**: 20260309_132808 | **Model**: gpt-5.4 | **Score**: 0.5 | **Turns**: 5

## Summary
Agent opened quick settings, saw "Internet" tile, assumed Wi-Fi was on. Then opened Settings app. Wi-Fi was actually OFF (status bar: "No internet"). Scored 0.5 — Settings open but Wi-Fi not enabled.

## Root Cause: PerceptionError (Wi-Fi state misread)
Agent didn't tap the toggle or verify via shell. Assumed presence of "Internet" tile = Wi-Fi on.

## Fix
For system toggles, prefer shell: `svc wifi enable`. Or add quick settings guidance to verify toggle state before assuming.
