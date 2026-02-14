# Accessibility Mode
## 1. Main App 界面
1.1 [good] agent running中， 点stop：很顺利，当前turn结束后完成。
1.2 [good] agent running中， 点take over: 很顺利，当前turn结束后，进入take over 状态。
1.3 [bad][P2] 在这个不管是点stop还是点takeover的时候,点完之后,UI都没有什么响应,所以我不知道现在是我的点击成功了,然后系统在pending,在等这个turn结束来执行暂停或停止,还是说我这个点击就没有成功。 这种时候如果这个按钮啊或者怎么样,显示一个transition状态,会更清晰一些。
1.4 [bad][P2] 在MainApp状态下，任务开始执行后，也显示Status Island。app已经在前台了，这个可有可无，应该去掉。
1.5 [bad][P1] idle状态和任务结束后，底部的input dock是单独的，跟Smart Capsule的UI的第三行(input row)是不同的，而我期待它们是同一个component，同一个code，不应该单独实现。


## 2. Overlay状态 界面
2.1 这三条跟main app状态下一样：1.1, 1.2, 1.3。

2.2 [good] 在takeover的状态下,我在输入框里输入了新的指令,点击了add note,之后又点击了resume。这个时候,后面继续执行的时候,是成功地考虑了我刚刚补充的信息的。
2.3 [bad] 不应该有这个return to app的这个按钮, 因为这个会改变屏幕状态,而会打乱agent自己对屏幕的操作。这不应该被允许。
2.4 [bad][P1] smart capsule 应该有"status island"按钮，按这个按钮后，smart capsule消失，status island出现。反过来，点击status island的话，status island 消失， smart capsule应该出现。这应该是一个可逆的过程。这样是为了用户在过程中可以选择减少屏幕遮挡。在take over状态下和正常执行状态下，都应该有这个功能。
2.5 [bad][P1] Status Island现在是always visible，应该按上一条的逻辑进行修改，任意时候smart capsule和status island只有一个visible。
2.6 [bad][keyboard pop issue is P2; agent see smart capsule UI issue is P0, 虽然这个跟UI状态机(我们现在的focus)没有任何关系] 出了个搞笑的事,在YouTube页面agent执行任务的时候,我点击了一下Smart Capsule最底下那行的input box,这时候弹出了一个键盘,键盘就把我的Smart Capsule的UI给全都盖住了,这是问题一。问题二是,这个时候呢,agent可能截图里面看到了我这个Smart Capsule的UI,他不知道为什么他决定去点击这个takeover来"get control of the device"，并且点击成功了。但这个是有问题的,他不应该看到Smart Capsule的UI。键盘他有没有看到我就不清楚了,但是Smart Capsule的UI他不应该看到。然后他就点击了一下takeover（明明是被键盘覆盖的还是点上了，可能是因为code自动过滤了键盘的a11y tree elements?）,系统就进入等待我操作的状态了。
2.7 [bad][agent see smart capsule UI issue is P0, 虽然这个跟UI状态机(我们现在的focus)没有任何关系; 焦点conflict，只有take over状态才能输入，这个要改状态机，和状态下渲染逻辑] 我在overlay状态下的时候,点击Smart Capsule的输入框,想输入一些内容,然后Add note。我发现这个时候,我会和Agent抢焦点,因为它能看到Smart Capsule嘛,然后我焦点点到那儿的时候,应该它的A11的也会看到这个,对它的percetion和action是有影响的。所以我想到可能大概有两个问题：1）一个就是让它看不到Smart Capsule。2）另外就是，这时候我点击smart capsule对话框的话，哪怕agent看不到smart capsule,屏幕上的focus element是不是还是只能有一个? 比如我也想type来add note,它也想type来做search,那就会有conflict。所以在accessibility这个模式下,那应该是只允许在点击takeover之后才能输入Add note,然后之后再resume这个合理吗?


# Virtual Display Mode
## 3. Main App 界面
3.1 [good] 这三条跟accessibility mode下的main app状态下一样：1.1, 1.2, 1.3。
3.2 [bad][P2] 这条跟1.4一样：一直在main app状态，任务开始后，显示status island。app已经在前台了，这个可有可无，应该去掉。
    - 3.2.1 [bad][P2] smart capsule第二行右侧显示了"status island"的按钮，但是点击没任何反应，这个按钮就不该存在。
3.3 [bad][P1] 在一个任务刚结束的时候,它会先渲染一个smart capsule的状态：只有第一行和第二行的状态且第二行没有左侧button(参考截图 ./screenshot/3.3.png)。然后再把这个会过一两秒消失掉,只显示input dock,然后这个input dock还是跟smart capsule第三行的状态是不一样的（同1.5，是个单独实现的component。我不知道这个显示为什么是这样的。理想情况下应该是任务执行一开始就显示三行smart capsule的UI，然后在任务结束后，就进入idle状态，第一行跟第二行都一块儿collapse掉，只显示第三行的input box跟button（不需要额外的input dock）。
3.4 [bad][P1;虽然好像跟UI状态机(我们现在的focus)没有关系] complete_task有时候不显示在main display的history里面。
3.5 [bad][P2；虽然好像跟UI状态机(我们现在的focus)没有关系] 如果我"add note"，这个会生效但是不会在chat history里面显示成一条user message，不知道session history里面有没有。
3.6 [good] 点击眼睛icon，可以切换到虚拟屏幕去，这很好。

## 4. 主屏幕-非Main App 界面
这时候说明用户在用手机干别的事情，agent正在后台虚拟屏幕执行任务。
4.1 [good] 这时候会显示status island，这是对的，可以给用户显示一下进度。
4.2 [bad][P2] 点击status island，会显示smart capsule在主屏幕上。这个不对。这时候应该要么跳转到virtual display（我prefer是这个设计），要么跳转到main app。在vd模式下，smart capsule不该出现在主屏幕非main app界面。


## 5. Virtual Display with Overlay 界面
5.1 [bad][P1] status island跟smart capsule会同时一直显示。点击Smart Capsule上的那个Status Island的那个icon那个按钮可以把Status Island打开,然后Smart Capsule关掉。反过来点击Status Island的时候会把Smart Capsule打开,Status Island关掉,但是在进入下一个turn之后,Status Island就又会显示出来,这个不应该。Status Island跟Smart Capsule应该在任意时间只有一个在显示。
5.2 [bad][P2] 点击smart capsule上的那个手机icon没有任何反应,它应该是退回main app界面的,但是现在点了没用。
5.3 [bad][P1] 任务结束后，好像会试图把最后一个virtual display上单开的app给launch到主屏幕上。但是这个实际上会有问题,比如YouTube如果我在virtual display上已经打开并播放一个视频,这时候在主app上再重新launch它的话,会把那个视频的播放给打断了。而且这个时候好像virtual display上的那个YouTube程序会卡住, 我不知道是不是一个app这样的双开就会导致这种问题, 我觉得这个逻辑我们现在就可以简化掉吧,就是不要把这个virtual display上的app给launch到主屏幕了,它任务结束了就还都在virtual  display这边就好了,改成一个no-op。
5.4 [bad][P2] 我不知道跟上面那个虚拟屏幕卡住之类的有没有关系,就是status island会一直显示"Working...",然后退回到主屏幕之后, 还是一直这样显示,然后点击status island会显示一个空的壁纸页面(除了顶部status bar没有任何内容),我猜这可能是virtual display,然后什么都没有打开的状态,这个也是个bug,需要修了。也可能跟没有complete_task message有关。
5.5 [good] virtual display底部上滑，可以返回到主屏幕。
5.6 [skipped] stop和take over我没测，我assume跟上面1/2/3 section的测试结果应该是类似的，我就assume它work了。