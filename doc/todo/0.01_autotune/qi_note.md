我想做一个自动化的agent tuning,基于eval run results。我现在其实每次都有一个大概类似的流程,然后这个流程我会跑很多round。我觉得其实这中间很多步骤是不需要human intervention的,只有每个iteration出了新的analysis和recommendation之后,找人review一下就可以了,别的过程完全不需要我。我觉得就一直跑就行了,我想把这个流程给formalize,变成一个agent skill。


我的大致流程example：
- 我会给claude先发 Task 1，
- 然后又给codex发 Task 2， get 2nd opinion。 (有时候可能跳过)
- 然后分别给claude和codex发Task 3， 来align。 (有时候可能跳过)
- 然后我review一下结果，iteratively跟claude/codex讨论，来得到最终的设计。
- 重新回到第一步Task 1。


一些以前的输出在doc/todo/0.01_eval_tune。你如果想看最近的，可以看doc/todo/0.01_eval_tune/group4，前面的结构也都大同小异。

在自动化已有流程的基础山，我还想improve it。我的progress缺乏全局的tracking：
1. 每个task的成功状态没有记载。现在只有eval_run -> task的raw记录。没有反向链接。比如我可以有一个board。每个task一个row，column是eval run。然后有一些column是stats，比如lastest_success_rate (取最近3个run， or less when it has less than 3 total runs）。这个可以有raw data，外加我的inspection_tool的visualization。
2. per-task progress: 每个task每次新的eval run，都有什么behavior变化。
3. overall progress: 
    - 总体发现了什么新的common问题，哪些解决了，哪些没解决，哪些部分解决。应该维护一个global todo markdown file，记录状态。
    - 应该有一个changelog，对应commit(?，eval run只run commit code? app/ eval/ 需要commit再run？)，记录每次iteration 修改了什么，跑了什么任务，结果跟上次有什么不同。

# Task 1 Example: group 4 round 3 fix and run

1. 我们当前关注的任务是：
eval/config/aw_subset_group_4_round1_reeval.txt里失败的任务。分析在doc/todo/0.01_eval_tune/group4/round2/eval_analysis_20260227_222506/common_problems_claude.md。你帮我fix这些issue。 follow sop/code_work.md。
    - 把 doc/todo/0.01_eval_tune/group4/round2/eval_analysis_20260227_222506/common_problems_claude.md中failed tasks写到eval/config/aw_subset_group_234_round3.txt，过程中exclude掉eval/config/cannot_handle_group.txt中的任务(这是已知当前无法完成的任务列表，没必要浪费资源去跑)。

2. 用eval runner重新跑eval/config/aw_subset_group_234_round3.txt。`eval/.venv/bin/python eval/aw_bridge/runner.py --tasks-file eval/config/aw_subset_group_234_round3.txt`. 
    - 注意，过程中间,task之间可能会产生stall。比如不知道为什么丢掉accessibility权限,这时候会卡住。如果在一个任务等待卡很久,那你可能需要再去手动给Android的Agent App grant一下accessibility permission，可能甚至需要你手动停止runner process再重跑一遍(这时候不要再重跑已经跑完的任务，缩小你的任务集合，跑剩余任务)。make sure跑完全部任务。
    - 这个issue应该是已经fix了，letting you know just in case。
3. 在eval runner跑的过程中，针对跑完的任务，用 /cog-tune skill分析每一个task的每一个turn。写到doc/todo/0.01_eval_tune/group4/round3/eval_analysis_[new run foldername]/per_task/*_claude.md
- 总结全部的single task analysis，归纳common problems，提出修改建议。写到doc/todo/0.01_eval_tune/group4/round3/eval_analysis_[new run foldername]/*_claude.md。

# Task 2 Example: eval analysis after run
- 我用eval runner重新跑了eval/config/aw_subset_group_1.txt。结果在eval/results/[TODO: last run foldername]/.
- 用 /cog-tune skill分析每一个task的每一个turn。写到doc/todo/0.01_eval_tune/round7/eval_analysis_[run foldername]/per_task/*_codex.md
- 总结全部的single task analysis，归纳common problems，提出修改建议。写到doc/todo/0.01_eval_tune/round7/eval_analysis_[run foldername]/*_codex.md。
- 过程中不要参考doc/todo/0.01_eval_tune/round7/eval_analysis_[run foldername]/ 下的*_claude.md文件，保持分析的独立性和客观性。


# Task 3 Example: align
Use /align skill. Now claude and codex work on aligning on the analysis and problems to fix.
- Read doc/todo/0.01_eval_tune/group4/round3/eval_analysis_[new run foldername]/*.md as initial inputs. If needed, look into per_task/ analysis or even raw trace logs.  
- Write to doc/todo/0.01_eval_tune/group4/round3/eval_analysis_[new run foldername]/。