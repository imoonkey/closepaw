# Task 1
你给我top-down的思考一下，我的click的perception和targeting该怎么做。

我的想法：
1. LLM告诉我click target的时候，是element > text > coordinate。
2. 但是因为种种原因，click element不一定成功？所以有时候要转换成coordinate来click。click本身又有不同android api的实现(e.g., ACTION_CLICK, 手势tap)。所以这里的code有点像屎山，看不懂，有bug。

我想的思路：
1. 每个api的click，和每个target对象(element, text, coordinate)，单独实现一个。是atomic的，不会这个失败了try那个。
2. 是根据入口(LLM说click什么target)，组合上面的atomic click，一个不行换另一个，直到屏幕状态改变为止(这里要实现一个shared detection function)。每个target对象有一个自己的优先排序，比如
- click(target-element_index): click element index -> 换算成坐标, click坐标，考虑别的element的遮挡等等。
- click(target=text): 找到text对应element，然后call target(element)? 还有必要把text翻译成coordinate，来尝试click吗？
- click(target=coordinate): click coordinate -> 找到coordinate找对应element, click element index
这个优先级排序是我瞎编的，而且每个里面可以try的atomic action不止两个(没考虑不同api)。我只是给一个example，真实情况怎么成功率最高，需要你决定，或者去实验。

- 同样适用于long press, swipe.
- bound这个target方法可以删掉了。


# Task 2
现在程序员似乎完成了Task 1，但是其实并没有。我的代码逻辑可能稍微清晰了一些，但依然是一坨屎山。我需要让我的代码无比无比无比简化。

## Atomic Actions
这部分是在AccessibilityPlatform里实现的。它只包裹底层API。不做复杂的retry。

1) 基于节点的操作：AccessibilityNodeInfo.performAction(...)

这是“对某个可访问性节点执行动作”，典型包括：
- ACTION_CLICK / ACTION_LONG_CLICK
- ACTION_SCROLL_FORWARD / BACKWARD
- ACTION_SET_TEXT（这类会用到 Bundle 参数）
- ACTION_FOCUS / CLEAR_FOCUS 等
优点：语义化、更稳（按钮就是按钮、列表就是列表），不依赖坐标。
缺点：找不到节点时就没法用（比如游戏/自绘 Canvas/截图渲染）。

2) 基于坐标/路径的操作：AccessibilityService.dispatchGesture(...)
这是“注入一段手势路径”，可以做到：
- 点某个坐标（tap）
- 滑动（swipe）
- 长按、拖拽（取决于你怎么构造路径/时长）
优点：只要屏幕上“那个位置”有效就能点。
缺点：脆弱（分辨率/布局/滚动/动画都可能导致坐标漂移）。

## UIAction
这一层定义的接口有问题。有两个选择：
1. 它定义的接口跟底层AccessibilityPlatform实现的一样。ClickNode就不该接收(x,y)。不然如果上层给了element_index,又转换成x,y,再用x,y去找node，这不对，应该element_index直接找node。text类似。
2. 要跟我mobile_action prompt里允许的action对上，比如UIAction可以选element_index, text, coordinate一类来target。这时候对每个action的fallback逻辑就该放到AccessibilityPlatform底下(这个文件太大，需要拆分了)。

需要在1/2之间做个选择。我觉得可以选2，但需要你深入思考来决定.

## tool/
@tool/hanlders, @tool/impl/mobileaction 这两个文件夹的意义我不清楚，分开什么意思。

这里面的代码绕来绕去太多层太复杂了。实际要实现的逻辑很简单，就是我上面再Task1里说的，按优先级try。

为了简单，先这么改：
1. 所有的click/ type/ long-press用一样的优先级，element_index -> text -> coordinates。 swipe不支持target element。
2. 如果LLM填了多个，比如填了element_index，也填了(x,y)，直接报错，说只能选one of the targeting method。multi-targeting是说支持不同target方法，但是一次action只能选一个target方法。
3. 在针对这个target做点击时，会有fallback。这是task 1里提到的。


## 总结
我希望我代码的层数尽可能压缩。每一层都有明确的定义。你需要帮我定义最简洁，最高效的接口。Keep It Simple Stupid。KISS! KISS!