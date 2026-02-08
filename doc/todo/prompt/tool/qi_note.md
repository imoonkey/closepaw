1. transcribe_screen()  怎么实现的 (a11y or OCR)？什么时候用？ 
2. system reminder 要删一删
3. tool不用返回screen state了，每个turn开始capture一下，放到user message就行了。tool return它本来就要return的一些meta的东西，不再做屏幕观测。
4. 现在主要看了tool的prompt，也就是input的部分，还没有看output的部分(tool返回)，和中间execution的部分(代码实现)。

每个tool，
1. 首先要定义好input,output，这两个是直接进入LLM context的。每部分都有两个优化目标: 1. maximize info and tool/agent task success, 2. minimize token usage，尽管有时候这两个目标是矛盾的。the art is in the trade-off.
2. 其次要优化好execution的部分。
三部分缺一不可。