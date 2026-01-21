# Multithread Work SOP

When asked to follow this SOP, you are working in parallel with other Cursor agents on this codebase. You must start a separate git working tree/branch to keep your work isolated and tracked.

## Core Principle

> **Use Git branch isolation: each Cursor conversation binds to its own working tree/branch, enabling parallel code changes without interference.**

---

## Recommended Approach: Git Worktrees

This is the most stable method for parallel work, especially for large repos.

### Setup Commands

```bash
# Create worktrees for parallel features
git worktree add ../repo-feature-a feature/a
git worktree add ../repo-feature-b feature/b
```

### Resulting Directory Structure

```
repo-main/        -> main branch
repo-feature-a/   -> feature/a branch
repo-feature-b/   -> feature/b branch
```

### How It Works

- Each worktree is opened in a **separate Cursor window**
- Each window has its **own conversation**
- Each Cursor window only sees its own branch's code
- Code changes **only affect the corresponding branch**

---

## Workflow for Cursor Agents

When you are assigned to work in a multithread context:

1. **Confirm your working tree/branch** - Ask the user which worktree or branch you are operating on
2. **Verify isolation** - Run `git branch` to confirm you are on the correct branch
3. **Work independently** - Make changes only within your assigned scope
4. **Commit regularly** - Keep your branch up to date with clear commits
5. **Coordinate merges** - The user will handle merging branches back to main

### Pre-Flight Checklist

Before making any changes, verify:

```bash
# Check current branch
git branch --show-current

# Check working tree status
git status

# Verify you're in the correct directory
pwd
```

---

## Notes

- Never switch branches within a conversation without explicit user instruction
- If you encounter merge conflicts or need to interact with another branch, inform the user
- Each conversation should remain scoped to its assigned branch throughout its lifecycle
