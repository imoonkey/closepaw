# Perceptor Improvement Plan

## 1. Current Architecture

`Perceptor.kt` 将 Android `AccessibilityNodeInfo` 树转为 `ScreenSnapshot`，再通过 `toPromptJson()` 生成 LLM 消费的 JSON。

当前管线：

```
AccessibilityNodeInfo tree
  → traverse() pass 1: interactive only (clickable/editable/scrollable)
  → traverse() pass 2: all content (if < 80 elements)
  → dedup by composite key
  → filter: min size 5px, screen intersection, keyboard resourceId
  → cap at 80 elements
  → toPromptJson(): flat JSON array
```

输出示例：
```json
[
  {"index": 0, "text": "Settings", "class": "TextView", "clickable": true, "focused": false, "long_clickable": false, "bounds": [0,128,1080,200], "center": [540,164]},
  {"index": 1, "text": "", "class": "LinearLayout", "focused": false, "long_clickable": false, "bounds": [0,200,1080,350], "center": [540,275]}
]
```

## 2. Reference Agents 对比

基于 `.reference/mobile_agent/` 下六个 agent 实现的完整代码分析。

### 2.1 架构总览

| | 当前 Perceptor | AndroidWorld (T3A/M3A) | DroidRun | Minitap | Agent-S | MAI-UI / MobileAgent |
|---|---|---|---|---|---|---|
| **感知源** | 设备端 a11y API | android_env protobuf | 设备端 Portal APK | uiautomator2 XML dump | AT-SPI (Linux) | 纯视觉 |
| **树→列表** | 两阶段 DFS 扁平化 | 仅保留叶节点+scrollable | 过滤后保留层级 | 全量扁平化 | 按 role 白名单过滤 | N/A |
| **LLM 格式** | JSON array | `UIElement.__str__()` dump | 缩进文本 `1. Button: "text"` | JSON array | TSV 表格 | 坐标 |
| **元素上限** | 80 硬上限 | 无 | 无 | 无 | 无 | N/A |
| **元素寻址** | index (0-based) | index (0-based) | index (1-based DFS) | resource_id + text + bounds 多策略 | index (0/1-based) | 坐标 |

### 2.2 属性保留对比

分析各 agent 从原始 a11y 树中提取并传递给 LLM 的属性集：

| 属性 | 当前 Perceptor | AndroidWorld | DroidRun | Minitap |
|---|---|---|---|---|
| text | 送 LLM | 送 LLM | 送 LLM（与 desc/id 合并为单字段） | 送 LLM |
| contentDescription | 与 text 合并送 LLM | 送 LLM（独立字段） | 合并到 text | 送 LLM |
| className | 短名送 LLM | 全名送 LLM | 短名送 LLM | 全名送 LLM |
| resourceId | **采集但不送 LLM** | 送 LLM | 送 LLM | 送 LLM |
| clickable | 稀疏编码（true 才写） | 显式 boolean | **不送 LLM**（内部过滤用） | 字符串 "true"/"false" |
| editable | 稀疏编码 | 显式 boolean | 不送 LLM | 字符串 |
| scrollable | 稀疏编码 | 显式 boolean | 不送 LLM | 字符串 |
| enabled | 采集不送 LLM | 送 LLM | 不送 LLM | 字符串 |
| focused | 显式 | 送 LLM | 不送 LLM | 字符串 |
| **checked** | **未采集** | 送 LLM | 内部过滤可用 | 字符串 |
| **selected** | **未采集** | 送 LLM | 内部过滤可用 | 字符串 |
| **checkable** | **未采集** | 送 LLM | 未使用 | 字符串 |
| **hintText** | **未采集** | 送 LLM | 内部过滤可用 | N/A |
| **visibleToUser** | **未采集（仅靠 bounds 近似）** | 送 LLM | 未使用 | N/A |
| bounds | 送 LLM（像素） | 送 LLM（像素+归一化双表示） | 送 LLM（像素或归一化 0-1000） | 送 LLM（字符串） |
| center | 送 LLM | 不送（可从 bounds 算） | 不送 | 不送 |
| long_clickable | 显式 | 送 LLM | 不送 LLM | 字符串 |

**关键发现**：
- 我们是唯一对 `clickable/editable/scrollable` 使用稀疏编码的 agent。AndroidWorld 显式写 boolean，Minitap 写字符串 "true"/"false"，DroidRun 干脆不送（用其他方式弥补）
- `checked`、`selected`、`checkable`、`hintText` 四个属性在我们的 Perceptor 中完全未采集，但我们自己的 `A11yTreeDumper`（trace 路径）已经在读这些字段，证明 API 可用
- `resourceId` 已经存在于 `PerceptionElement` 中但 `toPromptJson()` 不输出，所有其他使用 a11y tree 的 agent 都把它送给 LLM
- `visibleToUser` 未采集——我们用 bounds 与屏幕的相交判断近似，但 WebView 内部元素的 bounds 可能报出屏幕外坐标（如 x=1285 在 1080px 宽屏上），单靠 bounds 无法正确处理

### 2.3 过滤策略对比

| 阶段 | 当前 Perceptor | DroidRun (DetailedFilter) | AndroidWorld (M3A) | Agent-S |
|---|---|---|---|---|
| **不可见过滤** | `intersectsScreen()`：有任何像素重叠就保留 | `visibleAreaRatio() >= 10%`：至少 10% 面积可见 | `is_visible` flag 过滤 | `showing=true AND visible=true` |
| **尺寸过滤** | 宽或高 > 5px | 宽或高 > configurable min_size | bounds 不退化（x_min < x_max） | coords >= 0 且 size > 0 |
| **键盘过滤** | 5 种键盘 resourceId 前缀 | 仅 Gboard 1 种前缀 | 无 | 按 application 过滤 |
| **元素类型** | 无（所有 className 都保留） | 无 | 无 | `judge_node()` 按 role 白名单（button/heading/label/textbox/link 等） |
| **叶节点策略** | 无区分 | 父不可见但子可见时保留父 | 仅保留叶节点 + scrollable + 有 contentDescription | 无区分 |
| **去重** | composite key (id\|class\|text\|desc\|flags\|bounds) | 无显式去重 | 无显式去重 | 无显式去重 |
| **元素上限** | 80 硬上限 | 无 | 无 | 无 |

**关键发现**：
- 我们的 `intersectsScreen()` 是最宽松的可见度检查。DroidRun 的 10% 面积阈值更合理
- AndroidWorld 使用平台的 `visibleToUser` flag 是最准确的，我们完全忽略了这个信号
- AndroidWorld 的"仅保留叶节点"策略很激进但有效——中间容器（LinearLayout、FrameLayout）通常是噪声
- 我们是唯一有硬性元素上限的 agent——这保证了 token 可控但可能丢失重要元素。两阶段优先级策略（interactive first）部分弥补了这个问题，但当交互元素本身就超过 80 时仍有风险

### 2.4 层级/结构

| | 当前 Perceptor | DroidRun | AndroidWorld | Minitap |
|---|---|---|---|---|
| **保留层级** | 否（完全扁平） | 是（缩进文本） | 否（叶节点提取天然无层级） | 否 |
| **LLM 可感知结构** | 仅通过 bounds 空间推测 | 缩进明确表达父子关系 | 无 | 无 |
| **scroll 容器关系** | 不可知——LLM 不知道哪些元素在哪个 scrollable 容器内 | 缩进可推断 | 不可知 | 不可知 |

DroidRun 是唯一向 LLM 传递层级结构的 agent。它的做法是在文本格式中使用缩进，同时保持 flat index 用于 action：

```
1. FrameLayout: - (0,0,1080,2400)
  2. RecyclerView: "content_list" - (0,200,1080,2200) [scrollable]
    3. TextView: "Item 1" - (20,210,500,280)
    4. TextView: "Item 2" - (20,290,500,360)
  5. Button: "Load More" - (300,2210,780,2280)
```

这种格式让 LLM 能理解 item 3/4 在 scrollable 容器 2 内，而 button 5 在容器外。

### 2.5 元素寻址与 Action 执行

| | 当前 Perceptor | DroidRun | AndroidWorld | Minitap |
|---|---|---|---|---|
| **LLM 输出** | `element_index` | `element_index` | `index` | `resource_id` + `text` + `bounds`（多策略） |
| **执行方式** | 从 PerceptionElement 取 center 坐标 → 重新查询 a11y 树 → 找到坐标处可点击节点 | index → 从缓存查 bounds → 计算清除点 | index → 直接引用 UIElement | 按优先级：resource_id → bounds → text 逐级降级 |
| **遮挡处理** | 无（直接用 center） | `find_clear_point()` 四象限细分找非遮挡区域 | 无 | 无 |
| **时序一致性** | 弱（perception 和 action 间 a11y 树可能变化） | 强（缓存 snapshot 用于执行） | 中 | 弱（实时查询） |

### 2.6 独特模式

**AndroidWorld — 自然语言元素描述**

```python
# seeact_utils.py: _get_element_description()
'"Login" button'
'a "password" text box with the text "****"'
'a checkbox with the text "Accept Terms" that is checked'
'a switch with the text "WiFi" that is checked'
```

按 className 分流生成面向人类的描述，将 class+text+state 融合成单一字符串。对 LLM 友好度高，天然消除"稀疏属性"问题。

**DroidRun — 清除点检测**

```python
# geometry.py: find_clear_point()
# 当目标元素被其他元素部分遮挡时，
# 将元素递归细分为四象限，找到不被遮挡的子区域
# 最多 4 层递归，面积阈值 < 100px² 放弃
```

解决了"点击 center 但 center 被弹窗/overlay 遮挡"的问题。

**DroidRun — 坐标归一化**

```python
# coordinate.py: bounds_to_normalized()
# 将像素坐标映射到 [0, 1000] 范围，设备无关
NORMALIZED_MAX = 1000
n_x = int(x * 1000 / screen_width)
```

**Agent-S — OCR 补充 a11y 树**

```python
# LinuxOSACI.py: add_ocr_elements()
# 用 PaddleOCR 检测屏幕文本，通过 IOU < 0.1 去重后补充到元素列表
# 处理 a11y 树覆盖不到的自定义绘制控件
```

**Minitap — 多策略定位降级链**

```python
# 执行器按优先级尝试：
# 1. resource_id → 最稳定
# 2. bounds 坐标 → 精确但随布局变化
# 3. text 匹配 → 最后兜底
# 额外安全检查：ID 匹配到的元素 text 与预期不符时，忽略 ID
```

## 3. 系统性问题识别

基于 reference 对比和两个数据源的交叉分析：
- **debug-output**：110 个 run，1655 个 sanitized tree，152910 个 raw node，1564 次 tool call（44 种任务，YouTube/Gmail/Amazon/Temu/SHEIN 等真实 app）
- **eval/results**：26 个任务实例（其中仅 4 个 BrowserMultiply 有有效 perception 数据，其余为 LLM IO / infra failure）

以下按影响面从大到小排列。

### P1: 空文本可交互元素 — 47.2% 的交互元素无法被文本定位

**数据**（debug-output，1655 sanitized tree，54266 element）：
- 交互元素（clickable/editable/long_clickable）：28393 个（52.3%）
- 其中 **text 为空**：**13412 个（47.2%）**
- P50 每屏空文本交互元素占比：42.9%，P90：100%

**空文本交互元素 className 分布**：

| Class | Count | 特征 |
|-------|-------|------|
| ViewGroup | 2516 | 容器，text 在子节点 |
| FrameLayout | 2460 | 同上 |
| LinearLayout | 2262 | 同上 |
| View | 1644 | 通用 View |
| Button | 1546 | 按钮但无标签 |
| ImageView | 1513 | 图标按钮 |

这不是边缘 case，而是**最普遍的感知问题**。近一半的可操作元素对 LLM 来说是"无名按钮"。

**对比**：
- DroidRun 的 `_format_node()` 使用 `text || contentDescription || resourceId || className` 降级链，确保每个节点都有某种文本标识
- AndroidWorld 通过仅保留叶节点直接绕过了容器问题
- Agent-S 的 `judge_node()` 要求元素必须有 name 或 text 才保留

### P2: visibleToUser 未过滤 — 47.3% 的原始节点不可见

**数据**（debug-output，1629 raw tree，152910 node）：

| 类别 | Count | 占比 |
|------|-------|------|
| visibleToUser=true | 80607 | 52.7% |
| visibleToUser=false | 72303 | 47.3% |
| **不可见 + 可点击** | **14761** | **不可见节点的 20.4%** |
| **不可见 + 可点击 + 有文本** | **12316** | **最危险的假阳性** |

每文件不可见节点占比分布：P50=8.6%，P75=20.7%，P90=41.6%。

当前 `intersectsScreen()` 仅检查 bounds 是否与屏幕有像素重叠，完全忽略 `isVisibleToUser` flag。这意味着大量不可见但 bounds 在屏幕范围内的元素会通过过滤。

从 raw→sanitized 的过滤比（median 52→31 元素，存活率 55.2%）说明当前过滤在做工作，但缺少 `visibleToUser` 这个最准确的信号。

**对比**：AndroidWorld 直接用 `is_visible` flag 过滤。DroidRun 用 10% 可见面积阈值。Agent-S 要求 `showing=true AND visible=true`。

### P3: 稀疏属性编码 — clickable=false 不可见

**现象**：`clickable`、`editable`、`scrollable` 仅在 `true` 时写入 JSON，`false` 时省略。而 `focused` 和 `long_clickable` 始终写入。这种不一致性使 LLM 必须从属性**缺失**推断"不可交互"，而非从显式 `false` 值判断。

**数据**（eval/results BrowserMultiply trace）：在文本与任务目标高度匹配的非交互元素（如 "Open with Chrome" TextView）上，LLM 忽略了 `clickable` 属性缺失并直接点击。

**数据**（debug-output tool_result）：整体 tool 成功率 97.3%（1521/1564），24 次 click failure 中部分涉及对非交互元素的点击。虽然绝对失败率不高，但这类错误一旦发生会造成多 turn 浪费（agent 反复重试同一错误操作）。

**对比**：所有使用 a11y tree 的 reference agent 要么显式编码 boolean（AndroidWorld、Minitap），要么完全不送这些属性（DroidRun）。没有 agent 使用稀疏编码。

### P4: 缺失状态属性 — selected 有意义，checked 罕见

**数据**（debug-output，152910 raw node）：

| Flag | 节点数 | 占比 | 出现在文件中 |
|------|--------|------|------------|
| selected=true | 1661 | 1.09% | **641/1629 文件（39.3%）** |
| checkable=true | 185 | 0.12% | — |
| checked=true | 16 | 0.01% | 10/1629 文件（0.6%） |

`selected` 出现在近 40% 的屏幕上（主要用于 tab/navigation 选中状态），是有实际价值的信号。

`checked` 极度稀少（16 个节点，10 个文件），实际 app 中几乎不用原生 CheckBox/RadioButton。

`hintText`：未统计但在 EditText（765 个 sanitized 元素）中预期有值。

**对比**：AndroidWorld 全量保留这些属性。我们的 `A11yTreeDumper` 已读取但 Perceptor 不采集。

### P5: resourceId 覆盖率极低 — 不适合作为主定位手段

**数据**（debug-output，152910 raw node）：

| 维度 | 有 resourceId 的占比 |
|------|---------------------|
| 全部节点 | 9.9% |
| **交互节点** | **4.5%** |

**按 app 分布**：

| App | 交互节点 | 有 resourceId (%) |
|-----|----------|-------------------|
| **YouTube** | 14872 | **0 (0.0%)** |
| **Temu** | 6438 | **0 (0.0%)** |
| **SHEIN** | 5351 | **0 (0.0%)** |
| Amazon | 9639 | 1221 (12.7%) |
| Gmail | 3294 | 10 (0.3%) |
| Notion | 1792 | 756 (42.2%) |

YouTube 是最高频的目标 app，交互节点 resourceId 覆盖为 **零**。在真实使用场景中，resourceId 基本不可用。

`PerceptionElement.resourceId` 已经存储但 `toPromptJson()` 不输出。鉴于覆盖率数据，将其作为近期优先改进项的 ROI 很低。

**调整**：resourceId 输出改为**条件启用**——仅当当前屏幕 resourceId 密度超过阈值（如 >20% 元素有 id）时才写入 prompt，避免在大多数场景下无效增加 token。

### P6: 两阶段遍历打断空间一致性

**现象**：Pass 1 收集交互元素，Pass 2 收集内容元素。两者按各自 DFS 顺序排列。结果是空间上相邻的元素（如一个 Button 和它的 label TextView）在列表中可能相距甚远。

**对比**：DroidRun 的 filter→format 管线保持原始树序。AndroidWorld 的叶节点遍历也保持树序。没有 agent 像我们一样分两阶段按不同标准交叉收集。

### P7: 无层级上下文 — 扁平列表丢失结构信息

**现象**：所有元素扁平化为一维数组，LLM 不知道哪些元素在 scroll 容器内、哪些按钮属于哪个卡片/行、哪个 dialog 覆盖在哪个页面上。

**数据**（debug-output）：median 每屏 1 个 scrollable 元素，30.9% 的屏幕无 scrollable。scroll 容器归属信息在有 scrollable 的屏幕上有价值。

**对比**：DroidRun 是唯一向 LLM 传递层级结构的 agent（缩进文本），其他都是扁平。

### P8: bounds 相交过滤过于宽松

**现象**：`intersectsScreen()` 只要有 1px 重叠就通过。

**对比**：DroidRun 要求 10% 可见面积。AndroidWorld 用 `is_visible` flag。

**注**：此问题在引入 P2 的 `visibleToUser` 过滤后会大幅缓解——大部分边缘不可见元素已被 `visibleToUser=false` 拦截。可见面积过滤作为第二层防线。

## 4. Improvement Design

### Phase 1: 过滤质量提升 — visibleToUser + 可见面积

当前过滤是最大的功能缺陷（P1），直接导致最高频的 tool failure。

**改动**：

**1a. 采集 `visibleToUser`，作为首道过滤**

```kotlin
// Perceptor.kt traverse() 中新增
val visibleToUser = node.isVisibleToUser

// 过滤规则：
// visibleToUser=false 的元素直接跳过（不进入任何 pass）
// 这解决 WebView 内部不可见元素的问题
if (!visibleToUser) {
    // recycle and skip
}
```

这是 `AccessibilityNodeInfo` 平台提供的信号，比我们用 bounds 手动判断准确得多。AndroidWorld 就靠这一个 flag 做可见度过滤。

**1b. 用可见面积比替换 intersectsScreen()**

对于 `visibleToUser=true` 但部分超出屏幕的元素，用面积比过滤：

```kotlin
private fun visibleAreaRatio(rect: Rect, screenW: Int, screenH: Int): Float {
    val totalArea = rect.width().toLong() * rect.height().toLong()
    if (totalArea <= 0) return 0f
    val vL = rect.left.coerceAtLeast(0)
    val vT = rect.top.coerceAtLeast(0)
    val vR = rect.right.coerceAtMost(screenW)
    val vB = rect.bottom.coerceAtMost(screenH)
    val visibleArea = (vR - vL).toLong().coerceAtLeast(0) * (vB - vT).toLong().coerceAtLeast(0)
    return visibleArea.toFloat() / totalArea
}
```

阈值：非交互元素 10%，交互元素 1%（防止误删边缘按钮）。

**涉及文件**：
- `perception/Perceptor.kt`：`traverse()` 增加 `isVisibleToUser` 检查，替换 `intersectsScreen()`。新增 `visibleAreaRatio()`

---

### Phase 2: 属性表达 — 显式编码 + 缺失状态补全

解决 P3 和 P4。

**2a. clickable/editable/scrollable 始终显式输出**

```kotlin
// toPromptJson() 中：
// Before (sparse)
if (elem.isClickable) put("clickable", true)

// After (explicit)
put("clickable", elem.isClickable)
put("editable", elem.isEditable)
put("scrollable", elem.isScrollable)
```

token 增量约 1000 tokens/turn（80 元素 × ~50 字符），换来的是 LLM 可以明确看到 `"clickable": false` 而非推测属性缺失意味着 false。

**2b. 新增 selected/hintText（优先），checked/checkable（次要）**

根据数据：`selected` 出现在 39.3% 的屏幕上，是高价值信号。`checked` 仅出现在 0.6% 的屏幕上，优先级低。

`PerceptionElement` 新增字段：

```kotlin
data class PerceptionElement(
    // ... existing fields ...
    val isSelected: Boolean,     // 39.3% 屏幕有此状态，tab/navigation 选中
    val hintText: String,        // EditText 的 placeholder
    val isChecked: Boolean,      // 极少使用（0.6% 屏幕），但成本低
    val isCheckable: Boolean,    // 区分 Button vs Switch/CheckBox
)
```

`traverse()` 中采集：
```kotlin
val selected = node.isSelected
val hint = node.hintText?.toString()?.take(MAX_STRING_LENGTH) ?: ""
val checked = node.isChecked
val checkable = node.isCheckable
```

`toPromptJson()` 输出规则：`selected` 仅在 `true` 时写入（高频且有意义）。`hintText` 非空时写入。`checked` 仅在 `true` 时写入。`checkable` 仅在 `true` 时写入（极少出现，不增加常规 token）。

**涉及文件**：
- `model/Models.kt`：`PerceptionElement` 新增 4 字段
- `perception/Perceptor.kt`：`traverse()` 采集 + `toPromptJson()` 输出

---

### Phase 3: 元素文本丰富 — 解决空文本容器

解决 P2 和部分 P5。

**3a. text 降级链（参考 DroidRun _format_node）**

`toPromptJson()` 中合并 text 时使用降级链：

```kotlin
val mergedText = elem.text.ifBlank { elem.description }
    .ifBlank { elem.hintText }           // 新增：用 placeholder 填充
    .ifBlank { elem.resourceId.extractIdSuffix() }  // 新增：用 resourceId 尾部
```

`extractIdSuffix()` 将 `com.google.android.documentsui:id/icon_thumb` 截为 `icon_thumb`。既提供语义又节省 token。

**3b. 子节点文本冒泡**

对于 text 降级链后仍为空的**可交互**元素，尝试从子节点获取文本：

```kotlin
// snapshot() 返回前的后处理：
// 在 traverse() 中额外记录 {parentBounds → childTexts} 映射
// 后处理时，对每个空文本可交互元素，查找 bounds 完全包含在其内的非交互文本元素
// 将找到的文本拼接（最多 3 个，用 " | " 分隔）
```

实现上不需要严格的父子关系（这会改变 traverse 结构），而是用 bounds containment 近似：

```kotlin
private fun enrichEmptyTextElements(elements: MutableList<PerceptionElement>) {
    val interactiveEmpty = elements.filter {
        (it.isClickable || it.isEditable) && it.text.isBlank() && it.description.isBlank()
    }
    val textElements = elements.filter {
        !it.isClickable && !it.isEditable && !it.isScrollable && it.text.isNotBlank()
    }
    for (parent in interactiveEmpty) {
        val contained = textElements.filter { child ->
            parent.bounds.contains(child.bounds)
        }.take(3)
        if (contained.isNotEmpty()) {
            parent.text = contained.joinToString(" | ") { it.text }
        }
    }
}
```

**3c. resourceId 条件输出（低优先级）**

数据显示交互元素 resourceId 覆盖仅 4.5%，YouTube/Temu/SHEIN 为 0%。无条件输出 resourceId 在大多数场景下只是噪声。

策略：按屏幕密度条件启用。在 `toPromptJson()` 中先统计当前 snapshot 的 resourceId 非空比例，超过 20% 时才对非空元素输出 `id` 字段。

```kotlin
val idCoverage = elements.count { it.resourceId.isNotBlank() }.toFloat() / elements.size
val emitIds = idCoverage > 0.2f
// ...
if (emitIds && elem.resourceId.isNotBlank()) {
    put("id", elem.resourceId.extractIdSuffix())
}
```

**涉及文件**：
- `perception/Perceptor.kt`：`toPromptJson()` 改 text 合并逻辑 + 新增 `enrichEmptyTextElements()` + 输出 id/id_index

---

### Phase 4: 排序与一致性

解决 P7。

**4a. 单阶段收集 + 空间排序**

取消两阶段遍历的 index 分裂问题。保留两阶段策略（interactive first 确保优先收集），但在分配 index 前统一排序：

```kotlin
fun snapshot(...): ScreenSnapshot {
    // 收集阶段：不分配 index
    val collected = mutableListOf<PerceptionElementBuilder>()
    traverse(root, collected, ..., TraversalMode.INTERACTIVE_ONLY, ...)
    if (collected.size < MAX_ELEMENTS) {
        traverse(root, collected, ..., TraversalMode.ALL, ...)
    }

    // 排序阶段：按空间位置排序
    collected.sortWith(compareBy(
        { it.bounds.top / rowSnap(screenHeightPx) },  // 行优先
        { it.bounds.left },                             // 列其次
    ))

    // 分配 index
    collected.forEachIndexed { i, builder -> builder.index = i }

    return ScreenSnapshot(timestamp, collected.map { it.build() }.take(MAX_ELEMENTS))
}
```

`rowSnap()` 按屏幕高度的 2% 计算（如 2400px 屏幕 → 48px snap），避免微小偏移导致排序抖动。

**4b. 稳定性保证**

同一 a11y 树同一屏幕参数下，`snapshot()` 应产生相同输出。排序依赖的字段（bounds.top, bounds.left）不含随机性，dedup key 也是确定性的。

**涉及文件**：
- `perception/Perceptor.kt`：`snapshot()` 重构排序逻辑

---

### Phase 5: 结构上下文（可选 — A/B 实验决定）

解决 P6。token 开销较大，需实验验证收益。

**方案 A：depth 字段**

最简单的结构提示——在 `PerceptionElement` 中加一个 `depth: Int` 字段，在 `traverse()` 中传递层级计数器。LLM 可据此推断层级关系：

```json
{"index": 3, "text": "Item 1", "class": "TextView", "depth": 4, ...}
{"index": 4, "text": "Item 2", "class": "TextView", "depth": 4, ...}
{"index": 5, "text": "Load More", "class": "Button", "depth": 2, ...}
```

Token 增量极小（每元素 ~10 字符），但层级语义模糊。

**方案 B：scroll_container_index 标注**

仅标注"此元素所属的最近 scrollable 容器的 index"。解决最核心的结构需求：LLM 知道 scroll 后哪些元素会变化。

```json
{"index": 3, "text": "Item 1", "class": "TextView", "in_scroll": 0, ...}
{"index": 4, "text": "Item 2", "class": "TextView", "in_scroll": 0, ...}
{"index": 5, "text": "Load More", "class": "Button", ...}
```

**方案 C：DroidRun 式缩进文本格式**

格式从 JSON 切换为缩进文本。信息密度高、层级清晰，但改动面大（影响 prompt 构建、trace 解析、eval 工具）。

```
0. [ScrollView] (0,128,1080,2400) scrollable
  1. [Button] "Just once" (100,1800,500,1900) clickable
  2. [Button] "Always" (500,1800,900,1900) clickable
  3. [LinearLayout] "HTML Viewer" (100,1600,900,1750) clickable
  4. [TextView] "Open with Chrome" (100,1500,900,1590)
```

**推荐**：先实验方案 A（最小改动），如果 eval 有正向信号再考虑 B 或 C。

**涉及文件**：
- `model/Models.kt`：`PerceptionElement` 新增 `depth` 字段
- `perception/Perceptor.kt`：`traverse()` 传递 depth 参数 + `toPromptJson()` 输出

---

### Phase 6: Capture 鲁棒性

独立于其他 Phase，可随时实施。

**6a. rootInActiveWindow 短重试**

```kotlin
suspend fun captureAccessibilityTree(): AccessibilityNodeInfo? {
    repeat(3) { attempt ->
        val root = service.rootInActiveWindow
        if (root != null) return root
        delay(150)
    }
    return null
}
```

**6b. 空树质量元数据**

```kotlin
data class CaptureQuality(
    val attempts: Int,
    val elementCount: Int,
    val capturedAt: Long,
    val emptyReason: String?  // "null_root" | "zero_visible_elements" | null
)
```

写入 trace debug 信息，辅助诊断。

**涉及文件**：
- `platform/AccessibilityPlatform.kt`

## 5. Phase 依赖与执行建议

```
Phase 1 (过滤) ←── 无依赖，消除不可见元素污染
  ↕ 可并行
Phase 2 (属性) ←── 无依赖（纯 output 格式改动）
  ↓
Phase 3 (文本) ←── 依赖 Phase 2（hintText 字段），解决最大问题（47.2% 空文本）
  ↓
Phase 4 (排序) ←── 建议在 Phase 1/3 之后（过滤+文本变化影响排序输入）
  ↓
eval checkpoint ──→ 量化 Phase 1-4 的组合收益
  ↓
Phase 5 (结构) ←── A/B 实验验证
Phase 6 (capture) ←── 完全独立，随时可做
```

## 6. Data Baseline

来自 debug-output 的关键基线指标（Phase 实施前后对比用）：

### 元素分布

| 指标 | 值 |
|------|-----|
| 元素数 P50 | 31 |
| 元素数 P90 | 70 |
| 命中 80 上限的屏幕 | 4.2% |
| 交互元素占比 | 52.3% |
| 空文本交互元素占比 | **47.2%** |
| raw→sanitized 存活率 | 55.2% (median) |

### 类型分布 (sanitized)

| Class | 数量 | 占比 |
|-------|------|------|
| TextView | 17886 | 33.0% |
| ImageView | 7250 | 13.4% |
| View | 5864 | 10.8% |
| ViewGroup | 5400 | 10.0% |
| Button | 5348 | 9.9% |
| FrameLayout | 4027 | 7.4% |
| LinearLayout | 2860 | 5.3% |
| RecyclerView | 1306 | 2.4% |
| EditText | 765 | 1.4% |

### Tool 成功率

| 维度 | 值 |
|------|-----|
| 总 tool call 成功率 | 97.3% (1521/1564) |
| click 失败次数 | 24 |
| click 失败主因 | a11y 树完全为空（YouTube）+ 坐标猜测 |
| LLM 验证错误 | 8（多重 targeting 冲突） |

### Eval 验证指标

每个 Phase（至少 Phase 1-4 组合后）需在 eval 任务集上验证：

| 指标 | 定义 | 基线来源 |
|------|------|----------|
| task_success_rate | 任务完成率 | `trace/meta.json` → task_status |
| action_success_rate | tool call 无 error 的比率 | tool_result artifacts |
| invisible_element_clicks | 点击 visibleToUser=false 元素的次数 | tool_call_args + raw_a11y_tree 关联 |
| empty_text_ratio | text 为空的可交互元素占比 | sanitized_a11y_tree |
| avg_prompt_tokens | 平均每 turn a11y JSON tokens | llm_full_prompt 长度 |
| empty_tree_rate | snapshot 元素数为 0 的比率 | sanitized_a11y_tree |

## 7. Risks

| 风险 | 影响 | 缓解 |
|------|------|------|
| `visibleToUser` 在某些 ROM/WebView 中报告不准确 | 误删可见元素 | 保留 bounds 可见面积作为二级判断——`visibleToUser=false` 但可见面积 > 50% 时保留并 log warning |
| 显式 false 增加 token 导致长页面超出上下文 | 截断 | 监控 avg_prompt_tokens；必要时降低 MAX_ELEMENTS（如 60） |
| 子节点冒泡文本语义不对（多个无关子节点拼接） | LLM 误解元素功能 | 限制最多 3 个子节点；仅拼接 text 非空的直接子节点 |
| 空间排序的 rowSnap 在不同分辨率下表现不一致 | index 抖动未消除 | 按屏幕高度 2% 动态计算 snap 值 |
| Phase 5 层级信息增加认知负荷反而降低准确率 | 性能回归 | A/B 实验验证，负面结果则不合入 |

## 8. Affected Files Summary

| File | Phase | 改动类型 |
|------|-------|----------|
| `perception/Perceptor.kt` | 1,2,3,4,5 | traverse() 逻辑修改 + toPromptJson() 输出修改 + 新增 enrichEmptyTextElements() + 排序重构 |
| `model/Models.kt` | 2,5 | PerceptionElement 新增字段 (checked, selected, checkable, hintText, depth) |
| `platform/AccessibilityPlatform.kt` | 6 | 重试逻辑 + 质量元数据 |
| `app/src/test/...` | All | 新增/更新单元测试 |
