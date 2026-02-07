我现在build了我的AndroidAgent(this repo)，我现在不知道我的agent到底performance怎么样，执行任务成功率多少。

1. 现在有一些开源的evaluation framework，比如 .reference/eval 下的 android_world 和 mobile_world，但他们的framework都是python写的针对用adb的action的。我没有仔细看过他们的代码，不知道怎么用他们来evaluate。他们有一些自己unique的app，mobile world还要serve一个backend。
2. 如果可行的话，可能有一个思路是，把我的apk打包好，安装到他们的测试环境emulator上。每个task的eval，就让adb给发个命令，让它跑就完事了，类似 @scripts/debug-run.sh那样。不知道这样eval frametwork能不能拿到它evaluate需要的artifact来evaluate是否成功。
3. 更复杂一点的，就是把他们的任务核心拿出来，input task，所需app/环境等等，再另写代码，让我的app跑任务，然后evaluate。这样更难做到1-1复刻。
4. 最少的话，挑几个依赖常见app的任务，在我手机/emulator上跑debug-run.sh，然后手动evaluate是否正确。算是有点minimal QA to capture significant regression。

我的代码架构可以看 doc/main。两个eval framework都在 .reference/eval下面。


帮我设计一下我该怎么evaluate我的agent的core capability。写到 doc/todo/eval/design_[your_model_name].md下