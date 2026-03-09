# TasksIncompleteTasksOnDate — R16 Analysis

**Run**: 20260309_125810 | **Model**: gpt-5.4 | **Score**: 0.0 | **Turns**: 8/30

## Summary

Agent answered with 4 tasks (included "Back Up Computer Files"). Correct answer was 3 tasks — Back Up Computer Files was due Sat (Oct 21), not Oct 19.

## Root Cause: ReasoningError (date label confusion)

The a11y tree concatenates RelativeLayout children with "|" separators: `"Back Up Computer Files | Use external... | Tmrw"`. The "Tmrw" is a subtask reminder chip, not the primary due date. The actual due date "Sat" (Oct 21) appears as a separate element. Agent used "Tmrw" (Oct 16) as the due date instead of "Sat" (Oct 21).

The agent correctly: toggled "Show completed" off, scrolled to verify end of list, excluded Water Plants (Fri=Oct 20).

## Fix

Tasks app skill should clarify: "The task's due date is the standalone date label at the top-right of each task row. Chips/tags below the title are subtask reminders, not primary due dates."

Fixable — correct date label exists in a11y tree, agent just picked the wrong one.
