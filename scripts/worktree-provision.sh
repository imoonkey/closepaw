#!/usr/bin/env bash
# Worktree provision hook — called by orchestrate's worktree-create.sh after `git worktree add`.
# cwd = the new worktree. Symlinks per-machine untracked files from the main checkout so
# `./gradlew` and `scripts/debug-run.sh` work immediately.
set -euo pipefail

common_git_dir="$(git rev-parse --git-common-dir)"
main_checkout="$(cd "$(dirname "$common_git_dir")" && pwd)"

if [[ "$main_checkout" == "$(pwd)" ]]; then
  echo "worktree-provision: cwd is the main checkout — nothing to do" >&2
  exit 0
fi

link_if_present() {
  local name="$1"
  local src="$main_checkout/$name"
  if [[ ! -e "$src" ]]; then
    echo "worktree-provision: $name not present in main checkout, skipping" >&2
    return 0
  fi
  if [[ -e "$name" || -L "$name" ]]; then
    echo "worktree-provision: $name already present, skipping" >&2
    return 0
  fi
  ln -s "$src" "$name"
  echo "worktree-provision: linked $name → $src" >&2
}

link_if_present .env
link_if_present local.properties
