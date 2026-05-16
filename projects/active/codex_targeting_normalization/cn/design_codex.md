# Codex Targeting Normalization Design

## Goal

Codex-authenticated runs 有时会发出同时带 semantic target 和 raw coordinates 的 `mobile_action`，例如：

```json
{"action":"click","element_index":14,"x":540,"y":1230}
```

当前 runtime 会在 validation 阶段 reject，因为 `element_index`、`text`、`x/y` 被视为 mutually exclusive target methods。即使 semantic target 本身有效，这也会浪费一个 turn。

我们要接受这种常见的 Codex shape，但不能回到旧的 multi-selector fallback chain。一个 action 仍然应该只执行一个 canonical target。

## Current State

- `MobileActionTool.validateTargetedAction()` 会统计 `element_index`、`text`、`x/y`，超过一个就 reject。
- `MobileActionTool.parseOptionalTarget()` 已经有单一 target priority：`element_index` -> `text` -> `x/y`。
- `TargetResolver` 只把一个 `Target` resolve 成 point，加上可选的 bounds 和 semantic hint。
- `PointActionExecutorCore` 对 semantic targets 使用 node actions，对 coordinate targets 只走 gesture dispatch。
- `TypeExecutor` 独立 resolve target，目前拿到 point 后会丢掉 resolver warnings、bounds、semantic hint。
- `ScrollExecutor` 目前会 reject 没有 bounds 的 explicit semantic target，但这是 incidental behavior，不是明确 contract。
- `ActionDescriptionFormatter` 的 display priority 不同：`text` -> bounds -> point -> element，所以 dual-target call 的展示可能和 `MobileActionTool` 实际执行不一致。

## Approach

在 tool boundary 做 normalization。

保持 executor contract 简单：下游 action executors 仍然只接收一个 canonical target。唯一新增概念是 semantic targets 可以携带一个 model 提供的 optional coordinate hint。

Target priority:

1. `element_index`
2. `text`
3. `x/y`

Validation rules:

- `element_index` 和 `text` 仍然 mutually exclusive。两者同时出现时 reject，因为这是 ambiguous。
- 只要出现 `x` 或 `y`，就必须同时提供完整且 non-negative 的 `x/y`。
- `element_index + x/y` 只有在 `element_index >= 0` 时 valid。
- `text + x/y` 只有在 `text` non-blank 时 valid。
- `element_index + text + x/y` invalid，因为有两个 semantic targets。
- `text_index` 仍然 requires `text`。
- Bounds selectors (`x1/y1/x2/y2`) 继续 reject。
- `scroll` 允许 no target、semantic target、或 semantic target plus `x/y` hint。Bare `x/y` scroll invalid，因为 scroll 是 area-based。

Execution rules:

- 如果 semantic target resolves 且没有 coordinate hint，行为和现在完全一致。
- 如果 semantic target resolves 且 coordinate hint 在 resolved bounds 内，执行 semantic target。`click` / `long_press` 仍然优先用 node action。coordinate 只是 redundant evidence。
- In-bounds check 使用 strict half-open containment，匹配 Android `Rect.contains`：`left <= x < right && top <= y < bottom`。v1 不加 slop。如果真实 eval 显示 false ambiguity，再用 evidence 调整。
- 如果 semantic target resolves 但 coordinate hint 在 resolved bounds 外，dispatch 前 fail，返回 `Ambiguous` result。不要猜。
- 如果 semantic target 没有 resolve，但存在 coordinate hint，fallback 到 coordinate，带 warning，并标记 `coordinateFallback=true`。
- 如果 semantic target 没有 resolve，且没有 coordinate hint，像今天一样 fail。

对 point actions (`click`, `long_press`)，coordinate fallback 必须 skip semantic node-action channels，只用 gesture channels，因为没有 resolved semantic node。

对 `type`，coordinate fallback 是允许且明确的：

- semantic resolved：保留当前 direct `SetTextOnNodeAt` first，然后 tap-to-focus fallback；
- semantic missed + coordinate hint fallback：skip direct `SetTextOnNodeAt`，先 tap coordinate focus，再 `SetTextOnFocused`；
- 把 resolver warnings 带进 success/failure message，让 agent 知道 semantic targeting failed，并用了 coordinate fallback；
- pure coordinate `type` 保持当前行为，除非 implementation 证明同样的 tap-to-focus path 更安全。

对 `scroll`，不要使用 coordinate fallback。Scroll targeting 是 area-based，不是 point-based。`element_index + x/y` 可以通过 validation，但 canonical scroll target 仍然是 element。如果 explicit scroll element 无法 resolve，或者通过 `coordinateFallback=true` resolve，就按 unresolved scroll target fail。

对 `swipe`，不变。`start` 和 `end` 已经是 canonical coordinates。

## Components

### `Target`

给 semantic target variants 增加 optional coordinate hint：

```kotlin
sealed interface Target {
    data class ElementIndex(
        val index: Int,
        val coordinateHint: Coordinate? = null
    ) : Target

    data class Text(
        val text: String,
        val textIndex: Int = 0,
        val coordinateHint: Coordinate? = null
    ) : Target

    data class Coordinate(val x: Int, val y: Int) : Target
}
```

Default arguments 保持现有 construction sites source-compatible，并在 hint absent 时保留 equality。

### `TargetResolver`

先 resolve semantic targets。

为 ambiguous evidence 增加明确 result state：

```kotlin
sealed interface ResolveResult {
    data class Resolved(
        val point: Point,
        val bounds: Bounds? = null,
        val semanticHint: SemanticTargetHint? = null,
        val warnings: List<String> = emptyList(),
        val coordinateFallback: Boolean = false
    ) : ResolveResult

    data class NotFound(val reason: String) : ResolveResult
    data class Ambiguous(val reason: String) : ResolveResult
}
```

Resolution behavior:

- semantic target resolves, no hint：返回 semantic `Resolved`。
- semantic target resolves, hint inside bounds：返回 semantic `Resolved`；不要使用 hint point。
- semantic target resolves, hint outside bounds：返回 `Ambiguous("Ambiguous target: ...")`。
- semantic target misses, hint exists：返回 `Resolved(point = hintPoint, warnings = listOf("Used coordinate fallback after semantic target failed: ..."), coordinateFallback = true)`。
- semantic target misses, no hint：像今天一样返回 `NotFound`。
- coordinate target：不变，`coordinateFallback=false`。

### `MobileActionTool`

把 one-of count check 改成 canonical target validation：

- 分开 validate semantic count 和 coordinate presence；
- reject `hasElement && hasText`；
- 只要 coordinates 出现，就独立 validate complete, non-negative `x/y`；
- 即使有 valid coordinates，也继续 reject negative `element_index`；
- parse semantic target 时，把 `Target.Coordinate(x, y)` 挂到 `coordinateHint`；
- description 只描述 canonical primary target，不描述 hint。

Tool description 不要再说 coordinates 是 hard-mutually-exclusive。使用具体 wording：

> Prefer exactly one target. If both a semantic target and x/y are supplied, the semantic target is primary and x/y is treated only as a fallback hint.

### `PointActionExecutorCore`

把 semantic-channel guard 从 target-shape based 改成 resolved-semantics based：

```kotlin
if (channel.requiresSemantic && semanticHint == null) continue
```

在 action-result 层面，`ResolveResult.Ambiguous` 和 `NotFound` 都 map 成 `ActionOutcome.Failed`，但保留不同 reason string。

这样 coordinate-only calls 的行为不变，同时 semantic-miss coordinate fallback 不会在没有 resolved semantic node 时尝试 `ClickNodeAt` / `LongClickNodeAt`。

### `TypeExecutor`

保留当前 semantic-resolved path。新增明确的 coordinate-fallback path：

```text
semantic target resolved:
  SetTextOnNodeAt -> TapAt + SetTextOnFocused fallback

semantic target missed but coordinateHint fallback resolved:
  TapAt -> SetTextOnFocused

pure coordinate target:
  unchanged for this design
```

所有 `Resolved.warnings` 都必须通过 `formatActionMessage` 附加到 success 和 failure messages。

### `ScrollExecutor`

明确 reject coordinate fallback，而不是依赖偶然行为：

```kotlin
if (resolved is Resolved && resolved.coordinateFallback) return null
```

不要给 coordinate fallback results 附加 synthetic bounds。

### `ActionDescriptionFormatter`

让 display priority 和 execution priority 对齐：

1. `element_index`
2. `text`
3. bounds，只用于 legacy trace readability
4. `x/y`

`ActionTarget` 本身不需要 schema change；`decodeActionTarget()` 已经提取所有字段。只改 formatter ordering。

这样 logs 或 approval rows 不会在 runtime 实际会用 `element_index` 时展示成 "Click at (x,y)"。

### Docs

更新 `doc/main/infra/tool/mobile_action.md`，说明：

- semantic target plus coordinate hint 会被接受；
- semantic target 仍然是 primary；
- coordinate fallback 只在 semantic resolution failure 后发生；
- contradictory semantic and coordinate evidence 会在 dispatch 前 fail；
- `scroll` never uses coordinate fallback from a semantic target。

## Interactions

Target normalization state machine:

| Input shape | Canonical result |
| --- | --- |
| no target | 只在 target optional 的 action 中 valid |
| `x/y` | `Target.Coordinate` |
| `element_index` | `Target.ElementIndex` |
| `text` | `Target.Text` |
| `element_index + x/y` | `Target.ElementIndex(coordinateHint)` |
| `text + x/y` | `Target.Text(coordinateHint)` |
| `element_index + text` | invalid |
| `element_index=-1 + x/y` | invalid |
| partial `x/y` | invalid |
| bounds selector | invalid |

Semantic resolution state machine:

| State | Transition |
| --- | --- |
| semantic resolves, no hint | execute semantic target |
| semantic resolves, hint inside bounds | execute semantic target |
| semantic resolves, hint outside bounds | fail `Ambiguous` before dispatch |
| semantic fails, hint exists | execute coordinate fallback with warning, except `scroll` fails |
| semantic fails, no hint | fail as today |

## Tasks

### `target-model-coordinate-hint`

Scope: `app/src/main/kotlin/ai/closepaw/tool/action/Target.kt`, `TargetResolver.kt`

Acceptance:

- semantic target classes 接受 optional coordinate hints；
- resolver 优先 semantic resolution；
- resolver 对 semantic/hint consistency 使用 strict half-open containment；
- resolver 对 contradictory semantic/hint evidence 返回 `Ambiguous`；
- resolver 只在 semantic miss 后 fallback 到 hint；
- coordinate fallback results 携带 `coordinateFallback=true` 和 warning；
- existing coordinate-only results 保持 `coordinateFallback=false`。

Dependencies: none.

### `mobile-action-normalize-dual-target`

Scope: `app/src/main/kotlin/ai/closepaw/tool/impl/MobileActionTool.kt`

Acceptance:

- `click/long_press/type` 接受 `element_index + x/y` 和 `text + x/y`；
- `element_index + text` 仍然 invalid；
- negative `element_index` 即使有 valid coordinates 也仍然 invalid；
- partial 或 negative coordinates 仍然 invalid；
- parsed target 保持 canonical priority，并附加 coordinate hints；
- `MobileActionTool.description` 使用新的 semantic-primary / coordinate-hint wording。

Dependencies: `target-model-coordinate-hint`.

### `point-executor-resolved-semantic-guard`

Scope: `app/src/main/kotlin/ai/closepaw/tool/action/PointActionExecutorCore.kt`

Acceptance:

- semantic-resolved click 仍然先尝试 node action；
- coordinate fallback skip node action，使用 gesture；
- ambiguous semantic/hint evidence 不 dispatch，直接 fail；
- existing coordinate-only behavior 不变。

Dependencies: `target-model-coordinate-hint`.

### `type-executor-coordinate-fallback`

Scope: `app/src/main/kotlin/ai/closepaw/tool/action/TypeExecutor.kt`

Acceptance:

- semantic-resolved type 保留当前 `SetTextOnNodeAt` primary path；
- semantic-miss coordinate fallback 使用 `TapAt` then `SetTextOnFocused`；
- resolver warnings 出现在 type success/failure messages 中；
- pure coordinate type behavior 不变。

Dependencies: `target-model-coordinate-hint`.

### `scroll-coordinate-fallback-guard`

Scope: `app/src/main/kotlin/ai/closepaw/tool/action/ScrollExecutor.kt`

Acceptance:

- explicit semantic scroll targets 仍然 require resolved bounds；
- semantic-miss coordinate fallback 被明确 reject；
- 不为 coordinate fallback 创建 synthetic bounds。

Dependencies: `target-model-coordinate-hint`.

### `target-description-alignment`

Scope: `app/src/main/kotlin/ai/closepaw/agent/ActionDescriptionFormatter.kt`

Acceptance:

- formatter priority 和 runtime priority 一致；
- dual `element_index + x/y` 展示为 element-targeted；
- dual `text + x/y` 展示为 text-targeted；
- legacy trace input with all selector fields 优先展示 `element_index`。

Dependencies: `mobile-action-normalize-dual-target`.

### `targeting-tests-docs`

Scope: `app/src/test/kotlin/ai/closepaw/tool/impl/MobileActionToolTest.kt`, `app/src/test/kotlin/ai/closepaw/tool/action/*Test.kt`, `app/src/test/kotlin/ai/closepaw/agent/ActionTargetTest.kt`, `doc/main/infra/tool/mobile_action.md`

Acceptance:

- 实现下面的 concrete test matrix；
- docs 解释 canonicalization rule 和 `scroll` exception。

Dependencies: all implementation tasks.

## Test Matrix

### `MobileActionTool` validation

- `click element_index + x/y` -> valid。
- `click text + x/y` -> valid。
- `long_press element_index + x/y` -> valid。
- `type element_index + x/y + input_text` -> valid。
- `click element_index + text` -> invalid。
- `click element_index + text + x/y` -> invalid。
- `click element_index=-1 + x/y` -> invalid。
- `click x=10` with missing `y` -> invalid。
- `click x=-1, y=10` -> invalid。
- `click x1/y1/x2/y2` -> invalid。
- `scroll element_index + x/y` -> valid。
- `scroll bare x/y` with no semantic target -> invalid。
- description string contains semantic-primary / coordinate-hint wording。

### Target parsing

- `element_index + x/y` parses to `Target.ElementIndex(index, Coordinate(x, y))`。
- `text + x/y` parses to `Target.Text(text, textIndex, Coordinate(x, y))`。
- `element_index` only parses to `Target.ElementIndex(index, null)`。
- `Target.Text("x", 0)` equals `Target.Text("x", 0, null)`。

### `TargetResolver`

- semantic target resolves and hint is inside bounds -> semantic `Resolved` using element center, no warning。
- hint at `right - 1, bottom - 1` is inside。
- hint at `right, bottom` is outside。
- semantic target missing with hint -> coordinate fallback `Resolved`, `bounds=null`, `semanticHint=null`, warning present, `coordinateFallback=true`。
- semantic target missing without hint -> `NotFound`。
- text target follows the same inside / missing fallback rules。
- coordinate target behavior is unchanged。

### `PointActionExecutorCore`

- semantic resolves with hint inside bounds -> `ClickNodeAt` first。
- semantic missing with hint -> skip `ClickNodeAt`, use `TapAt`, warning appears in success message。
- pure `Coordinate` target behavior is unchanged。
- semantic resolves but hint outside bounds -> failed ambiguous result and no platform dispatch。

### `TypeExecutor`

- semantic resolves with hint inside bounds -> `SetTextOnNodeAt` first。
- semantic missing with hint -> `TapAt` then `SetTextOnFocused`。
- coordinate fallback warning appears in success/failure message。
- pure coordinate type behavior is unchanged。

### `ScrollExecutor`

- `scroll element_index=999 + x/y` with a missing target fails as unresolved scroll target。
- `scroll element_index=1 + x/y` resolves normally when the element exists。
- no coordinate fallback result is used as a scroll area。

### `ActionDescriptionFormatter`

- `element_index + x/y` -> "Click element N"。
- `text + x/y` -> "Click text \"...\" (index 0)"。
- legacy input with `element_index + text + bounds + point` -> "Click element N"。

### Docs

- `doc/main/infra/tool/mobile_action.md` documents semantic-primary coordinate hints, coordinate fallback, ambiguity failure, and the `scroll` exception。

## Trade-offs

### Prompt-only fix

Rejected. Prompt 已经说 prefer semantic selectors，tool description 也已经说 `EXACTLY ONE`。Codex 仍然会发 dual targets。Runtime validation 必须对真实收到的 model shape 足够 robust。

### Full multi-selector fallback chain

Rejected. 项目已经刻意从 implicit selector priority 迁出，因为它会隐藏 bad grounding，并可能执行和 model intended target 不同的目标。

### Always ignore coordinates when semantic exists

不够。它能修 validation error，但不能发现 contradictory model output，也不能在 semantic index stale 时利用显式给出的 coordinate。

### Use coordinates as primary when present

Rejected. 这会跳过更安全的 semantic/node-action path，并回退 overlap handling。Coordinates 是 fallback hint，不是 primary target。

### Slop around bounds

v1 reject。Fixed pixel slop 是 magic number，density-scaled slop 也仍然 speculative。Strict half-open containment 更简单也更安全。只有 eval traces 显示 false ambiguity 时才加 slop。

### Nested `target` schema

这个 change 里 reject。长期可能更干净，但这是更大的 LLM interface migration。Flat schema 已经部署，而且除了这个 dual-target shape 外，对两个 auth paths 都能工作。

## Self-review

这个 design 保留了关键 invariant：executors 只处理一个 canonical target。它能适配 Codex-specific redundant-coordinate shape，同时不重新打开 arbitrary multi-selector behavior。review changes 把 boundary semantics 写明确，补上 `type`，并把 `scroll` 的 no-coordinate-fallback 行为变成 tested contract。
