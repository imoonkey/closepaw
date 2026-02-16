# Accessibility Mode
1. [不用fix] [P2] accessibility platform下，takeover-> add note。加了一条之后，对话框变灰色，不让再加了。
2. [不用fix] [P2] percetion有问题，能看到smart capsule的内容。
3. [P1] 在任务执行时，smart capsule上没有把smart capsule collapse到status island的按钮。

# Virtual Display Mode
1. [P1] Done状态下，不该显示眼睛button来进入虚拟屏幕。
2. [P1] chat界面(main app界面)可以点击眼睛图标，切换到虚拟屏幕。但是虚拟屏幕端点击手机图标，无法退出虚拟屏幕回到chat界面。虚拟屏幕界面底部上滑，会回到真实屏幕的home界面。这时候查看recent apps，会发现Androind Agent app的截图是显示的虚拟屏幕的内容(e.g., 正在操作youtube播放歌曲, doc/todo/0.02_smart_capsule/round7/screenshots/p2.png)。我觉得这可能不是一个状态机定义的问题。而是状态机的实现问题，问题根源在于一个虚拟屏幕和主app的关系问题。虚拟屏幕可能在实际上是在main activity下面的，实际实现的时候没注意，所以导致上面的问题。
3. [P1] 虚拟屏幕界面底部上滑，会回到真实屏幕的home界面。这时候再点击桌面的android agent app，会回到chat界面，但是不是显示的正在进行的chat session，而是一个新的session(doc/todo/0.02_smart_capsule/round7/screenshots/p1.png)。
4. [P1] 任务结束后，chat history里面没有complete_task message。
5. [P1] 执行任务过程中，虚拟屏幕状态下，没有看到edge glow，应该显示edge glow。在accessibility mode下，执行任务过程中，可以看到edge glow。