我实现了doc/todo/0.02_smart_capsule/round5/system/final_design_claudecode.md，看我的last commit，在代码修改后发现的问题：
1. Virtual Display Mode下在虚拟屏幕的时候,Smart Capsule如果点那个显示Status Island的那个button,是会Smart Capsule消失,Status Island出现的,但是再点Status Island,Status Island消失了,Smart Capsule没有出现。
2. virtual dispay mode或者a11y platform mode, doc/todo/0.02_smart_capsule/round5/qi_bug_note.md. chat history里面依然没有complete_task message.
3. Virtual display mode下点击“📱”，该退回到主app界面，但是现在还是点了无效。
4. Virtual display mode下，点击take over后再输入内容点add note，结果smart capsule忽然整个消失了。然后虚拟屏幕一直处在pause状态。status island/ smart capsule都看不到。

这个还是有点漏洞百出啊。我不知道是状态机definition本身的问题，还是基于正确状态机的错误component状态/渲染的问题。

你要不做一个这个事儿，
1. 仿照我doc/todo/0.02_smart_capsule/round5/qi_bug_note.md里类似的形式，把不同状态，不同user flow都给我完整列出来，列出来status island/ smart capsule和它上面的各个component，都该是什么状态，点击该有什么结果。做一个完整的陈列。看看状态机定义是否能满足全部要求，或者你的user flow设计里有什么不合理的地方。两边iterate，直到user flow和状态机定义converge。这俩分别是两个doc。
2. 这样，在1之后我可以基于上面converge了的状态机doc，开始写tests，然后再改code来pass所有tests。follow /tdd skill的 process.
