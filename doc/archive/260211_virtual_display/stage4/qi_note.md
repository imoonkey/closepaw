为了实现 doc/todo/0.01_virtual_display/qi_note.md，我现在 doc/todo/0.01_virtual_display/stage1/final_design.md, doc/todo/0.01_virtual_display/stage2/review_summary_stage2_design.md, 和 doc/todo/0.01_virtual_display/stage3/refactor_design_claude.md 都实现完了。

现在有一个未完成的部分
- doc/todo/0.01_virtual_display/stage1/final_design.md 这里面Phase 4的UI设计还没有实现。
- 并且现在有一些已知的小bug， virtual display和主屏幕会干扰：
    - 现在在virtual display的时候，overlay（包含边缘glow+ smart capsule），有时候会出现。
    - 在virtual display打字的时候，我主屏幕键盘会弹出来。



# UI 设计
virtual display 的UI交互要简单设计一下。比如
    - 在执行的时候，会在灵动岛显示virtual display当前ai agent在操作的app，通过点击灵动岛可以观看virtual display.
    - overlay (glow+smart capsule)显示在vritual display页面(当用户在观看它的时候),而不是用户真实屏幕主页面。
    - 在virtual display时候，底部向上滑动可以退出virtual display，程序继续执行。
    - 在任务成功结束后，把virtual display最后打开的app当前状态挪到主屏幕，让用户看到。这是一种很口语很不专业的描述，我不知道这个underlying实现应该怎么描述。

在这部分，你是全世界最棒的设计师和产品经理，会设计出最棒的UI，最棒的交互，最棒的体验。精髓不在于复杂，反而在于简单，能够直击用户痛点，能够给用户带来愉悦的体验。像Steve Jobs一样设计你的产品！！

把你的设计写到 doc/todo/0.01_virtual_display/stage4/ui_design_{your model name, e.g., claude}.md 写作过程中你不会参考这个folder下别的design，独立思考。

# 系统设计
在设计完ui设计后，你要开始设计实现的逻辑。
write the design like if you are Linus Torvalds.
- 拥抱KISS principle，keep it simple stupid. 避免过度设计，避免过度工程化。嵌套层数不要太深。
- 设计high readability的code。
- 设计的过程，不要考虑代码的backward compatibility，最后把陈旧的历史代码可以直接deprecate，我产品还没有release，不需要考虑任何向后兼容。代码质量高，可读性高，只需要反映最新最优的实现，这对我更重要。
- 阅读我已有的代码，确保你的设计跟现有的codebase是aligned。

- 这个feature对我的项目至关重要，请用 /ultra-think 来设计，深思熟虑，考虑周全。 @.ai-dev/skills/ultra-think/SKILL.md

- 你会把你的设计写到 doc/todo/0.01_virtual_display/stage4/system_design_{your model name, e.g., claude}.md 下。写作过程中你不会参考这个folder下别的design，独立思考。


# Important Add-On Note
- 上面说的都是virtual display 模式下的。如果是在a11y模式下，那就跟原来基本一样哈，在android agent外显示overlay，在里面的时候不显示overlay。这个模式下不要有regression。

- 在现在这个阶段,我还没有设计和实现,但是在未来这个virtual display在user看它的时候,它也可以选择接管,然后在这个虚拟屏幕上进行交互,就仿佛他在操作这个实际的屏幕一样。呃在当前这个阶段的设计和实现还不需要考虑,但是呃我希望我现在的设计和实现呃是兼容我以后的这个想法的。