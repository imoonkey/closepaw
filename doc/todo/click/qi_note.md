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