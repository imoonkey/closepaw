# UI/UX Quality — Cross-Review Notes

## From Security & Privacy Review

### Settings Page: Bottom Sheet → Full Page

**Source:** P2.2 讨论 (security-privacy improvement plan)

当前 Settings 是 `ModalBottomSheet` (skipPartiallyExpanded=true), 随着功能增加 (数据留存控制、更多设置项), 内容越来越多。建议考虑:

1. 将 Settings 从 bottom sheet 改为正常的全屏页面
2. 保留当前三个顶级 section 的导航结构
3. 全屏页面可以更好地支持深层内容和未来扩展

这个改动影响 navigation pattern, 建议作为独立的 UI 改进 item 处理, 不在 security track 里做。
