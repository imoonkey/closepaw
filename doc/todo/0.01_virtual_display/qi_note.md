# Task
1. 采用shizuku方案.
2. virtual display 的UI交互要简单设计一下。比如
    - 在执行的时候，会在灵动岛显示，然后点击可以观看virtual display. 
    - smart capsule显示在vritual display页面而不是用户真实屏幕主页面。
    - 底部向上滑动可以退出virtual display，程序继续执行。
3. 对api不确定的地方，要上网verify api是否可靠。



## Added Note
- 我的app会检测shizuku是否available，不available的时候就用主屏幕操作，available的时候就用virtual display。
- 新的VirtualDisplayPlatform还是会用到a11y tree的api。这部分跟accessibilityPlatform尽可能share code，avoid large duplications。
- 如果你认为AndroidPlatform层接口需要做什么修改，也可以提出。It's not necessary, but don't refrain from it either if that is a good choice.
