# Google Files — 改动摘要

**行数**: 20 → 4（-80%）

## 删除了什么
- Section headers（Opening Files, Navigating to Downloads, Move Operations, Opening HTML Files）
- "Click file name via element_index. If 'Open with' chooser appears, select Chrome" — 通用 chooser 交互

## 保留了什么
- `element_index` not coordinates + left edge (x < 150) hamburger 重叠 — accessibility 陷阱
- Side drawer 误开 → Back 关闭
- Downloads 默认视图
- Move: swipe right fallback + shell `mv` fallback
