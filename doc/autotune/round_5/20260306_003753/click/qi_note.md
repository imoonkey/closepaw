现在有个很大的问题，就是至少Files里面 click execution其实还是不work的。

我之前debug的时候对这个问题有印象，当时是能调work了的，就是File里面，一次click action就正常出现click该有的变化。所以这个问题肯定是有解的。click这个问题反复出现过很多次了，每次都是修修补补，东边好了西边又有问题。你帮我这次彻底解决，first principle解决，每一个decision都要有first-principle的理由，和well-isolated以后的evidence(比如不能可能有overlay的问题，你试了试gesture tap，然后不work，就说gesture tap不work。应该是在一个最简单set up下，set up好了，没任何外界干扰，gesture tap不work，那才叫不work，确认是android底层或者这个app就是不支持，才是我们的极限)。
1. 好像gesture_tap 和 node action click里，有一个在Files里面work，另一个不太work。Action Debug你再试试，可能需要你灵活变通，想办法多试试。
2. 另外可能跟我的overlay有关系。有可能是overlay拦截touch。如果上面解决不了，看看能不能isolate问题是不是在这里？
3. 如果是node translation不对，或者因为element overlap导致click不对，或者找上层clickable node不对，这些都是我代码逻辑的问题。


别的现在都不重要，我就是要让我的click 100% work。点击execution这是很基础的东西。不应该存在说，click execute了不work，agent再去想办法绕道来work around this problem。click/scroll/swipe等等，能完全忠实语义work，是整个agent能work的基础。这个问题不解决，这个app就是垃圾，就是一坨屎，就不能发布。



# Your tasks
1. 你可以先看看我的mobile action实现，在doc/main/infra/tool/mobile_action.md里总结一下。我觉得这类问题bother me so much it deserves a deep dive and good documentation。

在此基础上做 /double-design，codex和claude先独立完成以下steps，然后再align。 Write to doc/autotune/round_5/click_bigtime/：
2. 然后你可以搜搜我过往的doc/archive，看看我在这个问题上，改来改去反复改过多少次，可能过去的一些思路会对你有启发。
3. 基于这些给出最完美的无懈可击的investigation/fix方案，能从逻辑上cover所有case的。

我要的是搞清楚什么是android system或者一些app的本身限制(also like a bug, but maybe made not work as a feature/limitation)。然后在这些限制范围内，尽可能完善我们agent的click implementation，做到在这些限制范围内的app都能work。