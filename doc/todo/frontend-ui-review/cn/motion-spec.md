# Motion Spec —— "Breath, not bounce"

ClosePaw 里每一个动画都遵循三条规则：

1. **只有四种 duration** —— `120ms`（micro）、`240ms`（transition）、`480ms`（mode）、`900ms`（breath）。
2. **只有两种 easing** —— `EaseInOutSine`（呼吸、loop）、`EaseOutCubic`（entry、transition）。绝不 `spring`，绝不 overshoot。
3. **每个 screen 只有一次 orchestrated entrance** —— 错开 120ms。之后的一切都是小且局部的。Delight 是被结构性地保持稀有的。

> 英文原版：[`../motion-spec.md`](../motion-spec.md)

---

## 1. The Breath —— 标志性动作

Capsule 在 Running 状态下的爪印：

```kotlin
val breath by rememberInfiniteTransition().animateFloat(
    initialValue = 1.0f, targetValue = 1.04f,
    animationSpec = infiniteRepeatable(
        animation  = tween(900, easing = EaseInOutSine),
        repeatMode = RepeatMode.Reverse,
    ),
)
Modifier.graphicsLayer {
    scaleX = breath; scaleY = breath
    // 同步 alpha 呼吸，更微妙
    alpha = 0.85f + (breath - 1.0f) * 3.75f   // 0.85 ↔ 1.0
}
```

吸 900ms，呼 900ms。总周期 1.8s。冷静、深思、活的。

**绝对不要应用 breath 到：**
- Takeover（已暂停 —— 爪必须**冻住**）
- WaitingForInput / Approval（注意力应在 question 上，而不是爪上）
- Done / Error（终态 —— 没有 loop）

---

## 2. Capsule mode transitions

任意两个非 Hidden mode 之间，用 `AnimatedContent`：

```kotlin
AnimatedContent(
    targetState = mode,
    transitionSpec = {
        (slideInVertically(tween(240, easing = EaseOutCubic)) { it / 3 } +
         fadeIn(tween(240))) togetherWith
        (slideOutVertically(tween(240, easing = EaseOutCubic)) { -it / 3 } +
         fadeOut(tween(120)))
    },
)
```

**特殊情况：**

| Transition | 覆盖 |
|---|---|
| Running → Done | 爪眨一下（scale 1.0 → 1.2 → 1.0，480ms，claw → moss 颜色 crossfade），停 900ms 后整个 capsule `scaleOut(0.96f) + fadeOut`（480ms） |
| Any → Error | 进入时横向抖动：`±3dp，120ms，3 个周期`。爪变 rust，无 breath。 |
| Running → Takeover | 爪**呼吸到一半冻住** —— 读当前 scale，120ms 内回到 1.0。颜色 240ms 内 crossfade claw → amber。 |
| Hidden → Running | 480ms：capsule 从底部上滑（起始 offset +24dp），爪到位时盖章（scale 0 → 1.0，240ms，延迟 240ms 进入 entrance） |

---

## 3. Action Visualizer

### Tap（click）
```
t=0ms    : claw 实心圆 r=6dp，alpha=1.0
t=0-280  : 扩散到 r=48dp，alpha 1.0 → 0（EaseOutCubic）
t=80ms   : satellite ring 出现，仅 stroke，r=12dp
t=80-360 : satellite 扩散到 r=64dp，alpha 0.6 → 0
```

### Long-press（按住）
```
t=0-240  : 同 tap entry，但 ring 冻在 r=32dp
t=240+   : 内部填充脉动：alpha 0.4 ↔ 0.7，900ms EaseInOutSine
t=松开    : ring 180ms 内塌缩到 r=0，tap 位置 fade in 一个爪印 stamp
```

### Swipe
```
Path 在 360ms 内从 start 画到 end（EaseOutCubic）。
Stroke 是 taper 的：起点 4dp，终点 2dp。
Path 加 perlin noise 偏移：±1.5px，每次 swipe 单独 seed（一致的 wobble）。
到达终点时，爪印用 claw 盖章，scale 0→1（160ms），停 200ms，fade out 240ms。
完整动画：760ms —— 仍在一个 mode-transition 预算内。
```

### Scroll vs. swipe
通过颜色**和**动作区分：
- **Swipe**（user-like 意图）：claw，终点有爪印 stamp。
- **Scroll**（reading 意图）：ink 40% alpha，无 stamp，无 taper。

---

## 4. Chat 屏幕 —— thinking indicator

三个 toe 依次填色：

```
t=0      : toe₁ ink @ 100%，toe₂ @ 30%，toe₃ @ 30%，pad @ 30%
t=225ms  : toe₂ → 100%
t=450ms  : toe₃ → 100%
t=675ms  : pad  → 100%
t=900ms  : reset —— 全部回到 30%
```

总时长 900ms = 半次 breath。和整体 motion 语言**节拍同步**，让 app 感觉
是 tempo-locked 的。

---

## 5. 冷启动 entrance（orchestrated）

这是 app 里**唯一**的 delight 时刻。每个进程第一次 compose `ChatScreen`
时跑：

```
t=0      : Paper 背景从 Ink → Paper 淡入（240ms）
t=120    : Fraunces 标题 "ClosePaw" 落位 —— translateY(-8dp → 0) + fadeIn，480ms EaseOutCubic
t=240    : Header 里爪印盖章 —— scale 0 → 1，claw，240ms EaseOutCubic
t=360    : Chat area 淡入 + translateY(8dp → 0)，480ms
t=840    : 如果 Capsule active，从底部上滑，480ms
```

`t=1320ms` 之后 app 进入安静状态。除非 user 或 agent 行动，否则不再有
预定动画。

---

## 6. Edge glow —— 活的光晕

现状：边缘静态 linear gradient。

提议：additive drift。

```kotlin
val drift by rememberInfiniteTransition().animateFloat(
    initialValue = -1f, targetValue = 1f,
    animationSpec = infiniteRepeatable(
        tween(8000, easing = EaseInOutSine), RepeatMode.Reverse,
    ),
)
// 应用到 radial gradient 的 center：
// center = Offset(edgeAnchor.x + drift * 2.dp.toPx(), edgeAnchor.y)
// Opacity 上限 0.12，不是当前那个更强的 gradient。
```

Glow 看上去和 capsule 一起呼吸（同样 8s 周期，正好是约 4.5 个 breath
cycle —— 故意 offbeat，让它感觉 organic 而不是 lock-stepped）。

---

## 7. **绝对不要**做动画的东西

抵抗诱惑：
- **不要按钮按下 scale。** 按钮只用颜色变深响应（pressed state token）。
- **不要 ripple。** Material ripple 是 off-brand；用 120ms 的 `Pressed` 颜色代替。
- **不要 list item enter 动画。** Session history 瞬时 render —— 它是账本，不是流。
- **不要 loading skeleton。** 要么内容已经在那里（带文字的纸），要么显示 thinking indicator。Skeleton 是 web pattern。
- **不要 parallax。** 编辑式田野笔记本里没有任何东西做 parallax。

克制本身就是重点。每一个你**不**加的动画都让你**加**的动画感觉是
intentional 的。
