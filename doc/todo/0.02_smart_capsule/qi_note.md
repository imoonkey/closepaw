UI可以部分参考豆包手机。但是要独立思考，争取在UX设计上更加创新，更加简单明了，更加符合用户直觉。

smart capsule: 上面显示agent thought，下面显示用户CTA按钮：接管/继续，补充，停止。

1. show agent thought。从prompt到UI都要改。告诉LLM agent thought会显示给用户来解释这一步行为，限制在手机屏幕一行内。UI上，在overlay上显示agent thought。
2. 现在的pause对应“接管”，用户在这期间可以手动操作手机完成步骤。resume的时候，接管前未完成的tool_call应该被取消而不是继续执行，agent应该capture screen状态，发给llm来决定下一步。
3. 增加一个”补充“按钮，弹出对话框可以打字，相当于插入一条user message。



4. 应该给加一个tool，叫ask_user()。这时候暂停，等待用户行为。可以是等待用户输入信息(ask user a question for an answer)，或者是请求用户操作手机比如登录账户或者处理某些权限问题(ask user to do something on phone)。这个可能该拆成两个tool。前端UI上要做相应的处理。