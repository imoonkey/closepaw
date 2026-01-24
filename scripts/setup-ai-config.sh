#!/bin/bash
# Setup symlinks for cross-tool compatibility
# Works with: Claude Code, Cursor, Codex (OpenAI)

set -e

cd "$(dirname "$0")/.."

echo "Setting up AI configuration symlinks..."

# Verify .claude directory exists
if [ ! -d ".claude" ]; then
    echo "Error: .claude directory not found"
    exit 1
fi

# Remove existing symlinks (preserve real directories)
[ -L .cursor ] && rm .cursor
[ -L .codex ] && rm .codex
[ -L AGENTS.md ] && rm AGENTS.md

# Create symlinks for tool directories
ln -sf .claude .cursor
ln -sf .claude .codex

# Create symlink for Codex AGENTS.md compatibility
if [ -f CLAUDE.md ]; then
    ln -sf CLAUDE.md AGENTS.md
fi

echo ""
echo "Symlinks created:"
echo "  .cursor -> .claude"
echo "  .codex -> .claude"
[ -f CLAUDE.md ] && echo "  AGENTS.md -> CLAUDE.md"

echo ""
echo "Directory structure:"
ls -la | grep -E '^\.(claude|cursor|codex)|CLAUDE\.md|AGENTS\.md' || true

echo ""
echo "Configuration files:"
echo "  CLAUDE.md - Project rules (always loaded)"
echo "  .claude/skills/ - Auto-discovered procedural knowledge"
echo "  .claude/agents/ - Specialized subagents"
echo "  .claude/commands/ - Slash command definitions"

echo ""
echo "Setup complete!"
