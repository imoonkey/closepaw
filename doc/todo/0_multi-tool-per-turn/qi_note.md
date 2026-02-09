我现在的agent每个turn只能调用一个tool。这在之前是有一定合理性的，之前只有moblie action (click/swipe etc.)的时候，每个action都可能让screen state发生变化，这时候如果有多个mobile action，那第二个第三个action可能还基于之前的screen state，没考虑上一个action的变化，那就会有问题。

现在有一些其他类型的任务了，比如write todos，scratchpad, complete task等，这些不改变屏幕状态，不影响后续action。那其实是可以并行执行的。比如一边写两个scratchpad entry，一边update todos，一边执行一个mobile action。

我想实现：
- 每个turn只要别超过一个mobile action其实就可以。这个可以在prompt里提示，但不需要系统层面的约束。
- 系统层面我可以并行/异步执行这些任务。我不确定这部分工程多大。给我一个KISS的design。