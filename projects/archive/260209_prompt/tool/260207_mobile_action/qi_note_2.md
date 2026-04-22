1. 现在mobile_action tool的prompt算optimized得差不多了，但是底下的code-level implementation我还没有参考，来improve我click, swipe, long press的成功执行率，包括targeting的preference order。当然因为他们用adb，而我用on-device的api，所以很多地方不太能直接参考。
2. Targeting里的bound感觉也可以去了。
3. Perception里把text/description合并了，targeting resolution要相应处理。这里我不确定做没做。