为了实现 doc/todo/0.01_virtual_display/qi_note.md，我现在 doc/todo/0.01_virtual_display/stage1/final_design.md 和 doc/todo/0.01_virtual_display/stage2/review_summary_stage2_design.md 都实现完了。

现在有两个未完成的任务
1. doc/todo/0.01_virtual_display/stage2/platform_reorg_plan.md 还差一点。Virtual Display 侧的重构目标（Definition of Done 前两条）已满足。但 "Accessibility 和 VD 实现展示对齐的结构和命名" 这一条还没做——AccessibilityPlatform 的 node action 逻辑没有提取到类似 AccessibilityNodeActionPerformer 的独立类中。
2. doc/todo/0.01_virtual_display/stage1/final_design.md 这里面Phase 4的UI设计还没有实现。并且现在有一些已知的小bug， virtual display和主屏幕会干扰：
    - 现在在virtual display的时候，overlay（包含边缘glow+ smart capsule），有时候会出现。
    - 在virtual display打字的时候，我主屏幕键盘会弹出来。

2. virtual display 的UI交互要简单设计一下。比如
    - 在执行的时候，会在灵动岛显示virtual display当前ai agent在操作的app，通过点击灵动岛可以观看virtual display.
    - overlay (glow+smart capsule)显示在vritual display页面(当用户在观看它的时候),而不是用户真实屏幕主页面。
    - 在virtual display时候，底部向上滑动可以退出virtual display，程序继续执行。
    - 在任务成功结束后，把virtual display最后打开的app当前状态挪到主屏幕，让用户看到。这是一种很口语很不专业的描述，我不知道这个underlying实现应该怎么描述。

