# Markor — 改动摘要

**行数**: 27 → 15（-44%）

## 删除了什么
- **CRITICAL 降级**: "scroll entire file list before selecting" 从 CRITICAL 降为普通 bullet — 这是通用 scan 规则，不是 app 特定 CRITICAL
- "Use the Markor UI for all file operations" — 显而易见
- Editing Tips: "Jump to Beginning" / "Undo" 保留但降级（从独立 section 变成普通 section）
- File Sorting: "Shell `ls -lt` works for Markor files" — tool 层面知识，不属于 app skill
- "Navigate Up or Back to return to file list" — 通用导航

## 保留了什么
- New-file extension 处理 — app quirk（name 和 extension 是分开的 field，extension 默认 `.md`，需要手动 clear）
- File list 可能在 fold 下方
- Exact filename match（`prefix_foo.md` ≠ `foo.md`）
- Move/copy 后验证
- Sort by date（3-dot → Sort → Date）+ 默认 ascending
- Editing 快捷键（Special Keys → Jump to Beginning / Undo）
