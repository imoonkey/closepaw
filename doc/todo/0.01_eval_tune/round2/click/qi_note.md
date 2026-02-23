是不是我的redesign可以改成这个设计：
1. 不管什么都resolve到(x, y)
2. 唯一fallback，并且不是基于screen state没变化，而是1.如果返回错误(which should rarely happens)：如果有element_index或者text+text_index，就直接从这个输入resolve到a11y tree的node上，然后node.performAction(ACTION_CLICK)。而不是先resolve到(x,y)，再在下一层去找node，因为这中间可能出现错位。

没有retry，没有jitter，没有per-attempt UI change detection。

把这个fallback写成phase 2吧，先设计但不用实现，如果没必要的话，就不实现了。然后写明白这个phase 2是否实现，取决于phase 1 run android world test之后看click execution有没有问题。

这个code应该最简单最可靠。能少写代码就少写代码。每一行都应该是必要的。