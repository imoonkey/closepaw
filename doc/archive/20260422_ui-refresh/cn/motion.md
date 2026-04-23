# Motion delta

> 这一轮所有新 motion 都复用现有 `ClosePawMotion` 的 duration 与 easing。
> 无新原语。每个 call site 都尊重 reduced-motion 契约。

## EmptyState 的 staggered reveal

| 步 | 元素 | 延迟 | Motion |
|---|---|---|---|
| 0 | "What can I help you with?" headline | 0ms | `fadeIn(TraceEnter) + slideInVertically(8dp → 0)` |
| 1 | Hairline rule | 80ms | 同上 |
| 2 | Suggestion 1 | 160ms | 同上 |
| 3 | Suggestion 2 | 240ms | 同上 |
| 4 | Suggestion 3 | 320ms | 同上 |

总感知加载 = ~560ms。右上 paw 立即按 full alpha 渲染（它锚定构图；其它元素围
绕它入场）。

Reduced-motion：每一步立即可见，无动画。

## Capsule ledger 计数器

`mode is CapsuleMode.Running` 时按 1Hz 更新。计数器文本本身不被动画——只是变值。
status dot 上的 `animateColorAsState`（`SmartCapsuleSurface.kt:242`）保留现有
`StatusFlip`（120ms）。

Reduced-motion：ticker 继续运行（这是 status signal，不是装饰——与 streaming
cursor blink 同类，对应 `Motion.kt:42-43`）。

## Send-button breath

| 阶段 | 起 | 止 | Duration | Easing |
|---|---|---|---|---|
| 1 | scale 1.0 | scale 1.04 | 120ms (`SurfaceSwap / 2`) | `EaseOutCubic` |
| 2 | scale 1.04 | scale 1.0 | 120ms (`SurfaceSwap / 2`) | `EaseOutCubic` |

仅在产生非空 submit 的点击时触发。空/disabled 点击不 breath。

Reduced-motion：完全跳过动画；立即 submit。

## Fleuron 与 PageMasthead

无 motion。它们是静态 ornament。（忍住给 fleuron 做淡入的冲动。印刷的 fleuron
不会淡入——它一直就在 page 上。）

## Paper grain

无 motion。texture 每个 session 烘焙一次。

## 不动的部分

- Capsule breath（`CapsuleBreath = 900ms`，`EaseInOutSine`，alpha 0.6 → 1.0
  reverse loop）—— 不变。
- Paw-toe 思考序列 —— 不变。
- Status dot 的颜色 flip —— 不变。
- Trace item 入场 —— 不变。
- Settings page slide —— 不变。
- Streaming cursor blink —— 不变。
- Overlay fade-out —— 不变。
