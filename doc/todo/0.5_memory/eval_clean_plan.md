# Eval Memory Hygiene Plan

## Goal

Make eval runs clean even after introducing `remember_experience` and the persistent memory store.

## Current Gaps

- Eval config can hide `remember_experience` from the tool list, but that alone does not stop:
  - prompt-time memory recall
  - automatic failure retention in `Agent`
  - stale memory files from earlier runs
- Docs currently imply manual `adb rm -rf` is enough, but eval should defend itself in code.

## Plan

1. Exclude `remember_experience` from eval `excluded_tools`.
2. Clear the app memory directory from the eval bridge before each task launch.
3. Set eval configs to exclude `remember_experience` by default.
4. Add regression tests for config loading and bridge launch/cleanup behavior.

## Expected Result

Eval tasks start with no persisted memory, cannot call the long-term memory tool, and cannot carry long-term memory across tasks.
