我现在autotune的eval run得太慢了，跑一个任务要5分钟以上，一个round如果 20个任务，要一两个小时。
1. 我之前想实现 @doc/todo/0.5_eval/parallel/ ，code写了一些，但从来没用过，不知道是否有效。 另外就是这个路线是不是很吃我的机器，可能内存或者cpu不够，导致哪怕实现了，也很难多个emulator并行跑。
2. 我不知道有没有什么便宜的cloud android emulator service，让我低成本的并行跑这些tasks。
使用 /design skill，你帮我分析分析，写个design。